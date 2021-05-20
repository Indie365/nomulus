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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.config.RegistryConfig;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.util.SelfSignedCaCertificate;
import google.registry.util.SendEmailService;
import java.security.cert.X509Certificate;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

class SendExpiringCertificateNotificationEmailActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2021-05-24T20:21:22Z"));
  private InternetAddress address;
  private CertificateChecker certificateChecker;
  private SendExpiringCertificateNotificationEmailAction action;
  private Registrar registrar;

  @Mock private SendEmailService sendEmailService;

  @BeforeEach
  void beforeEach() throws AddressException {
    certificateChecker =
        new CertificateChecker(
            ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
            30,
            15,
            2048,
            ImmutableSet.of("secp256r1", "secp384r1"),
            clock);
    address = new InternetAddress("test@example.com");
    action =
        new SendExpiringCertificateNotificationEmailAction(
            address, sendEmailService, certificateChecker);
    registrar =
        new Registrar.Builder()
            .setClientId("EXPG-NY")
            .setRegistrarName("EXPG")
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("27 2nd Ave"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("10003")
                    .setCountryCode("US")
                    .build())
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("27 2nd Ave"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("10003")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+1.6464612123")
            .setFaxNumber("+1.2122542090")
            .setEmailAddress("contact-us@expg.example")
            .setWhoisServer("whois.expg.example")
            .setUrl("http://www.expg.example")
            .build();
  }

  //
  @Test
  void sendNotification_success() {}

  @Test
  void sendNotification_failure() {}

  @Test
  void getRegistrarsWithExpiringCertificates_returnsAListOfRegistrars() throws Exception {
    X509Certificate newCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-01-02T00:00:00Z"),
                DateTime.parse("2021-05-27T00:00:00Z"))
            .cert();
    X509Certificate theCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-01-01T00:00:00Z"),
                DateTime.parse("2021-05-27T00:00:00Z"))
            .cert();

    Registrar reg =   new Registrar.Builder()
        .setClientId("NewRegistrar")
        .setRegistrarName("EXPG")
        .setClientCertificate(certificateChecker.serializeCertificate(newCertificate), clock.nowUtc())
        .setType(Registrar.Type.REAL)
        .setIanaIdentifier(8L)
        .setState(Registrar.State.ACTIVE)
        .setInternationalizedAddress(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("27 2nd Ave"))
                .setCity("New York")
                .setState("NY")
                .setZip("10003")
                .setCountryCode("US")
                .build())
        .setLocalizedAddress(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("27 2nd Ave"))
                .setCity("New York")
                .setState("NY")
                .setZip("10003")
                .setCountryCode("US")
                .build())
        .setPhoneNumber("+1.6464612123")
        .setFaxNumber("+1.2122542090")
        .setEmailAddress("contact-us@expg.example")
        .setWhoisServer("whois.expg.example")
        .setUrl("http://www.expg.example")
        .build();
    Registrar reg2 =   new Registrar.Builder()
        .setClientId("TheRegistrar")
        .setClientCertificate(certificateChecker.serializeCertificate(theCertificate), clock.nowUtc())
        .setRegistrarName("EXPG")
        .setType(Registrar.Type.REAL)
        .setIanaIdentifier(8L)
        .setState(Registrar.State.ACTIVE)
        .setInternationalizedAddress(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("27 2nd Ave"))
                .setCity("New York")
                .setState("NY")
                .setZip("10003")
                .setCountryCode("US")
                .build())
        .setLocalizedAddress(
            new RegistrarAddress.Builder()
                .setStreet(ImmutableList.of("27 2nd Ave"))
                .setCity("New York")
                .setState("NY")
                .setZip("10003")
                .setCountryCode("US")
                .build())
        .setPhoneNumber("+1.6464612123")
        .setFaxNumber("+1.2122542090")
        .setEmailAddress("contact-us@expg.example")
        .setWhoisServer("whois.expg.example")
        .setUrl("http://www.expg.example")
        .build();
    persistResource(reg);
    persistResource(reg2);

    ImmutableList<Registrar> registrars = action.getRegistrarsWithExpiringCertificates();
    assertThat(registrars.size()).isEqualTo(2);
  }

  @Test
  void getEmailAddresses_success_returnsAnEmptyList() {
    ImmutableSet<InternetAddress> recipients = action.getEmailAddresses(registrar, Type.TECH);
    assertThat(recipients.size()).isEqualTo(0);
  }

  @Test
  void getEmailAddresses_success_returnsAListOfEmails() {
    ImmutableList<RegistrarContact> contacts =
        ImmutableList.of(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Hurrikane")
                .setEmailAddress("hurrikane@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Huurock")
                .setEmailAddress("huurock@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Cebo")
                .setEmailAddress("cebo@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Soraya")
                .setEmailAddress("soraya@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Candice")
                .setEmailAddress("candice@example-registrar.tld")
                .setPhoneNumber("+1.3105551215")
                .setFaxNumber("+1.3105551216")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .setVisibleInWhoisAsTech(true)
                .build());
    persistSimpleResources(contacts);
    persistResource(registrar);
    ImmutableSet<InternetAddress> recipients = action.getEmailAddresses(registrar, Type.TECH);
    assertThat(recipients.size()).isEqualTo(3);
    ImmutableSet<InternetAddress> ccs = action.getEmailAddresses(registrar, Type.ADMIN);
    assertThat(ccs.size()).isEqualTo(2);
  }

  @Test
  void getEmailAddresses_failure_returnsPartialListOfEmails_skipInvalidEmails() {
    // when building a new RegistrarContact object, there's already an email validation process.
    // if the registrarContact is created successful, the email address of the contact object
    // should already be validated. Ideally, there should not be an AddressException when creating
    // a new InternetAddress using the email address string of the contact object.
  }

  @Test
  void getEmailBody_returnsEmailBodyContainsCertificateString() {
    String registrarName = "good registrar";
    String certExpirationDateStr = "2021-06-15";
    String failOverCertExpirationDateStr = "2021-06-15";
    String emailBody =
        action.getEmailBody(
            registrarName,
            DateTime.parse(certExpirationDateStr).toDate(),
            DateTime.parse(failOverCertExpirationDateStr).toDate());
    assertThat(emailBody).contains(registrarName);
    assertThat(emailBody).contains(certExpirationDateStr);
    assertThat(emailBody).contains(failOverCertExpirationDateStr);
  }

  void getEmailBody_returnsEmailBodyContainsCertificateString_onlyFailOver() {
    String registrarName = "good registrar";
    String failOverCertExpirationDateStr = "2021-06-15";
    String emailBody =
        action.getEmailBody(
            registrarName, null, DateTime.parse(failOverCertExpirationDateStr).toDate());
    assertThat(emailBody).contains(registrarName);
    assertThat(emailBody).doesNotContain("the certificate");
    assertThat(emailBody).contains(failOverCertExpirationDateStr);
  }

  void getEmailBody_returnsEmailBodyContainsCertificateString_onlyCert() {
    String registrarName = "good registrar";
    String certExpirationDateStr = "2021-06-15";
    String emailBody =
        action.getEmailBody(registrarName, DateTime.parse(certExpirationDateStr).toDate(), null);
    assertThat(emailBody).contains(registrarName);
    assertThat(emailBody).contains(certExpirationDateStr);
    assertThat(emailBody).doesNotContain("the failover certificate");
  }

  void getEmailBody_returnsEmptyString_noExpirationDates() {
    String registrarName = "good registrar";
    String emailBody = action.getEmailBody(registrarName, null, null);
    assertThat(emailBody).isEmpty();
  }
}
