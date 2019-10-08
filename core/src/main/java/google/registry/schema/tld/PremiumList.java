// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.tld;

import static com.google.common.base.Charsets.US_ASCII;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.hash.Funnels.stringFunnel;

import com.google.common.hash.BloomFilter;
import google.registry.model.CreateAutoTimestamp;
import java.math.BigDecimal;
import java.util.Map;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.Table;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/**
 * A list of premium prices for domain names.
 *
 * <p>Note that the primary key of this entity is {@link #revisionId}, which is auto-generated by
 * the database. So, if a retry of insertion happens after the previous attempt unexpectedly
 * succeeds, we will end up with having two exact same premium lists that differ only by revisionId.
 * This is fine though, because we only use the list with the highest revisionId.
 */
@Entity
@Table(indexes = {@Index(columnList = "name", name = "premiumlist_name_idx")})
public class PremiumList {

  @Column(nullable = false)
  private String name;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(nullable = false)
  private Long revisionId;

  @Column(nullable = false)
  private CreateAutoTimestamp creationTimestamp = CreateAutoTimestamp.create(null);

  @Column(nullable = false)
  private CurrencyUnit currency;

  @ElementCollection
  @CollectionTable(
      name = "PremiumEntry",
      joinColumns = @JoinColumn(name = "revisionId", referencedColumnName = "revisionId"))
  @MapKeyColumn(name = "domainLabel")
  @Column(name = "price", nullable = false)
  private Map<String, BigDecimal> labelsToPrices;

  @Column(nullable = false)
  private BloomFilter<String> bloomFilter;

  private PremiumList(String name, CurrencyUnit currency, Map<String, BigDecimal> labelsToPrices) {
    this.name = name;
    this.currency = currency;
    this.labelsToPrices = labelsToPrices;
    // ASCII is used for the charset because all premium list domain labels are stored punycoded.
    this.bloomFilter = BloomFilter.create(stringFunnel(US_ASCII), labelsToPrices.size());
    labelsToPrices.keySet().forEach(this.bloomFilter::put);
  }

  // Hibernate requires this default constructor.
  private PremiumList() {}

  /** Constructs a {@link PremiumList} object. */
  public static PremiumList create(
      String name, CurrencyUnit currency, Map<String, BigDecimal> labelsToPrices) {
    return new PremiumList(name, currency, labelsToPrices);
  }

  /** Returns the name of the premium list, which is usually also a TLD string. */
  public String getName() {
    return name;
  }

  /** Returns the ID of this revision, or throws if null. */
  public Long getRevisionId() {
    checkState(
        revisionId != null, "revisionId is null because it is not persisted in the database");
    return revisionId;
  }

  /** Returns the creation time of this revision of the premium list. */
  public DateTime getCreationTimestamp() {
    return creationTimestamp.getTimestamp();
  }

  /** Returns a {@link Map} of domain labels to prices. */
  public Map<String, BigDecimal> getLabelsToPrices() {
    return labelsToPrices;
  }

  /**
   * Returns a Bloom filter to determine whether a label might be premium, or is definitely not.
   *
   * <p>If the domain label might be premium, then the next step is to check for the existence of a
   * corresponding row in the PremiumListEntry table. Otherwise, we know for sure it's not premium,
   * and no DB load is required.
   */
  public BloomFilter<String> getBloomFilter() {
    return bloomFilter;
  }
}
