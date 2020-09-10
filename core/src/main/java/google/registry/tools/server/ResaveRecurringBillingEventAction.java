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

import static com.google.appengine.tools.cloudstorage.GcsServiceFactory.createGcsService;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.mapreduce.inputs.EppResourceInputs.createChildEntityInput;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.transfer.TransferData.TransferServerApproveEntity;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import javax.inject.Inject;

/**
 * A MapReduce that re-saves the problematic {@link
 * google.registry.model.billing.BillingEvent.Recurring} entities with unique id.
 */
@Action(
    service = Action.Service.TOOLS,
    path = "/_dr/task/resaveRecurringBillingEventWithUniqueId",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ResaveRecurringBillingEventAction implements Runnable {

  private static final String GCS_BUCKET = "gcsBucket";
  private static final String DRY_RUN = "dryRun";
  static final String FILENAME_FORMAT = "recurring_id_from_%d_to_%d.txt";

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;

  @Inject
  @Parameter(GCS_BUCKET)
  String gcsBucket;

  @Inject
  @Parameter(DRY_RUN)
  boolean isDryRun;

  @Inject
  @Config("gcsBufferSize")
  int gcsBufferSize;

  @Inject
  ResaveRecurringBillingEventAction() {}

  @Override
  public void run() {
    mrRunner
        .setJobName("Re-save problematic BillingEvent.Recurring entities with unique id")
        .setModuleName("tools")
        .runMapreduce(
            new ResaveRecurringBillingEventMapper(),
            new ResaveRecurringBillingEventReducer(gcsBucket, gcsBufferSize, isDryRun),
            ImmutableList.of(
                createChildEntityInput(
                    ImmutableSet.of(DomainBase.class),
                    ImmutableSet.of(Recurring.class, OneTime.class))))
        .sendLinkToMapreduceConsole(response);
  }

  /** Mapper to re-save all HistoryEntry entities. */
  public static class ResaveRecurringBillingEventMapper
      extends Mapper<BillingEvent, Long, BillingEvent> {

    private static final long serialVersionUID = 2696861651185517507L;

    @Override
    public final void map(BillingEvent billingEvent) {
      if (billingEvent instanceof Recurring) {
        emit(billingEvent.getId(), billingEvent);
      } else if (billingEvent instanceof OneTime) {
        OneTime oneTime = (OneTime) billingEvent;
        if (oneTime.getCancellationMatchingBillingEvent() != null) {
          emit(oneTime.getCancellationMatchingBillingEvent().getOfyKey().getId(), oneTime);
        }
      }
    }
  }

  /** Mapper to re-save all HistoryEntry entities. */
  public static class ResaveRecurringBillingEventReducer extends Reducer<Long, BillingEvent, Void> {

    private static final long serialVersionUID = 8113221467940534948L;

    private String bucket;
    private int gcsBufferSize;
    private boolean isDryRun;

    ResaveRecurringBillingEventReducer(String bucket, int gcsBufferSize, boolean isDryRun) {
      this.bucket = bucket;
      this.gcsBufferSize = gcsBufferSize;
      this.isDryRun = isDryRun;
    }

    @Override
    public void reduce(Long billingEventId, ReducerInput<BillingEvent> reducerInput) {
      BillingEventInput billingEventInput = getBillingEventInput(reducerInput);
      // If the set is empty or there is only 1 recurring billing event with this id, the recurring
      // billing event already uses a unique id so we don't need to make any change.
      if (billingEventInput.getRecurrings().size() <= 1) {
        return;
      }

      for (Recurring recurring : billingEventInput.getRecurrings()) {
        StringBuilder fileContent = new StringBuilder();
        ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();

        VKey<Recurring> oldRecurringVKey = recurring.createVKey();
        // By setting id to 0L, Buildable.build() will assign an application wide unique id to it.
        Recurring uniqIdRecurring = recurring.asBuilder().setId(0L).build();
        VKey<Recurring> newRecurringVKey = uniqIdRecurring.createVKey();
        entitiesToSave.add(uniqIdRecurring);
        fileContent.append(
            String.format(
                "Saved the new BillingEvent.Recurring entity with key %s.\n", newRecurringVKey));

        updateOneTimeBillingEvent(
            fileContent,
            entitiesToSave,
            oldRecurringVKey,
            newRecurringVKey,
            billingEventInput.getVKeyToOneTime());
        updateDomain(
            fileContent,
            entitiesToSave,
            oldRecurringVKey,
            newRecurringVKey,
            recurring.getParentKey().getParent());

        tm().transact(
                () -> {
                  if (!isDryRun) {
                    ofy().save().entities(entitiesToSave.build()).now();
                    ofy().delete().entity(recurring).now();
                  }
                  fileContent.append(
                      String.format(
                          "Deleted the old BillingEvent.Recurring entity with key %s.\n",
                          oldRecurringVKey));
                  createGcsFileFromBytes(
                      isDryRun,
                      oldRecurringVKey,
                      newRecurringVKey,
                      fileContent.toString().getBytes(StandardCharsets.UTF_8));
                });
      }
    }

    private BillingEventInput getBillingEventInput(ReducerInput<BillingEvent> billingEventInput) {
      ImmutableSet.Builder<Recurring> recurringBuilder = new ImmutableSet.Builder<>();
      ImmutableMultimap.Builder<VKey<? extends BillingEvent>, OneTime> oneTimeBuilder =
          new ImmutableMultimap.Builder<>();
      while (billingEventInput.hasNext()) {
        BillingEvent billingEvent = billingEventInput.next();
        if (billingEvent instanceof Recurring) {
          recurringBuilder.add((Recurring) billingEvent);
        } else if (billingEvent instanceof OneTime) {
          OneTime oneTime = (OneTime) billingEvent;
          oneTimeBuilder.put(oneTime.getCancellationMatchingBillingEvent(), oneTime);
        }
      }
      return new BillingEventInput(recurringBuilder.build(), oneTimeBuilder.build().asMap());
    }

    /**
     * Resaves all associated BillingEvent.OneTime entities with the updated
     * cancellationMatchingBillingEvent field pointing to the BillingEvent.Recurring entity with
     * unique id.
     */
    private static void updateOneTimeBillingEvent(
        StringBuilder fileContent,
        ImmutableSet.Builder<ImmutableObject> entitiesToSave,
        VKey<Recurring> oldRecurringVKey,
        VKey<Recurring> newRecurringVKey,
        ImmutableMap<VKey<? extends BillingEvent>, Collection<OneTime>> vkeyToOneTime) {
      if (vkeyToOneTime.get(oldRecurringVKey) != null) {
        vkeyToOneTime
            .get(oldRecurringVKey)
            .forEach(
                oneTime -> {
                  BillingEvent.OneTime updatedOneTime =
                      oneTime
                          .asBuilder()
                          .setCancellationMatchingBillingEvent(newRecurringVKey)
                          .build();
                  entitiesToSave.add(updatedOneTime);
                  fileContent.append(
                      String.format(
                          "Resaved %s with cancellationMatchingBillingEvent changed from %s to"
                              + " %s.\n",
                          oneTime.createVKey(),
                          oneTime.getCancellationMatchingBillingEvent(),
                          updatedOneTime.getCancellationMatchingBillingEvent()));
                });
      }
    }

    /**
     * The following 4 fields in the domain entity can be or have a reference to this
     * BillingEvent.Recurring entity, so we need to check them and replace them with the new entity
     * when necessary: 1. domain.autorenewBillingEvent 2.
     * domain.transferData.serverApproveAutorenewEvent 3. domain.transferData.serverApproveEntities
     * 4. domain.gracePeriods.billingEventRecurring
     */
    private static void updateDomain(
        StringBuilder fileContent,
        ImmutableSet.Builder<ImmutableObject> entitiesToSave,
        VKey<Recurring> oldRecurringVKey,
        VKey<Recurring> newRecurringVKey,
        Key<DomainBase> domainKey) {
      DomainBase domain = ofy().load().key(domainKey).now();
      DomainBase.Builder domainBuilder = domain.asBuilder();
      StringBuilder domainChange =
          new StringBuilder(
              String.format("Resaved domain %s with following changes:\n", domain.createVKey()));

      if (domain.getAutorenewBillingEvent() != null
          && domain.getAutorenewBillingEvent().equals(oldRecurringVKey)) {
        domainBuilder.setAutorenewBillingEvent(newRecurringVKey);
        domainChange.append(
            String.format(
                "  Changed autorenewBillingEvent from %s to %s.\n",
                oldRecurringVKey, newRecurringVKey));
      }

      if (domain.getTransferData().getServerApproveAutorenewEvent() != null
          && domain.getTransferData().getServerApproveAutorenewEvent().equals(oldRecurringVKey)) {
        Set<VKey<? extends TransferServerApproveEntity>> serverApproveEntities =
            Sets.union(
                Sets.difference(
                    domain.getTransferData().getServerApproveEntities(),
                    ImmutableSet.of(oldRecurringVKey)),
                ImmutableSet.of(newRecurringVKey));
        domainBuilder.setTransferData(
            domain
                .getTransferData()
                .asBuilder()
                .setServerApproveEntities(ImmutableSet.copyOf(serverApproveEntities))
                .setServerApproveAutorenewEvent(newRecurringVKey)
                .build());
        domainChange.append(
            String.format(
                "  Changed transferData.serverApproveAutoRenewEvent from %s to %s.\n",
                oldRecurringVKey, newRecurringVKey));
        domainChange.append(
            String.format(
                "  Changed transferData.serverApproveEntities to remove %s and add %s.\n",
                oldRecurringVKey, newRecurringVKey));
      }

      ImmutableSet<GracePeriod> updatedGracePeriod =
          domain.getGracePeriods().stream()
              .map(
                  gracePeriod ->
                      gracePeriod.getRecurringBillingEvent().equals(oldRecurringVKey)
                          ? gracePeriod.cloneWithRecurringBillingEvent(newRecurringVKey)
                          : gracePeriod)
              .collect(toImmutableSet());
      if (!updatedGracePeriod.equals(domain.getGracePeriods())) {
        domainBuilder.setGracePeriods(updatedGracePeriod);
        domainChange.append(
            String.format(
                "  Changed gracePeriods to remove %s and add %s.\n",
                oldRecurringVKey, newRecurringVKey));
      }

      DomainBase updatedDomain = domainBuilder.build();
      if (!updatedDomain.equals(domain)) {
        entitiesToSave.add(updatedDomain);
        fileContent.append(domainChange);
      }
    }

    private void createGcsFileFromBytes(
        boolean isDryRun,
        VKey<Recurring> oldRecurringVKey,
        VKey<Recurring> newRecurringVKey,
        byte[] bytes) {
      GcsUtils gcsUtils =
          new GcsUtils(createGcsService(RetryParams.getDefaultInstance()), gcsBufferSize);
      String filename =
          (isDryRun ? "dry_run/" : "")
              + String.format(
                  FILENAME_FORMAT,
                  oldRecurringVKey.getOfyKey().getId(),
                  newRecurringVKey.getOfyKey().getId());
      GcsFilename gcsFilename = new GcsFilename(bucket, filename);
      try {
        gcsUtils.createFromBytes(gcsFilename, bytes);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private static class BillingEventInput {
      ImmutableSet<Recurring> recurrings;
      ImmutableMap<VKey<? extends BillingEvent>, Collection<OneTime>> vKeyToOneTime;

      BillingEventInput(
          ImmutableSet<Recurring> recurrings,
          ImmutableMap<VKey<? extends BillingEvent>, Collection<OneTime>> vKeyToOneTime) {
        this.recurrings = recurrings;
        this.vKeyToOneTime = vKeyToOneTime;
      }

      ImmutableSet<Recurring> getRecurrings() {
        return recurrings;
      }

      ImmutableMap<VKey<? extends BillingEvent>, Collection<OneTime>> getVKeyToOneTime() {
        return vKeyToOneTime;
      }
    }
  }
}
