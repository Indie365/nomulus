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

package google.registry.beam.resave;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.util.DateTimeUtils;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.joda.time.DateTime;

/**
 * A Dataflow Flex pipeline that resaves changed EPP resources in SQL.
 *
 * <p>Due to the way that Hibernate works, if an entity is unchanged by {@link
 * EppResource#cloneProjectedAtTime(DateTime)} it will not actually be re-persisted to the database.
 * Thus, the only actual changes occur when objects are changed by projecting them to now, such as
 * when a pending transfer is resolved.
 */
public class ResaveAllEppResourcesPipeline implements Serializable {

  private static final ImmutableSet<Class<? extends EppResource>> EPP_RESOURCE_CLASSES =
      ImmutableSet.of(ContactResource.class, DomainBase.class, HostResource.class);

  /**
   * There exist three possible situations where we know we'll want to project domains to the
   * current point in time:
   *
   * <ul>
   *   <li>A pending domain transfer has expired.
   *   <li>A domain is past its expiration time without being deleted (this means it autorenewed).
   *   <li>A domain has expired grace periods.
   * </ul>
   *
   * <p>This command contains all three scenarios so that we can avoid querying the Domain table
   * multiple times, and to avoid projecting and resaving the same domain multiple times.
   */
  private static final String DOMAINS_TO_PROJECT_QUERY =
      "FROM Domain d WHERE (d.transferData.transferStatus = 'PENDING' AND"
          + " d.transferData.pendingTransferExpirationTime < current_timestamp()) OR"
          + " (d.registrationExpirationTime < current_timestamp() AND d.deletionTime ="
          + " (:END_OF_TIME)) OR (EXISTS (SELECT 1 FROM GracePeriod gp WHERE gp.domainRepoId ="
          + " d.repoId AND gp.expirationTime < current_timestamp()))";

  private final ResaveAllEppResourcesPipelineOptions options;

  ResaveAllEppResourcesPipeline(ResaveAllEppResourcesPipelineOptions options) {
    this.options = options;
  }

  PipelineResult run() {
    Pipeline pipeline = Pipeline.create(options);
    setupPipeline(pipeline);
    return pipeline.run();
  }

  void setupPipeline(Pipeline pipeline) {
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    DateTime currentTime = jpaTm().transact(() -> jpaTm().getTransactionTime());
    if (options.getIsFast()) {
      fastResaveContacts(pipeline, currentTime);
      fastResaveDomains(pipeline, currentTime);
    } else {
      EPP_RESOURCE_CLASSES.forEach(clazz -> forceResaveAllResources(pipeline, clazz, currentTime));
    }
  }

  /** Projects to the current time and saves any contacts with expired transfers. */
  private void fastResaveContacts(Pipeline pipeline, DateTime currentTime) {
    Read<ContactResource, ContactResource> read =
        RegistryJpaIO.read(
            "FROM Contact WHERE transferData.transferStatus = 'PENDING' AND"
                + " transferData.pendingTransferExpirationTime < current_timestamp()",
            ContactResource.class,
            c -> c);
    projectAndResaveResources(pipeline, ContactResource.class, currentTime, read);
  }

  /**
   * Projects to the current time and saves any domains with expired pending actions (e.g.
   * transfers, grace periods).
   *
   * <p>The logic of what might have changed is paraphrased from {@link
   * google.registry.model.domain.DomainContent#cloneProjectedAtTime(DateTime)}.
   */
  private void fastResaveDomains(Pipeline pipeline, DateTime currentTime) {
    Read<DomainBase, DomainBase> read =
        RegistryJpaIO.read(
            DOMAINS_TO_PROJECT_QUERY,
            ImmutableMap.of("END_OF_TIME", DateTimeUtils.END_OF_TIME),
            DomainBase.class,
            d -> d);
    projectAndResaveResources(pipeline, DomainBase.class, currentTime, read);
  }

  /** Projects all resources to the current time and saves them. */
  private <T extends EppResource> void forceResaveAllResources(
      Pipeline pipeline, Class<T> clazz, DateTime currentTime) {
    Read<T, T> read = RegistryJpaIO.read(() -> CriteriaQueryBuilder.create(clazz).build());
    projectAndResaveResources(pipeline, clazz, currentTime, read);
  }

  /** Projects and re-saves the result of the provided {@link Read}. */
  private <T extends EppResource> void projectAndResaveResources(
      Pipeline pipeline, Class<T> clazz, DateTime currentTime, Read<?, T> read) {
    String className = clazz.getSimpleName();
    pipeline
        .apply("Read " + className, read)
        .apply("MapToNow " + className, ParDo.of(new MapToNowFunction<>(currentTime)))
        .apply(
            "Write transformed " + className,
            RegistryJpaIO.<EppResource>write()
                .withName("Write transformed " + className)
                .withBatchSize(options.getSqlWriteBatchSize())
                .withShards(options.getSqlWriteShards()));
  }

  /** A {@link DoFn} that maps {@link EppResource}s to the current time. */
  private static class MapToNowFunction<T extends EppResource> extends DoFn<T, EppResource> {

    private final DateTime currentTime;

    private MapToNowFunction(DateTime currentTime) {
      this.currentTime = currentTime;
    }

    @ProcessElement
    public void processElement(
        @Element T originalResource, OutputReceiver<EppResource> outputReceiver) {
      outputReceiver.output(originalResource.cloneProjectedAtTime(currentTime));
    }
  }
}
