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

package google.registry.model.smd;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;

import com.google.common.collect.ImmutableMap;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public class SignedMarkRevocationListDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @RegisterExtension
  @Order(value = 1)
  final DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @Test
  void testSave_success() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.trySave(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.getLatestRevision().get();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list);
  }

  @Test
  void trySave_failureIsSwallowed() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.trySave(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.getLatestRevision().get();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list);

    // This should throw an exception, which is swallowed and nothing changed
    SignedMarkRevocationListDao.trySave(list);
    SignedMarkRevocationList secondFromDb = SignedMarkRevocationListDao.getLatestRevision().get();
    assertAboutImmutableObjects().that(secondFromDb).isEqualExceptFields(fromDb);
  }

  @Test
  void testRetrieval_notPresent() {
    assertThat(SignedMarkRevocationListDao.getLatestRevision().isPresent()).isFalse();
  }

  @Test
  void testSaveAndRetrieval_emptyList() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(fakeClock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.trySave(list);
    SignedMarkRevocationList fromDb = SignedMarkRevocationListDao.getLatestRevision().get();
    assertAboutImmutableObjects().that(fromDb).isEqualExceptFields(list);
  }

  @Test
  void testSave_multipleVersions() {
    SignedMarkRevocationList list =
        SignedMarkRevocationList.create(
            fakeClock.nowUtc(), ImmutableMap.of("mark", fakeClock.nowUtc().minusHours(1)));
    SignedMarkRevocationListDao.trySave(list);
    assertThat(
            SignedMarkRevocationListDao.getLatestRevision()
                .get()
                .isSmdRevoked("mark", fakeClock.nowUtc()))
        .isTrue();

    // Now remove the revocation
    SignedMarkRevocationList secondList =
        SignedMarkRevocationList.create(fakeClock.nowUtc(), ImmutableMap.of());
    SignedMarkRevocationListDao.trySave(secondList);
    assertThat(
            SignedMarkRevocationListDao.getLatestRevision()
                .get()
                .isSmdRevoked("mark", fakeClock.nowUtc()))
        .isFalse();
  }
}
