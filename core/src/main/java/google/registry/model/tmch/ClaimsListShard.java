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

package google.registry.model.tmch;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.base.Verify.verify;
import static google.registry.model.ofy.ObjectifyService.allocateId;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EmbedMap;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.OnSave;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.annotations.VirtualEntity;
import google.registry.model.common.CrossTldSingleton;
import google.registry.schema.replay.DatastoreOnlyEntity;
import google.registry.schema.replay.NonReplicatedEntity;
import google.registry.util.CollectionUtils;
import google.registry.util.Concurrent;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/**
 * A list of TMCH claims labels and their associated claims keys.
 *
 * <p>The claims list is actually sharded into multiple {@link ClaimsListShard} entities to work
 * around the Datastore limitation of 1M max size per entity. However, when calling {@link
 * #getFromDatastore} all of the shards are recombined into one {@link ClaimsListShard} object.
 *
 * <p>ClaimsList shards are tied to a specific revision and are persisted individually, then the
 * entire claims list is atomically shifted over to using the new shards by persisting the new
 * revision object and updating the {@link ClaimsListSingleton} pointing to it. This bypasses the
 * 10MB per transaction limit.
 *
 * <p>Therefore, it is never OK to save an instance of this class directly to Datastore. Instead you
 * must use the {@link #saveToDatastore} method to do it for you.
 *
 * <p>Note that the primary key of this entity is {@link #revisionId}, which is auto-generated by
 * the database. So, if a retry of insertion happens after the previous attempt unexpectedly
 * succeeds, we will end up with having two exact same claims list with only different {@link
 * #revisionId}. However, this is not an actual problem because we only use the claims list with
 * highest {@link #revisionId}.
 *
 * <p>TODO(b/162007765): Rename the class to ClaimsList and remove Datastore related fields and
 * methods.
 */
@Entity
@NotBackedUp(reason = Reason.EXTERNALLY_SOURCED)
@javax.persistence.Entity(name = "ClaimsList")
@Table
public class ClaimsListShard extends ImmutableObject implements NonReplicatedEntity {

  /** The number of claims list entries to store per shard. */
  private static final int SHARD_SIZE = 10000;

  @Transient @Id long id;

  @Transient @Parent Key<ClaimsListRevision> parent;

  @Ignore
  @javax.persistence.Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long revisionId;

  @Ignore
  @Column(nullable = false)
  CreateAutoTimestamp creationTimestamp = CreateAutoTimestamp.create(null);

  /**
   * When the claims list was last updated.
   *
   * <p>Note that the value of this field is parsed from the claims list file(See this <a
   * href="https://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.1">RFC</>), it is
   * the DNL List creation datetime from the rfc. Since this field has been used by Datastore, we
   * cannot change its name until we finish the migration.
   *
   * <p>TODO(b/166784536): Rename this field to tmdbGenerationTime.
   */
  @Column(name = "tmdb_generation_time", nullable = false)
  DateTime creationTime;

  /** A map from labels to claims keys. */
  @EmbedMap
  @ElementCollection
  @CollectionTable(
      name = "ClaimsEntry",
      joinColumns = @JoinColumn(name = "revisionId", referencedColumnName = "revisionId"))
  @MapKeyColumn(name = "domainLabel", nullable = false)
  @Column(name = "claimKey", nullable = false)
  Map<String, String> labelsToKeys;

  /** Indicates that this is a shard rather than a "full" list. */
  @Ignore @Transient boolean isShard = false;

  private static final Retrier LOADER_RETRIER = new Retrier(new SystemSleeper(), 2);

  private static Optional<ClaimsListShard> loadClaimsListShard() {
    // Find the most recent revision.
    Key<ClaimsListRevision> revisionKey = getCurrentRevision();
    if (revisionKey == null) {
      return Optional.empty();
    }

    Map<String, String> combinedLabelsToKeys = new HashMap<>();
    DateTime creationTime = START_OF_TIME;
    // Grab all of the keys for the shards that belong to the current revision.
    final List<Key<ClaimsListShard>> shardKeys =
        ofy().load().type(ClaimsListShard.class).ancestor(revisionKey).keys().list();

    List<ClaimsListShard> shards;
    try {
      // Load all of the shards concurrently, each in a separate transaction.
      shards =
          Concurrent.transform(
              shardKeys,
              key ->
                  tm().transactNewReadOnly(
                          () -> {
                            ClaimsListShard claimsListShard = ofy().load().key(key).now();
                            checkState(
                                claimsListShard != null,
                                "Key not found when loading claims list shards.");
                            return claimsListShard;
                          }));
    } catch (UncheckedExecutionException e) {
      // We retry on IllegalStateException. However, there's a checkState inside the
      // Concurrent.transform, so if it's thrown it'll be wrapped in an
      // UncheckedExecutionException. We want to unwrap it so it's caught by the retrier.
      if (e.getCause() != null) {
        throwIfUnchecked(e.getCause());
      }
      throw e;
    }

    // Combine the shards together and return the concatenated ClaimsList.
    if (!shards.isEmpty()) {
      creationTime = shards.get(0).creationTime;
      for (ClaimsListShard shard : shards) {
        combinedLabelsToKeys.putAll(shard.labelsToKeys);
        checkState(
            creationTime.equals(shard.creationTime),
            "Inconsistent claims list shard creation times.");
      }
    }

    return Optional.of(create(creationTime, ImmutableMap.copyOf(combinedLabelsToKeys)));
  };

