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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistNewRegistrars;

import google.registry.beam.TestPipelineExtension;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TmOverrideExtension;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link ResaveAllEppResourcesPipeline}. */
public class ResaveAllEppResourcesPipelineTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2022-03-10T00:00:00.000Z"));

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore =
      new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension
  final TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  @Order(Order.DEFAULT + 1)
  TmOverrideExtension tmOverrideExtension = TmOverrideExtension.withJpa();

  private final RegistryPipelineOptions options =
      PipelineOptionsFactory.create().as(RegistryPipelineOptions.class);

  @BeforeEach
  void beforeEach() {
    persistNewRegistrars("TheRegistrar", "NewRegistrar");
    createTld("tld");
  }

  @Test
  void testPipeline_unchangedEntity() {
    ContactResource contact = persistActiveContact("test123");
    DateTime creationTime = contact.getUpdateTimestamp().getTimestamp();
    fakeClock.advanceOneMilli();
    assertThat(loadByEntity(contact).getUpdateTimestamp().getTimestamp()).isEqualTo(creationTime);
    fakeClock.advanceOneMilli();
    runPipeline();
    assertThat(loadByEntity(contact)).isEqualTo(contact);
  }

  @Test
  void testPipeline_fulfilledTransfer() {
    DateTime now = fakeClock.nowUtc();
    DomainBase domain =
        persistDomainWithPendingTransfer(
            persistDomainWithDependentResources(
                "domain",
                "tld",
                persistActiveContact("jd1234"),
                now.minusDays(5),
                now.minusDays(5),
                now.plusYears(2)),
            now.minusDays(4),
            now.minusDays(1),
            now.plusYears(2));
    assertThat(domain.getStatusValues()).contains(StatusValue.PENDING_TRANSFER);
    assertThat(domain.getUpdateTimestamp().getTimestamp()).isEqualTo(now);
    fakeClock.advanceOneMilli();
    runPipeline();
    DomainBase postPipeline = loadByEntity(domain);
    assertThat(postPipeline.getStatusValues()).doesNotContain(StatusValue.PENDING_TRANSFER);
    assertThat(postPipeline.getUpdateTimestamp().getTimestamp()).isEqualTo(fakeClock.nowUtc());
  }

  private void runPipeline() {
    ResaveAllEppResourcesPipeline pipeline = new ResaveAllEppResourcesPipeline(options);
    pipeline.setupPipeline(testPipeline);
    testPipeline.run().waitUntilFinish();
  }
}
