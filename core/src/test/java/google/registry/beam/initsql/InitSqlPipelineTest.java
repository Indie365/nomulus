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

package google.registry.beam.initsql;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.backup.AppEngineEnvironment;
import google.registry.model.billing.BillingEvent;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestRule;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.io.File;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.TestPipeline;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InitSqlPipeline}. */
@RunWith(JUnit4.class)
public class InitSqlPipelineTest {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private static final ImmutableList<Class<?>> ALL_KINDS =
      ImmutableList.of(
          Registry.class,
          Registrar.class,
          ContactResource.class,
          HostResource.class,
          DomainBase.class,
          HistoryEntry.class);

  private transient FakeClock fakeClock = new FakeClock(START_TIME);

  @Rule public final transient InjectRule injectRule = new InjectRule();

  @Rule
  public final transient TestPipeline pipeline =
      TestPipeline.create().enableAbandonedNodeEnforcement(true);

  public final transient JpaIntegrationTestRule jpaIntegrationTestRule =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationTestRule();

  public final transient TemporaryFolder temporaryFolder = new TemporaryFolder();

  // Must not be transient!
  public final BeamJpaExtension beamJpaExtension =
      new BeamJpaExtension(temporaryFolder, jpaIntegrationTestRule.getDatabase());

  @Rule
  public final transient RuleChain jpaRules =
      RuleChain.outerRule(new DatastoreEntityExtension())
          .around(jpaIntegrationTestRule)
          .around(temporaryFolder)
          .around(beamJpaExtension);

  private File exportRootDir;
  private File exportDir;
  private File commitLogDir;

  private transient Registrar registrar1;
  private transient Registrar registrar2;
  private transient DomainBase domain;
  private transient ContactResource contact1;
  private transient ContactResource contact2;
  private transient HostResource hostResource;

  private transient HistoryEntry historyEntry;

