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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.SerializeUtils.deserialize;
import static google.registry.util.SerializeUtils.parse;
import static google.registry.util.SerializeUtils.serialize;
import static google.registry.util.SerializeUtils.stringify;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serializable;
import javax.mail.internet.AddressException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SerializeUtils}. */
class SerializeUtilsTest {

  static class Lol {
    @Override
    public String toString() {
      return "LOL_VALUE";
    }
  }

  @Test
  void testSerialize_nullValue_returnsNull() {
    assertThat(serialize(null)).isNull();
  }

  @Test
  void testDeserialize_nullValue_returnsNull() {
    assertThat(deserialize(Object.class, null)).isNull();
  }

  @Test
  void testSerializeDeserialize_stringValue_maintainsValue() {
    assertThat(deserialize(String.class, serialize("hello"))).isEqualTo("hello");
  }

  @Test
  void testSerialize_objectDoesntImplementSerialize_hasInformativeError() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> serialize(new Lol()));
    assertThat(thrown).hasMessageThat().contains("Unable to serialize: LOL_VALUE");
  }

  @Test
  void testDeserialize_badValue_hasInformativeError() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> deserialize(String.class, new byte[] {(byte) 0xff}));
    assertThat(thrown).hasMessageThat().contains("Unable to deserialize: objectBytes=FF");
  }

  @Test
  void testStringifyParse_serializableValue_maintainsValue() {
    Serializable value = "testValue";
    assertThat(parse(String.class, stringify(value))).isEqualTo(value);
  }

  @Test
  void testStringifyParse_stringValue_maintainsValue() throws AddressException {
    assertThat(parse(Serializable.class, stringify("hello"))).isEqualTo("hello");
  }

  @Test
  void testStringifyParse_longValue_maintainsValue() throws AddressException {
    long value = 12345;
    assertThat(parse(Serializable.class, stringify(value))).isEqualTo(value);
  }

  @Test
  void testStringify_nullValue_throwsException() {
    NullPointerException thrown = assertThrows(NullPointerException.class, () -> stringify(null));
    assertThat(thrown).hasMessageThat().contains("Object cannot be null");
  }
}
