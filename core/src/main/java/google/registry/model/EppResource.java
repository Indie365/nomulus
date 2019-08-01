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

package google.registry.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.union;
import static google.registry.config.RegistryConfig.getEppResourceCachingDuration;
import static google.registry.config.RegistryConfig.getEppResourceMaxCachedEntries;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import google.registry.config.RegistryConfig;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.transfer.TransferData;
import google.registry.util.NonFinalForTesting;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** An EPP entity object (i.e. a domain, contact, or host). */
public abstract class EppResource extends BackupGroupRoot implements Buildable {

  /**
   * Unique identifier in the registry for this resource.
   *
   * <p>This is in the (\w|_){1,80}-\w{1,8} format specified by RFC 5730 for roidType.
   * @see <a href="https://tools.ietf.org/html/rfc5730">RFC 5730</a>
   */
  @Id
  String repoId;

  /** The ID of the registrar that is currently sponsoring this resource. */
  @Index
  String currentSponsorClientId;

  /** The ID of the registrar that created this resource. */
  String creationClientId;

  /**
   * The ID of the registrar that last updated this resource.
   *
   * <p>This does not refer to the last delta made on this object, which might include out-of-band
   * edits; it only includes EPP-visible modifications such as {@literal <update>}. Can be null if
   * the resource has never been modified.
   */
  String lastEppUpdateClientId;

  /** The time when this resource was created. */
  // Map the method to XML, not the field, because if we map the field (with an adaptor class) it
  // will never be omitted from the xml even if the timestamp inside creationTime is null and we
  // return null from the adaptor. (Instead it gets written as an empty tag.)
  @Index
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /**
   * The time when this resource was or will be deleted.
   *
   * <ul>
   *   <li>For deleted resources, this is in the past.
   *   <li>For pending-delete resources, this is in the near future.
   *   <li>For active resources, this is {@code END_OF_TIME}.
   * </ul>
   *
   * <p>This scheme allows for setting pending deletes in the future and having them magically drop
   * out of the index at that time, as long as we query for resources whose deletion time is before
   * now.
   */
  @Index
  DateTime deletionTime;


  /**
   * The time that this resource was last updated.
   *
   * <p>This does not refer to the last delta made on this object, which might include out-of-band
   * edits; it only includes EPP-visible modifications such as {@literal <update>}. Can be null if
   * the resource has never been modified.
   */
  DateTime lastEppUpdateTime;

  /** Status values associated with this resource. */
  Set<StatusValue> status;

  /**
   * Sorted map of {@link DateTime} keys (modified time) to {@link CommitLogManifest} entries.
   *
   * <p><b>Note:</b> Only the last revision on a given date is stored. The key is the transaction
   * timestamp, not midnight.
   *
   * @see google.registry.model.translators.CommitLogRevisionsTranslatorFactory
   */
  ImmutableSortedMap<DateTime, Key<CommitLogManifest>> revisions = ImmutableSortedMap.of();

  public final String getRepoId() {
    return repoId;
  }

  public final DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  public final String getCreationClientId() {
    return creationClientId;
  }

  public final DateTime getLastEppUpdateTime() {
    return lastEppUpdateTime;
  }

  public final String getLastEppUpdateClientId() {
    return lastEppUpdateClientId;
  }

  /**
   * Get the stored value of {@link #currentSponsorClientId}.
   *
   * <p>For subordinate hosts, this value may not represent the actual current client id, which is
   * the client id of the superordinate host. For all other resources this is the true client id.
   */
  public final String getPersistedCurrentSponsorClientId() {
    return currentSponsorClientId;
  }

  public final ImmutableSet<StatusValue> getStatusValues() {
    return nullToEmptyImmutableCopy(status);
  }

  public final DateTime getDeletionTime() {
    return deletionTime;
  }

  public ImmutableSortedMap<DateTime, Key<CommitLogManifest>> getRevisions() {
    return nullToEmptyImmutableCopy(revisions);
  }

  /** Return a clone of the resource with timed status values modified using the given time. */
  public abstract EppResource cloneProjectedAtTime(DateTime now);

  /** Get the foreign key string for this resource. */
  public abstract String getForeignKey();