  @Before
  public void beforeEach() throws Exception {
    try (BackupTestStore store = new BackupTestStore(fakeClock)) {
      injectRule.setStaticField(Ofy.class, "clock", fakeClock);
      exportRootDir = temporaryFolder.newFolder();

      persistResource(newRegistry("com", "COM"));
      registrar1 = persistResource(AppEngineRule.makeRegistrar1());
      registrar2 = persistResource(AppEngineRule.makeRegistrar2());
      Key<DomainBase> domainKey = Key.create(null, DomainBase.class, "4-COM");
      hostResource =
          persistResource(
              new HostResource.Builder()
                  .setHostName("ns1.example.com")
                  .setSuperordinateDomain(VKey.from(domainKey))
                  .setRepoId("1-COM")
                  .setCreationClientId(registrar1.getClientId())
                  .setPersistedCurrentSponsorClientId(registrar2.getClientId())
                  .build());
      contact1 =
          persistResource(
              new ContactResource.Builder()
                  .setContactId("contact_id1")
                  .setRepoId("2-COM")
                  .setCreationClientId(registrar1.getClientId())
                  .setPersistedCurrentSponsorClientId(registrar2.getClientId())
                  .build());
      contact2 =
          persistResource(
              new ContactResource.Builder()
                  .setContactId("contact_id2")
                  .setRepoId("3-COM")
                  .setCreationClientId(registrar1.getClientId())
                  .setPersistedCurrentSponsorClientId(registrar1.getClientId())
                  .build());
      historyEntry = persistResource(new HistoryEntry.Builder().setParent(domainKey).build());
      Key<HistoryEntry> historyEntryKey = Key.create(historyEntry);
      Key<BillingEvent.OneTime> oneTimeBillKey =
          Key.create(historyEntryKey, BillingEvent.OneTime.class, 1);
      Key<BillingEvent.Recurring> recurringBillKey =
          Key.create(historyEntryKey, BillingEvent.Recurring.class, 2);
      Key<PollMessage.Autorenew> autorenewPollKey =
          Key.create(historyEntryKey, PollMessage.Autorenew.class, 3);
      Key<PollMessage.OneTime> onetimePollKey =
          Key.create(historyEntryKey, PollMessage.OneTime.class, 1);
      domain =
          persistResource(
              new DomainBase.Builder()
                  .setDomainName("example.com")
                  .setRepoId("4-COM")
                  .setCreationClientId(registrar1.getClientId())
                  .setLastEppUpdateTime(fakeClock.nowUtc())
                  .setLastEppUpdateClientId(registrar2.getClientId())
                  .setLastTransferTime(fakeClock.nowUtc())
                  .setStatusValues(
                      ImmutableSet.of(
                          StatusValue.CLIENT_DELETE_PROHIBITED,
                          StatusValue.SERVER_DELETE_PROHIBITED,
                          StatusValue.SERVER_TRANSFER_PROHIBITED,
                          StatusValue.SERVER_UPDATE_PROHIBITED,
                          StatusValue.SERVER_RENEW_PROHIBITED,
                          StatusValue.SERVER_HOLD))
                  .setRegistrant(contact1.createVKey())
                  .setContacts(
                      ImmutableSet.of(
                          DesignatedContact.create(
                              DesignatedContact.Type.ADMIN, contact2.createVKey())))
                  .setNameservers(ImmutableSet.of(hostResource.createVKey()))
                  .setSubordinateHosts(ImmutableSet.of("ns1.example.com"))
                  .setPersistedCurrentSponsorClientId(registrar2.getClientId())
                  .setRegistrationExpirationTime(fakeClock.nowUtc().plusYears(1))
                  .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("password")))
                  .setDsData(
                      ImmutableSet.of(DelegationSignerData.create(1, 2, 3, new byte[] {0, 1, 2})))
                  .setLaunchNotice(
                      LaunchNotice.create("tcnid", "validatorId", START_OF_TIME, START_OF_TIME))
                  .setTransferData(
                      new DomainTransferData.Builder()
                          .setGainingClientId(registrar1.getClientId())
                          .setLosingClientId(registrar2.getClientId())
                          .setPendingTransferExpirationTime(fakeClock.nowUtc())
                          .setServerApproveEntities(
                              ImmutableSet.of(
                                  VKey.from(oneTimeBillKey),
                                  VKey.from(recurringBillKey),
                                  VKey.from(autorenewPollKey)))
                          .setServerApproveBillingEvent(VKey.from(oneTimeBillKey))
                          .setServerApproveAutorenewEvent(VKey.from(recurringBillKey))
                          .setServerApproveAutorenewPollMessage(VKey.from(autorenewPollKey))
                          .setTransferRequestTime(fakeClock.nowUtc().plusDays(1))
                          .setTransferStatus(TransferStatus.SERVER_APPROVED)
                          .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                          .build())
                  .setDeletePollMessage(onetimePollKey)
                  .setAutorenewBillingEvent(recurringBillKey)
                  .setAutorenewPollMessage(autorenewPollKey)
                  .setSmdId("smdid")
                  .addGracePeriod(
                      GracePeriod.create(
                          GracePeriodStatus.ADD, fakeClock.nowUtc().plusDays(1), "registrar", null))
                  .build());
      exportDir = store.export(exportRootDir.getAbsolutePath(), ALL_KINDS, ImmutableSet.of());
      commitLogDir = temporaryFolder.newFolder();
    }
  }

  @Test
  @Category(NeedsRunner.class)
  public void runPipeline() {
    InitSqlPipelineOptions options =
        PipelineOptionsFactory.fromArgs(
                "--sqlCredentialUrlOverride="
                    + beamJpaExtension.getCredentialFile().getAbsolutePath(),
                "--commitLogStartTimestamp=" + START_TIME,
                "--commitLogEndTimestamp=" + fakeClock.nowUtc().plusMillis(1),
                "--datastoreExportDir=" + exportDir.getAbsolutePath(),
                "--commitLogDir=" + commitLogDir.getAbsolutePath())
            .withValidation()
            .as(InitSqlPipelineOptions.class);
    InitSqlPipeline initSqlPipeline = new InitSqlPipeline(options, pipeline);
    initSqlPipeline.run().waitUntilFinish();
    try (AppEngineEnvironment env = new AppEngineEnvironment("test")) {
      assertHostResourceEquals(
          jpaTm().transact(() -> jpaTm().load(hostResource.createVKey())), hostResource);
      assertThat(jpaTm().transact(() -> jpaTm().loadAll(Registrar.class)))
          .comparingElementsUsing(immutableObjectCorrespondence("lastUpdateTime"))
          .containsExactly(registrar1, registrar2);
      assertThat(jpaTm().transact(() -> jpaTm().loadAll(ContactResource.class)))
          .comparingElementsUsing(immutableObjectCorrespondence("revisions", "updateTimestamp"))
          .containsExactly(contact1, contact2);
      assertCleansedDomainEquals(jpaTm().transact(() -> jpaTm().load(domain.createVKey())), domain);
    }
  }

  private static void assertHostResourceEquals(HostResource actual, HostResource expected) {
    assertAboutImmutableObjects()
        .that(actual)
        .isEqualExceptFields(expected, "superordinateDomain", "revisions", "updateTimestamp");
    assertThat(actual.getSuperordinateDomain().getSqlKey())
        .isEqualTo(expected.getSuperordinateDomain().getSqlKey());
  }

  private static void assertCleansedDomainEquals(DomainBase actual, DomainBase expected) {
    assertAboutImmutableObjects()
        .that(actual)
        .isEqualExceptFields(
            expected,
            "adminContact",
            "registrantContact",
            "gracePeriods",
            "dsData",
            "allContacts",
            "revisions",
            "updateTimestamp",
            "autorenewBillingEvent",
            "autorenewPollMessage",
            "deletePollMessage",
            "nsHosts",
            "transferData");
    assertThat(actual.getAdminContact().getSqlKey())
        .isEqualTo(expected.getAdminContact().getSqlKey());
    assertThat(actual.getRegistrant().getSqlKey()).isEqualTo(expected.getRegistrant().getSqlKey());
    // TODO(weiminyu): compare gracePeriods, allContacts and dsData, when SQL model supports them.
  }
}
