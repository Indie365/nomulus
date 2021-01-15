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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Iterables;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.tools.CommandUtilities.ResourceType;
import google.registry.xml.XmlTransformer;
import java.util.Locale;
import org.joda.time.DateTime;

/** Command to show history entries. */
@Parameters(
    separators = " =",
    commandDescription = "Show history entries that occurred in a given time range")
final class GetHistoryEntriesCommand implements CommandWithRemoteApi {

  @Parameter(
      names = {"-a", "--after"},
      description = "Only show history entries that occurred at or after this time")
  private DateTime after = START_OF_TIME;

  @Parameter(
      names = {"-b", "--before"},
      description = "Only show history entries that occurred at or before this time")
  private DateTime before = END_OF_TIME;

  @Parameter(names = "--type", description = "Resource type.")
  private ResourceType type;

  @Parameter(names = "--id", description = "Foreign key of the resource.")
  private String uniqueId;

  @Override
  public void run() {
    Iterable<? extends HistoryEntry> historyEntries;
    if (type != null || uniqueId != null) {
      checkArgument(
          type != null && uniqueId != null,
          "If either of 'type' or 'id' are set then both must be");
      VKey<? extends EppResource> parentKey = type.getKey(uniqueId, DateTime.now(UTC));
      checkArgumentNotNull(parentKey, "Invalid resource ID");
      historyEntries = loadEntriesForSingleResource(parentKey);
    } else {
      historyEntries = loadAllHistoryEntries();
    }
    for (HistoryEntry entry : historyEntries) {
      System.out.printf(
          "Client: %s\nTime: %s\nClient TRID: %s\nServer TRID: %s\n%s\n",
          entry.getClientId(),
          entry.getModificationTime(),
          (entry.getTrid() == null) ? null : entry.getTrid().getClientTransactionId().orElse(null),
          (entry.getTrid() == null) ? null : entry.getTrid().getServerTransactionId(),
          entry.getXmlBytes() == null
              ? String.format("[no XML stored for %s]\n", entry.getType())
              : XmlTransformer.prettyPrint(entry.getXmlBytes()));
    }
  }

  private Iterable<? extends HistoryEntry> loadEntriesForSingleResource(
      VKey<? extends EppResource> parentKey) {
    if (tm().isOfy()) {
      return ofy()
          .load()
          .type(HistoryEntry.class)
          .ancestor(parentKey.getOfyKey())
          .order("modificationTime")
          .filter("modificationTime >=", after)
          .filter("modificationTime <=", before);
    } else {
      Class<? extends HistoryEntry> clazz =
          type.equals(ResourceType.CONTACT)
              ? ContactHistory.class
              : type.equals(ResourceType.HOST) ? HostHistory.class : DomainHistory.class;
      String repoIdFieldName = String.format("%sRepoId", type.name().toLowerCase(Locale.ENGLISH));
      String queryString =
          String.format(
              "SELECT entry FROM %s entry WHERE entry.modificationTime >= :afterTime AND "
                  + "entry.modificationTime <= :beforeTime AND entry.%s = :parentKey",
              clazz.getSimpleName(), repoIdFieldName);
      return jpaTm()
          .transact(
              () ->
                  jpaTm()
                      .getEntityManager()
                      .createQuery(queryString, clazz)
                      .setParameter("afterTime", after)
                      .setParameter("beforeTime", before)
                      .setParameter("parentKey", parentKey.getSqlKey().toString())
                      .getResultList());
    }
  }

  private Iterable<HistoryEntry> loadAllHistoryEntries() {
    if (tm().isOfy()) {
      return ofy()
          .load()
          .type(HistoryEntry.class)
          .order("modificationTime")
          .filter("modificationTime >=", after)
          .filter("modificationTime <=", before);
    } else {
      return jpaTm()
          .transact(
              () ->
                  Iterables.concat(
                      getAllHistoryEntriesFromTable(DomainHistory.class),
                      getAllHistoryEntriesFromTable(HostHistory.class),
                      getAllHistoryEntriesFromTable(ContactHistory.class)));
    }
  }

  private Iterable<? extends HistoryEntry> getAllHistoryEntriesFromTable(
      Class<? extends HistoryEntry> clazz) {
    return jpaTm()
        .getEntityManager()
        .createQuery(
            String.format(
                "SELECT entry FROM %s entry WHERE entry.modificationTime >= :afterTime AND "
                    + "entry.modificationTime <= :beforeTime",
                clazz.getSimpleName()),
            clazz)
        .setParameter("afterTime", after)
        .setParameter("beforeTime", before)
        .getResultList();
  }
}
