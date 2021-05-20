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
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.util.SelfSignedCaCertificate;
import google.registry.util.SendEmailService;
import java.security.cert.X509Certificate;
import javax.annotation.Nullable;
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
    InternetAddress address = new InternetAddress("test@example.com");
    action =
        new SendExpiringCertificateNotificationEmailAction(
            address, sendEmailService, certificateChecker);

    registrar =
        new Registrar.Builder()
            .setClientId("cliient")
            .setRegistrarName("theregistrar")
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+1.123456789")
            .setFaxNumber("+1.123456789")
            .setEmailAddress("contact-us@test.example")
            .setWhoisServer("whois.registrar.example")
            .setUrl("http://www.test.example")
            .build();
  }

  /** Returns a sample registrar with a customized registrar name, client id and certificate* */
  private Registrar createRegistrar(
      String clientId,
      String registrarName,
      @Nullable X509Certificate certificate,
      @Nullable X509Certificate failOverCertificate)
      throws Exception {
    // set up registrar with sample data
    Registrar.Builder builder =
        new Registrar.Builder()
            .setClientId(clientId)
            .setRegistrarName(registrarName)
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+1.123456789")
            .setFaxNumber("+1.123456789")
            .setEmailAddress("contact-us@test.example")
            .setWhoisServer("whois.registrar.example")
            .setUrl("http://www.test.example");

    if (failOverCertificate != null) {
      builder.setFailoverClientCertificate(
          certificateChecker.serializeCertificate(failOverCertificate), clock.nowUtc());
    }
    if (certificate != null) {
      builder.setClientCertificate(
          certificateChecker.serializeCertificate(certificate), clock.nowUtc());
    }
    return builder.build();
  }
  //
  @Test
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars() throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int NUM_OF_REGISTRARS = 10;
    int NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES = 2;
    for (int i = 1; i <= NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES; i++) {
      Registrar reg = createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null);
      persistResource(reg);
    }
    for (int i = NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES; i <= NUM_OF_REGISTRARS; i++) {
      Registrar reg = createRegistrar("goodcert" + i, "name" + i, certificate, null);
      persistResource(reg);
    }

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars_failOverCertificateBranch()
      throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int NUM_OF_REGISTRARS = 10;
    int NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES = 2;
    for (int i = 1; i <= NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES; i++) {
      Registrar reg = createRegistrar("oldcert" + i, "name" + i, null, expiringCertificate);
      persistResource(reg);
    }
    for (int i = NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES; i <= NUM_OF_REGISTRARS; i++) {
      Registrar reg = createRegistrar("goodcert" + i, "name" + i, null, certificate);
      persistResource(reg);
    }

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsAllRegistrars() throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();

    int NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES = 5;
    for (int i = 1; i <= NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES; i++) {
      Registrar reg = createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null);
      persistResource(reg);
    }
    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(NUM_OF_REGISTRARS_WITH_EXPIRING_CERTIFICATES);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsNoRegistrars() throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int NUM_OF_REGISTRARS = 10;
    for (int i = 1; i <= NUM_OF_REGISTRARS; i++) {
      Registrar reg = createRegistrar("goodcert" + i, "name" + i, certificate, null);
      persistResource(reg);
    }
    int NUM_OF_EXPIRING_REGISTRARS = 0;

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(NUM_OF_EXPIRING_REGISTRARS);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_noRegistrarsInDatabase() {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    int NUM_OF_REGISTRARS = 0;
    assertThat(results.size()).isEqualTo(NUM_OF_REGISTRARS);
  }

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

    Registrar reg =
        new Registrar.Builder()
            .setClientId("NewRegistrar")
            .setRegistrarName("EXPG")
            .setClientCertificate(
                certificateChecker.serializeCertificate(newCertificate), clock.nowUtc())
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
    Registrar reg2 =
        new Registrar.Builder()
            .setClientId("TheRegistrar")
            .setClientCertificate(
                certificateChecker.serializeCertificate(theCertificate), clock.nowUtc())
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
                .setName("John Doe")
                .setEmailAddress("jd@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John Smith")
                .setEmailAddress("js@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Will Doe")
                .setEmailAddress("will@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Mike Doe")
                .setEmailAddress("mike@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John T")
                .setEmailAddress("john@example-registrar.tld")
                .setPhoneNumber("+1.3105551215")
                .setFaxNumber("+1.3105551216")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .setVisibleInWhoisAsTech(true)
                .build());
    persistSimpleResources(contacts);
    persistResource(registrar);
    int EXPECTED_NUMBER_OF_TECH = 3;
    int EXPECTED_NUMBER_OF_ADMIN = 2;
    ImmutableSet<InternetAddress> recipients = action.getEmailAddresses(registrar, Type.TECH);
    assertThat(recipients.size()).isEqualTo(EXPECTED_NUMBER_OF_TECH);
    ImmutableSet<InternetAddress> ccs = action.getEmailAddresses(registrar, Type.ADMIN);
    assertThat(ccs.size()).isEqualTo(EXPECTED_NUMBER_OF_ADMIN);
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
