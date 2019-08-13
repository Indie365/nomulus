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

package google.registry.model.tmch;

import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.joda.time.DateTime;

/** A list of TMCH claims labels and their associated claims keys. */
public class ClaimsList {

  private final DateTime creationTime;
  private final ImmutableMap<String, String> labelsToKeys;

  private ClaimsList(DateTime creationTime, ImmutableMap<String, String> labelsToKeys) {
    this.creationTime = creationTime;
    this.labelsToKeys = labelsToKeys;
  }

  /** Constructs a {@link ClaimsList} object. */
  public static ClaimsList create(
      DateTime creationTime, ImmutableMap<String, String> labelsToKeys) {
    return new ClaimsList(creationTime, labelsToKeys);
  }

  /** Returns the creation time of this claims list. */
  public DateTime getCreationTime() {
    return creationTime;
  }

  /** Returns an {@link ImmutableMap} mapping domain label to its lookup key. */
  public ImmutableMap<String, String> getLabelsToKeys() {
    return labelsToKeys;
  }

  /** Returns the claim key for a given domain if there is one, empty otherwise. */
  public Optional<String> getClaimKey(String label) {
    return Optional.ofNullable(labelsToKeys.get(label));
  }
}
