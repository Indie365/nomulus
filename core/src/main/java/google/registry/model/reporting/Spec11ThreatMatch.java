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

package google.registry.model.reporting;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import google.registry.util.DomainNameUtils;
import java.io.Serializable;
import java.util.Set;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.LocalDate;

@Entity
@Table(
    indexes = {
      @Index(name = "spec11threatmatch_registrar_id_idx", columnList = "registrarId"),
      @Index(name = "spec11threatmatch_tld_idx", columnList = "tld"),
      @Index(name = "spec11threatmatch_check_date_idx", columnList = "checkDate")
    })
public class Spec11ThreatMatch extends ImmutableObject
    implements Buildable, SqlEntity, Serializable {

  /** The type of threat detected. */
  public enum ThreatType {
    THREAT_TYPE_UNSPECIFIED,
    MALWARE,
    SOCIAL_ENGINEERING,
    UNWANTED_SOFTWARE,
    POTENTIALLY_HARMFUL_APPLICATION
  }

  /** An auto-generated identifier and unique primary key for this entity. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  /** The name of the offending domain */
  @Column(nullable = false)
  String domainName;

  /** The types of threat detected. */
  @Column(nullable = false)
  Set<ThreatType> threatTypes;

  /** Primary key of the domain table and unique identifier for all EPP resources. */
  @Column(nullable = false)
  String domainRepoId;

  /** ID of the registrar at the moment of the scan. Domains may change registrars over time */
  @Column(nullable = false)
  String registrarId;

  /** Date on which the check was run, on which the domain was flagged as abusive. */
  @Column(nullable = false)
  LocalDate checkDate;

  /** The domain's top-level domain. */
  @Column(nullable = false)
  String tld;

  public Long getId() {
    return id;
  }

  public String getDomainName() {
    return domainName;
  }

  public ImmutableSet<ThreatType> getThreatTypes() {
    return ImmutableSet.copyOf(threatTypes);
  }

  public String getDomainRepoId() {
    return domainRepoId;
  }

  public String getRegistrarId() {
    return registrarId;
  }

  public LocalDate getCheckDate() {
    return checkDate;
  }

  public String getTld() {
    return tld;
  }

  @Override
  public ImmutableList<DatastoreEntity> toDatastoreEntities() {
    return ImmutableList.of(); // not stored in Datastore
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Spec11ThreatMatch}, since it is immutable. */
  public static class Builder extends Buildable.Builder<Spec11ThreatMatch> {
    public Builder() {}

    private Builder(Spec11ThreatMatch instance) {
      super(instance);
    }

    @Override
    public Spec11ThreatMatch build() {
      checkArgumentNotNull(getInstance().domainName, "Domain name cannot be null");
      checkArgumentNotNull(getInstance().threatTypes, "Threat types cannot be null");
      checkArgumentNotNull(getInstance().domainRepoId, "Repo ID cannot be null");
      checkArgumentNotNull(getInstance().registrarId, "Registrar ID cannot be null");
      checkArgumentNotNull(getInstance().checkDate, "Check date cannot be null");
      checkArgumentNotNull(getInstance().tld, "TLD cannot be null");

      return super.build();
    }

    public Builder setDomainName(String domainName) {
      getInstance().domainName = domainName;
      getInstance().tld = DomainNameUtils.getTldFromDomainName(domainName);
      return this;
    }

    public Builder setThreatTypes(ImmutableSet<ThreatType> threatTypes) {
      checkArgument(!isNullOrEmpty(threatTypes), "threatTypes cannot be null or empty.");

      getInstance().threatTypes = threatTypes;
      return this;
    }

    public Builder setDomainRepoId(String domainRepoId) {
      getInstance().domainRepoId = domainRepoId;
      return this;
    }

    public Builder setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return this;
    }

    public Builder setCheckDate(LocalDate checkDate) {
      getInstance().checkDate = checkDate;
      return this;
    }
  }
}
