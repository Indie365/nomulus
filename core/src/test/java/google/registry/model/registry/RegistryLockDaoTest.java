// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.registry;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.testing.AppEngineRule;
import java.util.Optional;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistryLockDao}. */
@RunWith(JUnit4.class)
public final class RegistryLockDaoTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Rule
  public final JpaIntegrationTestRule jpaRule =
      new JpaTestRules.Builder().buildIntegrationTestRule();

  @Test
  public void testSaveAndLoad_success() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    RegistryLock fromDatabase = RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
    assertThat(fromDatabase.getDomainName()).isEqualTo(lock.getDomainName());
    assertThat(fromDatabase.getVerificationCode()).isEqualTo(lock.getVerificationCode());
  }

  @Test
  public void testSaveAndLoad_failure_differentCode() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> RegistryLockDao.getByVerificationCode(UUID.randomUUID().toString()));
    assertThat(thrown).hasMessageThat().isEqualTo("No registry lock with this code");
  }

  @Test
  public void testSaveTwiceAndLoad_returnsLatest() {
    RegistryLock lock = createLock();
    jpaTm().transact(() -> RegistryLockDao.save(lock));
    jpaRule.getTxnClock().advanceOneMilli();
    jpaTm()
        .transact(
            () -> {
              RegistryLock updatedLock =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
              updatedLock.setCompletionTimestamp(jpaRule.getTxnClock().nowUtc());
              RegistryLockDao.save(updatedLock);
            });
    jpaTm()
        .transact(
            () -> {
              RegistryLock fromDatabase =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
              assertThat(fromDatabase.getCompletionTimestamp().get())
                  .isEqualTo(jpaRule.getTxnClock().nowUtc());
            });
  }

  @Test
  public void testUpdateLock_usingSamePrimaryKey() {
    RegistryLock lock = RegistryLockDao.save(createLock());
    jpaRule.getTxnClock().advanceOneMilli();
    RegistryLock updatedLock =
        lock.asBuilder().setCompletionTimestamp(jpaRule.getTxnClock().nowUtc()).build();
    jpaTm().transact(() -> RegistryLockDao.save(updatedLock));
    jpaTm()
        .transact(
            () -> {
              RegistryLock fromDatabase =
                  RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
              assertThat(fromDatabase.getCompletionTimestamp())
                  .isEqualTo(Optional.of(jpaRule.getTxnClock().nowUtc()));
            });
  }

  @Test
  public void testFailure_saveNull() {
    assertThrows(NullPointerException.class, () -> RegistryLockDao.save(null));
  }

  @Test
  public void testLoad_byRegistrarId() {
    RegistryLock lock = createLock();
    RegistryLock secondLock = createLock().asBuilder().setDomainName("otherexample.test").build();
    RegistryLockDao.save(lock);
    RegistryLockDao.save(secondLock);

    assertThat(
            RegistryLockDao.getByRegistrarId("TheRegistrar").stream()
                .map(RegistryLock::getDomainName)
                .collect(toImmutableSet()))
        .containsExactly("example.test", "otherexample.test");
    assertThat(RegistryLockDao.getByRegistrarId("nonexistent")).isEmpty();
  }

  @Test
  public void testLoad_byRepoId() {
    RegistryLock completedLock =
        createLock().asBuilder().setCompletionTimestamp(jpaRule.getTxnClock().nowUtc()).build();
    RegistryLockDao.save(completedLock);

    jpaRule.getTxnClock().advanceOneMilli();
    RegistryLock inProgressLock = createLock();
    RegistryLockDao.save(inProgressLock);

    Optional<RegistryLock> mostRecent = RegistryLockDao.getMostRecentByRepoId("repoId");
    assertThat(mostRecent.isPresent()).isTrue();
    assertThat(mostRecent.get().isVerified()).isFalse();
  }

  @Test
  public void testLoad_byRepoId_empty() {
    assertThat(RegistryLockDao.getMostRecentByRepoId("nonexistent").isPresent()).isFalse();
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setRepoId("repoId")
        .setDomainName("example.test")
        .setRegistrarId("TheRegistrar")
        .setAction(Action.LOCK)
        .setVerificationCode(UUID.randomUUID().toString())
        .isSuperuser(true)
        .build();
  }
}
