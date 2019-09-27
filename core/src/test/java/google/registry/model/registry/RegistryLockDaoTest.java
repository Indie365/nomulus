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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import java.util.UUID;
import javax.persistence.PersistenceException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistryLockDao}. */
@RunWith(JUnit4.class)
public final class RegistryLockDaoTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  private FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  @Test
  public void testSaveAndLoad_success() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    RegistryLock fromDatabase = RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
    assertThat(lock).isEqualTo(fromDatabase);
  }

  @Test
  public void testSaveAndLoad_failure_differentCode() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    PersistenceException exception =
        assertThrows(
            PersistenceException.class,
            () -> RegistryLockDao.getByVerificationCode(UUID.randomUUID().toString()));
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("No registry lock with this code");
    assertThat(exception).hasCauseThat().isInstanceOf(NullPointerException.class);
  }

  @Test
  public void testSaveTwiceAndLoad_returnsLatest() {
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    fakeClock.advanceOneMilli();
    RegistryLock secondVersion =
        lock.asBuilder().setCompletionTimestamp(fakeClock.nowUtc()).build();
    RegistryLockDao.save(secondVersion);
    RegistryLock fromDatabase = RegistryLockDao.getByVerificationCode(lock.getVerificationCode());
    assertThat(fromDatabase).isEqualTo(secondVersion);
  }

  @Test
  public void testFailure_saveNull() {
    assertThrows(NullPointerException.class, () -> RegistryLockDao.save(null));
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setRepoId("repoId")
        .setDomainName("example.test")
        .setRegistrarId("TheRegistrar")
        .setAction(Action.LOCK)
        .setCreationTimestamp(fakeClock.nowUtc())
        .setVerificationCode(UUID.randomUUID().toString())
        .isSuperuser(true)
        .build();
  }
}
