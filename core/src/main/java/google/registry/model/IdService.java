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
//
package google.registry.model;

import static com.google.common.base.Preconditions.checkState;

import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.common.annotations.VisibleForTesting;
import google.registry.config.RegistryEnvironment;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocates a globally unique {@link Long} number to use as an Ofy {@link @Id}.
 *
 * <p>In non-test environments the Id is generated by datastore, whereas in tests it's from an
 * atomic long number that's incremented every time this method is called.
 */
public final class IdService {

  /**
   * A placeholder String passed into DatastoreService.allocateIds that ensures that all ids are
   * initialized from the same id pool.
   */
  private static final String APP_WIDE_ALLOCATION_KIND = "common";

  /** Counts of used ids for use in unit tests. Outside tests this is never used. */
  private static final AtomicLong nextTestId = new AtomicLong(1); // ids cannot be zero

  /** Allocates an id. */
  public static long allocateId() {
    return RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get())
        ? nextTestId.getAndIncrement()
        : DatastoreServiceFactory.getDatastoreService()
            .allocateIds(APP_WIDE_ALLOCATION_KIND, 1)
            .iterator()
            .next()
            .getId();
  }

  /** Resets the global test id counter (i.e. sets the next id to 1). */
  @VisibleForTesting
  public static void resetNextTestId() {
    checkState(
        RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get()),
        "Can't call resetTestIdCounts() from RegistryEnvironment.%s",
        RegistryEnvironment.get());
    nextTestId.set(1); // ids cannot be zero
  }
}
