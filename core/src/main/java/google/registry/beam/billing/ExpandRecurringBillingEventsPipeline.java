// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.billing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Sets.difference;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.domain.Period.Unit.YEARS;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_AUTORENEW;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DateTimeUtils.latestOf;
import static org.apache.beam.sdk.values.TypeDescriptors.voids;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import dagger.Component;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.flows.custom.CustomLogicFactoryModule;
import google.registry.flows.custom.CustomLogicModule;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent.Cancellation;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.tld.Registry;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Singleton;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Wait;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.joda.time.DateTime;

/**
 * Definition of a Dataflow Flex pipeline template, which expands {@link Recurring} to {@link
 * OneTime} when an autorenew occurs within the given time frame.
 *
 * <p>This pipeline works in three stages:
 *
 * <ul>
 *   <li>Gather the {@link Recurring}s that are in scope for expansion. The exact condition of
 *       {@link Recurring}s to include can be found in {@link #getRecurringsInScope(Pipeline)}.
 *   <li>Expand the {@link Recurring}s to {@link OneTime} (and corresponding {@link DomainHistory})
 *       that fall within the [{@link #startTime}, {@link #endTime}) window, excluding those that
 *       are already present (to make this pipeline idempotent when running with the same parameters
 *       multiple times, either in parallel or in sequence). The {@link Recurring} is also updated
 *       with the information on when it was last expanded, so it would not be in scope for
 *       expansion until at least a year later.
 *   <li>If the cursor for billing events should be advanced, advance it to {@link #endTime} after
 *       all of the expansions in the previous step is done, only when it is currently at {@link
 *       #startTime}.
 * </ul>
 *
 * <p>Note that the creation of new {@link OneTime} and {@link DomainHistory} is done speculatively
 * as soon as its event time is in scope for expansion (i.e. within the window of operation). If a
 * domain is subsequently cancelled during the autorenew grace period, a {@link Cancellation} would
 * have been created to cancel the {@link OneTime} out. Similarly, a {@link DomainHistory} for the
 * delete will be created which negates the effect of the speculatively created {@link
 * DomainHistory}, specifically for the transaction records. Both the {@link OneTime} and {@link
 * DomainHistory} will only be used (and cancelled out) when the billing time becomes effective,
 * which is after the grace period, when the cancellations would have been written, if need be. This
 * is no different from what we do with manual renewals or normal creates, where entities are always
 * created for the action regardless of whether their effects will be negated later due to
 * subsequent actions within respective grace periods.
 *
 * <p>To stage this template locally, run {@code ./nom_build :core:sBP --environment=alpha \
 * --pipeline=expandBilling}.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * @see Cancellation#forGracePeriod
 * @see google.registry.flows.domain.DomainFlowUtils#createCancelingRecords
 * @see <a href="https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates">Using
 *     Flex Templates</a>
 */
public class ExpandRecurringBillingEventsPipeline implements Serializable {

  private static final long serialVersionUID = -5827984301386630194L;

  private static final DomainPricingLogic domainPricingLogic;

  private static final int batchSize;

  static {
    PipelineComponent pipelineComponent =
        DaggerExpandRecurringBillingEventsPipeline_PipelineComponent.create();
    domainPricingLogic = pipelineComponent.domainPricingLogic();
    batchSize = pipelineComponent.batchSize();
  }

  // Inclusive lower bound of the expansion window.
  private final DateTime startTime;
  // Exclusive lower bound of the expansion window.
  private final DateTime endTime;
  private final int shard;
  private final boolean isDryRun;
  private final boolean advanceCursor;
  private final Counter recurringsInScopeCounter =
      Metrics.counter("ExpandBilling", "RecurringsInScope");
  private final Counter expandedOneTimeCounter =
      Metrics.counter("ExpandBilling", "ExpandedOneTime");

  ExpandRecurringBillingEventsPipeline(ExpandRecurringBillingEventsPipelineOptions options) {
    startTime = DateTime.parse(options.getStartTime());
    endTime = DateTime.parse(options.getEndTime());
    checkArgument(
        startTime.isBefore(endTime),
        String.format("[%s, %s) is not a valid window of operation", startTime, endTime));
    shard = options.getShard();
    isDryRun = options.getIsDryRun();
    advanceCursor = options.getAdvanceCursor();
  }

