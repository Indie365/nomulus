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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import google.registry.testing.FakeClock;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.criteria.CriteriaQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link CriteriaQueryBuilder}. */
class CriteriaQueryBuilderTest {

  private final FakeClock fakeClock = new FakeClock();

  private TestEntity entity1 = new TestEntity("name1", "data");
  private TestEntity entity2 = new TestEntity("name2", "zztz");
  private TestEntity entity3 = new TestEntity("zzz", "aaa");

  @RegisterExtension
  final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder()
          .withClock(fakeClock)
          .withEntityClass(TestEntity.class)
          .buildUnitTestRule();

  @BeforeEach
  void beforeEach() {
    jpaTm().transact(() -> jpaTm().putAll(ImmutableList.of(entity1, entity2, entity3)));
  }

  @Test
  void testSuccess_noWhereClause() {
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .createQuery(CriteriaQueryBuilder.create(TestEntity.class).build())
                            .getResultList()))
        .containsExactly(entity1, entity2, entity3)
        .inOrder();
  }

  @Test
  void testSuccess_where_exactlyOne() {
    List<TestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<TestEntity> query =
                      CriteriaQueryBuilder.create(TestEntity.class)
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::equal,
                              "data",
                              "zztz")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity2);
  }

  @Test
  void testSuccess_where_like_oneResult() {
    List<TestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<TestEntity> query =
                      CriteriaQueryBuilder.create(TestEntity.class)
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "a%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity3);
  }

  @Test
  void testSuccess_where_like_twoResults() {
    List<TestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<TestEntity> query =
                      CriteriaQueryBuilder.create(TestEntity.class)
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%a%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity1, entity3).inOrder();
  }

  @Test
  void testSuccess_multipleWheres() {
    List<TestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<TestEntity> query =
                      CriteriaQueryBuilder.create(TestEntity.class)
                          // first "where" matches 1 and 3
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%a%")
                          // second "where" matches 1 and 2
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%t%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity1);
  }

  @Test
  void testSuccess_where_in_oneResult() {
    List<TestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<TestEntity> query =
                      CriteriaQueryBuilder.create(TestEntity.class)
                          .whereFieldIsIn("data", ImmutableList.of("aaa", "bbb"))
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity3).inOrder();
  }

  @Test
  void testSuccess_where_in_twoResults() {
    List<TestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<TestEntity> query =
                      CriteriaQueryBuilder.create(TestEntity.class)
                          .whereFieldIsIn("data", ImmutableList.of("aaa", "bbb", "data"))
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity1, entity3).inOrder();
  }

  @Test
  void testSuccess_orderBy() {
    List<TestEntity> result =
        jpaTm()
            .transact(
                () -> {
                  CriteriaQuery<TestEntity> query =
                      CriteriaQueryBuilder.create(TestEntity.class)
                          .orderBy(jpaTm().getEntityManager().getCriteriaBuilder()::asc, "data")
                          .where(
                              jpaTm().getEntityManager().getCriteriaBuilder()::like, "data", "%a%")
                          .build();
                  return jpaTm().getEntityManager().createQuery(query).getResultList();
                });
    assertThat(result).containsExactly(entity3, entity1).inOrder();
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {
    @Id private String name;

    @SuppressWarnings("unused")
    private String data;

    private TestEntity() {}

    private TestEntity(String name, String data) {
      this.name = name;
      this.data = data;
    }
  }
}
