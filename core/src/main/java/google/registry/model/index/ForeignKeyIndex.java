// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.index;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getEppResourceCachingDuration;
import static google.registry.config.RegistryConfig.getEppResourceMaxCachedEntries;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaJpaTm;
import static google.registry.util.CollectionUtils.entriesToImmutableMap;
import static google.registry.util.TypeUtils.instantiate;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Streams;
import google.registry.config.RegistryConfig;
import google.registry.model.BackupGroupRoot;
import google.registry.model.CacheUtils;
import google.registry.model.EppResource;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.util.NonFinalForTesting;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Class to map a foreign key to the active instance of {@link EppResource} whose unique id matches
 * the foreign key string. The instance is never deleted, but it is updated if a newer entity
 * becomes the active entity.
 */
public abstract class ForeignKeyIndex<E extends EppResource> extends BackupGroupRoot {

  /** The {@link ForeignKeyIndex} type for {@link Contact} entities. */
  public static class ForeignKeyContactIndex extends ForeignKeyIndex<Contact> {}

  /** The {@link ForeignKeyIndex} type for {@link Domain} entities. */
  public static class ForeignKeyDomainIndex extends ForeignKeyIndex<Domain> {}

  /** The {@link ForeignKeyIndex} type for {@link Host} entities. */
  public static class ForeignKeyHostIndex extends ForeignKeyIndex<Host> {}

  private static final ImmutableBiMap<
          Class<? extends EppResource>, Class<? extends ForeignKeyIndex<?>>>
      RESOURCE_CLASS_TO_FKI_CLASS =
          ImmutableBiMap.of(
              Contact.class, ForeignKeyContactIndex.class,
              Domain.class, ForeignKeyDomainIndex.class,
              Host.class, ForeignKeyHostIndex.class);

  private static final ImmutableMap<Class<? extends EppResource>, String>
      RESOURCE_CLASS_TO_FKI_PROPERTY =
          ImmutableMap.of(
              Contact.class, "contactId",
              Domain.class, "fullyQualifiedDomainName",
              Host.class, "fullyQualifiedHostName");

  String foreignKey;

  /**
   * The deletion time of this {@link ForeignKeyIndex}.
   *
   * <p>This will generally be equal to the deletion time of {@link #reference}. However, in the
   * case of a {@link Host} that was renamed, this field will hold the time of the rename.
   */
  DateTime deletionTime;

  /** The referenced resource. */
  VKey<E> reference;

  public String getForeignKey() {
    return foreignKey;
  }

  public DateTime getDeletionTime() {
    return deletionTime;
  }

  public VKey<E> getResourceKey() {
    return reference;
  }

  @SuppressWarnings("unchecked")
  public static <T extends EppResource> Class<ForeignKeyIndex<T>> mapToFkiClass(
      Class<T> resourceClass) {
    return (Class<ForeignKeyIndex<T>>) RESOURCE_CLASS_TO_FKI_CLASS.get(resourceClass);
  }

  /** Create a {@link ForeignKeyIndex} instance for a resource, expiring at a specified time. */
  @SuppressWarnings("unchecked")
  public static <E extends EppResource> ForeignKeyIndex<E> create(
      E resource, DateTime deletionTime) {
    Class<E> resourceClass = (Class<E>) resource.getClass();
    ForeignKeyIndex<E> instance = instantiate(mapToFkiClass(resourceClass));
    instance.reference = (VKey<E>) resource.createVKey();
    instance.foreignKey = resource.getForeignKey();
    instance.deletionTime = deletionTime;
    return instance;
  }

  /**
   * Loads a {@link VKey} to an {@link EppResource} from the database by foreign key.
   *
   * <p>Returns null if no foreign key index with this foreign key was ever created, or if the most
   * recently created foreign key index was deleted before time "now". This method does not actually
   * check that the referenced resource actually exists. However, for normal epp resources, it is
   * safe to assume that the referenced resource exists if the foreign key index does.
   *
   * @param clazz the resource type to load
   * @param foreignKey id to match
   * @param now the current logical time to use when checking for soft deletion of the foreign key
   *     index
   */
  @Nullable
  public static <E extends EppResource> VKey<E> loadAndGetKey(
      Class<E> clazz, String foreignKey, DateTime now) {
    ForeignKeyIndex<E> index = load(clazz, foreignKey, now);
    return index == null ? null : index.getResourceKey();
  }

