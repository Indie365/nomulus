// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.domain.fee;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.math.BigDecimal;

/** A credit, in currency units specified elsewhere in the xml, and with an optional description. */
public class Credit extends BaseFee {
  public static Credit create(BigDecimal cost, FeeType type, Object... descriptionArgs) {
    Credit instance = new Credit();
    instance.cost = checkNotNull(cost);
    checkArgument(instance.cost.signum() < 0);
    instance.type = checkNotNull(type);
    instance.generateDescription(descriptionArgs);
    return instance;
  }
}
