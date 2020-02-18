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

import static google.registry.locks.LocksModule.PARAM_OLD_UNLOCK_REVISION_ID;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.request.Action.Method.POST;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.RegistryLockDao;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.schema.domain.RegistryLock;
import google.registry.util.Clock;
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

  private final Long oldUnlockRevisionId;
  private final DomainLockUtils domainLockUtils;
  private final Response response;
  private final Clock clock;

  @Inject
  public RelockDomainAction(
      @Parameter(PARAM_OLD_UNLOCK_REVISION_ID) Long oldUnlockRevisionId,
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
    DomainBase domain;
    RegistryLock oldLock;
    try {
      oldLock =
          RegistryLockDao.getByRevisionId(oldUnlockRevisionId)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          String.format("Unknown revision ID %d", oldUnlockRevisionId)));
      domain = ofy().load().type(DomainBase.class).id(oldLock.getRepoId()).now();
      checkArgumentNotNull(
          domain, "Domain has been deleted for lock with revision ID %s", oldUnlockRevisionId);
    } catch (Exception e) {
      /* If there's a bad verification code or the domain has been deleted, we won't want to retry.
      AppEngine will retry on non-2xx error codes, so we return SC_NO_CONTENT (204) to avoid it.

      See https://cloud.google.com/appengine/docs/standard/java/taskqueue/push/retrying-tasks
      for more details. */
      logger.atSevere().withCause(e).log(
          "Exception when attempting to re-lock domain with old revision ID %d",
          oldUnlockRevisionId);
      response.setStatus(SC_NO_CONTENT);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Relock failed: %s", e.getMessage()));
      return;
    }
    try {
      if (domain.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES)) {
        logger.atInfo().log(
            "Domain %s already locked, no action necessary", domain.getFullyQualifiedDomainName());
      } else {
        RegistryLock registryLock =
            domainLockUtils.createRegistryLockRequest(
                oldLock.getDomainName(),
                oldLock.getRegistrarId(),
                oldLock.getRegistrarPocId(),
                oldLock.isSuperuser(),
                clock);
        registryLock =
            domainLockUtils.verifyAndApplyLock(
                registryLock.getVerificationCode(), oldLock.isSuperuser(), clock);
        // The old lock object should have a reference to the relock
        RegistryLockDao.save(oldLock.asBuilder().setRelock(registryLock).build());
        logger.atInfo().log("Re-locked domain %s", registryLock.getDomainName());
      }
      response.setStatus(SC_OK);
    } catch (Throwable e) {
      // Any errors that occur here are unexpected, so we should retry. Return a non-2xx error code
      // to get AppEngine to retry
      logger.atSevere().withCause(e).log(
          "Exception when attempting to re-lock domain %s", domain.getFullyQualifiedDomainName());
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setPayload(String.format("Relock failed: %s", e.getMessage()));
    }
  }
}
