// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import google.registry.model.common.FeatureFlag;
import java.io.PrintStream;
import javax.inject.Inject;

/** Command to list all {@link google.registry.model.common.FeatureFlag} objects. */
@Parameters(separators = " =", commandDescription = "List all feature flags.")
public class ListFeatureFlagsCommand implements Command {

  @Inject ObjectMapper mapper;

  @Override
  public void run() throws Exception {
    PrintStream printStream = new PrintStream(System.out, false, UTF_8);
    ImmutableList<FeatureFlag> featureFlags = FeatureFlag.getAll();
    for (FeatureFlag featureFlag : featureFlags) {
      printStream.println(mapper.writeValueAsString(featureFlag));
    }
  }
}