  /** Override of {@link Buildable#asBuilder} so that the extra methods are visible. */
  @Override
  public abstract Builder<?, ?> asBuilder();

  /** EppResources that are loaded via foreign keys should implement this marker interface. */
  public interface ForeignKeyedEppResource {}

  /** An interface for resources that have transfer data. */
  public interface ResourceWithTransferData {
    TransferData getTransferData();

    /**
     * The time that this resource was last transferred.
     *
     * <p>Can be null if the resource has never been transferred.
     */
    DateTime getLastTransferTime();
  }

  /** An interface for builders of resources that have transfer data. */
  public interface BuilderWithTransferData<B extends BuilderWithTransferData<B>> {
    B setTransferData(TransferData transferData);

    /** Set the time when this resource was transferred. */
    B setLastTransferTime(DateTime lastTransferTime);
  }

  /** Abstract builder for {@link EppResource} types. */
  public abstract static class Builder<T extends EppResource, B extends Builder<?, ?>>
      extends GenericBuilder<T, B> {

    /** Create a {@link Builder} wrapping a new instance. */
    protected Builder() {}

    /** Create a {@link Builder} wrapping the given instance. */
    protected Builder(T instance) {
      super(instance);
    }

    /**
     * Set the time this resource was created.
     *
     * <p>Note: This can only be used if the creation time hasn't already been set, which it is in
     * normal EPP flows.
     */
    public B setCreationTime(DateTime creationTime) {
      checkState(
          getInstance().creationTime.getTimestamp() == null,
          "creationTime can only be set once for EppResource.");
      getInstance().creationTime = CreateAutoTimestamp.create(creationTime);
      return thisCastToDerived();
    }

    /** Set the time this resource was created. Should only be used in tests. */
    @VisibleForTesting
    public B setCreationTimeForTest(DateTime creationTime) {
      getInstance().creationTime = CreateAutoTimestamp.create(creationTime);
      return thisCastToDerived();
    }

    /** Set the time after which this resource should be considered deleted. */
    public B setDeletionTime(DateTime deletionTime) {
      getInstance().deletionTime = deletionTime;
      return thisCastToDerived();
    }

    /** Set the current sponsoring registrar. */
    public B setPersistedCurrentSponsorClientId(String currentSponsorClientId) {
      getInstance().currentSponsorClientId = currentSponsorClientId;
      return thisCastToDerived();
    }

    /** Set the registrar that created this resource. */
    public B setCreationClientId(String creationClientId) {
      getInstance().creationClientId = creationClientId;
      return thisCastToDerived();
    }

    /** Set the time when a {@literal <update>} was performed on this resource. */
    public B setLastEppUpdateTime(DateTime lastEppUpdateTime) {
      getInstance().lastEppUpdateTime = lastEppUpdateTime;
      return thisCastToDerived();
    }

    /** Set the registrar who last performed a {@literal <update>} on this resource. */
    public B setLastEppUpdateClientId(String lastEppUpdateClientId) {
      getInstance().lastEppUpdateClientId = lastEppUpdateClientId;
      return thisCastToDerived();
    }

    /** Set this resource's status values. */
    public B setStatusValues(ImmutableSet<StatusValue> statusValues) {
      Class<? extends EppResource> resourceClass = getInstance().getClass();
      for (StatusValue statusValue : nullToEmpty(statusValues)) {
        checkArgument(
            statusValue.isAllowedOn(resourceClass),
            "The %s status cannot be set on %s",
            statusValue,
            resourceClass.getSimpleName());
      }
      getInstance().status = statusValues;
      return thisCastToDerived();
    }

    /** Add to this resource's status values. */
    public B addStatusValue(StatusValue statusValue) {
      return addStatusValues(ImmutableSet.of(statusValue));
    }

    /** Remove from this resource's status values. */
    public B removeStatusValue(StatusValue statusValue) {
      return removeStatusValues(ImmutableSet.of(statusValue));
    }

    /** Add to this resource's status values. */
    public B addStatusValues(ImmutableSet<StatusValue> statusValues) {
      return setStatusValues(ImmutableSet.copyOf(
          union(getInstance().getStatusValues(), statusValues)));
    }

    /** Remove from this resource's status values. */
    public B removeStatusValues(ImmutableSet<StatusValue> statusValues) {
      return setStatusValues(ImmutableSet.copyOf(
          difference(getInstance().getStatusValues(), statusValues)));
    }

    /** Set this resource's repoId. */
    public B setRepoId(String repoId) {
      getInstance().repoId = repoId;
      return thisCastToDerived();
    }

    /** Build the resource, nullifying empty strings and sets and setting defaults. */
    @Override
    public T build() {
      // An EPP object has an implicit status of OK if no pending operations or prohibitions exist.
      removeStatusValue(StatusValue.OK);
      if (getInstance().getStatusValues().isEmpty()) {
        addStatusValue(StatusValue.OK);
      }
      // If there is no deletion time, set it to END_OF_TIME.
      setDeletionTime(Optional.ofNullable(getInstance().deletionTime).orElse(END_OF_TIME));
      return ImmutableObject.cloneEmptyToNull(super.build());
    }
  }

