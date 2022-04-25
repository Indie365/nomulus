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

package google.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.SPECIFIED;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.model.tld.label.PremiumList;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link DomainFlowUtils}. */
@DualDatabaseTest
class DomainFlowUtilsTest extends ResourceFlowTestCase<DomainInfoFlow, DomainBase> {

  @BeforeEach
  void setup() {
    setEppInput("domain_info.xml");
    createTld("tld");
    persistResource(AppEngineExtension.makeRegistrar1().asBuilder().build());
  }

  @TestOfyAndSql
  void testValidateDomainNameAcceptsValidName() throws EppException {
    assertThat(DomainFlowUtils.validateDomainName("example.tld")).isNotNull();
  }

  @TestOfyAndSql
  void testValidateDomainName_IllegalCharacters() {
    BadDomainNameCharacterException thrown =
        assertThrows(
            BadDomainNameCharacterException.class,
            () -> DomainFlowUtils.validateDomainName("$.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain names can only contain a-z, 0-9, '.' and '-'");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_DomainNameWithEmptyParts() {
    EmptyDomainNamePartException thrown =
        assertThrows(
            EmptyDomainNamePartException.class,
            () -> DomainFlowUtils.validateDomainName("example."));
    assertThat(thrown).hasMessageThat().isEqualTo("No part of a domain name can be empty");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_DomainNameWithLessThanTwoParts() {
    BadDomainNamePartsCountException thrown =
        assertThrows(
            BadDomainNamePartsCountException.class,
            () -> DomainFlowUtils.validateDomainName("example"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name must have exactly one part above the TLD");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_invalidTLD() {
    TldDoesNotExistException thrown =
        assertThrows(
            TldDoesNotExistException.class,
            () -> DomainFlowUtils.validateDomainName("example.nosuchtld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name is under tld nosuchtld which doesn't exist");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_DomainNameIsTooLong() {
    DomainLabelTooLongException thrown =
        assertThrows(
            DomainLabelTooLongException.class,
            () ->
                DomainFlowUtils.validateDomainName(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain labels cannot be longer than 63 characters");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_leadingDash() {
    LeadingDashException thrown =
        assertThrows(
            LeadingDashException.class, () -> DomainFlowUtils.validateDomainName("-example.foo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain labels cannot begin with a dash");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_trailingDash() {
    TrailingDashException thrown =
        assertThrows(
            TrailingDashException.class, () -> DomainFlowUtils.validateDomainName("example-.foo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain labels cannot end with a dash");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_invalidIDN() {
    InvalidPunycodeException thrown =
        assertThrows(
            InvalidPunycodeException.class,
            () -> DomainFlowUtils.validateDomainName("xn--abcd.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name starts with xn-- but is not a valid IDN");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testValidateDomainName_containsInvalidDashes() {
    DashesInThirdAndFourthException thrown =
        assertThrows(
            DashesInThirdAndFourthException.class,
            () -> DomainFlowUtils.validateDomainName("ab--cd.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Non-IDN domain names cannot contain dashes in the third or fourth position");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_standardDomain_noBilling_returnsStandardPrice() {
    createTld("example");
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "premium,USD 100"))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 1), clock.nowUtc(), Money.of(USD, 10)))
            .build());
    assertThat(DomainFlowUtils.getDomainRenewPrice("standard.example", clock.nowUtc(), null))
        .isEqualTo(Money.of(USD, 10));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_standardDomain_noBilling_returnsStandardCost() {
    createTld("example");
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "premium,USD 100"))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 1), clock.nowUtc(), Money.of(USD, 10)))
            .build());
    assertThat(DomainFlowUtils.getDomainRenewCost("standard.example", clock.nowUtc(), 5, null))
        .isEqualTo(Money.of(USD, 50));
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_premiumDomain_noBilling_returnsPremiumPrice() {
    createTld("example");
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "premium,USD 100"))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 1), clock.nowUtc(), Money.of(USD, 10)))
            .build());
    assertThat(DomainFlowUtils.getDomainRenewPrice("premium.example", clock.nowUtc(), null))
        .isEqualTo(Money.of(USD, 100));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_premiumDomain_noBilling_returnsPremiumCost() {
    createTld("example");
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld2", USD, "premium,USD 100"))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 1), clock.nowUtc(), Money.of(USD, 10)))
            .build());
    assertThat(DomainFlowUtils.getDomainRenewCost("premium.example", clock.nowUtc(), 5, null))
        .isEqualTo(Money.of(USD, 500));
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_premiumDomain_default_returnsNonPremiumPrice() {
    assertThat(
            DomainFlowUtils.getDomainRenewPrice(
                "premium.example",
                clock.nowUtc(),
                getRecurringBillingEvent(
                    DEFAULT,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 100));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_premiumDomain_default_returnsNonPremiumCost() {
    assertThat(
            DomainFlowUtils.getDomainRenewCost(
                "premium.example",
                clock.nowUtc(),
                5,
                getRecurringBillingEvent(
                    DEFAULT,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 500));
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_standardDomain_default_returnsNonPremiumPrice() {
    assertThat(
            DomainFlowUtils.getDomainRenewPrice(
                "standard.example",
                clock.nowUtc(),
                getRecurringBillingEvent(
                    DEFAULT,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 10));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_standardDomain_default_returnsNonPremiumCost() {
    assertThat(
            DomainFlowUtils.getDomainRenewCost(
                "standard.example",
                clock.nowUtc(),
                5,
                getRecurringBillingEvent(
                    DEFAULT,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 50));
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_premiumDomain_anchorTenant_returnsNonPremiumPrice() {
    assertThat(
            DomainFlowUtils.getDomainRenewPrice(
                "premium.example",
                clock.nowUtc(),
                getRecurringBillingEvent(
                    NONPREMIUM,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 10));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_premiumDomain_anchorTenant_returnsNonPremiumCost() {
    assertThat(
            DomainFlowUtils.getDomainRenewCost(
                "premium.example",
                clock.nowUtc(),
                5,
                getRecurringBillingEvent(
                    NONPREMIUM,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 50));
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_standardDomain_anchorTenant_returnsNonPremiumPrice() {
    assertThat(
            DomainFlowUtils.getDomainRenewPrice(
                "standard.example",
                clock.nowUtc(),
                getRecurringBillingEvent(
                    NONPREMIUM,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 10));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_standardDomain_anchorTenant_returnsNonPremiumCost() {
    assertThat(
            DomainFlowUtils.getDomainRenewCost(
                "standard.example",
                clock.nowUtc(),
                5,
                getRecurringBillingEvent(
                    NONPREMIUM,
                    Optional.empty(),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 50));
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_standardDomain_internalRegistration_returnsSpecifiedPrice() {
    assertThat(
            DomainFlowUtils.getDomainRenewPrice(
                "standard.example",
                clock.nowUtc(),
                getRecurringBillingEvent(
                    SPECIFIED,
                    Optional.of(Money.of(USD, 1)),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 1));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_standardDomain_internalRegistration_returnsSpecifiedCost() {
    assertThat(
            DomainFlowUtils.getDomainRenewCost(
                "standard.example",
                clock.nowUtc(),
                5,
                getRecurringBillingEvent(
                    SPECIFIED,
                    Optional.of(Money.of(USD, 1)),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 5));
  }

  @TestOfyAndSql
  void testGetDomainRenewPrice_premiumDomain_internalRegistration_returnsSpecifiedPrice() {
    assertThat(
            DomainFlowUtils.getDomainRenewPrice(
                "premium.example",
                clock.nowUtc(),
                getRecurringBillingEvent(
                    SPECIFIED,
                    Optional.of(Money.of(USD, 17)),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 17));
  }

  @TestOfyAndSql
  void testGetDomainRenewCost_premiumDomain_internalRegistration_returnsSpecifiedCost() {
    assertThat(
            DomainFlowUtils.getDomainRenewCost(
                "premium.example",
                clock.nowUtc(),
                5,
                getRecurringBillingEvent(
                    SPECIFIED,
                    Optional.of(Money.of(USD, 17)),
                    Optional.of(persistPremiumList("tld2", USD, "premium,USD 100")))))
        .isEqualTo(Money.of(USD, 85));
  }

  /** helps to set up the domain info and returns a recurring billing event for testing */
  private Recurring getRecurringBillingEvent(
      RenewalPriceBehavior renewalPriceBehavior,
      Optional<Money> renewalPrice,
      Optional<PremiumList> premiumList) {
    createTld("example");
    DomainBase domain =
        persistResource(
            newDomainBase("test.example")
                .asBuilder()
                .setCreationTimeForTest(DateTime.parse("1999-01-05T00:00:00Z"))
                .build());

    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain.getCreationRegistrarId())
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(DateTime.parse("1999-01-05T00:00:00Z"))
                .setDomain(domain)
                .build());

    Recurring recurring =
        persistResource(
            new BillingEvent.Recurring.Builder()
                .setParent(historyEntry)
                .setRegistrarId(domain.getCreationRegistrarId())
                .setEventTime(DateTime.parse("2000-01-05T00:00:00Z"))
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setId(2L)
                .setReason(Reason.RENEW)
                .setRenewalPriceBehavior(renewalPriceBehavior)
                .setRenewalPrice(renewalPrice.isPresent() ? renewalPrice.get() : null)
                .setRecurrenceEndTime(END_OF_TIME)
                .setTargetId(domain.getDomainName())
                .build());

    persistResource(
        Registry.get("example")
            .asBuilder()
            .setPremiumList(premiumList.isPresent() ? premiumList.get() : null)
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 8), clock.nowUtc(), Money.of(USD, 10)))
            .build());
    return recurring;
  }
}
