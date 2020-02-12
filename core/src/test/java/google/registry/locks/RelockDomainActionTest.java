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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;

import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.UserInfo;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RelockDomainAction}. */
@RunWith(JUnit4.class)
public class RelockDomainActionTest {

  private static final String DOMAIN_NAME = "example.tld";
  private static final String CLIENT_ID = "TheRegistrar";
  private static final String POC_ID = "marla.singer@example.com";

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock();
  private final DomainLockUtils domainLockUtils =
      new DomainLockUtils(new DeterministicStringGenerator(Alphabets.BASE_58));

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder()
          .withDatastore()
          .withUserService(UserInfo.create(POC_ID, "12345"))
          .build();

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(clock).buildIntegrationWithCoverageRule();

  private DomainBase domain;
  private RelockDomainAction action;

  @Before
  public void setup() {
    createTlds("tld", "net");
    HostResource host = persistActiveHost("ns1.example.net");
    domain = persistResource(newDomainBase(DOMAIN_NAME, host));
  }

  @Test
  public void testLock() {
    action = createAction(DOMAIN_NAME, CLIENT_ID, Optional.of(POC_ID), false);
    action.run();
    assertThat(reloadDomain(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  public void testSuccess_isAdmin_noPocId() {
    action = createAction(DOMAIN_NAME, CLIENT_ID, Optional.empty(), true);
    action.run();
    assertThat(reloadDomain(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  public void testFailure_missingDomainName() {
    action = createAction(null, CLIENT_ID, Optional.of(POC_ID), false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Relock failed: fullyQualifiedDomainName cannot be null");
  }

  @Test
  public void testFailure_nonexistentDomainName() {
    action = createAction("nonexistent.tld", CLIENT_ID, Optional.of(POC_ID), false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload())
        .isEqualTo("Relock failed: Domain 'nonexistent.tld' does not exist or is deleted");
  }

  @Test
  public void testFailure_missingClientId() {
    action = createAction(DOMAIN_NAME, null, Optional.of(POC_ID), false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload()).isEqualTo("Relock failed: clientId cannot be null");
  }

  @Test
  public void testFailure_missingRegistrarPoc_notAdmin() {
    action = createAction(DOMAIN_NAME, CLIENT_ID, Optional.empty(), false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    assertThat(response.getPayload())
        .isEqualTo("Relock failed: registrarPocId cannot be null if not isAdmin");
  }

  private DomainBase reloadDomain(DomainBase domain) {
    return ofy().load().entity(domain).now();
  }

  private RelockDomainAction createAction(
      String domainName, String clientId, Optional<String> registrarPocId, boolean isAdmin) {
    return new RelockDomainAction(
        domainName, clientId, registrarPocId, isAdmin, domainLockUtils, response, clock);
  }
}
