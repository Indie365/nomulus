// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import com.beust.jcommander.Parameters;
import google.registry.config.RegistryEnvironment;
import google.registry.tools.Command.GtechCommand;

/** Command to create a TLD in sandbox, separated out for Gtech use. */
@Parameters(separators = " =", commandDescription = "Create new sandbox TLD(s)")
final class CreateSandboxTldCommand extends CreateTldCommand implements GtechCommand {

  @Override
  void assertAllowedEnvironment() {
    checkArgument(
        RegistryEnvironment.get() == RegistryEnvironment.SANDBOX,
        "This command can only be run in the sandbox environment");
  }
}
