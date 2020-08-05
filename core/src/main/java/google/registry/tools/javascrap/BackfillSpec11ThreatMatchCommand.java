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

import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import google.registry.beam.spec11.Spec11Pipeline;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.gcs.GcsUtils;
import google.registry.model.domain.DomainBase;
import google.registry.model.reporting.Spec11ThreatMatch;
import google.registry.model.reporting.Spec11ThreatMatch.ThreatType;
import google.registry.tools.ConfirmingCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import jdk.nashorn.internal.ir.annotations.Immutable;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class BackfillSpec11ThreatMatchCommand extends ConfirmingCommand {

  private static final YearMonth START_MONTH = new YearMonth(2019, 01);
  private static final YearMonth END_MONTH =
      new YearMonth(); // Constructs a YearMonth with the current year and month

  private static int numFiles = 0;
  private static final Pattern FILENAME_PATTERN =
      Pattern.compile("SPEC11_MONTHLY_REPORT_(\\d{4}-\\d{2}-\\d{2})");
  private static final String REPORTING_FOLDER = "domain-registry-reporting/icann/spec11/";
  private static ImmutableMap<GcsFilename, LocalDate> filenamesToDates;

  @Inject GcsUtils gcsUtils;

  @Override
  protected String prompt() throws IOException {
    filenamesToDates = mapFilenamesToDates(START_MONTH, END_MONTH);
    return String.format("Parsing through %d files.", numFiles);
  }

  @Override
  protected String execute() {
    ImmutableSet.Builder<GcsFilename> failedFilesBuilder = new ImmutableSet.Builder<>();
    for (GcsFilename spec11ReportFilename : filenamesToDates.keySet()) {
      try {
        ImmutableList<Spec11ThreatMatch> threatMatches =
            getSpec11ThreatMatchesFromFile(
                spec11ReportFilename, filenamesToDates.get(spec11ReportFilename));
        jpaTm().transact(() -> jpaTm().saveNewOrUpdateAll(threatMatches));
      } catch (IOException e) {
        failedFilesBuilder.add(spec11ReportFilename);
      }
    }
    ImmutableSet<GcsFilename> failedFiles = failedFilesBuilder.build();
    if (failedFiles.isEmpty()) {
      return String.format("Successfully parsed through %d files.", numFiles);
    } else {
      return String.format(
          "Successfully parsed through %d files. We failed to parse through the following files: %s",
          numFiles - failedFiles.size(), failedFiles);
    }
  }

  protected ImmutableList<Spec11ThreatMatch> createSpec11ThreatMatches(String line, LocalDate date)
      throws JSONException {
    JSONObject reportJSON = new JSONObject(line);
    JSONArray threatMatchesArray = reportJSON.getJSONArray(Spec11Pipeline.THREAT_MATCHES_FIELD);
    ImmutableList.Builder<Spec11ThreatMatch> threatMatches = ImmutableList.builder();
    for (int i = 0; i < threatMatchesArray.length(); i++) {
      ThreatMatch threatMatch = ThreatMatch.fromJSON(threatMatchesArray.getJSONObject(i));
      String domainName = threatMatch.fullyQualifiedDomainName();
      Spec11ThreatMatch spec11ThreatMatch =
          new Spec11ThreatMatch.Builder()
              .setThreatTypes(ImmutableSet.of(ThreatType.valueOf(threatMatch.threatType())))
              .setDomainName(domainName)
              .setCheckDate(date)
              .setRegistrarId(reportJSON.getString(Spec11Pipeline.REGISTRAR_CLIENT_ID_FIELD))
              .setDomainRepoId(getDomainRepoId(domainName, date.toDateTimeAtStartOfDay()))
              .build();
      threatMatches.add(spec11ThreatMatch);
    }
    return threatMatches.build();
  }

  protected LocalDate getDateFromFilename(String filename, Pattern pattern) {
    Matcher matcher = pattern.matcher(filename);
    return LocalDate.parse(matcher.group(0), ISODateTimeFormat.date());
  }

  private String getDomainRepoId(String domainName, DateTime now) {
    DomainBase domain =
        loadByForeignKeyCached(DomainBase.class, domainName, now)
            .orElseThrow(
                () -> new IllegalArgumentException(String.format("Unknown domain %s", domainName)));
    return domain.getRepoId();
  }

  protected ImmutableList<Spec11ThreatMatch> getSpec11ThreatMatchesFromFile(
      GcsFilename spec11ReportFilename, LocalDate date) throws IOException, JSONException {
    ImmutableList.Builder<Spec11ThreatMatch> threatMatches = ImmutableList.builder();
    try (InputStream in = gcsUtils.openInputStream(spec11ReportFilename)) {
      ImmutableList<String> reportLines =
          ImmutableList.copyOf(CharStreams.toString(new InputStreamReader(in, UTF_8)).split("\n"));
      // Iterate from 1 to size() to skip the header at line 0.
      for (int i = 1; i < reportLines.size(); i++) {
        threatMatches.addAll(createSpec11ThreatMatches(reportLines.get(i), date));
      }
      return threatMatches.build();
    }
  }

  /**
   * Get all of the folders that contain the JSON files and list all files from each folder. Map
   * each GcsFilename to a LocalDate corresponding to the date of the pipeline run.
   *
   * @param startMonth the month at which we begin parsing the files
   * @param endMonth the month at which we stop parsing the files
   * @return the number of files to be parsed
   * @throws IOException
   */
  private ImmutableMap<GcsFilename, LocalDate> mapFilenamesToDates(
      YearMonth startMonth, YearMonth endMonth) throws IOException {
    ImmutableMap.Builder<GcsFilename, LocalDate> mappedFilenamesToDates =
        new ImmutableMap.Builder<>();
    while (!startMonth.isAfter(endMonth)) {
      String bucket = String.format("%s%s", REPORTING_FOLDER, startMonth.toString());
      ImmutableList<String> filesFromBucket =
          gcsUtils.listFolderObjects(bucket, "SPEC11_MONTHLY_REPORT_");
      for (String filename : filesFromBucket) {
        LocalDate fileDate = getDateFromFilename(filename, FILENAME_PATTERN);
        mappedFilenamesToDates.put(
            new GcsFilename(
                "domain-registry-reporting", Spec11Pipeline.getSpec11ReportFilePath(fileDate)),
            fileDate);
        numFiles += 1;
      }
      startMonth = startMonth.plusMonths(1);
    }
    return mappedFilenamesToDates.build();
  }
}
