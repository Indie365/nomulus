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

package google.registry.persistence.transaction;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.QueryComposer.Comparator;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.ImmutableObject;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import google.registry.testing.TestSqlOnly;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
public class QueryComposerTest {

  private final FakeClock fakeClock = new FakeClock();

  TestEntity alpha = new TestEntity("alpha", 3);
  TestEntity bravo = new TestEntity("bravo", 2);
  TestEntity charlie = new TestEntity("charlie", 1);

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withClock(fakeClock)
          .withDatastoreAndCloudSql()
          .withOfyTestEntities(TestEntity.class)
          .withJpaUnitTestEntities(TestEntity.class)
          .build();

  public QueryComposerTest() {}

  @BeforeEach
  void setUp() {
    tm().transact(
            () -> {
              tm().insert(alpha);
              tm().insert(bravo);
              tm().insert(charlie);
            });
  }

  @TestOfyAndSql
  public void testFirstQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "bravo")
                        .first()
                        .map(DatabaseHelper::assertDetached)
                        .get()))
        .isEqualTo(charlie);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GTE, "charlie")
                        .first()
                        .map(DatabaseHelper::assertDetached)
                        .get()))
        .isEqualTo(charlie);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LT, "bravo")
                        .first()
                        .map(DatabaseHelper::assertDetached)
                        .get()))
        .isEqualTo(alpha);
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LTE, "alpha")
                        .first()
                        .map(DatabaseHelper::assertDetached)
                        .get()))
        .isEqualTo(alpha);
  }

  @TestOfyAndSql
  public void testCount() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GTE, "bravo")
                        .count()))
        .isEqualTo(2L);
  }

  @TestOfyAndSql
  public void testGetSingleResult() {
    assertThat(
            transactIfJpaTm(
                () ->
                    DatabaseHelper.assertDetached(
                        tm().createQueryComposer(TestEntity.class)
                            .where("name", Comparator.EQ, "alpha")
                            .getSingleResult())))
        .isEqualTo(alpha);
  }

  @TestOfyAndSql
  public void testGetSingleResult_noResults() {
    assertThrows(
        NoResultException.class,
        () ->
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.EQ, "ziggy")
                        .getSingleResult()));
  }

  @TestOfyAndSql
  public void testGetSingleResult_nonUniqueResult() {
    assertThrows(
        NonUniqueResultException.class,
        () ->
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "alpha")
                        .getSingleResult()));
  }

  @TestOfyAndSql
  public void testStreamQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "alpha")
                        .stream()
                        .map(DatabaseHelper::assertDetached)
                        .collect(toImmutableList())))
        .isEqualTo(ImmutableList.of(bravo, charlie));
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GTE, "bravo")
                        .stream()
                        .map(DatabaseHelper::assertDetached)
                        .collect(toImmutableList())))
        .isEqualTo(ImmutableList.of(bravo, charlie));
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LT, "charlie")
                        .stream()
                        .map(DatabaseHelper::assertDetached)
                        .collect(toImmutableList())))
        .isEqualTo(ImmutableList.of(alpha, bravo));
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.LTE, "bravo")
                        .stream()
                        .map(DatabaseHelper::assertDetached)
                        .collect(toImmutableList())))
        .isEqualTo(ImmutableList.of(alpha, bravo));
  }

  @TestOfyAndSql
  public void testListQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "alpha")
                        .list()))
        .isEqualTo(ImmutableList.of(bravo, charlie));
  }

  @TestOfyAndSql
  public void testNonPrimaryKey() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("val", Comparator.EQ, 2)
                        .first()
                        .map(DatabaseHelper::assertDetached)
                        .get()))
        .isEqualTo(bravo);
  }

  @TestOfyAndSql
  public void testOrderBy() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("val", Comparator.GT, 1)
                        .orderBy("val")
                        .stream()
                        .map(DatabaseHelper::assertDetached)
                        .collect(toImmutableList())))
        .isEqualTo(ImmutableList.of(bravo, alpha));
  }

  @TestOfyAndSql
  public void testEmptyQueries() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "foxtrot")
                        .first()))
        .isEqualTo(Optional.empty());
    assertThat(
            transactIfJpaTm(
                () ->
                    tm()
                        .createQueryComposer(TestEntity.class)
                        .where("name", Comparator.GT, "foxtrot")
                        .stream()
                        .collect(toImmutableList())))
        .isEqualTo(ImmutableList.of());
  }

  @TestOfyOnly
  void testMultipleInequalities_failsDatastore() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("val", Comparator.GT, 1)
                        .where("name", Comparator.LT, "b")
                        .list()))
        .hasMessageThat()
        .isEqualTo(
            "Datastore cannot handle inequality queries on multiple fields, we found 2 fields.");
  }

  @TestSqlOnly
  void testMultipleInequalities_succeedsSql() {
    assertThat(
            transactIfJpaTm(
                () ->
                    tm().createQueryComposer(TestEntity.class)
                        .where("val", Comparator.GT, 1)
                        .where("name", Comparator.LT, "b")
                        .list()))
        .containsExactly(alpha);
  }

  @javax.persistence.Entity
  @Entity(name = "QueryComposerTestEntity")
  private static class TestEntity extends ImmutableObject {
    @javax.persistence.Id @Id private String name;

    @Index
    // Renaming this implicitly verifies that property names work for hibernate queries.
    @Column(name = "some_value")
    private int val;

    public TestEntity() {}

    public TestEntity(String name, int val) {
      this.name = name;
      this.val = val;
    }

    public int getVal() {
      return val;
    }

    public String getName() {
      return name;
    }
  }
}
