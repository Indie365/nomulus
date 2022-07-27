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

package google.registry.model.eppcommon;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.AddressTest.TestEntity.TestAddress;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link Address}. */
class AddressTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  private TestEntity entity;

  private static TestEntity saveAndLoad(TestEntity entity) {
    insertInDb(entity);
    return loadByEntity(entity);
  }

  @Test
  void testSuccess_setStreet() {
    TestAddress address = createAddress("123 W 14th St", "8th Fl", "Rm 8");
    assertThat(address.street).containsExactly("123 W 14th St", "8th Fl", "Rm 8");
    assertThat(address.streetLine1).isEqualTo("123 W 14th St");
    assertThat(address.streetLine2).isEqualTo("8th Fl");
    assertThat(address.streetLine3).isEqualTo("Rm 8");
  }
  /** Test the persist behavior. */
  @Test
  void testSuccess_saveAndLoadStreetLines() {
    entity = new TestEntity(1L, createAddress("123 W 14th St", "8th Fl", "Rm 8"));
    assertThat(saveAndLoad(entity).address.getStreet())
        .containsExactly("123 W 14th St", "8th Fl", "Rm 8");
  }

  /** Test the merge behavior. */
  @Test
  void testSuccess_putAndLoadStreetLines() {
    entity = new TestEntity(1L, createAddress("123 W 14th St", "8th Fl", "Rm 8"));
    jpaTm().transact(() -> jpaTm().put(entity));
    assertThat(loadByEntity(entity).address.getStreet())
        .containsExactly("123 W 14th St", "8th Fl", "Rm 8");
  }

  @Test
  void testSuccess_setsNullStreetLine() {
    entity = new TestEntity(1L, createAddress("line1", "line2"));
    TestEntity savedEntity = saveAndLoad(entity);
    assertThat(savedEntity.address.streetLine1).isEqualTo("line1");
    assertThat(savedEntity.address.streetLine2).isEqualTo("line2");
    assertThat(savedEntity.address.streetLine3).isNull();
  }

  @Test
  void testFailure_tooManyStreetLines() {
    assertThrows(
        IllegalArgumentException.class, () -> createAddress("line1", "line2", "line3", "line4"));
  }

  private static TestAddress createAddress(String... streetList) {
    return new TestAddress.Builder()
        .setStreet(ImmutableList.copyOf(streetList))
        .setCity("New York")
        .setState("NY")
        .setZip("10011")
        .setCountryCode("US")
        .build();
  }

  @Entity(name = "TestEntity")
  static class TestEntity extends ImmutableObject {

    @Id long id;

    TestAddress address;

    TestEntity() {}

    TestEntity(Long id, TestAddress address) {
      this.id = id;
      this.address = address;
    }

    @Embeddable
    public static class TestAddress extends Address {

      public static class Builder extends Address.Builder<TestAddress> {}
    }
  }
}
