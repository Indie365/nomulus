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
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.VKey;
import org.joda.time.LocalDate;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SafeBrowsingThreat}. */
public class SafeBrowsingThreatTest extends EntityTestCase {

  private SafeBrowsingThreat threat;
  private static final LocalDate DATE = LocalDate.parse("2020-06-10", ISODateTimeFormat.date());

  public SafeBrowsingThreatTest() {
    super(true);
  }

  @BeforeEach
  public void setUp() {

    VKey<ContactResource> registrantContactKey =
        VKey.createSql(ContactResource.class, "contact_id");
    String domainRepoId = "4-TLD";
    createTld("tld");

    /** Persist a registrar for the purpose of testing a foreign key reference. */
    String registrarClientId = "registrar";
    saveRegistrar(registrarClientId);

    /** Persist a domain for the purpose of testing a foreign key reference. */
    DomainBase domain =
        new DomainBase()
            .asBuilder()
            .setCreationClientId(registrarClientId)
            .setPersistedCurrentSponsorClientId(registrarClientId)
            .setDomainName("foo.tld")
            .setRepoId(domainRepoId)
            .setRegistrant(registrantContactKey)
            .setContacts(ImmutableSet.of())
            .build();

    /** Persist a contact for the foreign key reference in the Domain table. */
    ContactResource registrantContact =
        new ContactResource.Builder()
            .setRepoId("contact_id")
            .setCreationClientId(registrarClientId)
            .setTransferData(new ContactTransferData.Builder().build())
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
            .setCheckDate(DATE)
            .setDomainName("foo.tld")
            .setDomainRepoId(domainRepoId)
            .setRegistrarId(registrarClientId)
            .build();
  }

  @Test
  public void testPersistence() {
    jpaTm().transact(() -> jpaTm().saveNew(threat));

    VKey<SafeBrowsingThreat> threatVKey = VKey.createSql(SafeBrowsingThreat.class, threat.getId());
    SafeBrowsingThreat persistedThreat = jpaTm().transact(() -> jpaTm().load(threatVKey));
    threat.id = persistedThreat.id;
    assertThat(threat).isEqualTo(persistedThreat);
  }

  @Test
  public void testFailure_threatsWithNullFields() {
    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setRegistrarId(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setDomainName(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setCheckDate(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setThreatType(null).build());

    assertThrows(
        IllegalArgumentException.class, () -> threat.asBuilder().setDomainRepoId(null).build());
  }
}
