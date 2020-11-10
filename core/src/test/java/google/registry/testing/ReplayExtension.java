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

package google.registry.testing;

import google.registry.model.ofy.ReplayQueue;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A JUnit extension that replays datastore transactions against postgresql.
 *
 * <p>This extension must be ordered before AppEngineExtension. If AppEngineExtension is not used,
 * JpaTransactionManagerException must be, and this extension should be ordered _after_
 * JpaTransactionManagerException.
 */
public class ReplayExtension implements BeforeEachCallback, AfterEachCallback {

  FakeClock clock;

  public ReplayExtension(FakeClock clock) {
    this.clock = clock;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    DatastoreHelper.setClock(clock);
    DatastoreHelper.setAlwaysSaveWithBackup(true);
    ReplayQueue.clear();
    context.getStore(ExtensionContext.Namespace.GLOBAL).put(ReplayExtension.class, this);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // This ensures that we do the replay even if we're not called from AppEngineExtension.  It
    // should be safe to call replayToSql() twice, as the replay queue should be empty the second
    // time.
    replayToSql();
  }

  public void replayToSql() {
    DatastoreHelper.setAlwaysSaveWithBackup(false);
    ReplayQueue.replay();
  }
}
