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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.sampleThreatMatches;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParser;
import google.registry.tools.CommandTestCase;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link BackfillSpec11ThreatMatchesCommand}. */
public class BackfillSpec11ThreatMatchesCommandTest
    extends CommandTestCase<BackfillSpec11ThreatMatchesCommand> {

  private static final LocalDate CURRENT_DATE = DateTime.parse("2020-11-22").toLocalDate();
  private final Spec11RegistrarThreatMatchesParser threatMatchesParser =
      mock(Spec11RegistrarThreatMatchesParser.class);

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("com");
    persistActiveDomain("a.com");
    persistActiveDomain("b.com");
    persistActiveDomain("c.com");
    fakeClock.setTo(CURRENT_DATE.toDateTimeAtStartOfDay());
    command.threatMatchesParser = threatMatchesParser;
    command.clock = fakeClock;
    when(threatMatchesParser.getRegistrarThreatMatches(any(LocalDate.class)))
        .thenReturn(ImmutableSet.of());
  }

  @Test
  void testSuccess_singleFile() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    runCommandForced();
    assertInStdout("Attempt to backfill Spec11 results from 692 files?");
    assertInStdout("Successfully parsed through 692 files with 3 threats.");
    verifyExactlyThreeEntriesInDbFromLastDay();
  }

  @Test
  void testSuccess_sameDomain_multipleDays() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    when(threatMatchesParser.getRegistrarThreatMatches(LocalDate.parse("2019-01-01")))
        .thenReturn(sampleThreatMatches());
    runCommandForced();
    assertInStdout("Attempt to backfill Spec11 results from 692 files?");
    assertInStdout("Successfully parsed through 692 files with 6 threats.");
    jpaTm()
        .transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> threatMatches =
                  jpaTm().loadAll(Spec11ThreatMatch.class);
              assertThat(threatMatches).hasSize(6);
              assertThat(
                      threatMatches.stream()
                          .map(Spec11ThreatMatch::getDomainName)
                          .collect(toImmutableSet()))
                  .containsExactly("a.com", "b.com", "c.com");
              assertThat(
                      threatMatches.stream()
                          .map(Spec11ThreatMatch::getCheckDate)
                          .collect(toImmutableSet()))
                  .containsExactly(CURRENT_DATE, LocalDate.parse("2019-01-01"));
            });
  }

  @Test
  void testSuccess_empty() throws Exception {
    runCommandForced();
    assertInStdout("Attempt to backfill Spec11 results from 692 files?");
    assertInStdout("Successfully parsed through 692 files with 0 threats.");
  }

  @Test
  void testSuccess_sameDayTwice() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    runCommandForced();
    runCommandForced();
    verifyExactlyThreeEntriesInDbFromLastDay();
  }

  @Test
  void testFailure_twoFilesFail() throws Exception {
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE))
        .thenReturn(sampleThreatMatches());
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE.minusDays(1)))
        .thenThrow(new RuntimeException("hi"));
    when(threatMatchesParser.getRegistrarThreatMatches(CURRENT_DATE.minusDays(2)))
        .thenThrow(new RuntimeException("hi"));
    runCommandForced();
    assertInStdout("Attempt to backfill Spec11 results from 692 files?");
    assertInStdout(
        "Successfully parsed through 690 files with 3 threats. Failed to parse through "
            + "files with the following dates: 2020-11-20\n2020-11-21");
    verifyExactlyThreeEntriesInDbFromLastDay();
  }

  private void verifyExactlyThreeEntriesInDbFromLastDay() {
    jpaTm()
        .transact(
            () -> {
              ImmutableList<Spec11ThreatMatch> threatMatches =
                  jpaTm().loadAll(Spec11ThreatMatch.class);
              assertThat(threatMatches)
                  .comparingElementsUsing(immutableObjectCorrespondence("id", "domainRepoId"))
                  .containsExactly(
                      expectedThreatMatch("TheRegistrar", "a.com"),
                      expectedThreatMatch("NewRegistrar", "b.com"),
                      expectedThreatMatch("NewRegistrar", "c.com"));
            });
  }

  private Spec11ThreatMatch expectedThreatMatch(String registrarId, String domainName) {
    return new Spec11ThreatMatch.Builder()
        .setDomainRepoId("ignored")
        .setDomainName(domainName)
        .setRegistrarId(registrarId)
        .setCheckDate(CURRENT_DATE)
        .setThreatTypes(ImmutableSet.of(ThreatType.MALWARE))
        .build();
  }
}
