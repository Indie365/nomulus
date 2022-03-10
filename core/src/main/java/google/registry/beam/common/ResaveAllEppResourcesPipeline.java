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

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableSet;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
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

  private final RegistryPipelineOptions options;

  ResaveAllEppResourcesPipeline(RegistryPipelineOptions options) {
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
    EPP_RESOURCE_CLASSES.forEach(clazz -> resaveEppResources(pipeline, clazz, currentTime));
  }

  /** Projects all resources to the current time and saves them. */
  <T extends EppResource> void resaveEppResources(
      Pipeline pipeline, Class<T> clazz, DateTime currentTime) {
    String simpleName = clazz.getSimpleName();
    Read<T, T> read = RegistryJpaIO.read(() -> CriteriaQueryBuilder.create(clazz).build());
    pipeline
        .apply("Read " + simpleName, read)
        .apply("MapToNow " + simpleName, ParDo.of(new MapToNowFunction<>(currentTime)))
        .apply(
            "Write transformed " + simpleName,
            RegistryJpaIO.<EppResource>write()
                .withName("Write transformed " + simpleName)
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
