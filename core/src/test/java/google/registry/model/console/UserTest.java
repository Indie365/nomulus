// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.console;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests for {@link User}. */
public class UserTest {

  @Test
  void testFailure_badInputs() {
    User.Builder builder = new User.Builder();
    assertThrows(IllegalArgumentException.class, () -> builder.setGaiaId(null));
    assertThrows(IllegalArgumentException.class, () -> builder.setEmailAddress(""));
    assertThrows(NullPointerException.class, () -> builder.setEmailAddress(null));
    assertThrows(IllegalArgumentException.class, () -> builder.setEmailAddress(""));
    assertThrows(IllegalArgumentException.class, () -> builder.setUserRoles(null));
    assertThrows(IllegalArgumentException.class, builder::build);
    builder.setGaiaId("gaiaId");
    assertThrows(IllegalArgumentException.class, builder::build);
    assertThrows(IllegalArgumentException.class, () -> builder.setEmailAddress("invalidEmail"));
    builder.setEmailAddress("email@email.com");
    assertThrows(IllegalArgumentException.class, builder::build);
    builder.setUserRoles(new UserRoles.Builder().build());
    builder.build();
  }
}