  /**
   * Load a {@link ForeignKeyIndex} by class and id string that is active at or after the specified
   * moment in time.
   *
   * <p>This will return null if the {@link ForeignKeyIndex} doesn't exist or has been soft deleted.
   */
  @Nullable
  public static <E extends EppResource> ForeignKeyIndex<E> load(
      Class<E> clazz, String foreignKey, DateTime now) {
    return load(clazz, ImmutableList.of(foreignKey), now).get(foreignKey);
  }

  /**
   * Load a map of {@link ForeignKeyIndex} instances by class and id strings that are active at or
   * after the specified moment in time.
   *
   * <p>The returned map will omit any keys for which the {@link ForeignKeyIndex} doesn't exist or
   * has been soft deleted.
   */
  public static <E extends EppResource> ImmutableMap<String, ForeignKeyIndex<E>> load(
      Class<E> clazz, Collection<String> foreignKeys, final DateTime now) {
    return loadIndexesFromStore(clazz, foreignKeys, false).entrySet().stream()
        .filter(e -> now.isBefore(e.getValue().getDeletionTime()))
        .collect(entriesToImmutableMap());
  }

  /**
   * Helper method to load all of the most recent {@link ForeignKeyIndex}es for the given foreign
   * keys, regardless of whether or not they have been soft-deleted.
   *
   * <p>Used by both the cached (w/o deletion check) and the non-cached (with deletion check) calls.
   */
  private static <E extends EppResource>
      ImmutableMap<String, ForeignKeyIndex<E>> loadIndexesFromStore(
          Class<E> clazz, Collection<String> foreignKeys, boolean useReplicaJpaTm) {
    String property = RESOURCE_CLASS_TO_FKI_PROPERTY.get(clazz);
    JpaTransactionManager jpaTmToUse = useReplicaJpaTm ? replicaJpaTm() : jpaTm();
    ImmutableList<ForeignKeyIndex<E>> indexes =
        jpaTmToUse.transact(
            () ->
                jpaTmToUse
                    .criteriaQuery(
                        CriteriaQueryBuilder.create(clazz)
                            .whereFieldIsIn(property, foreignKeys)
                            .build())
                    .getResultStream()
                    .map(e -> create(e, e.getDeletionTime()))
                    .collect(toImmutableList()));
    // We need to find and return the entities with the maximum deletionTime for each foreign key.
    return Multimaps.index(indexes, ForeignKeyIndex::getForeignKey).asMap().entrySet().stream()
        .map(
            entry ->
                Maps.immutableEntry(
                    entry.getKey(),
                    entry.getValue().stream()
                        .max(Comparator.comparing(ForeignKeyIndex::getDeletionTime))
                        .get()))
        .collect(entriesToImmutableMap());
  }

