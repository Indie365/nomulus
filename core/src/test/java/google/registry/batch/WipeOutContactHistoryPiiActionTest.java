// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.Disclose;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.PresenceMarker;
import google.registry.model.eppcommon.StatusValue;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestSqlOnly;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link WipeOutContactHistoryPiiAction}. */
@DualDatabaseTest
class WipeOutContactHistoryPiiActionTest {
  private static final int MIN_MONTHS_BEFORE_WIPE_OUT = 18;
  private static final int BATCH_SIZE = 100;
  private static final ContactResource defaultContactResource =
      new ContactResource.Builder()
          .setContactId("sh8013")
          .setRepoId("2FF-ROID")
          .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_DELETE_PROHIBITED))
          .setLocalizedPostalInfo(
              new PostalInfo.Builder()
                  .setType(PostalInfo.Type.LOCALIZED)
                  .setAddress(
                      new ContactAddress.Builder()
                          .setStreet(ImmutableList.of("123 Grand Ave"))
                          .build())
                  .build())
          .setInternationalizedPostalInfo(
              new PostalInfo.Builder()
                  .setType(PostalInfo.Type.INTERNATIONALIZED)
                  .setName("John Doe")
                  .setOrg("Example Inc.")
                  .setAddress(
                      new ContactAddress.Builder()
                          .setStreet(ImmutableList.of("123 Example Dr.", "Suite 100"))
                          .setCity("Dulles")
                          .setState("VA")
                          .setZip("20166-6503")
                          .setCountryCode("US")
                          .build())
                  .build())
          .setVoiceNumber(
              new ContactPhoneNumber.Builder()
                  .setPhoneNumber("+1.7035555555")
                  .setExtension("1234")
                  .build())
          .setFaxNumber(new ContactPhoneNumber.Builder().setPhoneNumber("+1.7035555556").build())
          .setEmailAddress("jdoe@example.com")
          .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
          .setCreationRegistrarId("NewRegistrar")
          .setLastEppUpdateRegistrarId("NewRegistrar")
          .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
          .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
          .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
          .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("2fooBAR")))
          .setDisclose(
              new Disclose.Builder()
                  .setFlag(true)
                  .setVoice(new PresenceMarker())
                  .setEmail(new PresenceMarker())
                  .build())
          .build();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();
  private final FakeClock clock = new FakeClock(DateTime.parse("2021-08-26T20:21:22Z"));

  private FakeResponse response;
  private WipeOutContactHistoryPiiAction action =
      new WipeOutContactHistoryPiiAction(clock, MIN_MONTHS_BEFORE_WIPE_OUT, BATCH_SIZE, response);

  @BeforeEach
  void beforeEach() {
    response = new FakeResponse();
    action =
        new WipeOutContactHistoryPiiAction(clock, MIN_MONTHS_BEFORE_WIPE_OUT, BATCH_SIZE, response);
  }

  @TestSqlOnly
  void getAllHistoryEntries_returnsEmptyList() {
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action
                            .getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                            .getResultList()))
        .isEmpty();
  }

  @TestSqlOnly
  void getAllHistoryEntries_persistOnlyEntitiesThatShouldBeWiped() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(
            20, MIN_MONTHS_BEFORE_WIPE_OUT + 1, 0, defaultContactResource);

    jpaTm()
        .transact(
            () -> {
              int actualNumOfOldEntities = 0;
              ImmutableList<ContactHistory> entities =
                  ImmutableList.copyOf(
                      action
                          .getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                          .getResultList());
              for (ContactHistory entity : entities) {
                assertThat(expectedToBeWipedOut.contains(entity)).isTrue();
                actualNumOfOldEntities++;
              }
              assertThat(actualNumOfOldEntities).isEqualTo(expectedToBeWipedOut.size());
            });
  }

  @TestSqlOnly
  void getAllHistoryEntries_persistOnlyEntitiesThatShouldNotBeWiped() {
    ImmutableList<ContactHistory> expectedNotToBeWiped =
        persistLotsOfContactHistoryEntities(
            20, MIN_MONTHS_BEFORE_WIPE_OUT, 0, defaultContactResource);
    jpaTm()
        .transact(
            () -> {
              int actualNumOfOldEntities = 0;
              ImmutableList<ContactHistory> entities =
                  ImmutableList.copyOf(
                      action
                          .getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                          .getResultList());
              for (ContactHistory entity : entities) {
                assertThat(expectedNotToBeWiped.contains(entity)).isFalse();
                actualNumOfOldEntities++;
              }
              assertThat(actualNumOfOldEntities).isEqualTo(0);
            });
  }

  @TestSqlOnly
  void getAllHistoryEntries_persistLotsOfEntities_returnsOnlyPartOfTheEntities() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(40, 20, 0, defaultContactResource);
    ImmutableList<ContactHistory> expectedNotToBeWiped =
        persistLotsOfContactHistoryEntities(15, 17, 5, defaultContactResource);
    jpaTm()
        .transact(
            () -> {
              int actualNumOfOldEntities = 0;
              ImmutableList<ContactHistory> entities =
                  ImmutableList.copyOf(
                      action
                          .getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT)
                          .getResultList());
              for (ContactHistory entity : entities) {
                assertThat(expectedToBeWipedOut.contains(entity)).isTrue();
                actualNumOfOldEntities++;
              }
              assertThat(actualNumOfOldEntities).isEqualTo(expectedToBeWipedOut.size());
            });
  }

  @TestSqlOnly
  void wipeOutContactHistoryData_testOneBatch_Success() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(20, 20, 0, defaultContactResource);
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action.wipeOutContactHistoryData(
                            action.getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT))))
        .isEqualTo(expectedToBeWipedOut.size());
  }

  @TestSqlOnly
  void wipeOutContactHistoryData_testMoreThanOneBatch_smallDataSet_Success() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(450, 20, 0, defaultContactResource);
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action.wipeOutContactHistoryData(
                            action.getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT))))
        .isEqualTo(expectedToBeWipedOut.size());
  }

  @TestSqlOnly
  void wipeOutContactHistoryData_testMoreThanOneBatch_largeDataSet_Success() {
    ImmutableList<ContactHistory> expectedToBeWipedOut =
        persistLotsOfContactHistoryEntities(10000, 20, 0, defaultContactResource);
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        action.wipeOutContactHistoryData(
                            action.getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT))))
        .isEqualTo(expectedToBeWipedOut.size());
  }

  @TestSqlOnly
  void wipeOutContactHistoryData_testMixOfWipedAndUpwipedData_Success() {
    int expectedMonthsFromNow1 = 20;
    ImmutableList<ContactHistory> expectedToBeWipedOut1 =
        persistLotsOfContactHistoryEntities(20, expectedMonthsFromNow1, 0, defaultContactResource);
    jpaTm()
        .transact(
            () -> {
              Query query = action.getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT);
              assertThat(action.wipeOutContactHistoryData(query))
                  .isEqualTo(expectedToBeWipedOut1.size());
              ScrollableResults data = query.scroll(ScrollMode.FORWARD_ONLY);
              for (int i = 1; data.next(); i = (i + 1) % BATCH_SIZE) {
                ContactHistory contactHistory = (ContactHistory) data.get(0);
                assertThat(contactHistory.getModificationTime())
                    .isEqualTo(clock.nowUtc().minusMonths(expectedMonthsFromNow1));
                assertThat(contactHistory.getContactBase().get().getEmailAddress()).isNull();
                if (i == 0) {
                  // reset batch builder and flush the session to avoid OOM issue
                  jpaTm().getEntityManager().flush();
                  jpaTm().getEntityManager().clear();
                }
              }
            });
    int expectedMonthsFromNow2 = 21;
    ImmutableList<ContactHistory> expectedToWipedOut2 =
        persistLotsOfContactHistoryEntities(10, expectedMonthsFromNow2, 0, defaultContactResource);
    // Since pii fields of data from expectedToBeWipedOut1 have been wiped, only ContactHistory
    // entities from expectedToWipedOut2 are expected to show up and be wiped.
    jpaTm()
        .transact(
            () -> {
              Query query = action.getAllHistoryEntriesOlderThan(MIN_MONTHS_BEFORE_WIPE_OUT);
              assertThat(action.wipeOutContactHistoryData(query))
                  .isEqualTo(expectedToWipedOut2.size());
              ScrollableResults data = query.scroll(ScrollMode.FORWARD_ONLY);
              for (int i = 1; data.next(); i = (i + 1) % BATCH_SIZE) {
                ContactHistory contactHistory = (ContactHistory) data.get(0);
                assertThat(contactHistory.getModificationTime())
                    .isEqualTo(clock.nowUtc().minusMonths(expectedMonthsFromNow2));
                assertThat(contactHistory.getContactBase().get().getEmailAddress()).isNull();
                if (i == 0) {
                  // reset batch builder and flush the session to avoid OOM issue
                  jpaTm().getEntityManager().flush();
                  jpaTm().getEntityManager().clear();
                }
              }
            });
  }

  @TestSqlOnly
  void wipeOutContactHistoryPii_wipeOutSingleEntity_success() {
    ContactHistory contactHistory =
        persistResource(
            new ContactHistory()
                .asBuilder()
                .setRegistrarId("NewRegistrar")
                .setModificationTime(clock.nowUtc().minusMonths(MIN_MONTHS_BEFORE_WIPE_OUT + 1))
                .setContact(persistResource(defaultContactResource))
                .setType(ContactHistory.Type.CONTACT_DELETE)
                .build());
    ImmutableList.Builder<ContactHistory> contactHistoryData = new ImmutableList.Builder();

    jpaTm()
        .transact(
            () -> action.wipeOutContactHistoryPii(contactHistoryData.add(contactHistory).build()));

    jpaTm()
        .transact(
            () -> {
              ContactHistory contactHistoryFromDb = jpaTm().loadByKey(contactHistory.createVKey());
              assertThat(contactHistoryFromDb.getParentVKey())
                  .isEqualTo(contactHistory.getParentVKey());
              ContactBase contactResourceFromDb = contactHistoryFromDb.getContactBase().get();
              assertThat(contactResourceFromDb.getEmailAddress()).isNull();
              assertThat(contactResourceFromDb.getFaxNumber()).isNull();
              assertThat(contactResourceFromDb.getInternationalizedPostalInfo()).isNull();
              assertThat(contactResourceFromDb.getLocalizedPostalInfo()).isNull();
              assertThat(contactResourceFromDb.getVoiceNumber()).isNull();
            });
  }

  @TestSqlOnly
  void wipeOutContactHistoryPii_wipeOutMultipleEntities_success() {
    ImmutableList<ContactHistory> contactHistoryEntities =
        persistLotsOfContactHistoryEntities(20, 20, 0, defaultContactResource);

    jpaTm().transact(() -> action.wipeOutContactHistoryPii(contactHistoryEntities));

    jpaTm()
        .transact(
            () -> {
              for (ContactHistory contactHistory : contactHistoryEntities) {
                ContactHistory contactHistoryFromDb =
                    jpaTm().loadByKey(contactHistory.createVKey());
                ContactBase contactResourceFromDb = contactHistoryFromDb.getContactBase().get();
                assertThat(contactResourceFromDb.getEmailAddress()).isNull();
                assertThat(contactResourceFromDb.getFaxNumber()).isNull();
                assertThat(contactResourceFromDb.getInternationalizedPostalInfo()).isNull();
                assertThat(contactResourceFromDb.getLocalizedPostalInfo()).isNull();
                assertThat(contactResourceFromDb.getVoiceNumber()).isNull();
              }
            });
  }

  @TestSqlOnly
  void run_testSmallSetOfData_success() {
    ImmutableList<ContactHistory> contactHistoryEntities =
        persistLotsOfContactHistoryEntities(20, 20, 0, defaultContactResource);

    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @TestSqlOnly
  void run_testLargeSetOfData_success() {
    ImmutableList<ContactHistory> contactHistoryEntities =
        persistLotsOfContactHistoryEntities(1000, 20, 0, defaultContactResource);

    action.run();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  /** persists a number of ContactHistory entities for load and query testing. */
  ImmutableList<ContactHistory> persistLotsOfContactHistoryEntities(
      int numOfEntities, int minusMonths, int minusDays, ContactResource contact) {
    ImmutableList.Builder<ContactHistory> expectedEntitesBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < numOfEntities; i++) {
      expectedEntitesBuilder.add(
          persistResource(
              new ContactHistory()
                  .asBuilder()
                  .setRegistrarId("NewRegistrar")
                  .setModificationTime(clock.nowUtc().minusMonths(minusMonths).minusDays(minusDays))
                  .setType(ContactHistory.Type.CONTACT_DELETE)
                  .setContact(persistResource(contact))
                  .build()));
    }
    return expectedEntitesBuilder.build();
  }
}
