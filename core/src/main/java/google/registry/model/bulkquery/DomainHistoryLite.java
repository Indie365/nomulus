// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.bulkquery;

import google.registry.model.EppResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.reporting.HistoryEntry;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Entity;

/**
 * A 'light' version of {@link DomainHistory} with only base table ("DomainHistory") attributes,
 * which allows fast bulk loading. They are used in in-memory assembly of {@code DomainHistory}
 * instances along with bulk-loaded child entities ({@code GracePeriodHistory}, etc). The in-memory
 * assembly achieves much higher performance than loading {@code DomainHistory} directly.
 *
 * <p>Please refer to {@link BulkQueryEntities} for more information.
 *
 * <p>This class is adapted from {@link DomainHistory} by removing the {@code dsDataHistories},
 * {@code gracePeriodHistories}, and {@code nsHosts} fields and associated methods.
 */
@Entity(name = "DomainHistory")
@Access(AccessType.FIELD)
@AttributeOverride(name = "repoId", column = @Column(name = "domainRepoId"))
public class DomainHistoryLite extends HistoryEntry {

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

  /** The length of time that a create, allocate, renewal, or transfer request was issued for. */
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "historyPeriodUnit")),
    @AttributeOverride(name = "value", column = @Column(name = "historyPeriodValue"))
  })
  @SuppressWarnings("unused")
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
  @SuppressWarnings("unused")
  String otherRegistrarId;

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    throw new UnsupportedOperationException("Cannot get Domain from DomainHistoryLite");
  }

  @Override
  public Builder<? extends HistoryEntry, ?> asBuilder() {
    throw new UnsupportedOperationException("DomainHistoryLite cannot be converted to a Builder");
  }
}