  /** Returns the revision id of this claims list, or throws exception if it is null. */
  public Long getRevisionId() {
    checkState(
        revisionId != null, "revisionId is null because it is not persisted in the database");
    return revisionId;
  }

  /**
   * Returns the time when the external TMDB service generated this revision of the claims list.
   *
   * @see <a href="https://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.1">DNL List
   *     creation datetime</a>
   */
  public DateTime getTmdbGenerationTime() {
    return creationTime;
  }

  /** Returns the creation time of this claims list. */
  public DateTime getCreationTimestamp() {
    return creationTimestamp.getTimestamp();
  }

  /** Returns the claim key for a given domain if there is one, empty otherwise. */
  public Optional<String> getClaimKey(String label) {
    return Optional.ofNullable(labelsToKeys.get(label));
  }

  /** Returns an {@link Map} mapping domain label to its lookup key. */
  public ImmutableMap<String, String> getLabelsToKeys() {
    return ImmutableMap.copyOf(labelsToKeys);
  }

  /** Returns the number of claims. */
  public int size() {
    return labelsToKeys.size();
  }

  /**
   * Save the Claims list to Datastore by writing the new shards in a series of transactions,
   * switching over to using them atomically, then deleting the old ones.
   */
  void save() {
    saveToDatastore(SHARD_SIZE);
  }

  @VisibleForTesting
  void saveToDatastore(int shardSize) {
    // Figure out what the next versionId should be based on which ones already exist.
    final Key<ClaimsListRevision> oldRevision = getCurrentRevision();
    final Key<ClaimsListRevision> parentKey = ClaimsListRevision.createKey();

    // Save the ClaimsList shards in separate transactions.
    Concurrent.transform(
        CollectionUtils.partitionMap(labelsToKeys, shardSize),
        (final ImmutableMap<String, String> labelsToKeysShard) ->
            tm().transact(
                    () -> {
                      ClaimsListShard shard = create(creationTime, labelsToKeysShard);
                      shard.isShard = true;
                      shard.parent = parentKey;
                      ofy().saveWithoutBackup().entity(shard);
                      return shard;
                    }));

    // Persist the new revision, thus causing the newly created shards to go live.
    tm().transact(
            () -> {
              verify(
                  (getCurrentRevision() == null && oldRevision == null)
                      || getCurrentRevision().equals(oldRevision),
                  "Registries' ClaimsList was updated by someone else while attempting to update.");
              ofy().saveWithoutBackup().entity(ClaimsListSingleton.create(parentKey));
              // Delete the old ClaimsListShard entities.
              if (oldRevision != null) {
                ofy()
                    .deleteWithoutBackup()
                    .keys(ofy().load().type(ClaimsListShard.class).ancestor(oldRevision).keys());
              }
            });
  }

  public static ClaimsListShard create(
      DateTime tmdbGenerationTime, Map<String, String> labelsToKeys) {
    ClaimsListShard instance = new ClaimsListShard();
    instance.id = allocateId();
    instance.creationTime = checkNotNull(tmdbGenerationTime);
    instance.labelsToKeys = checkNotNull(labelsToKeys);
    return instance;
  }

  /** Return a single logical instance that combines all Datastore shards. */
  static Optional<ClaimsListShard> getFromDatastore() {
    return LOADER_RETRIER.callWithRetry(
        ClaimsListShard::loadClaimsListShard, IllegalStateException.class);
  }

  /** As a safety mechanism, fail if someone tries to save this class directly. */
  @OnSave
  void disallowUnshardedSaves() {
    if (!isShard) {
      throw new UnshardedSaveException();
    }
  }

  /** Virtual parent entity for claims list shards of a specific revision. */
  @Entity
  @VirtualEntity
  public static class ClaimsListRevision extends ImmutableObject implements DatastoreOnlyEntity {
    @Parent Key<ClaimsListSingleton> parent;

    @Id long versionId;

    @VisibleForTesting
    public static Key<ClaimsListRevision> createKey(ClaimsListSingleton singleton) {
      ClaimsListRevision revision = new ClaimsListRevision();
      revision.versionId = allocateId();
      revision.parent = Key.create(singleton);
      return Key.create(revision);
    }

    @VisibleForTesting
    public static Key<ClaimsListRevision> createKey() {
      return createKey(new ClaimsListSingleton());
    }
  }

  /**
   * Serves as the coordinating claims list singleton linking to the {@link ClaimsListRevision} that
   * is live.
   */
  @Entity
  @NotBackedUp(reason = Reason.EXTERNALLY_SOURCED)
  public static class ClaimsListSingleton extends CrossTldSingleton implements DatastoreOnlyEntity {
    Key<ClaimsListRevision> activeRevision;

    static ClaimsListSingleton create(Key<ClaimsListRevision> revision) {
      ClaimsListSingleton instance = new ClaimsListSingleton();
      instance.activeRevision = revision;
      return instance;
    }

    @VisibleForTesting
    public void setActiveRevision(Key<ClaimsListRevision> revision) {
      activeRevision = revision;
    }
  }

  /**
   * Returns the current ClaimsListRevision if there is one, or null if no claims list revisions
   * have ever been persisted yet.
   */
  @Nullable
  public static Key<ClaimsListRevision> getCurrentRevision() {
    ClaimsListSingleton singleton = ofy().load().entity(new ClaimsListSingleton()).now();
    return singleton == null ? null : singleton.activeRevision;
  }

  /** Exception when trying to directly save a {@link ClaimsListShard} without sharding. */
  public static class UnshardedSaveException extends RuntimeException {}
}
