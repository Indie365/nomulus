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

package google.registry.tools.server;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.GcsTestingUtils.readGcsFile;
import static google.registry.tools.server.ResaveRecurringBillingEventAction.FILENAME_FORMAT;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static java.util.function.Function.identity;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.ListOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResaveRecurringBillingEventAction}. */
class ResaveRecurringBillingEventActionTest
    extends MapreduceTestCase<ResaveRecurringBillingEventAction> {

  private static final DatastoreService datastoreService =
      DatastoreServiceFactory.getDatastoreService();
  private static final String BUCKET = "test-bucket";
  private final GcsService gcsService = GcsServiceFactory.createGcsService();

  private final DateTime now = DateTime.now(UTC);
  private DomainBase domain1;
  private DomainBase domain2;
  private HistoryEntry historyEntry1;
  private HistoryEntry historyEntry2;
  private BillingEvent.Recurring recurring1;
  private BillingEvent.Recurring recurring2;

  @BeforeEach
  void beforeEach() {
    action = new ResaveRecurringBillingEventAction();
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
    action.gcsBucket = BUCKET;
    action.gcsBufferSize = 0;
    action.isDryRun = false;

    createTld("tld");
    domain1 = persistActiveDomain("foo.tld");
    domain2 = persistActiveDomain("bar.tld");
    historyEntry1 =
        persistResource(
            new HistoryEntry.Builder().setParent(domain1).setModificationTime(now).build());
    historyEntry2 =
        persistResource(
            new HistoryEntry.Builder()
                .setParent(domain2)
                .setModificationTime(now.plusDays(1))
                .build());
    recurring1 =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setParent(historyEntry1)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setReason(Reason.RENEW)
                .setEventTime(now.plusYears(1))
                .setRecurrenceEndTime(END_OF_TIME)
                .setClientId("a registrar")
                .setTargetId("foo.tld")
                .build());
    recurring2 =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setId(recurring1.getId())
                .setParent(historyEntry2)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setReason(Reason.RENEW)
                .setEventTime(now.plusYears(1))
                .setRecurrenceEndTime(END_OF_TIME)
                .setClientId("a registrar")
                .setTargetId("bar.tld")
                .build());
  }

  private void runMapreduce() {
    action.run();
    try {
      executeTasksUntilEmpty("mapreduce");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testDryRun() {
    action.isDryRun = true;

    runMapreduce();

    assertNotChangeInDatastore(
        domain1, domain2, historyEntry1, historyEntry2, recurring1, recurring2);

    assertThat(getGcsFileNames("dry_run/").size()).isEqualTo(2);
  }

  @Test
  void testDoNothingWhenNoDuplicateId() {
    deleteResource(recurring2);
    recurring2 = persistResource(recurring2.asBuilder().setId(0L).build());
    assertThat(recurring1.getId()).isNotEqualTo(recurring2.getId());

    runMapreduce();

    assertNotChangeInDatastore(
        domain1, domain2, historyEntry1, historyEntry2, recurring1, recurring2);
    assertThat(getGcsFileNames()).isEmpty();
  }

  @Test
  void testOnlyResaveBillingEventsCorrectly() {
    assertThat(recurring1.getId()).isEqualTo(recurring2.getId());

    runMapreduce();

    assertNotChangeExceptUpdateTime(domain1, domain2, historyEntry1, historyEntry2);
    assertNotInDatastore(recurring1, recurring2);

    ImmutableMap<String, GcsFilename> gcsFilenames = getGcsFileNames();
    assertThat(gcsFilenames.size()).isEqualTo(2);

    ImmutableList<BillingEvent.Recurring> recurrings = loadAllRecurrings();
    assertThat(recurrings.size()).isEqualTo(2);

    recurrings.forEach(
        newRecurring -> {
          if (newRecurring.getTargetId().equals("foo.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring1);
            assertCorrectGcsFileContent(gcsFilenames, recurring1, newRecurring);
          } else if (newRecurring.getTargetId().equals("bar.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring2);
            assertCorrectGcsFileContent(gcsFilenames, recurring2, newRecurring);
          } else {
            fail("Unknown BillingEvent.Recurring entity: " + newRecurring.createVKey());
          }
        });
  }

  @Test
  void testResaveAssociatedDomainAndOneTimeBillingEventCorrectly() {
    assertThat(recurring1.getId()).isEqualTo(recurring2.getId());
    domain1 =
        persistResource(
            domain1
                .asBuilder()
                .setAutorenewBillingEvent(recurring1.createVKey())
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurring(
                            GracePeriodStatus.AUTO_RENEW,
                            domain1.getRepoId(),
                            now.plusDays(45),
                            "a registrar",
                            recurring1.createVKey())))
                .setTransferData(
                    new DomainTransferData.Builder()
                        .setServerApproveAutorenewEvent(recurring1.createVKey())
                        .setServerApproveEntities(ImmutableSet.of(recurring1.createVKey()))
                        .build())
                .build());

    BillingEvent.OneTime oneTime =
        persistResource(
            new BillingEvent.OneTime.Builder()
                .setClientId("a registrar")
                .setTargetId("foo.tld")
                .setParent(historyEntry1)
                .setReason(Reason.CREATE)
                .setFlags(ImmutableSet.of(Flag.SYNTHETIC))
                .setSyntheticCreationTime(now)
                .setPeriodYears(2)
                .setCost(Money.of(USD, 1))
                .setEventTime(now)
                .setBillingTime(now.plusDays(5))
                .setCancellationMatchingBillingEvent(recurring1.createVKey())
                .build());

    runMapreduce();

    assertNotChangeExceptUpdateTime(domain2, historyEntry1, historyEntry2);
    assertNotInDatastore(recurring1, recurring2);
    ImmutableMap<String, GcsFilename> gcsFilenames = getGcsFileNames();
    assertThat(gcsFilenames.size()).isEqualTo(2);
    ImmutableList<BillingEvent.Recurring> recurrings = loadAllRecurrings();
    assertThat(recurrings.size()).isEqualTo(2);

    recurrings.forEach(
        newRecurring -> {
          if (newRecurring.getTargetId().equals("foo.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring1);
            assertCorrectGcsFileContent(gcsFilenames, recurring1, newRecurring);

            BillingEvent.OneTime persistedOneTime = ofy().load().entity(oneTime).now();
            assertAboutImmutableObjects()
                .that(persistedOneTime)
                .isEqualExceptFields(oneTime, "cancellationMatchingBillingEvent");
            assertThat(persistedOneTime.getCancellationMatchingBillingEvent())
                .isEqualTo(newRecurring.createVKey());

            DomainBase persistedDomain = ofy().load().entity(domain1).now();
            assertAboutImmutableObjects()
                .that(persistedDomain)
                .isEqualExceptFields(
                    domain1,
                    "updateTimestamp",
                    "revisions",
                    "gracePeriods",
                    "transferData",
                    "autorenewBillingEvent");
            assertThat(persistedDomain.getAutorenewBillingEvent())
                .isEqualTo(newRecurring.createVKey());
            assertThat(persistedDomain.getGracePeriods())
                .containsExactly(
                    GracePeriod.createForRecurring(
                        GracePeriodStatus.AUTO_RENEW,
                        domain1.getRepoId(),
                        now.plusDays(45),
                        "a registrar",
                        newRecurring.createVKey()));
            assertThat(persistedDomain.getTransferData().getServerApproveAutorenewEvent())
                .isEqualTo(newRecurring.createVKey());
            assertThat(persistedDomain.getTransferData().getServerApproveEntities())
                .containsExactly(newRecurring.createVKey());

          } else if (newRecurring.getTargetId().equals("bar.tld")) {
            assertSameRecurringEntityExceptId(newRecurring, recurring2);
            assertCorrectGcsFileContent(gcsFilenames, recurring2, newRecurring);
          } else {
            fail("Unknown BillingEvent.Recurring entity: " + newRecurring.createVKey());
          }
        });
  }

  private ImmutableMap<String, GcsFilename> getGcsFileNames() {
    return getGcsFileNames("");
  }

  private ImmutableMap<String, GcsFilename> getGcsFileNames(String prefix) {
    try {
      return ImmutableList.copyOf(
              gcsService.list(
                  BUCKET, new ListOptions.Builder().setPrefix(prefix).setRecursive(true).build()))
          .stream()
          .map(listItem -> new GcsFilename(BUCKET, listItem.getName()))
          .collect(toImmutableMap(GcsFilename::getObjectName, identity()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void assertCorrectGcsFileContent(
      ImmutableMap<String, GcsFilename> gcsFileNames,
      BillingEvent.Recurring oldRecurring,
      BillingEvent.Recurring newRecurring) {
    String fileName = String.format(FILENAME_FORMAT, oldRecurring.getId(), newRecurring.getId());
    try {
      String fileContent =
          new String(readGcsFile(gcsService, gcsFileNames.get(fileName)), StandardCharsets.UTF_8);
      assertThat(fileContent).contains(oldRecurring.createVKey().getOfyKey().toString());
      assertThat(fileContent).contains(newRecurring.createVKey().getOfyKey().toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void assertNotInDatastore(ImmutableObject... entities) {
    for (ImmutableObject entity : entities) {
      assertThat(ofy().load().entity(entity).now()).isNull();
    }
  }

  private static void assertNotChangeInDatastore(ImmutableObject... entities) {
    for (ImmutableObject entity : entities) {
      assertThat(ofy().load().entity(entity).now()).isEqualTo(entity);
    }
  }

  private static void assertNotChangeExceptUpdateTime(ImmutableObject... entities) {
    for (ImmutableObject entity : entities) {
      assertAboutImmutableObjects()
          .that(ofy().load().entity(entity).now())
          .isEqualExceptFields(entity, "updateTimestamp", "revisions");
    }
  }

  private static void assertSameRecurringEntityExceptId(
      BillingEvent.Recurring recurring1, BillingEvent.Recurring recurring2) {
    assertAboutImmutableObjects().that(recurring1).isEqualExceptFields(recurring2, "id");
  }

  private static ImmutableList<BillingEvent.Recurring> loadAllRecurrings() {
    return ImmutableList.copyOf(ofy().load().type(BillingEvent.Recurring.class));
  }
}
