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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.reporting.SafeBrowsingThreat.ThreatType.MALWARE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.transfer.TransferData;
import google.registry.persistence.VKey;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SafeBrowsingThreat}. */
public class SafeBrowsingThreatTest extends EntityTestCase {

  private SafeBrowsingThreat threat;
  private final LocalDate date = new LocalDate();

  public SafeBrowsingThreatTest() {
    super(true);
  }

  @BeforeEach
  public void setUp() {
    DomainBase domain;
    ContactResource registrantContact;
    VKey<ContactResource> registrantContactKey;

    registrantContactKey = VKey.createSql(ContactResource.class, "contact_id");
    String domainRepoId = "4-TLD";
    createTld("tld");

    /** Persist a registrar for the purpose of testing a foreign key reference. */
    String registrarClientId = "registrar";
    saveRegistrar(registrarClientId);

    /** Persist a domain for the purpose of testing a foreign key reference. */
    domain =
        new DomainBase()
            .asBuilder()
            .setCreationClientId(registrarClientId)
            .setPersistedCurrentSponsorClientId(registrarClientId)
            .setFullyQualifiedDomainName("foo.tld")
            .setRepoId(domainRepoId)
            .setRegistrant(registrantContactKey)
            .setContacts(ImmutableSet.of())
            .build();

    /** Persist a contact for the foreign key reference in the Domain table. */
    registrantContact =
        new ContactResource.Builder()
            .setRepoId("contact_id")
            .setCreationClientId(registrarClientId)
            .setTransferData(new TransferData.Builder().build())
            .setPersistedCurrentSponsorClientId(registrarClientId)
            .build();

    jpaTm()
        .transact(
            () -> {
              jpaTm().saveNew(registrantContact);
              jpaTm().saveNew(domain);
            });

    threat =
        new SafeBrowsingThreat.Builder()
            .setThreatType(MALWARE)
            .setCheckDate(date)
            .setDomainName("foo.tld")
            .setDomainRepoId(domainRepoId)
            .setRegistrarId(registrarClientId)
            .build();
  }

  @Test
  public void testPersistence() {
    jpaTm().transact(() -> jpaTm().saveNew(threat));

    VKey<SafeBrowsingThreat> threatVKey = VKey.createSql(SafeBrowsingThreat.class, 1L);
    SafeBrowsingThreat persistedThreat = jpaTm().transact(() -> jpaTm().load(threatVKey));
    threat.id = persistedThreat.id;
    assertThat(threat).isEqualTo(persistedThreat);
  }

  @Test
  public void testFailure_threatsWithNullFields() {
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
        .setRegistrarId("testRegistrar")
        .setDomainName("threat.com")
        .setCheckDate(date)
        .setThreatType(MALWARE)
        .setDomainRepoId("testDomain");
  }
}
