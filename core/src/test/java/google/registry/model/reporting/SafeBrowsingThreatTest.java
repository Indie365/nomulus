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

package google.registry.model.reporting;

import google.registry.model.EntityTestCase;
import google.registry.persistence.VKey;
import google.registry.testing.DatastoreHelper;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.reporting.SafeBrowsingThreat.ThreatType.MALWARE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.junit.Assert.assertThrows;

/** Unit tests for {@link SafeBrowsingThreat}. */
public class SafeBrowsingThreatTest extends EntityTestCase {

  private SafeBrowsingThreat threatOrg;
  private final LocalDate date = new LocalDate();

  public SafeBrowsingThreatTest() {
    super(true);
  }

  @BeforeEach
  public void setUp() {
    String domainForeignKey = DatastoreHelper.persistActiveDomain("foo.tld").getForeignKey();
    String registrarForeignKey = "registrar";
    saveRegistrar(registrarForeignKey);

    threatOrg =
        new SafeBrowsingThreat.Builder()
            .setThreatType(MALWARE)
            .setCheckDate(date)
            .setDomainName("threat.org")
            .setTld("org")
            .setDomainRepoId(domainForeignKey)
            .setRegistrarId(registrarForeignKey)
            .build();

    jpaTm().transact(() -> jpaTm().saveNew(threatOrg));
  }

  @Test
  public void testPersistence() {
    VKey<SafeBrowsingThreat> threatOrgVKey = VKey.createSql(SafeBrowsingThreat.class, 1L);
    SafeBrowsingThreat persistedThreatOrg = jpaTm().transact(() -> jpaTm().load(threatOrgVKey));
    assertThreatsEqual(threatOrg, persistedThreatOrg);
  }

  @Test
  public void testFailure_threatsWithNullFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> commonInit(new SafeBrowsingThreat.Builder()).setTld(null).build());

    assertThrows(
        IllegalArgumentException.class,
        () -> commonInit(new SafeBrowsingThreat.Builder()).setRegistrarId(null).build());

    assertThrows(
        IllegalArgumentException.class,
        () -> commonInit(new SafeBrowsingThreat.Builder()).setDomainName(null).build());

    assertThrows(
        IllegalArgumentException.class,
        () -> commonInit(new SafeBrowsingThreat.Builder()).setCheckDate(null).build());

    assertThrows(
        IllegalArgumentException.class,
        () -> commonInit(new SafeBrowsingThreat.Builder()).setThreatType(null).build());

    assertThrows(
        IllegalArgumentException.class,
        () -> commonInit(new SafeBrowsingThreat.Builder()).setDomainRepoId(null).build());
  }

  private SafeBrowsingThreat.Builder commonInit(SafeBrowsingThreat.Builder builder) {
    return builder
        .setTld("com")
        .setRegistrarId("testRegistrar")
        .setDomainName("threat.com")
        .setCheckDate(date)
        .setThreatType(MALWARE)
        .setDomainRepoId("testDomain");
  }

  private void assertThreatsEqual(SafeBrowsingThreat threat1, SafeBrowsingThreat threat2) {
    assertThat(threat1.getCheckDate()).isEqualTo(threat2.getCheckDate());
    assertThat(threat1.getTld()).isEqualTo(threat2.getTld());
    assertThat(threat1.getThreatType()).isEqualTo(threat2.getThreatType());
    assertThat(threat1.getDomainRepoId()).isEqualTo(threat2.getDomainRepoId());
    assertThat(threat1.getRegistrarId()).isEqualTo(threat2.getRegistrarId());
    assertThat(threat1.getDomainName()).isEqualTo(threat2.getDomainName());
  }
}
