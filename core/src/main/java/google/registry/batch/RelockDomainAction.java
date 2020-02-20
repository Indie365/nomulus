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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.request.Action.Method.POST;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registry.RegistryLockDao;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.schema.domain.RegistryLock;
import google.registry.tools.DomainLockUtils;
import google.registry.util.Clock;
import google.registry.util.DateTimeUtils;
import javax.inject.Inject;

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

  private final long oldUnlockRevisionId;
  private final DomainLockUtils domainLockUtils;
  private final Response response;
  private final Clock clock;

  @Inject
  public RelockDomainAction(
      @Parameter("oldUnlockRevisionId") long oldUnlockRevisionId,
      DomainLockUtils domainLockUtils,
      Response response,
      Clock clock) {
    this.oldUnlockRevisionId = oldUnlockRevisionId;
    this.domainLockUtils = domainLockUtils;
    this.response = response;
    this.clock = clock;
  }

  @Override
  public void run() {
    RegistryLock relock = jpaTm().transact(this::validateAndCreateRelock);
    if (relock != null) {
      applyRelock(relock);
    }
  }

  private RegistryLock validateAndCreateRelock() {
    DomainBase domain;
    RegistryLock oldLock;
    try {
      oldLock =
          RegistryLockDao.getByRevisionId(oldUnlockRevisionId)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format("Unknown revision ID %d", oldUnlockRevisionId)));
      domain =
          ofy()
              .load()
              .type(DomainBase.class)
              .id(oldLock.getRepoId())
              .now()
              .cloneProjectedAtTime(jpaTm().getTransactionTime());
      verifyDomainAndLockState(oldLock, domain);
    } catch (Throwable t) {
      /* If there's a bad verification code or the domain is in a bad state, we won't want to retry.
       * AppEngine will retry on non-2xx error codes, so we return SC_NO_CONTENT (204) to avoid it.
       *
       * See https://cloud.google.com/appengine/docs/standard/java/taskqueue/push/retrying-tasks
       * for more details on retry behavior. */
      logger.atSevere().withCause(t).log(
          "Exception when attempting to re-lock domain with old revision ID %d.",
          oldUnlockRevisionId);
      response.setStatus(SC_NO_CONTENT);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Relock failed: %s", t.getMessage()));
      return null;
    }
    return createRelock(oldLock, domain);
  }

  private RegistryLock createRelock(RegistryLock oldLock, DomainBase domain) {
    try {
      if (domain.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES)) {
        logger.atInfo().log(
            "Domain %s already locked, no action necessary.", domain.getFullyQualifiedDomainName());
        return null;
      } else {
        RegistryLock registryLock =
            domainLockUtils.createRegistryLockRequest(
                oldLock.getDomainName(),
                oldLock.getRegistrarId(),
                oldLock.getRegistrarPocId(),
                oldLock.isSuperuser(),
                clock);
        // The old lock object should have a reference to the relock
        RegistryLockDao.save(oldLock.asBuilder().setRelock(registryLock).build());
        return registryLock;
      }
    } catch (Throwable t) {
      setUnexpectedErrorInResponse(domain.getFullyQualifiedDomainName(), t);
      return null;
    }
  }

  private void applyRelock(RegistryLock relock) {
    try {
      domainLockUtils.verifyAndApplyLock(relock.getVerificationCode(), relock.isSuperuser(), clock);
      logger.atInfo().log("Re-locked domain %s.", relock.getDomainName());
      response.setStatus(SC_OK);
    } catch (Throwable t) {
      setUnexpectedErrorInResponse(relock.getDomainName(), t);
    }
  }

  private void setUnexpectedErrorInResponse(String domainName, Throwable t) {
    // Any errors that occur here are unexpected, so we should retry. Return a non-2xx
    // error code to get AppEngine to retry
    logger.atSevere().withCause(t).log(
        "Exception when attempting to re-lock domain %s.", domainName);
    response.setStatus(SC_INTERNAL_SERVER_ERROR);
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    response.setPayload(String.format("Relock failed: %s", t.getMessage()));
  }

  private void verifyDomainAndLockState(RegistryLock oldLock, DomainBase domain) {
    // Domain shouldn't be deleted or have a pending transfer/delete
    String domainName = domain.getFullyQualifiedDomainName();
    checkArgument(
        !DateTimeUtils.isAtOrAfter(clock.nowUtc(), domain.getDeletionTime()),
        "Domain %s has been deleted",
        domainName);
    ImmutableSet<StatusValue> statusValues = domain.getStatusValues();
    checkArgument(
        !statusValues.contains(StatusValue.PENDING_DELETE),
        "Domain %s has a pending delete",
        domainName);
    checkArgument(
        !statusValues.contains(StatusValue.PENDING_TRANSFER),
        "Domain %s has a pending transfer",
        domainName);
    checkArgument(
        domain.getCurrentSponsorClientId().equals(oldLock.getRegistrarId()),
        "Domain %s has been transferred from registrar %s to registrar %s since the unlock",
        domainName,
        oldLock.getRegistrarId(),
        domain.getCurrentSponsorClientId());

    // Relock shouldn't have been set already
    checkArgument(
        oldLock.getRelock() == null,
        "Relock already set on old lock with revision ID %s",
        oldLock.getRevisionId());
  }
}
