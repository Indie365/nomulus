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

import google.registry.model.common.DatabaseMigrationStateWrapper;
import google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;

/** Tests for {@link google.registry.tools.CheckDatabaseMigrationStateCommand}. */
@DualDatabaseTest
public class CheckDatabaseMigrationStateCommandTest
    extends CommandTestCase<CheckDatabaseMigrationStateCommand> {

  @TestOfyAndSql
  void testInitial_returnsDatastoreOnly() throws Exception {
    runCommand();
    assertStdoutIs("Current migration state: DATASTORE_ONLY\n");
  }

  @TestOfyAndSql
  void testOtherState() throws Exception {
    DatabaseMigrationStateWrapper.set(MigrationState.DATASTORE_PRIMARY);
    DatabaseMigrationStateWrapper.set(MigrationState.DATASTORE_PRIMARY_READ_ONLY);
    DatabaseMigrationStateWrapper.set(MigrationState.SQL_PRIMARY);
    runCommand();
    assertStdoutIs("Current migration state: SQL_PRIMARY\n");
  }
}
