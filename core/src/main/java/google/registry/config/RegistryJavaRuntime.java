// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.config;

import com.google.appengine.api.utils.SystemProperty;
import com.google.appengine.api.utils.SystemProperty.Environment.Value;
import com.google.common.base.Ascii;

/**
 * This class provides methods to get/set the Java runtime, e.g. App Engine, local JVM, where
 * the running instance is.
 */
public enum RegistryJavaRuntime {
  /** App Engine runtime. */
  APPENGINE,

  /** Local JVM runtime. Nomulus tool uses this. */
  LOCALJVM,

  /** Unit test runtime. */
  UNITTEST;

  /** Sets this enum as the name of the registry runtime. */
  public RegistryJavaRuntime setup() {
    return setup(SystemPropertySetter.PRODUCTION_IMPL);
  }

  /**
   * Sets this enum as the name of the registry Java runtime using specified {@link
   * SystemPropertySetter}.
   */
  public RegistryJavaRuntime setup(SystemPropertySetter systemPropertySetter) {
    systemPropertySetter.setProperty(PROPERTY, name());
    return this;
  }

  /** Returns the type of Java runtime for the current running instance. */
  public static RegistryJavaRuntime get() {
    if (System.getProperty(PROPERTY) != null) {
      return valueOf(Ascii.toUpperCase(System.getProperty(PROPERTY)));
    } else if (SystemProperty.environment.equals(Value.Production)) {
      return APPENGINE;
    } else {
      return UNITTEST;
    }
  }

  /** System property for configuring which Java runtime we are in. */
  private static final String PROPERTY = "google.registry.runtime";
}
