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

package google.registry.tools.javascrap;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.model.reporting.Spec11ThreatMatchDao;
import google.registry.reporting.spec11.RegistrarThreatMatches;
import google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParser;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;

/**
 * Scrap tool to backfill {@link Spec11ThreatMatch} objects from prior days.
 *
 * <p>This will load the previously-existing Spec11 files from GCS (looking back to 2019-01-01 (a
 * rough estimate of when we started using this format) and convert those RegistrarThreatMatches
 * objects into the new Spec11ThreatMatch format. It will then insert these entries into SQL.
 */
@Parameters(
    commandDescription =
        "Backfills Spec11 threat match entries from the old and deprecated GCS JSON files to the "
            + "Cloud SQL database.")
public class BackfillSpec11ThreatMatchesCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  private static final LocalDate START_DATE = new LocalDate(2019, 1, 1);
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Spec11RegistrarThreatMatchesParser threatMatchesParser;
  // Inject the clock for testing purposes
  @Inject Clock clock;

  @Override
  protected String prompt() {
    return String.format(
        "We will attempt to backfill Spec11 results from %d files.", getDatesToBackfill().size());
  }

  @Override
  protected String execute() {
    ImmutableList<LocalDate> dates = getDatesToBackfill();
    ImmutableSet.Builder<LocalDate> failedDatesBuilder = new ImmutableSet.Builder<>();
    int totalNumThreats = 0;
    for (LocalDate date : dates) {
      try {
        // It's OK if the file doesn't exist for a particular date; the result will be empty.
        ImmutableList<Spec11ThreatMatch> threatMatches =
            threatMatchesParser.getRegistrarThreatMatches(date).stream()
                .map(rtm -> registrarThreatMatchesToNewObjects(rtm, date))
                .flatMap(ImmutableList::stream)
                .collect(toImmutableList());
        if (!threatMatches.isEmpty()) {
          jpaTm()
              .transact(
                  () -> {
                    Spec11ThreatMatchDao.deleteEntriesByDate(jpaTm(), date);
                    jpaTm().putAll(threatMatches);
                  });
        }
        totalNumThreats += threatMatches.size();
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Error parsing through file with date %s.", date);
        failedDatesBuilder.add(date);
      }
    }
    ImmutableSet<LocalDate> failedDates = failedDatesBuilder.build();
    if (failedDates.isEmpty()) {
      return String.format(
          "Successfully parsed through %d files with %d threats.", dates.size(), totalNumThreats);
    } else {
      return String.format(
          "Successfully parsed through %d files with %d threats. "
              + "Failed to parse through files with the following dates: %s",
          dates.size() - failedDates.size(), totalNumThreats, Joiner.on('\n').join(failedDates));
    }
  }

  /** Converts the previous {@link RegistrarThreatMatches} objects to {@link Spec11ThreatMatch}. */
  private ImmutableList<Spec11ThreatMatch> registrarThreatMatchesToNewObjects(
      RegistrarThreatMatches registrarThreatMatches, LocalDate date) {
    return registrarThreatMatches.threatMatches().stream()
        .map(
            threatMatch ->
                new Spec11ThreatMatch.Builder()
                    .setThreatTypes(ImmutableSet.of(ThreatType.valueOf(threatMatch.threatType())))
                    .setCheckDate(date)
                    .setRegistrarId(registrarThreatMatches.clientId())
                    .setDomainName(threatMatch.fullyQualifiedDomainName())
                    .setDomainRepoId(
                        getDomainRepoId(
                            threatMatch.fullyQualifiedDomainName(), date.toDateTimeAtStartOfDay()))
                    .build())
        .collect(toImmutableList());
  }

  private String getDomainRepoId(String domainName, DateTime now) {
    return loadByForeignKeyCached(DomainBase.class, domainName, now)
        .orElseThrow(
            () -> new IllegalArgumentException(String.format("Unknown domain %s", domainName)))
        .getRepoId();
  }

  /** Returns the list of dates between {@link #START_DATE} and now (UTC), inclusive. */
  private ImmutableList<LocalDate> getDatesToBackfill() {
    ImmutableList.Builder<LocalDate> result = new ImmutableList.Builder<>();
    LocalDate endDate = clock.nowUtc().toLocalDate();
    for (LocalDate currentDate = START_DATE;
        !currentDate.isAfter(endDate);
        currentDate = currentDate.plusDays(1)) {
      result.add(currentDate);
    }
    return result.build();
  }
}
