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

package google.registry.model.ofy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

public class ObjectWeights {

  // Mapping from class name to "weight" (which in this case is the order in which the class must
  // be "put" in a transaction with respect to instances of other classes).  Lower weight classes
  // are put first, by default all classes have a weight of zero.
  static final ImmutableMap<String, Integer> CLASS_WEIGHTS =
      ImmutableMap.of(
          "HistoryEntry", -10,
          "AllocationToken", -9,
          "ContactResource", 5,
          "DomainBase", 10);

  // The beginning of the range of weights reserved for delete.  This must be greater than any of
  // the values in CLASS_WEIGHTS by enough overhead to accommodate  any negative values in it.
  // Note: by design, deletions will happen in the opposite order of insertions, which is necessary
  // to make sure foreign keys aren't violated during deletion.
  @VisibleForTesting static final int DELETE_RANGE = Integer.MAX_VALUE / 2;

  /** Returns the weight of the entity type in the map entry. */
  public static int getObjectWeight(String kind, boolean isDelete) {
    int weight = CLASS_WEIGHTS.getOrDefault(kind, 0);
    return isDelete ? DELETE_RANGE - weight : weight;
  }
}
