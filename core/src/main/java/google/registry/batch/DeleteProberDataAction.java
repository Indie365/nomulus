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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryEnvironment.PRODUCTION;
import static google.registry.mapreduce.MapreduceRunner.PARAM_DRY_RUN;
import static google.registry.model.ResourceTransferUtils.updateForeignKeyIndexDeletionTime;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.registry.Registries.getTldsOfType;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_DELETE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_TLDS;
import static org.joda.time.DateTimeZone.UTC;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryEnvironment;
import google.registry.dns.DnsQueue;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.EppResourceUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.util.List;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Deletes all prober DomainBases and their subordinate history entries, poll messages, and
 * billing events, along with their ForeignKeyDomainIndex and EppResourceIndex entities.
 *
 * <p>See: https://www.youtube.com/watch?v=xuuv0syoHnM
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/deleteProberData",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class DeleteProberDataAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * The maximum amount of time we allow a prober domain to be in use.
   *
   * <p>In practice, the prober's connection will time out well before this duration. This includes
   * a decent buffer.
   */
  private static final Duration DOMAIN_USED_DURATION = Duration.standardHours(1);
  /**
   * The minimum amount of time we want a domain to be "soft deleted".
   *
   * <p>The domain has to remain soft deleted for at least enough time for the DNS task to run and
   * remove it from DNS itself. This is probably on the order of minutes.
   */
  private static final Duration SOFT_DELETE_DELAY = Duration.standardHours(1);

  private static final DnsQueue dnsQueue = DnsQueue.create();

  @Inject @Parameter(PARAM_DRY_RUN) boolean isDryRun;
  /** List of TLDs to work on. If empty - will work on all TLDs that end with .test. */
  @Inject @Parameter(PARAM_TLDS) ImmutableSet<String> tlds;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminRegistrarId;

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject DeleteProberDataAction() {}

  @Override
  public void run() {
    checkState(
        !Strings.isNullOrEmpty(registryAdminRegistrarId),
        "Registry admin client ID must be configured for prober data deletion to work");
    checkArgument(
        !PRODUCTION.equals(RegistryEnvironment.get())
            || tlds.stream().allMatch(tld -> tld.endsWith(".test")),
        "On production, can only work on TLDs that end with .test");
    ImmutableSet<String> deletableTlds =
        getTldsOfType(TldType.TEST)
            .stream()
            .filter(tld -> tlds.isEmpty() ? tld.endsWith(".test") : tlds.contains(tld))
            .collect(toImmutableSet());
    checkArgument(
        tlds.isEmpty() || deletableTlds.equals(tlds),
        "If tlds are given, they must all exist and be TEST tlds. Given: %s, not found: %s",
        tlds,
        Sets.difference(tlds, deletableTlds));
    ImmutableSet<String> proberRoidSuffixes =
        deletableTlds.stream()
            .map(tld -> Registry.get(tld).getRoidSuffix())
            .collect(toImmutableSet());
    if (tm().isOfy()) {
      mrRunner
          .setJobName("Delete prober data")
          .setModuleName("backend")
          .runMapOnly(
              new DeleteProberDataMapper(proberRoidSuffixes, isDryRun, registryAdminRegistrarId),
              ImmutableList.of(EppResourceInputs.createKeyInput(DomainBase.class)))
          .sendLinkToMapreduceConsole(response);
    } else {
      tm().transact(() -> deleteProberDataSql(deletableTlds, proberRoidSuffixes));
    }
  }

  private void deleteProberDataSql(
      ImmutableSet<String> deletableTlds, ImmutableSet<String> proberRoidSuffixes) {
    loadRelevantDomainsSql(deletableTlds, proberRoidSuffixes).forEach(this::deleteDomain);
  }

  private void deleteDomain(DomainBase domain) {
    DateTime now = tm().getTransactionTime();
    // If the domain is still active, that means that the prober encountered a failure and did not
    // successfully soft-delete the domain (thus leaving its DNS entry published). We soft-delete
    // it now so that the DNS entry can be handled. The domain will then be hard-deleted the next
    // time the mapreduce is run.
    if (EppResourceUtils.isActive(domain, now)) {
      if (isDryRun) {
        logger.atInfo().log(
            "Would soft-delete the active domain: %s (%s)",
            domain.getDomainName(), domain.getRepoId());
      } else {
        softDeleteDomain(domain, registryAdminRegistrarId, dnsQueue);
      }
    } else {
      hardDeleteDomainSql(domain, now);
    }
  }

  private void hardDeleteDomainSql(DomainBase domain, DateTime now) {
    // Delete billing events, history objects, poll messages, subordinate hosts, and the domain
    getBillingEventsForDomain(domain).forEach(this::hardDeleteIfNotDryRun);
    HistoryEntryDao.loadHistoryObjectsForResource(domain.createVKey()).stream()
        .map(HistoryEntry::createVKey)
        .forEach(this::hardDeleteIfNotDryRun);
    getPollMessagesForDomain(domain).stream()
        .map(PollMessage::createVKey)
        .forEach(this::hardDeleteIfNotDryRun);
    getSubordinateHostsForDomain(domain, now).forEach(this::hardDeleteIfNotDryRun);
    hardDeleteIfNotDryRun(domain.createVKey());
  }

  private void hardDeleteIfNotDryRun(VKey<? extends ImmutableObject> key) {
    if (isDryRun) {
      logger.atInfo().log("Would hard-delete entity with key %s", key);
    } else {
      tm().delete(key);
    }
  }

  // Take a DNS queue + admin registrar id as input so that it can be called from the mapper as well
  private static void softDeleteDomain(
      DomainBase domain, String registryAdminRegistrarId, DnsQueue localDnsQueue) {
    tm().transactNew(
            () -> {
              DomainBase deletedDomain =
                  domain
                      .asBuilder()
                      .setDeletionTime(tm().getTransactionTime())
                      .setStatusValues(null)
                      .build();
              DomainHistory historyEntry =
                  new DomainHistory.Builder()
                      .setDomain(domain)
                      .setType(DOMAIN_DELETE)
                      .setModificationTime(tm().getTransactionTime())
                      .setBySuperuser(true)
                      .setReason("Deletion of prober data")
                      .setClientId(registryAdminRegistrarId)
                      .build();
              // Note that we don't bother handling grace periods, billing events, pending
              // transfers, poll messages, or auto-renews because these will all be hard-deleted
              // the next time the mapreduce runs anyway.
              tm().putAll(deletedDomain, historyEntry);
              updateForeignKeyIndexDeletionTime(deletedDomain);
              localDnsQueue.addDomainRefreshTask(deletedDomain.getDomainName());
            });
  }

  private ImmutableList<VKey<HostResource>> getSubordinateHostsForDomain(
      DomainBase domain, DateTime now) {
    return domain.getSubordinateHosts().stream()
        .map(hostname -> EppResourceUtils.loadByForeignKey(HostResource.class, hostname, now).get())
        .map(HostResource::createVKey)
        .collect(toImmutableList());
  }

  private ImmutableList<PollMessage> getPollMessagesForDomain(DomainBase domain) {
    return tm().createQueryComposer(PollMessage.class)
        .where("domainRepoId", Comparator.EQ, domain.getRepoId())
        .list();
  }

  private Stream<VKey<? extends BillingEvent>> getBillingEventsForDomain(DomainBase domain) {
    String repoId = domain.getRepoId();
    return Streams.concat(
        tm()
            .createQueryComposer(BillingEvent.OneTime.class)
            .where("domainRepoId", Comparator.EQ, repoId)
            .stream()
            .map(BillingEvent.OneTime::createVKey),
        tm()
            .createQueryComposer(BillingEvent.Recurring.class)
            .where("domainRepoId", Comparator.EQ, repoId)
            .stream()
            .map(BillingEvent.Recurring::createVKey),
        tm()
            .createQueryComposer(BillingEvent.Cancellation.class)
            .where("domainRepoId", Comparator.EQ, repoId)
            .stream()
            .map(BillingEvent.Cancellation::createVKey));
  }

  private Stream<DomainBase> loadRelevantDomainsSql(
      ImmutableSet<String> deletableTlds, ImmutableSet<String> proberRoidSuffixes) {
    return jpaTm()
        .query(
            "FROM Domain WHERE tld IN :tlds AND fullyQualifiedDomainName NOT LIKE 'nic.%'",
            DomainBase.class)
        .setParameter("tlds", deletableTlds)
        .getResultStream()
        .filter(domain -> shouldIncludeDomain(domain, proberRoidSuffixes));
  }

  private boolean shouldIncludeDomain(DomainBase domain, ImmutableSet<String> proberRoidSuffixes) {
    // Don't delete domains with subordinate hosts
    if (!domain.getSubordinateHosts().isEmpty()) {
      return false;
    }
    String roidSuffix = Iterables.getLast(Splitter.on('-').split(domain.getRepoId()));
    if (!proberRoidSuffixes.contains(roidSuffix)) {
      return false;
    }
    DateTime now = tm().getTransactionTime();
    if (now.isBefore(domain.getCreationTime().plus(DOMAIN_USED_DURATION))) {
      return false;
    }
    // If the domain isn't active, we want to make sure it hasn't been active for "a while" before
    // deleting it. This prevents accidental double-map with the same key from immediately
    // deleting active domains
    return EppResourceUtils.isActive(domain, now)
        || !now.isBefore(domain.getDeletionTime().plus(SOFT_DELETE_DELAY));
  }

  /** Provides the map method that runs for each existing DomainBase entity. */
  public static class DeleteProberDataMapper extends Mapper<Key<DomainBase>, Void, Void> {

    private static final DnsQueue dnsQueue = DnsQueue.create();
    private static final long serialVersionUID = -7724537393697576369L;

    private final ImmutableSet<String> proberRoidSuffixes;
    private final Boolean isDryRun;
    private final String registryAdminRegistrarId;

    public DeleteProberDataMapper(
        ImmutableSet<String> proberRoidSuffixes,
        Boolean isDryRun,
        String registryAdminRegistrarId) {
      this.proberRoidSuffixes = proberRoidSuffixes;
      this.isDryRun = isDryRun;
      this.registryAdminRegistrarId = registryAdminRegistrarId;
    }

    @Override
    public final void map(Key<DomainBase> key) {
      try {
        String roidSuffix = Iterables.getLast(Splitter.on('-').split(key.getName()));
        if (proberRoidSuffixes.contains(roidSuffix)) {
          deleteDomain(key);
        } else {
          getContext().incrementCounter("skipped, non-prober data");
        }
      } catch (Throwable t) {
        logger.atSevere().withCause(t).log("Error while deleting prober data for key %s", key);
        getContext().incrementCounter(String.format("error, kind %s", key.getKind()));
      }
    }

    private void deleteDomain(final Key<DomainBase> domainKey) {
      final DomainBase domain = auditedOfy().load().key(domainKey).now();

      DateTime now = DateTime.now(UTC);

      if (domain == null) {
        // Depending on how stale Datastore indexes are, we can get keys to resources that are
        // already deleted (e.g. by a recent previous invocation of this mapreduce). So ignore them.
        getContext().incrementCounter("already deleted");
        return;
      }

      String domainName = domain.getDomainName();
      if (domainName.equals("nic." + domain.getTld())) {
        getContext().incrementCounter("skipped, NIC domain");
        return;
      }
      if (now.isBefore(domain.getCreationTime().plus(DOMAIN_USED_DURATION))) {
        getContext().incrementCounter("skipped, domain too new");
        return;
      }
      if (!domain.getSubordinateHosts().isEmpty()) {
        logger.atWarning().log(
            "Cannot delete domain %s (%s) because it has subordinate hosts.",
            domainName, domainKey);
        getContext().incrementCounter("skipped, had subordinate host(s)");
        return;
      }

      // If the domain is still active, that means that the prober encountered a failure and did not
      // successfully soft-delete the domain (thus leaving its DNS entry published). We soft-delete
      // it now so that the DNS entry can be handled. The domain will then be hard-deleted the next
      // time the mapreduce is run.
      if (EppResourceUtils.isActive(domain, now)) {
        if (isDryRun) {
          logger.atInfo().log(
              "Would soft-delete the active domain: %s (%s)", domainName, domainKey);
        } else {
          softDeleteDomain(domain, registryAdminRegistrarId, dnsQueue);
        }
        getContext().incrementCounter("domains soft-deleted");
        return;
      }
      // If the domain isn't active, we want to make sure it hasn't been active for "a while" before
      // deleting it. This prevents accidental double-map with the same key from immediately
      // deleting active domains
      if (now.isBefore(domain.getDeletionTime().plus(SOFT_DELETE_DELAY))) {
        getContext().incrementCounter("skipped, domain too recently soft deleted");
        return;
      }

      final Key<EppResourceIndex> eppIndex = Key.create(EppResourceIndex.create(domainKey));
      final Key<? extends ForeignKeyIndex<?>> fki = ForeignKeyIndex.createKey(domain);

      int entitiesDeleted =
          tm().transact(
                  () -> {
                    // This ancestor query selects all descendant HistoryEntries, BillingEvents,
                    // PollMessages,
                    // and TLD-specific entities, as well as the domain itself.
                    List<Key<Object>> domainAndDependentKeys =
                        auditedOfy().load().ancestor(domainKey).keys().list();
                    ImmutableSet<Key<?>> allKeys =
                        new ImmutableSet.Builder<Key<?>>()
                            .add(fki)
                            .add(eppIndex)
                            .addAll(domainAndDependentKeys)
                            .build();
                    if (isDryRun) {
                      logger.atInfo().log("Would hard-delete the following entities: %s", allKeys);
                    } else {
                      auditedOfy().deleteWithoutBackup().keys(allKeys);
                    }
                    return allKeys.size();
                  });
      getContext().incrementCounter("domains hard-deleted");
      getContext().incrementCounter("total entities hard-deleted", entitiesDeleted);
    }
  }
}
