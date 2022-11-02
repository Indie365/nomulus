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

package google.registry.persistence;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.function.Function.identity;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.util.SerializeUtils;
import java.io.Serializable;

/**
 * VKey is an abstraction that encapsulates the key concept.
 *
 * <p>A VKey instance must contain the JPA primary key for the referenced entity class.
 */
public class VKey<T> extends ImmutableObject implements Serializable {

  private static final long serialVersionUID = -5291472863840231240L;

  // Info that's stored in VKey string generated via stringify().
  private static final String SQL_LOOKUP_KEY = "sql";
  private static final String CLASS_TYPE = "kind";

  // Web safe delimiters that won't be used in base 64.
  private static final String KV_SEPARATOR = ":";
  private static final String DELIMITER = "@";

  private static final ImmutableMap<String, Class<? extends EppResource>> EPP_RESOURCE_CLASS_MAP =
      ImmutableList.of(Domain.class, Host.class, Contact.class).stream()
          .collect(toImmutableMap(Class::getSimpleName, identity()));

  // The SQL key for the referenced entity.
  Serializable sqlKey;

  Class<? extends T> kind;

  @SuppressWarnings("unused")
  VKey() {}

  VKey(Class<? extends T> kind, Serializable sqlKey) {
    this.kind = kind;
    this.sqlKey = sqlKey;
  }

  /** Creates a {@link VKey} with supplied the sql primary key. */
  public static <T> VKey<T> createSql(Class<T> kind, Serializable sqlKey) {
    checkArgumentNotNull(kind, "kind must not be null");
    checkArgumentNotNull(sqlKey, "sqlKey must not be null");
    return new VKey<>(kind, sqlKey);
  }

  /**
   * Constructs a {@link VKey} from the string representation of a vkey.
   *
   * <p>There are two types of string representations: 1) existing ofy key string handled by
   * fromWebsafeKey() and 2) string encoded via stringify() where @ separates the substrings and
   * each of the substrings contains a look up key, ":", and its corresponding value. The key info
   * is encoded via Base64. The string begins with "kind:" and it must contains at least ofy key or
   * sql key.
   *
   * <p>Example of a Vkey string by fromWebsafeKey(): "agR0ZXN0chYLEgpEb21haW5CYXNlIgZST0lELTEM"
   *
   * <p>Example of a vkey string by stringify(): "kind:TestObject@sql:rO0ABXQAA2Zvbw" +
   * "@ofy:agR0ZXN0cjELEg9FbnRpdHlHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M", where sql
   * key and ofy key values are encoded in Base64.
   */
  public static <T extends EppResource> VKey<T> createEppVKeyFromString(String keyString) {
    ImmutableMap<String, String> kvs =
        ImmutableMap.copyOf(
            Splitter.on(DELIMITER).withKeyValueSeparator(KV_SEPARATOR).split(keyString));
    String classString = kvs.get(CLASS_TYPE);
    if (classString == null) {
      throw new IllegalArgumentException(
          String.format("%s does not contain the required key: %s", keyString, CLASS_TYPE));
    }
    @SuppressWarnings("unchecked")
    Class<T> classType = (Class<T>) EPP_RESOURCE_CLASS_MAP.get(classString);
    if (classType == null) {
      throw new IllegalArgumentException(String.format("%s is not an EppResource", classString));
    }
    String encodedString = kvs.get(SQL_LOOKUP_KEY);
    if (encodedString == null) {
      throw new IllegalArgumentException(
          String.format("%s does not contain the required key: %s", keyString, SQL_LOOKUP_KEY));
    }
    return VKey.createSql(
        classType, SerializeUtils.parse(Serializable.class, kvs.get(SQL_LOOKUP_KEY)));
  }

  /** Returns the type of the entity. */
  public Class<? extends T> getKind() {
    return this.kind;
  }

  /** Returns the SQL primary key. */
  public Serializable getSqlKey() {
    checkState(sqlKey != null, "Attempting obtain a null SQL key.");
    return this.sqlKey;
  }

  /**
   * Constructs the string representation of a {@link VKey}.
   *
   * <p>The string representation of a vkey contains its kind, and sql key or ofy key, or both. Each
   * of the keys is first serialized into a byte array then encoded via Base64 into a web safe
   * string.
   *
   * <p>The string representation of a vkey contains key values pairs separated by delimiter "@".
   * Another delimiter ":" is put in between each key and value. The following is the complete
   * format of the string: "kind:class_name@sql:encoded_sqlKey@ofy:encoded_ofyKey", where kind is
   * required. The string representation may contain an encoded ofy key, or an encoded sql key, or
   * both.
   */
  public String stringify() {
    return Joiner.on(DELIMITER)
        .join(
            CLASS_TYPE + KV_SEPARATOR + getKind().getSimpleName(),
            SQL_LOOKUP_KEY + KV_SEPARATOR + SerializeUtils.stringify(getSqlKey()));
  }

  /**
   * Constructs the readable string representation of a {@link VKey}.
   *
   * <p>This readable string representation of a vkey contains its kind and its sql key or ofy key,
   * or both.
   */
  @Override
  public String toString() {
    return String.format("VKey<%s>(%s:%s)", getKind().getSimpleName(), SQL_LOOKUP_KEY, sqlKey);
  }
}