  private PipelineResult run(Pipeline pipeline) {
    setupPipeline(pipeline);
    return pipeline.run();
  }

  void setupPipeline(Pipeline pipeline) {
    PCollection<KV<Integer, KV<Recurring, KV<Domain, Registry>>>> recurrings =
        getRecurringsInScope(pipeline);
    PCollection<Void> expanded = expandRecurrings(recurrings);
    if (!isDryRun && advanceCursor) {
      advanceCursor(expanded);
    }
  }

  PCollection<KV<Integer, KV<Recurring, KV<Domain, Registry>>>> getRecurringsInScope(
      Pipeline pipeline) {
    return pipeline.apply(
        "Read all Recurrings in scope",
        RegistryJpaIO.read(
                "SELECT br, d, tld "
                    + "FROM BillingRecurrence as br, Domain as d, Tld as tld "
                    + "WHERE br.domainRepoId = d.repoId "
                    + "AND d.tld = tld.tldStr "
                    // Recurrence should not close before the first event time.
                    + "AND br.eventTime < br.recurrenceEndTime "
                    // First event time should be before end time.
                    + "AND br.eventTime < :endTime "
                    // Recurrence should not close before start time.
                    + "AND :startTime < br.recurrenceEndTime "
                    // Last expansion should happen at least one year before start time.
                    + "AND br.recurrenceLastExpansion <= :oneYearAgo",
                ImmutableMap.of(
                    "endTime",
                    endTime,
                    "startTime",
                    startTime,
                    "oneYearAgo",
                    endTime.minusYears(1)),
                Object[].class,
                (Object[] row) -> {
                  Recurring recurring = (Recurring) row[0];
                  Domain domain = (Domain) row[1];
                  Registry registry = (Registry) row[2];
                  // Note that because all elements are mapped to the same dummy key, the next
                  // batching transform will effectively be serial. This however does not matter for
                  // our use case because the elements were obtained from a SQL read query, which
                  // are returned sequentially already. Therefore, having a sequential step to group
                  // them does not reduce overall parallelism of the pipeline, and the batches can
                  // then be distributed to all available workers for further processing, where the
                  // main benefit of parallelism shows. In fact, distributing elements to random
                  // keys in this step might increase overall latency as more elements need to be
                  // processed to generate a batch, when batching only occurs on elements with the
                  // same key (therefore increasing the overall buffer size of elements waiting to
                  // be grouped, across all keys), delaying subsequent steps when batches could be
                  // produced as soon as possible if the buffer size is kept at the minimum (same as
                  // batch size).
                  //
                  // See: https://stackoverflow.com/a/44956702/791306
                  return KV.of(
                      ThreadLocalRandom.current().nextInt(shard),
                      KV.of(recurring, KV.of(domain, registry)));
                })
            .withCoder(
                KvCoder.of(
                    VarIntCoder.of(),
                    KvCoder.of(
                        SerializableCoder.of(Recurring.class),
                        KvCoder.of(
                            SerializableCoder.of(Domain.class),
                            SerializableCoder.of(Registry.class))))));
  }

  private PCollection<Void> expandRecurrings(
      PCollection<KV<Integer, KV<Recurring, KV<Domain, Registry>>>> recurrings) {
    return recurrings
        .apply(
            "Group into batches",
            GroupIntoBatches.<Integer, KV<Recurring, KV<Domain, Registry>>>ofSize(batchSize)
                .withShardedKey())
        .apply(
            "Expand and save Recurrings into OneTimes and corresponding DomainHistories",
            MapElements.into(voids())
                .via(
                    element -> {
                      Iterable<KV<Recurring, KV<Domain, Registry>>> kvs = element.getValue();
                      tm().transact(
                              () -> {
                                ImmutableSet.Builder<ImmutableObject> results =
                                    new ImmutableSet.Builder<>();
                                kvs.forEach(kv -> expandOneRecurring(kv, results));
                                if (!isDryRun) {
                                  tm().putAll(results.build());
                                }
                              });
                      return null;
                    }));
  }

