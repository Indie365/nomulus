// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.locks;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.locks.LocksModule.PARAM_CLIENT_ID;
import static google.registry.locks.LocksModule.PARAM_FULLY_QUALIFIED_DOMAIN_NAME;
import static google.registry.locks.LocksModule.PARAM_IS_ADMIN;
import static google.registry.locks.LocksModule.PARAM_REGISTRAR_POC_ID;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.request.Action.Method.POST;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.model.domain.DomainBase;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.schema.domain.RegistryLock;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Task that re-locks a previously-Registry-Locked domain after some predetermined period of time.
 */
@Action(
    service = Action.Service.BACKEND,
    path = RelockDomainAction.PATH,
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class RelockDomainAction implements Runnable {

  public static final String PATH = "/_dr/task/relockDomain";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String fullyQualifiedDomainName;
  private final String clientId;
  private final Optional<String> registrarPocId;
  private final boolean isAdmin;
  private final DomainLockUtils domainLockUtils;
  private final Response response;
  private final Clock clock;

  @Inject
  public RelockDomainAction(
      @Parameter(PARAM_FULLY_QUALIFIED_DOMAIN_NAME) String fullyQualifiedDomainName,
      @Parameter(PARAM_CLIENT_ID) String clientId,
      @Parameter(PARAM_REGISTRAR_POC_ID) Optional<String> registrarPocId,
      @Parameter(PARAM_IS_ADMIN) boolean isAdmin,
      DomainLockUtils domainLockUtils,
      Response response,
      Clock clock) {
    this.fullyQualifiedDomainName = fullyQualifiedDomainName;
    this.clientId = clientId;
    this.registrarPocId = registrarPocId;
    this.isAdmin = isAdmin;
    this.domainLockUtils = domainLockUtils;
    this.response = response;
    this.clock = clock;
  }

  @Override
  public void run() {
    try {
      checkArgumentNotNull(fullyQualifiedDomainName, "fullyQualifiedDomainName cannot be null");
      checkArgumentNotNull(clientId, "clientId cannot be null");
      checkArgument(
          isAdmin || registrarPocId.isPresent(), "registrarPocId cannot be null if not isAdmin");
    } catch (Exception e) {
      logger.atSevere().log(
          "Exception when attempting to re-lock domain %s", fullyQualifiedDomainName);
      response.setStatus(SC_NO_CONTENT);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Relock failed: %s", e.getMessage()));
      return;
    }
    try {
      DateTime now = clock.nowUtc();
      DomainBase domainBase =
          loadByForeignKey(DomainBase.class, fullyQualifiedDomainName, now)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format(
                              "Domain '%s' does not exist or is deleted",
                              fullyQualifiedDomainName)));
      if (domainBase.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES)) {
        logger.atInfo().log(
            "Domain %s already locked, no action necessary", fullyQualifiedDomainName);
      } else {
        RegistryLock registryLock =
            domainLockUtils.createRegistryLockRequest(
                fullyQualifiedDomainName, clientId, registrarPocId.orElse(null), isAdmin, clock);
        domainLockUtils.verifyAndApplyLock(registryLock.getVerificationCode(), isAdmin, clock);
        logger.atInfo().log("Re-locked domain %s", fullyQualifiedDomainName);
      }
      response.setStatus(SC_OK);
    } catch (Throwable e) {
      logger.atSevere().log(
          "Exception when attempting to re-lock domain %s", fullyQualifiedDomainName);
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Relock failed: %s", e.getMessage()));
    }
  }
}
