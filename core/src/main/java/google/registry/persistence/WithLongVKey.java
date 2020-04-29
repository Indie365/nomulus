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

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import javax.persistence.AttributeConverter;
import javax.persistence.Entity;

/**
 * Annotation for {@link Entity} which id is long type and needs an {@link AttributeConverter} for
 * its VKey.
 */
@Target({ElementType.TYPE})
public @interface WithLongVKey {
  /**
   * Sets the suffix of the class name for the {@link AttributeConverter} generated by
   * LongVKeyProcessor. If not set, the suffix will be the type name of the VKey. Note that the
   * class name will be "VKeyConverter_" concatenated with the suffix.
   */
  String classNameSuffix() default "";
}
