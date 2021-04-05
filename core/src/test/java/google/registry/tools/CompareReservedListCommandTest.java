// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.google.common.collect.ImmutableMap;
import google.registry.model.registry.label.ReservationType;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.model.registry.label.ReservedListDatastoreDao;
import google.registry.model.registry.label.ReservedListDualDatabaseDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CompareReservedListsCommand}. */
public class CompareReservedListCommandTest extends CommandTestCase<CompareReservedListsCommand> {

  private ReservedList reservedList;
  private ReservedList reservedList2;

  @BeforeEach
  void setUp() {
    reservedList =
        new ReservedList.Builder()
            .setName("testlist")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(
                ImmutableMap.of(
                    "food",
                    ReservedListEntry.create(
                        "food", ReservationType.RESERVED_FOR_SPECIFIC_USE, null),
                    "music",
                    ReservedListEntry.create(
                        "music", ReservationType.FULLY_BLOCKED, "fully blocked")))
            .build();

    reservedList2 =
        new ReservedList.Builder()
            .setName("testlist2")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(
                ImmutableMap.of(
                    "candy",
                    ReservedListEntry.create("candy", ReservationType.ALLOWED_IN_SUNRISE, null)))
            .build();

    ReservedListDualDatabaseDao.save(reservedList);
    ReservedListDualDatabaseDao.save(reservedList2);
  }

  @Test
  void test_success() throws Exception {
    runCommand();
    String stdout = getStdoutAsString();
    assertThat(stdout).isEqualTo("Found 0 unequal list(s).\n");
  }

  @Test
  void test_listMissingFromCloudSql() throws Exception {
    jpaTm().transact(() -> jpaTm().delete(reservedList));
    runCommand();
    String stdout = getStdoutAsString();
    assertThat(stdout)
        .isEqualTo(
            "ReservedList with name testlist is present in Datastore, but not in Cloud SQL\n"
                + "Found 1 unequal list(s).\n");
  }

  @Test
  void test_listMissingFromDatastore() throws Exception {
    ofyTm().transact(() -> ofyTm().delete(reservedList));
    runCommand();
    String stdout = getStdoutAsString();
    assertThat(stdout)
        .isEqualTo(
            "ReservedList with name testlist is present in Cloud SQL, but not in Datastore\n"
                + "Found 1 unequal list(s).\n");
  }

  @Test
  void test_listsDiffer() throws Exception {
    ImmutableMap<String, ReservedListEntry> newReservations =
        ImmutableMap.of(
            "food",
            ReservedListEntry.create("food", ReservationType.RESERVED_FOR_SPECIFIC_USE, null));
    ReservedList secondList =
        new ReservedList.Builder()
            .setName("testlist")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(newReservations)
            .build();
    ReservedListDatastoreDao.save(secondList);
    runCommand();
    String stdout = getStdoutAsString();
    assertThat(stdout)
        .isEqualTo(
            "ReservedList with name testlist has different entries in each database\n"
                + "Found 1 unequal list(s).\n");
  }

  @Test
  void test_listsDifferAndMissing() throws Exception {
    ofyTm().transact(() -> ofyTm().delete(reservedList2));
    ImmutableMap<String, ReservedListEntry> newReservations =
        ImmutableMap.of(
            "food",
            ReservedListEntry.create("food", ReservationType.RESERVED_FOR_SPECIFIC_USE, null));
    ReservedList secondList =
        new ReservedList.Builder()
            .setName("testlist")
            .setLastUpdateTime(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(newReservations)
            .build();
    ReservedListDatastoreDao.save(secondList);
    runCommand();
    String stdout = getStdoutAsString();
    assertThat(stdout)
        .isEqualTo(
            "ReservedList with name testlist has different entries in each database\n"
                + "ReservedList with name testlist2 is present in Cloud SQL, but not in Datastore\n"
                + "Found 2 unequal list(s).\n");
  }
}
