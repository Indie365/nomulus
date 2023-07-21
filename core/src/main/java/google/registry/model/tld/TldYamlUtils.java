// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
package google.registry.model.tld;

import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.collect.Ordering.natural;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.VKey;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SortedMap;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

public class TldYamlUtils {
  public static class MoneySerializer extends StdSerializer<Money> {

    public MoneySerializer() {
      this(null);
    }

    public MoneySerializer(Class<Money> t) {
      super(t);
    }

    @Override
    public void serialize(Money value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartObject();
      gen.writeStringField("currency", String.valueOf(value.getCurrencyUnit()));
      gen.writeNumberField("amount", value.getAmount());
      gen.writeEndObject();
    }
  }

  public static class MoneyDeserializer extends StdDeserializer<Money> {
    public MoneyDeserializer() {
      this(null);
    }

    public MoneyDeserializer(Class<Money> t) {
      super(t);
    }

    static class MoneyJson {
      public String currency;
      public BigDecimal amount;
    }

    @Override
    public Money deserialize(JsonParser jp, DeserializationContext context) throws IOException {
      MoneyJson json = jp.readValueAs(MoneyJson.class);
      CurrencyUnit currencyUnit = CurrencyUnit.of(json.currency);
      return Money.of(currencyUnit, json.amount);
    }
  }

  public static class CurrencySerializer extends StdSerializer<CurrencyUnit> {

    public CurrencySerializer() {
      this(null);
    }

    public CurrencySerializer(Class<CurrencyUnit> t) {
      super(t);
    }

    @Override
    public void serialize(CurrencyUnit value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeString(value.getCode());
    }
  }

  public static class CurrencyDeserializer extends StdDeserializer<CurrencyUnit> {
    public CurrencyDeserializer() {
      this(null);
    }

    public CurrencyDeserializer(Class<CurrencyUnit> t) {
      super(t);
    }

    @Override
    public CurrencyUnit deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      String currencyCode = jp.readValueAs(String.class);
      return CurrencyUnit.of(currencyCode);
    }
  }

  public static class TokenVKeyListSerializer extends StdSerializer<List<VKey<AllocationToken>>> {

    public TokenVKeyListSerializer() {
      this(null);
    }

    public TokenVKeyListSerializer(Class<List<VKey<AllocationToken>>> t) {
      super(t);
    }

    @Override
    public void serialize(
        List<VKey<AllocationToken>> list, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartArray();
      for (VKey<AllocationToken> vkey : list) {
        gen.writeString(vkey.getKey().toString());
      }
      gen.writeEndArray();
    }
  }

  public static class TokenVKeyListDeserializer
      extends StdDeserializer<List<VKey<AllocationToken>>> {
    public TokenVKeyListDeserializer() {
      this(null);
    }

    public TokenVKeyListDeserializer(Class<VKey<AllocationToken>> t) {
      super(t);
    }

    @Override
    public List<VKey<AllocationToken>> deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      List<VKey<AllocationToken>> tokens = new ArrayList<>();
      String[] keyStrings = jp.readValueAs(String[].class);
      for (String token : keyStrings) {
        tokens.add(VKey.create(AllocationToken.class, token));
      }
      return tokens;
    }
  }

  public static class TimedTransitionPropertyTldStateDeserializer
      extends StdDeserializer<TimedTransitionProperty<TldState>> {

    public TimedTransitionPropertyTldStateDeserializer() {
      this(null);
    }

    public TimedTransitionPropertyTldStateDeserializer(Class<TimedTransitionProperty<TldState>> t) {
      super(t);
    }

    @Override
    public TimedTransitionProperty<TldState> deserialize(
        JsonParser jp, DeserializationContext context) throws IOException {
      SortedMap<String, String> valueMap = jp.readValueAs(SortedMap.class);
      return TimedTransitionProperty.fromValueMap(
          valueMap.keySet().stream()
              .collect(
                  toImmutableSortedMap(
                      natural(), DateTime::parse, key -> TldState.valueOf(valueMap.get(key)))));
    }
  }

  public static class TimedTransitionPropertyMoneyDeserializer
      extends StdDeserializer<TimedTransitionProperty<Money>> {

    public TimedTransitionPropertyMoneyDeserializer() {
      this(null);
    }

    public TimedTransitionPropertyMoneyDeserializer(Class<TimedTransitionProperty<Money>> t) {
      super(t);
    }

    @Override
    public TimedTransitionProperty<Money> deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      SortedMap<String, LinkedHashMap> valueMap = jp.readValueAs(SortedMap.class);
      return TimedTransitionProperty.fromValueMap(
          valueMap.keySet().stream()
              .collect(
                  toImmutableSortedMap(
                      natural(),
                      DateTime::parse,
                      key ->
                          Money.of(
                              CurrencyUnit.of(valueMap.get(key).get("currency").toString()),
                              (double) valueMap.get(key).get("amount")))));
    }
  }

  public static class CreateAutoTimestampDeserializer extends StdDeserializer<CreateAutoTimestamp> {
    public CreateAutoTimestampDeserializer() {
      this(null);
    }

    public CreateAutoTimestampDeserializer(Class<CreateAutoTimestamp> t) {
      super(t);
    }

    @Override
    public CreateAutoTimestamp deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      DateTime creationTime = jp.readValueAs(DateTime.class);
      return CreateAutoTimestamp.create(creationTime);
    }
  }
}
