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

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import google.registry.model.registrar.Registrar.BillingAccountEntry;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.joda.money.CurrencyUnit;

/**
 * JPA converter to for storing/retrieving {@link Map<CurrencyUnit, BillingAccountEntry>} objects.
 */
@Converter(autoApply = true)
public class CurrencyToBillingMapConverter
    implements AttributeConverter<Map<CurrencyUnit, BillingAccountEntry>, String> {

  @Override
  @Nullable
  public String convertToDatabaseColumn(
      @Nullable Map<CurrencyUnit, BillingAccountEntry> attribute) {
    return attribute == null
        ? null
        : new Gson()
            .toJson(
                attribute.entrySet().stream()
                    .collect(
                        toImmutableMap(
                            pair -> pair.getKey().getCode(),
                            pair -> pair.getValue().getAccountId())));
  }

  @Override
  @Nullable
  public Map<CurrencyUnit, BillingAccountEntry> convertToEntityAttribute(@Nullable String dbData) {
    return dbData == null
        ? null
        : new Gson()
            .fromJson(dbData, JsonObject.class).entrySet().stream()
                .collect(
                    Collectors.toMap(
                        entry -> CurrencyUnit.of(entry.getKey()),
                        entry ->
                            new BillingAccountEntry(
                                CurrencyUnit.of(entry.getKey()), entry.getValue().getAsString())));
  }
}
