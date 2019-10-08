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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.schema.tld.PremiumList;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeJsonResponse;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CreateOrUpdatePremiumListAction}. */
@RunWith(JUnit4.class)
public class CreateOrUpdatePremiumListActionTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  private CreatePremiumListAction action;
  private FakeJsonResponse response;

  @Before
  public void init() {
    action = new CreatePremiumListAction();
    response = new FakeJsonResponse();
    action.response = response;
    action.name = "testlist";
  }

  @Test
  public void parseInputToPremiumList_works() {
    action.inputData = "foo,USD 99.50\n" + "bar,USD 30\n" + "baz,USD 10\n";
    PremiumList premiumList = action.parseInputToPremiumList();
    assertThat(premiumList.getName()).isEqualTo("testlist");
    assertThat(premiumList.getLabelsToPrices())
        .containsExactly("foo", twoDigits(99.50), "bar", twoDigits(30), "baz", twoDigits(10));
  }

  @Test
  public void parseInputToPremiumList_throwsOnInconsistentCurrencies() {
    action.inputData = "foo,USD 99.50\n" + "bar,USD 30\n" + "baz,JPY 990\n";
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> action.parseInputToPremiumList());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("The Cloud SQL schema requires exactly one currency, but got: [JPY, USD]");
  }

  private static BigDecimal twoDigits(double num) {
    return BigDecimal.valueOf((long) (num * 100.0), 2);
  }
}
