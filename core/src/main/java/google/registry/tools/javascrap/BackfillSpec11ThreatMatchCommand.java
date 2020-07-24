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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.CharStreams;
import google.registry.beam.spec11.Spec11Pipeline;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.gcs.GcsUtils;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.Months;
import org.joda.time.YearMonth;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class BackfillSpec11ThreatMatchCommand {

  private final GcsUtils gcsUtils;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final YearMonth START_DATE = new YearMonth(2019, 01);
  private static final YearMonth END_DATE = new YearMonth(2020, 07);
  private static final String REPORTING_BUCKET = "domain-registry-reporting/icann/spec11/";

  @Inject
  public BackfillSpec11ThreatMatchCommand(GcsUtils gcsUtils) {
    this.gcsUtils = gcsUtils;
  }

  public void persistSpec11ThreatMatches() throws IOException {
    ImmutableList<LocalDate> dateList = getDateList();
    ImmutableList<GcsFilename> spec11ReportFilenames = getSpec11ReportFilenames(dateList);

    for (GcsFilename spec11ReportFilename : spec11ReportFilenames) {
      ImmutableList<Spec11ThreatMatch> threatMatches =
          getSpec11ThreatMatchesFromFile(spec11ReportFilename);
      for (Spec11ThreatMatch threatMatch : threatMatches) {
        jpaTm()
            .transact(
                () -> {
                  jpaTm().saveNew(threatMatch);
                });
      }
    }
  }

  private ImmutableList<Spec11ThreatMatch> createSpec11ThreatMatches(String line, String date)
      throws JSONException {
    JSONObject reportJSON = new JSONObject(line);
    String registrarId = reportJSON.getString(Spec11Pipeline.REGISTRAR_CLIENT_ID_FIELD);
    JSONArray threatMatchesArray = reportJSON.getJSONArray(Spec11Pipeline.THREAT_MATCHES_FIELD);
    ImmutableList.Builder<Spec11ThreatMatch> threatMatches = ImmutableList.builder();
    for (int i = 0; i < threatMatchesArray.length(); i++) {
      ThreatMatch threatMatch = ThreatMatch.fromJSON(threatMatchesArray.getJSONObject(i));
      String domainName = threatMatch.fullyQualifiedDomainName();
      Spec11ThreatMatch spec11ThreatMatch =
          new Spec11ThreatMatch.Builder()
              .setThreatTypes(ImmutableSet.of(ThreatType.valueOf(threatMatch.threatType())))
              .setDomainName(domainName)
              .setCheckDate(LocalDate.parse(date, ISODateTimeFormat.date()))
              .setRegistrarId(registrarId)
              .setDomainRepoId(getDomainRepoId(domainName, registrarId, new DateTime()))
              .build();
      threatMatches.add(spec11ThreatMatch);
    }
    return threatMatches.build();
  }

  private ImmutableList<Spec11ThreatMatch> getSpec11ThreatMatchesFromFile(
      GcsFilename spec11ReportFilename) throws IOException, JSONException {
    ImmutableList.Builder<Spec11ThreatMatch> builder = ImmutableList.builder();
    try (InputStream in = gcsUtils.openInputStream(spec11ReportFilename)) {
      ImmutableList<String> reportLines =
          ImmutableList.copyOf(CharStreams.toString(new InputStreamReader(in, UTF_8)).split("\n"));
      // Iterate from 1 to size() to skip the header at line 0.
      for (int i = 1; i < reportLines.size(); i++) {
        String date = getDateFromFilename(spec11ReportFilename.getBucketName());
        for (Spec11ThreatMatch threatMatch : createSpec11ThreatMatches(reportLines.get(i), date)) {
          builder.add(threatMatch);
        }
      }
      return builder.build();
    }
  }

  // This method has not been tested yet, and there may be an off-by-one error
  private List<YearMonth> getMonthsList(YearMonth start, YearMonth end) {
    final int months = Months.monthsBetween(start, end).getMonths();

    return Stream.iterate(start, month -> month.plusMonths(1))
        .limit(months)
        .collect(Collectors.toList());
  }

  /**
   * The format of the filename is SPEC11_MONTHLY_REPORT_yyyy-mm-dd. The date starts at the 22nd
   * character.
   */
  private String getDateFromFilename(String filename) {
    return filename.substring(22);
  }

  private ImmutableList<LocalDate> getDateList() throws IOException {
    List<YearMonth> bucketMonths = getMonthsList(START_DATE, END_DATE);
    ImmutableList.Builder<LocalDate> dateList = new ImmutableList.Builder<>();
    for (YearMonth bucketMonth : bucketMonths) {
      ImmutableList<String> filesFromBucket =
          gcsUtils.listFolderObjects(REPORTING_BUCKET, bucketMonth.toString());
      for (String file : filesFromBucket) {
        String date = getDateFromFilename(file);
        LocalDate fileDate = LocalDate.parse(date, ISODateTimeFormat.date());
        dateList.add(fileDate);
      }
    }
    return dateList.build();
  }

  private GcsFilename getGcsFilename(String reportingBucket, LocalDate localDate) {
    return new GcsFilename(reportingBucket, Spec11Pipeline.getSpec11ReportFilePath(localDate));
  }

  private ImmutableList<GcsFilename> getSpec11ReportFilenames(ImmutableList<LocalDate> dateList)
      throws IOException {
    ImmutableList.Builder<GcsFilename> spec11ReportFilenames = new ImmutableList.Builder<>();
    for (LocalDate date : dateList) {
      GcsFilename gcsFilename = getGcsFilename(REPORTING_BUCKET, date);
      spec11ReportFilenames.add(gcsFilename);
    }
    return spec11ReportFilenames.build();
  }

  private String getDomainRepoId(String domainName, String registrarId, DateTime now) {
    DomainBase domain =
        loadByForeignKeyCached(DomainBase.class, domainName, now)
            .orElseThrow(
                () -> new IllegalArgumentException(String.format("Unknown domain %s", domainName)));
    // The user must have specified the correct registrar ID
    checkArgument(
        domain.getCurrentSponsorClientId().equals(registrarId),
        "Domain %s is not owned by registrar %s",
        domainName,
        registrarId);
    return domain.getRepoId();
  }
}
