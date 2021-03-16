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

package google.registry.model.tmch;

import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static google.registry.model.CacheUtils.tryMemoizeWithExpiration;
import static google.registry.model.DatabaseMigrationUtils.isDatastore;
import static google.registry.model.DatabaseMigrationUtils.suppressExceptionUnlessInTest;
import static google.registry.model.common.DatabaseTransitionSchedule.TransitionId.CLAIMS_LIST;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import google.registry.util.NonFinalForTesting;
import java.util.Optional;

/**
 * DAO for {@link ClaimsListShard} objects that handles the branching paths for SQL and Datastore.
 *
 * <p>For write actions, this class will perform the action against the primary database then, after
 * * that success or failure, against the secondary database. If the secondary database fails, an
 * error is logged (but not thrown).
 *
 * <p>For read actions, we will log if the primary and secondary databases * have different values
 * (or if the retrieval from the second database fails).
 */
public class ClaimsListDualDatabaseDao {

  /** In-memory cache for claims list. */
  @NonFinalForTesting
  private static Supplier<ClaimsListShard> claimsListCache =
      tryMemoizeWithExpiration(
          getDomainLabelListCacheDuration(), ClaimsListDualDatabaseDao::getUncached);

  /**
   * Saves the given {@link ClaimsListShard} to both the primary and secondary databases, logging
   * and skipping errors in the secondary DB.
   */
  public static void save(ClaimsListShard claimsList) {
    if (isDatastore(CLAIMS_LIST)) {
      claimsList.save();
      suppressExceptionUnlessInTest(
          () -> ClaimsListSqlDao.save(claimsList), "Error saving ClaimsList to SQL.");
    } else {
      ClaimsListSqlDao.save(claimsList);
      suppressExceptionUnlessInTest(claimsList::save, "Error saving ClaimsListShard to Datastore.");
    }
  }

  /** Returns the most recent revision of the {@link ClaimsListShard}, from cache. */
  public static ClaimsListShard get() {
    return claimsListCache.get();
  }

  /** Retrieves and compares the latest revision from the databases. */
  private static ClaimsListShard getUncached() {
    Optional<ClaimsListShard> primaryResult;
    if (isDatastore(CLAIMS_LIST)) {
      primaryResult = ClaimsListShard.getFromDatastore();
      suppressExceptionUnlessInTest(
          () -> {
            Optional<ClaimsListShard> secondaryResult = ClaimsListSqlDao.get();
            if (claimsListsUnequal(primaryResult, secondaryResult)) {
              throw new IllegalStateException(
                  String.format(
                      "Unequal ClaimsList values from primary Datastore DB (%s) "
                          + "and secondary SQL db (%s).",
                      primaryResult, secondaryResult));
            }
          },
          "Error loading ClaimsList from SQL.");
    } else {
      primaryResult = ClaimsListSqlDao.get();
      suppressExceptionUnlessInTest(
          () -> {
            Optional<ClaimsListShard> secondaryResult = ClaimsListShard.getFromDatastore();
            if (claimsListsUnequal(primaryResult, secondaryResult)) {
              throw new IllegalStateException(
                  String.format(
                      "Unequal ClaimsList values from primary SQL DB (%s) "
                          + "and secondary Datastore db (%s).",
                      primaryResult, secondaryResult));
            }
          },
          "Error loading ClaimsListShard from Datastore.");
    }
    return primaryResult.orElse(ClaimsListShard.create(START_OF_TIME, ImmutableMap.of()));
  }

  private static boolean claimsListsUnequal(
      Optional<ClaimsListShard> primary, Optional<ClaimsListShard> secondary) {
    if (!primary.isPresent()) {
      return secondary.isPresent();
    }
    if (!secondary.isPresent()) {
      return true;
    }
    // Certain fields (e.g. id, creation timestamp) may be different and that's OK
    return !primary.get().getLabelsToKeys().equals(secondary.get().getLabelsToKeys());
  }

  private ClaimsListDualDatabaseDao() {}
}
