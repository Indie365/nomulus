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
import static google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState.DATASTORE_PRIMARY;
import static google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState.SQL_ONLY;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.model.common.DatabaseMigrationStateWrapper;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;

/** Tests for {@link SetDatabaseMigrationStateCommand}. */
@DualDatabaseTest
public class SetDatabaseMigrationStateCommandTest
    extends CommandTestCase<SetDatabaseMigrationStateCommand> {

  @TestOfyAndSql
  void testSuccess_setsDatastorePrimary() throws Exception {
    runCommandForced("DATASTORE_PRIMARY");
    assertThat(DatabaseMigrationStateWrapper.get()).isEqualTo(DATASTORE_PRIMARY);
  }

  @TestOfyAndSql
  void testSuccess_setsEntireChain() throws Exception {
    runCommandForced("DATASTORE_PRIMARY");
    runCommandForced("DATASTORE_PRIMARY_READ_ONLY");
    runCommandForced("SQL_PRIMARY");
    runCommandForced("SQL_ONLY");
    assertThat(DatabaseMigrationStateWrapper.get()).isEqualTo(SQL_ONLY);
  }

  @TestOfyAndSql
  void testSuccess_goesBackwards() throws Exception {
    runCommandForced("DATASTORE_PRIMARY");
    runCommandForced("DATASTORE_PRIMARY_READ_ONLY");
    runCommandForced("DATASTORE_PRIMARY");
    assertThat(DatabaseMigrationStateWrapper.get()).isEqualTo(DATASTORE_PRIMARY);
  }

  @TestOfyAndSql
  void testFailure_invalidTransition() {
    assertThrows(
        IllegalArgumentException.class, () -> runCommandForced("DATASTORE_PRIMARY_READ_ONLY"));
  }

  @TestOfyAndSql
  void testFailure_invalidParam() {
    assertThrows(IllegalArgumentException.class, () -> runCommandForced("FOOBAR"));
  }

  @TestOfyAndSql
  void testFailure_multipleMainParams() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommandForced("DATASTORE_ONLY", "DATASTORE_PRIMARY"));
  }

  @TestOfyAndSql
  void testFailure_noParams() {
    assertThrows(ParameterException.class, this::runCommandForced);
  }
}
