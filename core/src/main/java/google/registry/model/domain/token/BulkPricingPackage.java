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

package google.registry.model.domain.token;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.persistence.VKey;
import google.registry.persistence.converter.JodaMoneyType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Optional;
import javax.annotation.Nullable;
import org.hibernate.annotations.CompositeType;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * An entity representing a bulk pricing promotion. Note that this table is still called
 * PackagePromotion in Cloud SQL.
 */
@Entity(name = "PackagePromotion")
@Table(indexes = {@jakarta.persistence.Index(columnList = "token")})
public class BulkPricingPackage extends ImmutableObject implements Buildable {

  /** An autogenerated identifier for the bulk pricing promotion. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "package_promotion_id")
  long bulkPricingId;

  /** The allocation token string for the bulk pricing package. */
  @Column(nullable = false)
  VKey<AllocationToken> token;

  /** The maximum number of active domains the bulk pricing package allows at any given time. */
  @Column(nullable = false)
  int maxDomains;

  /** The maximum number of domains that can be created in the bulk pricing package each year. */
  @Column(nullable = false)
  int maxCreates;

  /** The annual price of the bulk pricing package. */
  @CompositeType(JodaMoneyType.class)
  @AttributeOverride(
      name = "amount",
      // Override Hibernate6 default (numeric(38,2)) to match real schema definition (numeric(19,2).
      column = @Column(name = "package_price_amount", precision = 19, scale = 2, nullable = false))
  @AttributeOverride(
      name = "currency",
      column = @Column(name = "package_price_currency", nullable = false))
  Money bulkPrice;

  /** The next billing date of the bulk pricing package. */
  @Column(nullable = false)
  DateTime nextBillingDate = END_OF_TIME;

  /**
   * Date the last warning email was sent that the bulk pricing package has exceeded the maxDomains
   * limit.
   */
  @Nullable DateTime lastNotificationSent;

  public long getId() {
    return bulkPricingId;
  }

  public VKey<AllocationToken> getToken() {
    return token;
  }

  public int getMaxDomains() {
    return maxDomains;
  }

  public int getMaxCreates() {
    return maxCreates;
  }

  public Money getBulkPrice() {
    return bulkPrice;
  }

  public DateTime getNextBillingDate() {
    return nextBillingDate;
  }

  public Optional<DateTime> getLastNotificationSent() {
    return Optional.ofNullable(lastNotificationSent);
  }

  /** Loads and returns a BulkPricingPackage entity by its token string directly from Cloud SQL. */
  public static Optional<BulkPricingPackage> loadByTokenString(String tokenString) {
    tm().assertInTransaction();
    return tm().query("FROM PackagePromotion WHERE token = :token", BulkPricingPackage.class)
        .setParameter("token", VKey.create(AllocationToken.class, tokenString))
        .getResultStream()
        .findFirst();
  }

  @Override
  public VKey<BulkPricingPackage> createVKey() {
    return VKey.create(BulkPricingPackage.class, bulkPricingId);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link BulkPricingPackage} objects, since they are immutable. */
  public static class Builder extends Buildable.Builder<BulkPricingPackage> {
    public Builder() {}

    private Builder(BulkPricingPackage instance) {
      super(instance);
    }

    @Override
    public BulkPricingPackage build() {
      checkArgumentNotNull(getInstance().token, "Allocation token must be specified");
      AllocationToken allocationToken = tm().transact(() -> tm().loadByKey(getInstance().token));
      checkArgument(
          allocationToken.tokenType == TokenType.BULK_PRICING,
          "Allocation token must be a BULK_PRICING type");
      return super.build();
    }

    public Builder setToken(AllocationToken token) {
      checkArgumentNotNull(token, "Allocation token must not be null");
      checkArgument(
          token.tokenType == TokenType.BULK_PRICING,
          "Allocation token must be a BULK_PRICING type");
      getInstance().token = token.createVKey();
      return this;
    }

    public Builder setMaxDomains(int maxDomains) {
      checkArgumentNotNull(maxDomains, "maxDomains must not be null");
      getInstance().maxDomains = maxDomains;
      return this;
    }

    public Builder setMaxCreates(int maxCreates) {
      checkArgumentNotNull(maxCreates, "maxCreates must not be null");
      getInstance().maxCreates = maxCreates;
      return this;
    }

    public Builder setBulkPrice(Money bulkPrice) {
      checkArgumentNotNull(bulkPrice, "Bulk price must not be null");
      getInstance().bulkPrice = bulkPrice;
      return this;
    }

    public Builder setNextBillingDate(DateTime nextBillingDate) {
      checkArgumentNotNull(nextBillingDate, "Next billing date must not be null");
      getInstance().nextBillingDate = nextBillingDate;
      return this;
    }

    public Builder setLastNotificationSent(@Nullable DateTime lastNotificationSent) {
      getInstance().lastNotificationSent = lastNotificationSent;
      return this;
    }
  }
}
