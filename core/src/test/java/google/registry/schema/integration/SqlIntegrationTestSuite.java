// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.integration;

import google.registry.model.registry.RegistryLockDaoTest;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.schema.cursor.CursorDaoTest;
import google.registry.schema.tld.PremiumListDaoTest;
import google.registry.schema.tld.PremiumListUtilsTest;
import google.registry.schema.tld.ReservedListDaoTest;
import google.registry.schema.tmch.ClaimsListDaoTest;
import google.registry.tools.CreateReservedListCommandTest;
import google.registry.tools.UpdateReservedListCommandTest;
import google.registry.tools.server.CreatePremiumListActionTest;
import google.registry.tools.server.UpdatePremiumListActionTest;
import google.registry.ui.server.registrar.RegistryLockGetActionTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/**
 * Groups all tests that may depends on Cloud SQL schema. They will be run for server-schema
 * compatibility check.
 *
 * <p>Schema dependency is approximated by the use of {@link JpaIntegrationTestRule}.
 *
 * @see SqlIntegrationMembershipTest
 */
// TODO(weiminyu): refactor JpaTransactionManagerRule to eliminate false positives.
@RunWith(Suite.class)
@SuiteClasses({
  ClaimsListDaoTest.class,
  CreateReservedListCommandTest.class,
  CursorDaoTest.class,
  CreatePremiumListActionTest.class,
  PremiumListDaoTest.class,
  PremiumListUtilsTest.class,
  RegistryLockDaoTest.class,
  RegistryLockGetActionTest.class,
  ReservedListDaoTest.class,
  UpdatePremiumListActionTest.class,
  UpdateReservedListCommandTest.class
})
public class SqlIntegrationTestSuite {}