  static final CacheLoader<VKey<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>> CACHE_LOADER =
      new CacheLoader<VKey<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>>() {

        @Override
        public Optional<ForeignKeyIndex<?>> load(VKey<ForeignKeyIndex<?>> key) {
          String foreignKey = key.getSqlKey().toString();
          return Optional.ofNullable(
              loadIndexesFromStore(
                      RESOURCE_CLASS_TO_FKI_CLASS.inverse().get(key.getKind()),
                      ImmutableSet.of(foreignKey),
                      true)
                  .get(foreignKey));
        }

        @Override
        public Map<VKey<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>> loadAll(
            Iterable<? extends VKey<ForeignKeyIndex<?>>> keys) {
          if (!keys.iterator().hasNext()) {
            return ImmutableMap.of();
          }
          Class<? extends EppResource> resourceClass =
              RESOURCE_CLASS_TO_FKI_CLASS.inverse().get(keys.iterator().next().getKind());
          ImmutableSet<String> foreignKeys =
              Streams.stream(keys).map(v -> v.getSqlKey().toString()).collect(toImmutableSet());
          ImmutableSet<VKey<ForeignKeyIndex<?>>> typedKeys = ImmutableSet.copyOf(keys);
          ImmutableMap<String, ? extends ForeignKeyIndex<? extends EppResource>> existingFkis =
              loadIndexesFromStore(resourceClass, foreignKeys, true);
          // ofy omits keys that don't have values in Datastore, so re-add them in
          // here with Optional.empty() values.
          return Maps.asMap(
              typedKeys,
              (VKey<ForeignKeyIndex<?>> key) ->
                  Optional.ofNullable(existingFkis.getOrDefault(key.getSqlKey().toString(), null)));
        }
      };

  /**
   * A limited size, limited time cache for foreign key entities.
   *
   * <p>This is only used to cache foreign key entities for the purposes of checking whether they
   * exist (and if so, what entity they point to) during a few domain flows. Any other operations on
   * foreign keys should not use this cache.
   *
   * <p>Note that the value type of this cache is Optional because the foreign keys in question are
   * coming from external commands, and thus don't necessarily represent entities in our system that
   * actually exist. So we cache the fact that they *don't* exist by using Optional.empty(), and
   * then several layers up the EPP command will fail with an error message like "The contact with
   * given IDs (blah) don't exist."
   */
  @NonFinalForTesting
  private static LoadingCache<VKey<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>>
      cacheForeignKeyIndexes = createForeignKeyIndexesCache(getEppResourceCachingDuration());

  private static LoadingCache<VKey<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>>
      createForeignKeyIndexesCache(Duration expiry) {
    return CacheUtils.newCacheBuilder(expiry)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(CACHE_LOADER);
  }

  @VisibleForTesting
  public static void setCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getEppResourceCachingDuration());
    cacheForeignKeyIndexes = createForeignKeyIndexesCache(effectiveExpiry);
  }

  /**
   * Load a list of {@link ForeignKeyIndex} instances by class and id strings that are active at or
   * after the specified moment in time, using the cache if enabled.
   *
   * <p>The returned map will omit any keys for which the {@link ForeignKeyIndex} doesn't exist or
   * has been soft deleted.
   *
   * <p>Don't use the cached version of this method unless you really need it for performance
   * reasons, and are OK with the trade-offs in loss of transactional consistency.
   */
  public static <E extends EppResource> ImmutableMap<String, ForeignKeyIndex<E>> loadCached(
      Class<E> clazz, Collection<String> foreignKeys, final DateTime now) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return load(clazz, foreignKeys, now);
    }
    Class<? extends ForeignKeyIndex<?>> fkiClass = mapToFkiClass(clazz);
    // Safe to cast VKey<FKI<E>> to VKey<FKI<?>>
    @SuppressWarnings("unchecked")
    ImmutableList<VKey<ForeignKeyIndex<?>>> fkiVKeys =
        foreignKeys.stream()
            .map(fk -> (VKey<ForeignKeyIndex<?>>) VKey.createSql(fkiClass, fk))
            .collect(toImmutableList());
    // This cast is safe because when we loaded ForeignKeyIndexes above we used type clazz, which
    // is scoped to E.
    @SuppressWarnings("unchecked")
    ImmutableMap<String, ForeignKeyIndex<E>> fkisFromCache =
        cacheForeignKeyIndexes.getAll(fkiVKeys).entrySet().stream()
            .filter(entry -> entry.getValue().isPresent())
            .filter(entry -> now.isBefore(entry.getValue().get().getDeletionTime()))
            .collect(
                toImmutableMap(
                    entry -> entry.getKey().getSqlKey().toString(),
                    entry -> (ForeignKeyIndex<E>) entry.getValue().get()));
    return fkisFromCache;
  }
}