  private void expandOneRecurring(
      KV<Recurring, KV<Domain, Registry>> kv, ImmutableSet.Builder<ImmutableObject> results) {
    Recurring recurring = kv.getKey();
    // If the next event time is at or after recurrence end time, it could not have happened. It
    // would have been better if we could filter this kind of recurring out in SQL, but JPQL does
    // not support the needed arithmetic operation.
    if (!recurring
        .getRecurrenceLastExpansion()
        .plusYears(1)
        .isBefore(recurring.getRecurrenceEndTime())) {
      return;
    }
    recurringsInScopeCounter.inc();
    Domain domain = kv.getValue().getKey();
    Registry tld = kv.getValue().getValue();

    // Determine the complete set of EventTimes this recurring event should expand to within
    // [max(recurrenceLastExpansion + 1 yr, startTime), min(recurrenceEndTime, endTime)).
    ImmutableSet<DateTime> eventTimes =
        ImmutableSet.copyOf(
            recurring
                .getRecurrenceTimeOfYear()
                .getInstancesInRange(
                    Range.closedOpen(
                        latestOf(recurring.getRecurrenceLastExpansion().plusYears(1), startTime),
                        earliestOf(recurring.getRecurrenceEndTime(), endTime))));

    // Find the times for which the OneTime billing event are already created, making this expansion
    // idempotent. There is no need to match to the domain repo ID as the cancellation matching
    // billing event itself can only be for a single domain.
    ImmutableSet<DateTime> existingEventTimes =
        ImmutableSet.copyOf(
            jpaTm()
                .query(
                    "SELECT eventTime FROM BillingEvent WHERE cancellationMatchingBillingEvent ="
                        + " :key",
                    DateTime.class)
                .setParameter("key", recurring.createVKey())
                .getResultList());

    Set<DateTime> eventTimesToExpand = difference(eventTimes, existingEventTimes);

    if (eventTimesToExpand.isEmpty()) {
      return;
    }

    DateTime recurrenceLastExpansionTime = recurring.getRecurrenceLastExpansion();

    // Create new OneTime and DomainHistory for EventTimes that needs to be expanded.
    for (DateTime eventTime : eventTimesToExpand) {
      recurrenceLastExpansionTime = latestOf(recurrenceLastExpansionTime, eventTime);
      expandedOneTimeCounter.inc();
      DateTime billingTime = eventTime.plus(tld.getAutoRenewGracePeriodLength());
      if (domain.getDeletionTime().isBefore(billingTime)) {}

      // Note that the DomainHistory is created as of transaction time, as opposed to event time.
      // This might be counterintuitive because other DomainHistories are created at the time
      // mutation events occur, such as in DomainDeleteFlow or DomainRenewFlow. Therefore, it is
      // possible to have a DomainHistory for a delete after autorenew (during the grace period)
      // with a modification time before that of the DomainHistory for the autorenew itself. This is
      // not ideal, but necessary because we save the *current* state of the domain (as of
      // transaction time) to the DomainHistory , instead of the state of the domain as of event
      // time (which would required loading the domain from DomainHistory at event time). Even
      // though that is seemly possible, it generally is a bad idea to create DomainHistories
      // retroactively and in all instances that we create a HistoryEntry we always set the
      // modification time to the transaction time. It would also violate the invariance that a
      // DomainHistory with a higher revision ID (which is always allocated with monotonic increase)
      // always has a later modification time. Because the domain entity itself did not change as
      // part of the expansion, the belatedly created DomainHistory does not actually affect
      // anything materially (e.g. RDE). We can understand it in such a way that this history
      // represents not when the domain is autorenewed (at event time), but when its autorenew
      // billing event is created (at transaction time).
      DomainHistory historyEntry =
          new DomainHistory.Builder()
              .setBySuperuser(false)
              .setRegistrarId(recurring.getRegistrarId())
              .setModificationTime(tm().getTransactionTime())
              .setDomain(domain)
              .setPeriod(Period.create(1, YEARS))
              .setReason("Domain autorenewal by ExpandRecurringBillingEventsPipeline")
              .setRequestedByRegistrar(false)
              .setType(DOMAIN_AUTORENEW)
              .setDomainTransactionRecords(
                  // Don't write a domain transaction record if the domain is deleted before billing
                  // time (i.e. within the autorenew grace period). We cannot rely on a negating
                  // DomainHistory created by DomainDeleteFlow because it only cancels transaction
                  // records already present. In this case the domain was deleted before this
                  // pipeline runs to expand the OneTime (which should be rare because this pipeline
                  // should run every day), and no negating transaction records would have been
                  // created when the deletion occurred.
                  //
                  // See: DomainFlowUtils#createCancellingRecords
                  domain.getDeletionTime().isBefore(billingTime)
                      ? ImmutableSet.of()
                      : ImmutableSet.of(
                          DomainTransactionRecord.create(
                              tld.getTldStr(),
                              // We report this when the autorenew grace period ends.
                              billingTime,
                              TransactionReportField.netRenewsFieldFromYears(1),
                              1)))
              .build();
      results.add(historyEntry);

      // It is OK to always create a OneTime, even though the domain might be deleted later during
      // autorenew grace period.
      OneTime oneTime =
          new OneTime.Builder()
              .setBillingTime(billingTime)
              .setRegistrarId(recurring.getRegistrarId())
              // Determine the cost for a one-year renewal.
              .setCost(
                  domainPricingLogic
                      .getRenewPrice(tld, recurring.getTargetId(), eventTime, 1, recurring)
                      .getRenewCost())
              .setEventTime(eventTime)
              .setFlags(union(recurring.getFlags(), Flag.SYNTHETIC))
              .setDomainHistory(historyEntry)
              .setPeriodYears(1)
              .setReason(recurring.getReason())
              .setSyntheticCreationTime(endTime)
              .setCancellationMatchingBillingEvent(recurring)
              .setTargetId(recurring.getTargetId())
              .build();
      results.add(oneTime);
    }
    results.add(
        recurring.asBuilder().setRecurrenceLastExpansion(recurrenceLastExpansionTime).build());
  }

