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

package google.registry.model.registry.label;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static google.registry.config.RegistryConfig.getSingletonCachePersistDuration;
import static google.registry.model.DatabaseMigrationUtils.suppressExceptionUnlessInTest;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Streams;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.schema.tld.PremiumListSqlDao;
import google.registry.util.NonFinalForTesting;
import java.util.List;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.Duration;

/**
 * DAO for {@link PremiumList} objects that handles the branching paths for SQL and Datastore.
 *
 * <p>This class caches a mapping from premium list name to {@link PremiumList} object, but
 * delegates the retrieval of prices and other information about those lists to the Datastore/SQL
 * DAOs. This is because while the {@link PremiumList} objects are the same in both cases, the ways
 * in which we map that object to the entries themselves are different in the two backends.
 *
 * <p>For write actions, this class will perform the action against the primary database then, after
 * that success or failure, against the secondary database. If the secondary database fails, an
 * error is logged (but not thrown).
 *
 * <p>For read actions, when retrieving a price, we will log if the primary and secondary databases
 * have different values (or if the retrieval from the second database fails).
 *
 * <p>TODO (gbrodman): Change the isOfy() calls to the runtime selection of DBs when available
 */
public class PremiumListDualDao {

  /**
   * In-memory cache for premium lists.
   *
   * <p>This is cached for a shorter duration because we need to periodically reload this entity to
   * check if a new revision has been published, and if so, then use that.
   *
   * <p>We also cache the absence of premium lists with a given name to avoid unnecessary pointless
   * lookups.
   */
  @NonFinalForTesting
  static LoadingCache<String, Optional<PremiumList>> premiumListCache =
      createPremiumListCache(getDomainLabelListCacheDuration());

