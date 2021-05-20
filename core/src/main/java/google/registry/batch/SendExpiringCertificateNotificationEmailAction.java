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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.Date;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/* An action that sends notification emails to registrars whose certificates are expiring soon.
 * */
@Action(
    service = Action.Service.BACKEND,
    path = SendExpiringCertificateNotificationEmailAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class SendExpiringCertificateNotificationEmailAction implements Runnable {

  public static final String PATH = "/_dr/task/sendExpiringCertificateNotificationEmail";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // This value is used as an offset when storing the last notification email sent date. This is
  // used to handle corner cases when the update happens in between the day switch.
  // For instance,if the job starts at 2:00 am every day and it finishes at 2:03 of the same day,
  // then next day at 2am, the date difference will be less than a day, which will lead to the date
  // difference between two successive email sent date being the expected email interval days + 1;
  private static final int UPDATE_TIME_OFFSET = 10;

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd");

  private final SendEmailService sendEmailService;
  private final InternetAddress gSuiteOutgoingEmailAddress;
  private final CertificateChecker certificateChecker;

  @Inject
  public SendExpiringCertificateNotificationEmailAction(
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      SendEmailService sendEmailService,
      CertificateChecker certificateChecker) {

    this.sendEmailService = sendEmailService;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
    this.certificateChecker = certificateChecker;
  }

  @Override
  public void run() {
    sendNotificationEmails();
  }

  // get a list of registrars that should receive expiring notification emails; there are two
  // certificates that should be considered (main client certificate and failover certificate).
  // the registrars should receive notifications if one of the certificate checks returns true.
  protected ImmutableList<Registrar> getRegistrarsWithExpiringCertificates() {
    return Streams.stream(Registrar.loadAllCached())
        .sorted(Comparator.comparing(Registrar::getRegistrarName, String.CASE_INSENSITIVE_ORDER))
        .filter(
            registrar ->
                (registrar.getClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringNotificationSentDate(),
                            registrar.getClientCertificate().get()))
                    || (registrar.getFailoverClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringFailoverCertNotificationSentDate(),
                            registrar.getFailoverClientCertificate().get())))
        .collect(toImmutableList());
  }

  // send notification emails to registrars with expiring certificates
  protected void sendNotificationEmails() {
    logger.atInfo().log("Getting list of registrars with expiring certificates.");

    ImmutableList<Registrar> registrars = getRegistrarsWithExpiringCertificates();
    // TODO: remove placeholder text once there's more info on email from business side
    // This can be injected from a config file; will determine once the subject string is finalized
    String subject = "random subject";

    for (Registrar registrar : registrars) {
      // get expiration date of the main certificate, only if the registrar should receive a
      // notification regarding this certificate; otherwise, the date remains null.
      X509Certificate certificate;
      Date certificateExpirationDate = null;
      if (registrar.getClientCertificate().isPresent()) {
        certificate = certificateChecker.getCertificate(registrar.getClientCertificate().get());
        DateTime lastExpiringNotificationSentDate = registrar.getLastExpiringNotificationSentDate();
        certificateExpirationDate =
            certificateChecker.shouldReceiveExpiringNotification(
                    lastExpiringNotificationSentDate, registrar.getClientCertificate().get())
                ? certificate.getNotAfter()
                : null;
      }
      // get expiration date of the fallOver certificate, only if the registrar should receive a
      // notification regarding this fallOver certificate; otherwise, the date remains null.
      X509Certificate failOverCertificate;
      Date failOverCertificateExpirationDate = null;
      if (registrar.getFailoverClientCertificate().isPresent()) {
        failOverCertificate =
            certificateChecker.getCertificate(registrar.getFailoverClientCertificate().get());
        DateTime lastExpiringFailoverCertNotificationSentDate =
            registrar.getLastExpiringFailoverCertNotificationSentDate();
        failOverCertificateExpirationDate =
            certificateChecker.shouldReceiveExpiringNotification(
                    lastExpiringFailoverCertNotificationSentDate,
                    registrar.getFailoverClientCertificate().get())
                ? failOverCertificate.getNotAfter()
                : null;
      }

      try {
        ImmutableSet<InternetAddress> recipients = getEmailAddresses(registrar, Type.TECH);
        if (!recipients.isEmpty()) {
          ImmutableSet<InternetAddress> ccs = getEmailAddresses(registrar, Type.ADMIN);
          EmailMessage.Builder msgBuilder =
              EmailMessage.newBuilder()
                  .setFrom(gSuiteOutgoingEmailAddress)
                  .setBody(
                      getEmailBody(
                          registrar.getRegistrarName(),
                          certificateExpirationDate,
                          failOverCertificateExpirationDate))
                  .setSubject(subject)
                  .setRecipients(recipients);
          if (!ccs.isEmpty()) {
            msgBuilder.setCcs(ccs);
          }
          sendEmailService.sendEmail(msgBuilder.build());

          // using an offset to ensure that date comparison between two successive dates is always
          // greater than 1 day; this date is set as last updated date, for applicable
          // certificate(s)
          DateTime lastNotificationSentDate = DateTime.now().minusMinutes(UPDATE_TIME_OFFSET);

          if (certificateExpirationDate != null && failOverCertificateExpirationDate != null) {
            tm().transact(
                    () ->
                        tm().put(
                                registrar
                                    .asBuilder()
                                    .setLastExpiringFailoverCertNotificationSentDate(
                                        lastNotificationSentDate)
                                    .setLastExpiringCertNotificationSentDate(
                                        lastNotificationSentDate)));
          } else if (certificateExpirationDate != null) {
            tm().transact(
                    () ->
                        tm().put(
                                registrar
                                    .asBuilder()
                                    .setLastExpiringCertNotificationSentDate(
                                        lastNotificationSentDate)));
          } else if (failOverCertificateExpirationDate != null) {
            tm().transact(
                    () ->
                        tm().put(
                                registrar
                                    .asBuilder()
                                    .setLastExpiringFailoverCertNotificationSentDate(
                                        lastNotificationSentDate)));
          }

        } else {
          logger.atWarning().log(
              "Registrar %s contains no email addresses to receive notification email.",
              registrar.getRegistrarName());
        }

      } catch (Exception e) {
        logger.atWarning().withCause(e).log(
            "Failed to send expiring certificate notification email to registrar %s",
            registrar.getRegistrarName());
      }
    }
  }

  // returns a list of email addresses that should receive the same email
  protected ImmutableSet<InternetAddress> getEmailAddresses(Registrar registrar, Type contactType) {
    ImmutableSortedSet<RegistrarContact> contacts = registrar.getContactsOfType(contactType);
    ImmutableSet.Builder<InternetAddress> recipientEmails = new ImmutableSet.Builder<>();
    for (RegistrarContact contact : contacts) {
      try {
        recipientEmails.add(new InternetAddress(contact.getEmailAddress()));
      } catch (AddressException e) {
        logger.atWarning().withCause(e).log(
            "Contact email address %s is invalid for contact %s; skipping.",
            contact.getEmailAddress(), contact.getName());
      }
    }
    return recipientEmails.build();
  }

  // TODO: get email content from business side
  // generates email body with registrar name and certificate expiration date  as parameters;
  protected String getEmailBody(
      String registrar,
      @Nullable Date certificateExpirationDate,
      @Nullable Date failOverCertificateExpirationDate) {
    if (certificateExpirationDate == null && failOverCertificateExpirationDate == null) return "";
    String firstLine = String.format("Hi %s,\n", registrar);
    String mainContent = "  We are here to inform you:\n";
    String expiringCertificates = "";
    if (certificateExpirationDate != null) {
      expiringCertificates +=
          String.format(
              "     the certificate expires on %s.\n",
              DATE_FORMATTER.print(new DateTime(certificateExpirationDate)));
    }
    if (failOverCertificateExpirationDate != null) {
      expiringCertificates +=
          String.format(
              "     the failover certificate expires on %s.\n",
              DATE_FORMATTER.print(new DateTime(failOverCertificateExpirationDate)));
    }
    return firstLine + mainContent + expiringCertificates;
  }
}