  private PDone advanceCursor(PCollection<Void> persisted) {
    return PDone.in(
        persisted
            .getPipeline()
            .apply("Create one dummy element", Create.of((Void) null))
            .apply("Wait for all saves to finish", Wait.on(persisted))
            // Because only one dummy element is created in the start PCollection, this
            // transform is guaranteed to only process one element and therefore only run once.
            // Because the previous step waits for all emissions of voids from the expansion step to
            // finish, this transform is guaranteed to run only after all expansions are done and
            // persisted.
            .apply(
                "Advance cursor",
                ParDo.of(
                    new DoFn<Void, Void>() {
                      @ProcessElement
                      public void processElement() {
                        tm().transact(
                                () -> {
                                  DateTime currentCursorTime =
                                      tm().loadByKeyIfPresent(
                                              Cursor.createGlobalVKey(RECURRING_BILLING))
                                          .orElse(
                                              Cursor.createGlobal(RECURRING_BILLING, START_OF_TIME))
                                          .getCursorTime();
                                  if (!currentCursorTime.equals(startTime)) {
                                    throw new IllegalStateException(
                                        String.format(
                                            "Current cursor position %s does not match start time"
                                                + " %s.",
                                            currentCursorTime, startTime));
                                  }
                                  tm().put(Cursor.createGlobal(RECURRING_BILLING, endTime));
                                });
                      }
                    }))
            .getPipeline());
  }

  public static void main(String[] args) {
    PipelineOptionsFactory.register(ExpandRecurringBillingEventsPipelineOptions.class);
    ExpandRecurringBillingEventsPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(ExpandRecurringBillingEventsPipelineOptions.class);
    // Hardcode the transaction level to be at repeatable read because we do not want concurrent
    // runs of the pipeline for the same window to create duplicate OneTimes. This ensures that the
    // set of existing OneTimes do not change by the time new OneTimes are inserted within a
    // transaction.
    //
    // Note that the SQL spec itself allows for phantom read in a transaction, but PostgreSQL's
    // implementation does not, which means the set of OneTimes that satisfy the condition of being
    // matched to the same recurring events does not change during the transaction, preventing
    // the aforementioned duplication.
    //
    // We could have used the default SERIALIZABLE level, but that would have locked the entire
    // BillingEvent table and force every write to it to be done sequentially, effectively reducing
    // the parallelism of the pipeline to 1.
    //
    // See: https://www.postgresql.org/docs/current/transaction-iso.html
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_SERIALIZABLE);
    Pipeline pipeline = Pipeline.create(options);
    new ExpandRecurringBillingEventsPipeline(options).run(pipeline);
  }

  @Singleton
  @Component(
      modules = {CustomLogicModule.class, CustomLogicFactoryModule.class, ConfigModule.class})
  interface PipelineComponent {

    DomainPricingLogic domainPricingLogic();

    @Config("jdbcBatchSize")
    int batchSize();
  }
}
