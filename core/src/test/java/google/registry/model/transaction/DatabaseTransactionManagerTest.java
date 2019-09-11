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

package google.registry.model.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.persistence.PersistenceModule;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceException;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.PostgreSQLContainer;

/** Unit tests for {@link DatabaseTransactionManager}. */
@RunWith(JUnit4.class)
public class DatabaseTransactionManagerTest {
  @Rule public PostgreSQLContainer database = new PostgreSQLContainer();

  private DateTime now = DateTime.now(UTC);
  private Clock clock = new FakeClock(now);
  private DatabaseTransactionManager txnManager;

  @Before
  public void init() {
    EntityManagerFactory emf =
        PersistenceModule.create(
            database.getJdbcUrl(),
            database.getUsername(),
            database.getPassword(),
            PersistenceModule.providesDefaultDatabaseConfigs());
    txnManager = new DatabaseTransactionManager(emf, clock);
  }

  @Test
  public void inTransaction_returnsCorrespondingResult() {
    assertThat(txnManager.inTransaction()).isFalse();
    txnManager.transact(
        () -> {
          assertThat(txnManager.inTransaction()).isTrue();
        });
    assertThat(txnManager.inTransaction()).isFalse();
  }

  @Test
  public void assertInTransaction_throwsExceptionWhenNotInTransaction() {
    assertThrows(PersistenceException.class, () -> txnManager.assertInTransaction());
    txnManager.transact(() -> txnManager.assertInTransaction());
    assertThrows(PersistenceException.class, () -> txnManager.assertInTransaction());
  }

  @Test
  public void getTransactionTime_throwsExceptionWhenNotInTransaction() {
    assertThrows(PersistenceException.class, () -> txnManager.getTransactionTime());
    txnManager.transact(
        () -> {
          assertThat(txnManager.getTransactionTime()).isEqualTo(now);
        });
    assertThrows(PersistenceException.class, () -> txnManager.getTransactionTime());
  }
}
