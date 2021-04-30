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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.io.Files;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.schema.tld.PremiumEntry;
import google.registry.schema.tld.PremiumListSqlDao;
import java.io.File;
import java.io.IOException;
import java.util.Optional;
import org.joda.money.BigMoney;
import org.junit.jupiter.api.BeforeEach;

/** Base class for common testing setup for create and update commands for Premium Lists. */
abstract class CreateOrUpdatePremiumListCommandTestCase<T extends CreateOrUpdatePremiumListCommand>
    extends CommandTestCase<T> {

  protected static final String TLD_TEST = "prime";
  protected String premiumTermsPath;

  @BeforeEach
  void beforeEachCreateOrUpdateReservedListCommandTestCase() throws IOException {
    // set up for initial data
    File premiumTermsFile = tmpDir.resolve("prime.txt").toFile();
    String premiumTermsCsv = "foo,USD 2020";
    Files.asCharSink(premiumTermsFile, UTF_8).write(premiumTermsCsv);
    premiumTermsPath = premiumTermsFile.getPath();
  }

  /*
  To get premium list content as a set of string. This is a workaround to avoid dealing with
  Hibernate.LazyInitizationException error,
  "Cannot evaluate google.registry.model.registry.label.PremiumList.toString()'".
   Ideally, the following should be the way to verify info in latest revision of a premium list:

    PremiumList existingPremiumList =
        PremiumListDualDao.getLatestRevision(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Could not update premium list %s because it doesn't exist.", name)));
    assertThat(persistedList.getLabelsToPrices()).containsEntry("foo", new BigDecimal("9000.00"));
    assertThat(persistedList.size()).isEqualTo(1);

  * */
  ImmutableSet<String> getLatestPremiumListStringHelper(String name) {
    Optional<PremiumList> sqlList = PremiumListSqlDao.getLatestRevision(name);
    checkArgument(
        sqlList.isPresent(),
        String.format("Could not update premium list %s because it doesn't exist.", name));
    Iterable<PremiumEntry> sqlListEntries =
        jpaTm().transact(() -> PremiumListSqlDao.loadPremiumListEntriesUncached(sqlList.get()));

    return Streams.stream(sqlListEntries)
        .map(
            premiumEntry ->
                new PremiumListEntry.Builder()
                    .setPrice(
                        BigMoney.of(sqlList.get().getCurrency(), premiumEntry.getPrice()).toMoney())
                    .setLabel(premiumEntry.getDomainLabel())
                    .build()
                    .toString())
        .collect(toImmutableSet());
  }
}
