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

import google.registry.model.eppcommon.StatusValue;
import java.util.Set;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA {@link AttributeConverter} for storing/retrieving {@link Set<StatusValue>}. */
@Converter(autoApply = true)
public class StatusValueSetConverter extends StringSetConverterBase<StatusValue> {

  @Override
  String toString(StatusValue element) {
    return element.name();
  }

  @Override
  StatusValue fromString(String value) {
    return StatusValue.valueOf(value);
  }
}