  @VisibleForTesting
  public static void setPremiumListCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getSingletonCachePersistDuration());
    premiumListCache = createPremiumListCache(effectiveExpiry);
  }

  @VisibleForTesting
  public static LoadingCache<String, Optional<PremiumList>> createPremiumListCache(
      Duration cachePersistDuration) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(java.time.Duration.ofMillis(cachePersistDuration.getMillis()))
        .build(
            new CacheLoader<String, Optional<PremiumList>>() {
              @Override
              public Optional<PremiumList> load(final String name) {
                return tm().doTransactionless(() -> loadPremiumListUncached(name));
              }
            });
  }

  /**
   * Retrieves from the cache and returns the most recent premium list with the given name, or
   * absent if no such list exists.
   */
  public static Optional<PremiumList> getLatestRevision(String premiumListName) {
    return premiumListCache.getUnchecked(premiumListName);
  }

  /**
   * Returns the premium price for the specified label and registry.
   *
   * <p>Returns absent if the label is not premium or there is no premium list for this registry.
   *
   * <p>Retrieves the price from both primary and secondary databases, and logs in the event of a
   * failure in the secondary (but does not throw an exception).
   */
  public static Optional<Money> getPremiumPrice(String label, Registry registry) {
    if (registry.getPremiumList() == null) {
      return Optional.empty();
    }
    Optional<PremiumList> premiumListOptional =
        premiumListCache.getUnchecked(registry.getPremiumList().getName());
    if (!premiumListOptional.isPresent()) {
      return Optional.empty();
    }
    PremiumList premiumList = premiumListOptional.get();
    Optional<Money> primaryResult;
    if (tm().isOfy()) {
      primaryResult =
          PremiumListDatastoreDao.getPremiumPrice(premiumList, label, registry.getTldStr());
    } else {
      primaryResult = PremiumListSqlDao.getPremiumPrice(premiumList, label);
    }
    // Also load the value from the secondary DB, compare the two results, and log if different.
    if (tm().isOfy()) {
      suppressExceptionUnlessInTest(
          () -> {
            Optional<Money> secondaryResult = PremiumListSqlDao.getPremiumPrice(premiumList, label);
            if (!primaryResult.equals(secondaryResult)) {
              throw new IllegalStateException(
                  String.format(
                      "Unequal prices for domain %s.%s from primary Datastore DB (%s) and "
                          + "secondary SQL db (%s).",
                      label, registry.getTldStr(), primaryResult, secondaryResult));
            }
          },
          String.format(
              "Error loading price of domain %s.%s from Cloud SQL.", label, registry.getTldStr()));
    } else {
      suppressExceptionUnlessInTest(
          () -> {
            Optional<Money> secondaryResult =
                PremiumListDatastoreDao.getPremiumPrice(premiumList, label, registry.getTldStr());
            if (!primaryResult.equals(secondaryResult)) {
              throw new IllegalStateException(
                  String.format(
                      "Unequal prices for domain %s.%s from primary SQL DB (%s) and secondary "
                          + "Datastore db (%s).",
                      label, registry.getTldStr(), primaryResult, secondaryResult));
            }
          },
          String.format(
              "Error loading price of domain %s.%s from Datastore.", label, registry.getTldStr()));
    }
    return primaryResult;
  }

  /**
   * Saves the given list data to both primary and secondary databases.
   *
   * <p>Logs but doesn't throw an exception in the event of a failure when writing to the secondary
   * database.
   */
  public static PremiumList save(String name, List<String> inputData) {
    PremiumList result;
    if (tm().isOfy()) {
      result = PremiumListDatastoreDao.save(name, inputData);
      suppressExceptionUnlessInTest(
          () -> PremiumListSqlDao.save(name, inputData), "Error when saving premium list to SQL.");
    } else {
      result = PremiumListSqlDao.save(name, inputData);
      suppressExceptionUnlessInTest(
          () -> PremiumListDatastoreDao.save(name, inputData),
          "Error when saving premium list to Datastore.");
    }
    premiumListCache.invalidate(name);
    return result;
  }

  /**
   * Deletes the premium list.
   *
   * <p>Logs but doesn't throw an exception in the event of a failure when deleting from the
   * secondary database.
   */
  public static void delete(PremiumList premiumList) {
    if (tm().isOfy()) {
      PremiumListDatastoreDao.delete(premiumList);
      suppressExceptionUnlessInTest(
          () -> PremiumListSqlDao.delete(premiumList),
          "Error when deleting premium list from SQL.");
    } else {
      PremiumListSqlDao.delete(premiumList);
      suppressExceptionUnlessInTest(
          () -> PremiumListDatastoreDao.delete(premiumList),
          "Error when deleting premium list from Datastore.");
    }
    premiumListCache.invalidate(premiumList.getName());
  }

  /** Returns whether or not there exists a premium list with the given name. */
  public static boolean exists(String premiumListName) {
    // It may seem like overkill, but loading the list has ways been the way we check existence and
    // given that we usually load the list around the time we check existence, we'll hit the cache
    return premiumListCache.getUnchecked(premiumListName).isPresent();
  }

  /**
   * Returns all {@link PremiumListEntry PremiumListEntries} in the list with the given name.
   *
   * <p>This is an expensive operation and should only be used when the entire list is required.
   */
  public static Iterable<PremiumListEntry> loadAllPremiumListEntries(String premiumListName) {
    PremiumList premiumList =
        getLatestRevision(premiumListName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("No list with name %s", premiumListName)));
    if (tm().isOfy()) {
      return PremiumListDatastoreDao.loadPremiumListEntriesUncached(premiumList);
    } else {
      CurrencyUnit currencyUnit = premiumList.getCurrency();
      return Streams.stream(PremiumListSqlDao.loadPremiumListEntriesUncached(premiumList))
          .map(
              premiumEntry ->
                  new PremiumListEntry.Builder()
                      .setPrice(Money.of(currencyUnit, premiumEntry.getPrice()))
                      .setLabel(premiumEntry.getDomainLabel())
                      .build())
          .collect(toImmutableList());
    }
  }

  private static Optional<PremiumList> loadPremiumListUncached(String premiumListName) {
    Optional<PremiumList> result;
    if (tm().isOfy()) {
      result = PremiumListDatastoreDao.getLatestRevisionUncached(premiumListName);
      suppressExceptionUnlessInTest(
          () -> {
            Optional<PremiumList> sqlResult =
                PremiumListSqlDao.getLatestRevisionUncached(premiumListName);
            if (result.isPresent() && !sqlResult.isPresent()) {
              throw new IllegalStateException(
                  String.format(
                      "Premium list %s is present in Datastore but not SQL.", premiumListName));
            } else if (!result.isPresent() && sqlResult.isPresent()) {
              throw new IllegalStateException(
                  String.format(
                      "Premium list %s is present in SQL but not Datastore.", premiumListName));
            }
          },
          String.format("Error loading premium list %s from SQL.", premiumListName));
    } else {
      result = PremiumListSqlDao.getLatestRevisionUncached(premiumListName);
      suppressExceptionUnlessInTest(
          () -> {
            Optional<PremiumList> datastoreResult =
                PremiumListDatastoreDao.getLatestRevisionUncached(premiumListName);
            if (result.isPresent() && !datastoreResult.isPresent()) {
              throw new IllegalStateException(
                  String.format(
                      "Premium list %s is present in SQL but not Datastore.", premiumListName));
            } else if (!result.isPresent() && datastoreResult.isPresent()) {
              throw new IllegalStateException(
                  String.format(
                      "Premium list %s is present in Datastore but not SQL.", premiumListName));
            }
          },
          String.format("Error loading premium list %s from Datastore.", premiumListName));
    }
    return result;
  }

  private PremiumListDualDao() {}
}
