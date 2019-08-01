// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.ui.server.registrar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.joda.time.DateTimeZone.UTC;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.RequestMethod;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.util.PreconditionsUtils;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Admin servlet that allows for getting or updating registrar locks for a particular registrar.
 *
 * Note: locks / unlocks must be verified separately before they are written permanently.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistrarDomainLockAction.PATH,
    method = {Method.POST, Method.GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class RegistrarDomainLockAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Gson GSON = new Gson();

  private static final String LOCKS_PARAM = "locks";
  private static final String FULLY_QUALIFIED_DOMAIN_NAME_PARAM = "fullyQualifiedDomainName";
  private static final String LOCKED_TIME_PARAM = "lockedTime";
  private static final String LOCKED_BY_PARAM = "lockedBy";

  public static final String PATH = "/registrar-domain-lock";

  @Inject @RequestMethod Method method;
  @Inject Response response;
  @Inject AuthenticatedRegistrarAccessor registrarAccessor;
  @Inject AuthResult authResult;

  @Inject @Parameter(PARAM_CLIENT_ID)
  Optional<String> paramClientId;

  @Inject @Parameter(FULLY_QUALIFIED_DOMAIN_NAME_PARAM)
  Optional<String> fullyQualifiedDomainName;

  @Inject @Parameter("lockOrUnlock")
  Optional<String> lockOrUnlock;

  @Inject RegistrarDomainLockAction() {}

  @Override
  public void run() {
    checkArgument(authResult.userAuthInfo().isPresent(), "User auth info must be present");
    response.setContentType(MediaType.JSON_UTF_8);
    response.setHeader(X_FRAME_OPTIONS, "SAMEORIGIN"); // Disallow iframing.
    response.setHeader("X-Ui-Compatible", "IE=edge"); // Ask IE not to be silly.

    Registrar registrar;
    try {
      registrar = getRegistrarAndVerifyLockAccess();
    } catch (RegistrarAccessDeniedException e) {
      logger.atWarning().withCause(e).log(
          "User %s doesn't have access to registrar console.", authResult.userIdForLogging());
      response.setStatus(SC_FORBIDDEN);
      return;
    }

    if (Action.Method.GET.equals(method)) {
      runGet(registrar);
    } else if (Method.POST.equals(method)) {
      runPost(registrar);
    } else {
      throw new UnsupportedOperationException("Only GET/POST requests are supported");
    }
  }

  private void runGet(Registrar registrar) {
    ImmutableList<DummyRegistrarLock> lockedDomains = getLockedDomains(registrar);
    Map<String, ?> resultMap = createResultMap(registrar, lockedDomains);
    response.setPayload(GSON.toJson(resultMap));
  }

  private void runPost(Registrar registrar) {
    String domainName =
        fullyQualifiedDomainName.orElseThrow(
            () -> new IllegalArgumentException("Must supply domain name to lock/unlock"));
    // this is hacky but we'll have something better when it's actually implemented
    String lockOrUnlockParam = lockOrUnlock.orElseThrow(() -> new IllegalArgumentException("Must supply lockOrUnlock"));
    boolean lock;
    if ("lock".equals(lockOrUnlockParam)) {
      lock = true;
    } else if ("unlock".equals(lockOrUnlockParam)) {
      lock = false;
    } else {
      throw new IllegalArgumentException("lockOrUnlock must be either 'lock' or 'unlock'");
    }

    // TODO: actually do the lock / unlock
    logger.atInfo().log(
        String.format("Performing action %s to domain %s", lock ? "lock" : "unlock", domainName));
    runGet(registrar);
  }

  private Registrar getRegistrarAndVerifyLockAccess() throws RegistrarAccessDeniedException {
    String clientId = paramClientId.orElse(registrarAccessor.guessClientId());
    Registrar registrar = registrarAccessor.getRegistrar(clientId);
    verifyRegistrarLockAccess(registrar);
    return registrar;
  }

  private Map<String, ?> createResultMap(
      Registrar registrar, ImmutableList<DummyRegistrarLock> lockedDomains) {
    PreconditionsUtils.checkArgumentNotNull(lockedDomains);
    return ImmutableMap.of(
        PARAM_CLIENT_ID, registrar.getClientId(),
        LOCKS_PARAM,
            lockedDomains.stream().map(DummyRegistrarLock::toMap).collect(toImmutableList()));
  }

  private ImmutableList<DummyRegistrarLock> getLockedDomains(Registrar registrar) {
    PreconditionsUtils.checkArgumentNotNull(registrar);
    return ImmutableList.of(
        DummyRegistrarLock.create("test.test", DateTime.now(UTC), "John Doe"),
        DummyRegistrarLock.create("othertest.test", DateTime.now(UTC).minusDays(20), "Jane Doe"),
        DummyRegistrarLock.create("differenttld.tld", DateTime.now(UTC).minusMonths(5), "Foo Bar"));
  }

  private void verifyRegistrarLockAccess(Registrar registrar) {
    PreconditionsUtils.checkArgumentNotNull(registrar);
    // TODO: check the actual value once we store it
  }

  @AutoValue
  abstract static class DummyRegistrarLock {
    abstract String fullyQualifiedDomainName();

    abstract DateTime lockedTime();

    abstract String lockedBy();

    static DummyRegistrarLock create(
        String fullyQualifiedDomainName, DateTime lockedTime, String lockedBy) {
      return new AutoValue_RegistrarDomainLockAction_DummyRegistrarLock(
          fullyQualifiedDomainName, lockedTime, lockedBy);
    }

    ImmutableMap<String, ?> toMap() {
      return ImmutableMap.of(
          FULLY_QUALIFIED_DOMAIN_NAME_PARAM,
          fullyQualifiedDomainName(),
          LOCKED_TIME_PARAM,
          lockedTime().toString(),
          LOCKED_BY_PARAM,
          lockedBy());
    }
  }
}