  static final CacheLoader<Key<? extends EppResource>, EppResource> CACHE_LOADER =
      new CacheLoader<Key<? extends EppResource>, EppResource>() {

        @Override
        public EppResource load(Key<? extends EppResource> key) {
          return tm().doTransactionless(() -> ofy().load().key(key).now());
        }

        @Override
        public Map<Key<? extends EppResource>, EppResource> loadAll(
            Iterable<? extends Key<? extends EppResource>> keys) {
          return tm().doTransactionless(() -> loadMultiple(keys));
        }
      };

  /**
   * A limited size, limited time cache for EPP resource entities.
   *
   * <p>This is only used to cache contacts and hosts for the purposes of checking whether they are
   * deleted or in pending delete during a few domain flows. Any operations on contacts and hosts
   * directly should of course never use the cache.
   */
  @NonFinalForTesting
  private static LoadingCache<Key<? extends EppResource>, EppResource> cacheEppResources =
      createEppResourcesCache(getEppResourceCachingDuration());

  private static LoadingCache<Key<? extends EppResource>, EppResource> createEppResourcesCache(
      Duration expiry) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(expiry.getMillis(), MILLISECONDS)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(CACHE_LOADER);
  }

  @VisibleForTesting
  public static void setCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getEppResourceCachingDuration());
    cacheEppResources = createEppResourcesCache(effectiveExpiry);
  }

  private static ImmutableMap<Key<? extends EppResource>, EppResource> loadMultiple(
      Iterable<? extends Key<? extends EppResource>> keys) {
    // This cast is safe because, in Objectify, Key<? extends EppResource> can also be
    // treated as a Key<EppResource>.
    @SuppressWarnings("unchecked")
    ImmutableList<Key<EppResource>> typedKeys =
        Streams.stream(keys).map(key -> (Key<EppResource>) key).collect(toImmutableList());

    // Typing shenanigans are required to return the map with the correct required generic type.
    return ofy().load().keys(typedKeys).entrySet().stream()
        .collect(ImmutableMap.toImmutableMap(Entry::getKey, Entry::getValue));
  }

  /**
   * Loads the given EppResources by their keys using the cache (if enabled).
   *
   * <p>Don't use this unless you really need it for performance reasons, and be sure that you are
   * OK with the trade-offs in loss of transactional consistency.
   */
  public static ImmutableMap<Key<? extends EppResource>, EppResource> loadCached(
      Iterable<Key<? extends EppResource>> keys) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return loadMultiple(keys);
    }
    try {
      return cacheEppResources.getAll(keys);
    } catch (ExecutionException e) {
      throw new RuntimeException("Error loading cached EppResources", e.getCause());
    }
  }

  /**
   * Loads a given EppResource by its key using the cache (if enabled).
   *
   * <p>Don't use this unless you really need it for performance reasons, and be sure that you are
   * OK with the trade-offs in loss of transactional consistency.
   */
  public static <T extends EppResource> T loadCached(Key<T> key) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return ofy().load().key(key).now();
    }
    try {
      // Safe to cast because loading a Key<T> returns an entity of type T.
      @SuppressWarnings("unchecked")
      T resource = (T) cacheEppResources.get(key);
      return resource;
    } catch (ExecutionException e) {
      throw new RuntimeException("Error loading cached EppResources", e.getCause());
    }
  }
}
