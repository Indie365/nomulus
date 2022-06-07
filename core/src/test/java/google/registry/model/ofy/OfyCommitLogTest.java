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

package google.registry.model.ofy;

import static com.google.appengine.api.datastore.EntityTranslator.convertToPb;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.CommitLogBucket.getBucketKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.BackupGroupRoot;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestObject.TestVirtualObject;
import google.registry.testing.TmOverrideExtension;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests ensuring {@link Ofy} saves transactions to {@link CommitLogManifest}. */
public class OfyCommitLogTest {

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  TmOverrideExtension tmOverrideExtension = TmOverrideExtension.withOfy();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestVirtualObject.class, Root.class, Child.class)
          .build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  @Test
  void testTransact_doesNothing_noCommitLogIsSaved() {
    tm().transact(() -> {});
    assertThat(auditedOfy().load().type(CommitLogManifest.class)).isEmpty();
  }

  @Test
  void testTransact_savesDataAndCommitLog() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())).now());
    assertThat(auditedOfy().load().key(Key.create(getCrossTldKey(), Root.class, 1)).now().value)
        .isEqualTo("value");
    assertThat(auditedOfy().load().type(CommitLogManifest.class)).hasSize(1);
    assertThat(auditedOfy().load().type(CommitLogMutation.class)).hasSize(1);
  }

  @Test
  void testTransact_saveWithoutBackup_noCommitLogIsSaved() {
    tm().transact(
            () -> auditedOfy().saveWithoutBackup().entity(Root.create(1, getCrossTldKey())).now());
    assertThat(auditedOfy().load().key(Key.create(getCrossTldKey(), Root.class, 1)).now().value)
        .isEqualTo("value");
    assertThat(auditedOfy().load().type(CommitLogManifest.class)).isEmpty();
    assertThat(auditedOfy().load().type(CommitLogMutation.class)).isEmpty();
  }

  @Test
  void testTransact_deleteWithoutBackup_noCommitLogIsSaved() {
    tm().transact(
            () -> auditedOfy().saveWithoutBackup().entity(Root.create(1, getCrossTldKey())).now());
    tm().transact(() -> auditedOfy().deleteWithoutBackup().key(Key.create(Root.class, 1)));
    assertThat(auditedOfy().load().key(Key.create(Root.class, 1)).now()).isNull();
    assertThat(auditedOfy().load().type(CommitLogManifest.class)).isEmpty();
    assertThat(auditedOfy().load().type(CommitLogMutation.class)).isEmpty();
  }

  @Test
  void testTransact_savesEntity_itsProtobufFormIsStoredInCommitLog() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())).now());
    final byte[] entityProtoBytes =
        auditedOfy().load().type(CommitLogMutation.class).first().now().entityProtoBytes;
    // This transaction is needed so that save().toEntity() can access
    // auditedOfy().getTransactionTime()
    // when it attempts to set the update timestamp.
    tm().transact(
            () ->
                assertThat(entityProtoBytes)
                    .isEqualTo(
                        convertToPb(auditedOfy().save().toEntity(Root.create(1, getCrossTldKey())))
                            .toByteArray()));
  }

  @Test
  void testTransact_savesEntity_mutationIsChildOfManifest() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())).now());
    assertThat(
            auditedOfy()
                .load()
                .type(CommitLogMutation.class)
                .ancestor(auditedOfy().load().type(CommitLogManifest.class).first().now()))
        .hasSize(1);
  }

  @Test
  void testTransactNew_savesDataAndCommitLog() {
    tm().transactNew(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())).now());
    assertThat(auditedOfy().load().key(Key.create(getCrossTldKey(), Root.class, 1)).now().value)
        .isEqualTo("value");
    assertThat(auditedOfy().load().type(CommitLogManifest.class)).hasSize(1);
    assertThat(auditedOfy().load().type(CommitLogMutation.class)).hasSize(1);
  }

  @Test
  void testTransact_multipleSaves_logsMultipleMutations() {
    tm().transact(
            () -> {
              auditedOfy().save().entity(Root.create(1, getCrossTldKey())).now();
              auditedOfy().save().entity(Root.create(2, getCrossTldKey())).now();
            });
    assertThat(auditedOfy().load().type(CommitLogManifest.class)).hasSize(1);
    assertThat(auditedOfy().load().type(CommitLogMutation.class)).hasSize(2);
  }

  @Test
  void testTransact_deletion_deletesAndLogsWithoutMutation() {
    tm().transact(
            () -> auditedOfy().saveWithoutBackup().entity(Root.create(1, getCrossTldKey())).now());
    clock.advanceOneMilli();
    final Key<Root> otherTldKey = Key.create(getCrossTldKey(), Root.class, 1);
    tm().transact(() -> auditedOfy().delete().key(otherTldKey));
    assertThat(auditedOfy().load().key(otherTldKey).now()).isNull();
    assertThat(auditedOfy().load().type(CommitLogManifest.class)).hasSize(1);
    assertThat(auditedOfy().load().type(CommitLogMutation.class)).isEmpty();
    assertThat(auditedOfy().load().type(CommitLogManifest.class).first().now().getDeletions())
        .containsExactly(otherTldKey);
  }

  @Test
  void testTransactNew_deleteNotBackedUpKind_throws() {
    final CommitLogManifest backupsArentAllowedOnMe =
        CommitLogManifest.create(getBucketKey(1), clock.nowUtc(), ImmutableSet.of());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transactNew(() -> auditedOfy().delete().entity(backupsArentAllowedOnMe)));
    assertThat(thrown).hasMessageThat().contains("Can't save/delete a @NotBackedUp");
  }

  @Test
  void testTransactNew_saveNotBackedUpKind_throws() {
    final CommitLogManifest backupsArentAllowedOnMe =
        CommitLogManifest.create(getBucketKey(1), clock.nowUtc(), ImmutableSet.of());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transactNew(() -> auditedOfy().save().entity(backupsArentAllowedOnMe)));
    assertThat(thrown).hasMessageThat().contains("Can't save/delete a @NotBackedUp");
  }

  @Test
  void testTransactNew_deleteVirtualEntityKey_throws() {
    final Key<TestVirtualObject> virtualEntityKey = TestVirtualObject.createKey("virtual");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transactNew(() -> auditedOfy().delete().key(virtualEntityKey)));
    assertThat(thrown).hasMessageThat().contains("Can't save/delete a @VirtualEntity");
  }

  @Test
  void testTransactNew_saveVirtualEntity_throws() {
    final TestVirtualObject virtualEntity = TestVirtualObject.create("virtual");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> tm().transactNew(() -> auditedOfy().save().entity(virtualEntity)));
    assertThat(thrown).hasMessageThat().contains("Can't save/delete a @VirtualEntity");
  }

  @Test
  void test_deleteWithoutBackup_withVirtualEntityKey_throws() {
    final Key<TestVirtualObject> virtualEntityKey = TestVirtualObject.createKey("virtual");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> auditedOfy().deleteWithoutBackup().key(virtualEntityKey));
    assertThat(thrown).hasMessageThat().contains("Can't save/delete a @VirtualEntity");
  }

  @Test
  void test_saveWithoutBackup_withVirtualEntity_throws() {
    final TestVirtualObject virtualEntity = TestVirtualObject.create("virtual");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> auditedOfy().saveWithoutBackup().entity(virtualEntity));
    assertThat(thrown).hasMessageThat().contains("Can't save/delete a @VirtualEntity");
  }

  @Test
  void testTransact_twoSavesOnSameKey_throws() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tm().transact(
                        () -> {
                          auditedOfy().save().entity(Root.create(1, getCrossTldKey()));
                          auditedOfy().save().entity(Root.create(1, getCrossTldKey()));
                        }));
    assertThat(thrown).hasMessageThat().contains("Multiple entries with same key");
  }

  @Test
  void testTransact_saveAndDeleteSameKey_throws() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tm().transact(
                        () -> {
                          auditedOfy().save().entity(Root.create(1, getCrossTldKey()));
                          auditedOfy().delete().entity(Root.create(1, getCrossTldKey()));
                        }));
    assertThat(thrown).hasMessageThat().contains("Multiple entries with same key");
  }

  @Test
  void testSavingRootAndChild_updatesTimestampOnBackupGroupRoot() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())));
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
    clock.advanceOneMilli();
    tm().transact(
            () -> {
              auditedOfy().save().entity(Root.create(1, getCrossTldKey()));
              auditedOfy().save().entity(new Child());
            });
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
  }

  @Test
  void testSavingOnlyChild_updatesTimestampOnBackupGroupRoot() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())));
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
    clock.advanceOneMilli();
    tm().transact(() -> auditedOfy().save().entity(new Child()));
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
  }

  @Test
  void testDeletingChild_updatesTimestampOnBackupGroupRoot() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())));
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
    clock.advanceOneMilli();
    // The fact that the child was never persisted is irrelevant.
    tm().transact(() -> auditedOfy().delete().entity(new Child()));
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
  }

  @Test
  void testReadingRoot_doesntUpdateTimestamp() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())));
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
    clock.advanceOneMilli();
    tm().transact(
            () -> {
              // Don't remove this line, as without saving *something* the commit log code will
              // never be invoked and the test will trivially pass.
              auditedOfy().save().entity(Root.create(2, getCrossTldKey()));
              auditedOfy().load().entity(Root.create(1, getCrossTldKey()));
            });
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc().minusMillis(1));
  }

  @Test
  void testReadingChild_doesntUpdateTimestampOnBackupGroupRoot() {
    tm().transact(() -> auditedOfy().save().entity(Root.create(1, getCrossTldKey())));
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
    clock.advanceOneMilli();
    tm().transact(
            () -> {
              // Don't remove this line, as without saving *something* the commit log code will
              // never be invoked and the test will trivially pass
              auditedOfy().save().entity(Root.create(2, getCrossTldKey()));
              auditedOfy().load().entity(new Child()); // All Child objects are under Root(1).
            });
    auditedOfy().clearSessionCache();
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc().minusMillis(1));
  }

  @Test
  void testSavingAcrossBackupGroupRoots_updatesCorrectTimestamps() {
    // Create three roots.
    tm().transact(
            () -> {
              auditedOfy().save().entity(Root.create(1, getCrossTldKey()));
              auditedOfy().save().entity(Root.create(2, getCrossTldKey()));
              auditedOfy().save().entity(Root.create(3, getCrossTldKey()));
            });
    auditedOfy().clearSessionCache();
    for (int i = 1; i <= 3; i++) {
      assertThat(
              auditedOfy()
                  .load()
                  .key(Key.create(getCrossTldKey(), Root.class, i))
                  .now()
                  .getUpdateTimestamp()
                  .getTimestamp())
          .isEqualTo(clock.nowUtc());
    }
    clock.advanceOneMilli();
    // Mutate one root, and a child of a second, ignoring the third.
    tm().transact(
            () -> {
              auditedOfy().save().entity(new Child()); // All Child objects are under Root(1).
              auditedOfy().save().entity(Root.create(2, getCrossTldKey()));
            });
    auditedOfy().clearSessionCache();
    // Child was touched.
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 1))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
    // Directly touched.
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 2))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc());
    // Wasn't touched.
    assertThat(
            auditedOfy()
                .load()
                .key(Key.create(getCrossTldKey(), Root.class, 3))
                .now()
                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(clock.nowUtc().minusMillis(1));
  }

  @Entity
  static class Root extends BackupGroupRoot {

    @Parent
    Key<EntityGroupRoot> parent;

    @Id
    long id;

    String value;

    static Root create(long id, Key<EntityGroupRoot> parent) {
      Root result = new Root();
      result.parent = parent;
      result.id = id;
      result.value = "value";
      return result;
    }
  }

  @Entity
  static class Child extends ImmutableObject {
    @Parent
    Key<Root> parent = Key.create(Root.create(1, getCrossTldKey()));

    @Id
    long id = 1;
  }
}
