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
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.persistence.VKey;
import google.registry.schema.tld.PremiumEntry;
import google.registry.schema.tld.PremiumListSqlDao;
import google.registry.schema.tld.PremiumListUtils;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.joda.money.BigMoney;

/** Command to safely update {@link PremiumList} in Datastore for a given TLD. */
@Parameters(separators = " =", commandDescription = "Update a PremiumList in Datastore.")
class UpdatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Override
  // Using UpdatePremiumListAction.java as reference;
  protected void init() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    Optional<PremiumList> sqlList = PremiumListSqlDao.getLatestRevision(name);
    checkArgument(
        sqlList.isPresent(),
        String.format("Could not update premium list %s because it doesn't exist.", name));

    List<String> existingStr = getExistingPremiumListEntry(sqlList).asList();
    allLines = Files.readAllLines(inputFile, UTF_8);
    inputLineCount = allLines.size();

    // reconstructing existing premium list to bypass Hibernate lazy initialization exception
    PremiumList existingPremiumList = PremiumListUtils.parseToPremiumList(name, existingStr);
    PremiumList updatedPremiumList = PremiumListUtils.parseToPremiumList(name, allLines);

    // use LabelsToPrices() for comparison between old and new premium lists since they have
    // different creation date, updated date even if they have same content;
    if (!existingPremiumList.getLabelsToPrices().equals(updatedPremiumList.getLabelsToPrices())) {
      stageEntityChange(
          existingPremiumList,
          updatedPremiumList,
          VKey.createOfy(PremiumList.class, Key.create(existingPremiumList)));
    }
  }

  // to get the premium list entry data as an immutable set of string, this is a workaround without
  // Hibernate.LazyInitializationException error. It occurs if 
  private ImmutableSet<String> getExistingPremiumListEntry(Optional<PremiumList> list) {
    Iterable<PremiumEntry> sqlListEntries =
        jpaTm().transact(() -> PremiumListSqlDao.loadPremiumListEntriesUncached(list.get()));
    return Streams.stream(sqlListEntries)
        .map(
            premiumEntry ->
                new PremiumListEntry.Builder()
                    .setPrice(
                        BigMoney.of(list.get().getCurrency(), premiumEntry.getPrice()).toMoney())
                    .setLabel(premiumEntry.getDomainLabel())
                    .build()
                    .toString())
        .collect(toImmutableSet());
  }
}
