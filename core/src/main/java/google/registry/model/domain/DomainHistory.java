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

package google.registry.model.domain;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.EppResource;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.host.Host;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.JoinColumns;
import javax.persistence.JoinTable;
import javax.persistence.OneToMany;
import javax.persistence.PostLoad;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.hibernate.Hibernate;
import org.hibernate.collection.internal.PersistentSet;

/**
 * A persisted history entry representing an EPP modification to a domain.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the domain entity at this point in time. We persist a raw {@link DomainBase} so that the
 * foreign-keyed fields in that class can refer to this object.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "creationTime"),
      @Index(columnList = "historyRegistrarId"),
      @Index(columnList = "historyType"),
      @Index(columnList = "historyModificationTime")
    })
@Access(AccessType.FIELD)
@AttributeOverride(name = "repoId", column = @Column(name = "domainRepoId"))
public class DomainHistory extends HistoryEntry {

  // Store DomainBase instead of Domain, so we don't pick up its @Id
  // @Nullable for the sake of pre-Registry-3.0 history objects
  @Nullable
  @Access(AccessType.PROPERTY)
  public DomainBase getRawDomainBase() {
    return (DomainBase) eppResource;
  }

  protected void setRawDomainBase(DomainBase domainBase) {
    eppResource = domainBase;
  }

  // We could have reused domainBase.nsHosts here, but Hibernate throws a weird exception after
  // we change to use a composite primary key.
  // TODO(b/166776754): Investigate if we can reuse domainBase.nsHosts for storing host keys.
  @ElementCollection
  @JoinTable(
      name = "DomainHistoryHost",
      indexes =
          @Index(
              columnList =
                  "domain_history_history_revision_id,domain_history_domain_repo_id,host_repo_id",
              unique = true))
  @EmptySetToNull
  @Column(name = "host_repo_id")
  Set<VKey<Host>> nsHosts;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumns({
    @JoinColumn(
        name = "domainHistoryRevisionId",
        referencedColumnName = "historyRevisionId",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "domainRepoId",
        referencedColumnName = "domainRepoId",
        insertable = false,
        updatable = false)
  })
  // HashSet rather than ImmutableSet so that Hibernate can fill them out lazily on request
  @Ignore
  Set<DomainDsDataHistory> dsDataHistories = new HashSet<>();

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumns({
    @JoinColumn(
        name = "domainHistoryRevisionId",
        referencedColumnName = "historyRevisionId",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "domainRepoId",
        referencedColumnName = "domainRepoId",
        insertable = false,
        updatable = false)
  })
  // HashSet rather than ImmutableSet so that Hibernate can fill them out lazily on request
  Set<GracePeriodHistory> gracePeriodHistories = new HashSet<>();

  /** The length of time that a create, allocate, renewal, or transfer request was issued for. */
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "historyPeriodUnit")),
    @AttributeOverride(name = "value", column = @Column(name = "historyPeriodValue"))
  })
  Period period;

  /**
   * For transfers, the id of the other registrar.
   *
   * <p>For requests and cancels, the other registrar is the losing party (because the registrar
   * sending the EPP transfer command is the gaining party). For approves and rejects, the other
   * registrar is the gaining party.
   */
  @Nullable
  @Column(name = "historyOtherRegistrarId")
  String otherRegistrarId;

  /**
   * Logging field for transaction reporting.
   *
   * <p>This will be empty for any {@link HistoryEntry} generated before this field was added. This
   * will also be empty if the {@link HistoryEntry} refers to an EPP mutation that does not affect
   * domain transaction counts (such as contact or host mutations).
   */
  @Transient @EmptySetToNull Set<DomainTransactionRecord> domainTransactionRecords;

  public Set<DomainTransactionRecord> getDomainTransactionRecords() {
    return nullToEmptyImmutableCopy(domainTransactionRecords);
  }

  /** Do not use this method directly. Use the {@link HistoryEntry.Builder} instead. */
  protected void setDomainTransactionRecords(
      Set<DomainTransactionRecord> domainTransactionRecords) {
    // Note: how we wish to treat this Hibernate setter depends on the current state of the object
    // and what's passed in. The key principle is that we wish to maintain the link between parent
    // and child objects, meaning that we should keep around whichever of the two sets (the
    // parameter vs the class variable) and clear/populate that as appropriate.
    //
    // If the class variable is a PersistentSet, and we overwrite it here, Hibernate will throw
    // an exception "A collection with cascade=”all-delete-orphan” was no longer referenced by the
    // owning entity instance". See https://stackoverflow.com/questions/5587482 for more details.
    if (this.domainTransactionRecords instanceof PersistentSet) {
      Set<DomainTransactionRecord> nonNullRecords = nullToEmpty(domainTransactionRecords);
      this.domainTransactionRecords.retainAll(nonNullRecords);
      this.domainTransactionRecords.addAll(nonNullRecords);
    } else {
      this.domainTransactionRecords = ImmutableSet.copyOf(domainTransactionRecords);
    }
  }

  /**
   * Logging field for transaction reporting.
   *
   * <p>This will be empty for any DomainHistory/HistoryEntry generated before this field was added,
   * mid-2017, as well as any action that does not generate billable events (e.g. updates).
   *
   * <p>This method is dedicated for Hibernate, external caller should use {@link
   * #getDomainTransactionRecords()}.
   */
  @Access(AccessType.PROPERTY)
  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumn(name = "historyRevisionId", referencedColumnName = "historyRevisionId")
  @JoinColumn(name = "domainRepoId", referencedColumnName = "domainRepoId")
  @SuppressWarnings("unused")
  private Set<DomainTransactionRecord> getInternalDomainTransactionRecords() {
    return domainTransactionRecords;
  }

  /** Sets the domain transaction records. This method is dedicated for Hibernate. */
  @SuppressWarnings("unused")
  private void setInternalDomainTransactionRecords(
      Set<DomainTransactionRecord> domainTransactionRecords) {
    setDomainTransactionRecords(domainTransactionRecords);
  }

  /** Returns keys to the {@link Host} that are the nameservers for the domain. */
  public Set<VKey<Host>> getNsHosts() {
    return ImmutableSet.copyOf(nsHosts);
  }

  /** Returns the collection of {@link DomainDsDataHistory} instances. */
  public ImmutableSet<DomainDsDataHistory> getDsDataHistories() {
    return nullToEmptyImmutableCopy(dsDataHistories);
  }

  /**
   * The values of all the fields on the {@link DomainBase} object after the action represented by
   * this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<DomainBase> getDomainBase() {
    return Optional.ofNullable(getRawDomainBase());
  }

  public Set<GracePeriodHistory> getGracePeriodHistories() {
    return nullToEmptyImmutableCopy(gracePeriodHistories);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<DomainHistory> createVKey() {
    return VKey.createSql(DomainHistory.class, getHistoryEntryId());
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getDomainBase().map(domainBase -> new Domain.Builder().copyFrom(domainBase).build());
  }

  public String getOtherRegistrarId() {
    return otherRegistrarId;
  }

  public Period getPeriod() {
    return period;
  }

  @PostLoad
  void postLoad() {
    // TODO(b/188044616): Determine why Eager loading doesn't work here.
    Hibernate.initialize(domainTransactionRecords);
    Hibernate.initialize(nsHosts);
    Hibernate.initialize(dsDataHistories);
    Hibernate.initialize(gracePeriodHistories);

    DomainBase domainBase = getRawDomainBase();
    if (domainBase != null) {
      domainBase.nsHosts = nullToEmptyImmutableCopy(nsHosts);
      domainBase.gracePeriods =
          gracePeriodHistories.stream()
              .map(GracePeriod::createFromHistory)
              .collect(toImmutableSet());
      domainBase.dsData =
          dsDataHistories.stream().map(DomainDsData::create).collect(toImmutableSet());
      // Normally Hibernate would see that the domain fields are all null and would fill
      // domainBase with a null object. Unfortunately, the updateTimestamp is never null in SQL.
      if (domainBase.getDomainName() == null) {
        setRawDomainBase(null);
      }
    }
  }

  private static void fillAuxiliaryFieldsFromDomain(DomainHistory domainHistory) {
    DomainBase domainBase = domainHistory.getRawDomainBase();
    if (domainBase != null) {
      domainHistory.nsHosts = nullToEmptyImmutableCopy(domainBase.nsHosts);
      domainHistory.dsDataHistories =
          nullToEmptyImmutableCopy(domainBase.getDsData()).stream()
              .filter(dsData -> dsData.getDigest() != null && dsData.getDigest().length > 0)
              .map(dsData -> DomainDsDataHistory.createFrom(domainHistory.getRevisionId(), dsData))
              .collect(toImmutableSet());
      domainHistory.gracePeriodHistories =
          nullToEmptyImmutableCopy(domainBase.getGracePeriods()).stream()
              .map(
                  gracePeriod ->
                      GracePeriodHistory.createFrom(domainHistory.getRevisionId(), gracePeriod))
              .collect(toImmutableSet());
    } else {
      domainHistory.nsHosts = ImmutableSet.of();
    }
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends HistoryEntry.Builder<DomainHistory, DomainHistory.Builder> {

    public Builder() {}

    public Builder(DomainHistory instance) {
      super(instance);
    }

    public Builder setPeriod(Period period) {
      getInstance().period = period;
      return this;
    }

    public Builder setOtherRegistrarId(String otherRegistrarId) {
      getInstance().otherRegistrarId = otherRegistrarId;
      return this;
    }

    public Builder setDomainTransactionRecords(
        ImmutableSet<DomainTransactionRecord> domainTransactionRecords) {
      getInstance().setDomainTransactionRecords(domainTransactionRecords);
      return this;
    }

    public Builder copyFrom(DomainHistory historyEntry) {
      copy(historyEntry, getInstance());
      getInstance().period = historyEntry.period;
      getInstance().otherRegistrarId = historyEntry.otherRegistrarId;
      getInstance().domainTransactionRecords =
          historyEntry.domainTransactionRecords == null
              ? null
              : ImmutableSet.copyOf(historyEntry.domainTransactionRecords);
      return this;
    }

    @Override
    public DomainHistory build() {
      DomainHistory instance = super.build();
      // TODO(b/171990736): Assert instance.domainBase is not null after database migration.
      // Note that we cannot assert that instance.domainBase is not null here because this
      // builder is also used to convert legacy HistoryEntry objects to DomainHistory, when
      // domainBase is not available.
      fillAuxiliaryFieldsFromDomain(instance);
      return instance;
    }

    public DomainHistory buildAndAssemble(
        ImmutableSet<DomainDsDataHistory> dsDataHistories,
        ImmutableSet<VKey<Host>> domainHistoryHosts,
        ImmutableSet<GracePeriodHistory> gracePeriodHistories,
        ImmutableSet<DomainTransactionRecord> transactionRecords) {
      DomainHistory instance = super.build();
      instance.dsDataHistories = dsDataHistories;
      instance.nsHosts = domainHistoryHosts;
      instance.gracePeriodHistories = gracePeriodHistories;
      instance.domainTransactionRecords = transactionRecords;
      instance.hashCode = null;
      return instance;
    }
  }
}
