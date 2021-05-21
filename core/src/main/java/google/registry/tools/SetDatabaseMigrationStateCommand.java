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

import static com.google.common.base.Preconditions.checkArgument;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Iterables;
import google.registry.model.common.DatabaseMigrationStateWrapper;
import google.registry.model.common.DatabaseMigrationStateWrapper.MigrationState;
import java.util.List;

/** Command to set the current Registry 3.0 migration state of the database. */
@Parameters(separators = " =", commandDescription = "Set the current database migration state.")
public class SetDatabaseMigrationStateCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  @Parameter(description = "MigrationState to set", required = true)
  List<String> mainParameters;

  @Override
  protected String prompt() {
    checkArgument(mainParameters.size() == 1, "Must provide exactly one migration state to set");
    MigrationState currentState = DatabaseMigrationStateWrapper.get();
    MigrationState newState = MigrationState.valueOf(Iterables.getOnlyElement(mainParameters));
    return String.format(
        "Attempt to change from migration state %s to state %s?", currentState, newState);
  }

  @Override
  protected String execute() {
    MigrationState currentState = DatabaseMigrationStateWrapper.get();
    MigrationState newState = MigrationState.valueOf(Iterables.getOnlyElement(mainParameters));
    DatabaseMigrationStateWrapper.set(newState);
    return String.format("Successfully changed from state %s to state %s", currentState, newState);
  }
}
