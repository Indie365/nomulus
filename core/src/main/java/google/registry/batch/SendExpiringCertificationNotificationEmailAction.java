package google.registry.batch;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static java.util.stream.Collectors.toCollection;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppController;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

/* An action that sends notification emails to registrars whose certificates are expiring soon.
* */
@Action(
    service = Action.Service.BACKEND,
    path = SendExpiringCertificationNotificationEmailAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class SendExpiringCertificationNotificationEmailAction implements Runnable {

  public static final String PATH = "/_dr/task/sendExpiringCertificationNotificationEmail";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final EppController eppController;
  private final String registryAdminClientId;
  private final Clock clock;
  private final Response response;
  private final SendEmailService sendEmailService;
  private final InternetAddress gSuiteOutgoingEmailAddress;

  public SendExpiringCertificationNotificationEmailAction(
      EppController eppController,
      @Config("registryAdminClientId") String registryAdminClientId,
      Clock clock,
      Response response,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress,
      SendEmailService sendEmailService) {
    this.eppController = eppController;
    this.registryAdminClientId = registryAdminClientId;
    this.clock = clock;
    this.response = response;
    this.sendEmailService = sendEmailService;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
  }

  @Override
  public void run() {
    response.setContentType(PLAIN_TEXT_UTF_8);
    sendNotification(getEmailAddresses());
  }

  // 1. get a set of certificates that should receive notification
  private Collection<InternetAddress> getEmailAddresses() {
    return Streams.stream(Registrar.loadAllCached())
        .map(registrar -> {
          if (!registrar.isLive()) {
            return null;
          }
          String certificateStr = registrar.getClientCertificate().toString();
          X509Certificate certificate = CertificateChecker.getCertificate(certificateStr);

          try {
            return CertificateChecker.shouldReceiveExpiringNotification(certificate)
                ? new InternetAddress((registrar.getEmailAddress())) : null;
          } catch (AddressException e) {
            e.printStackTrace();
            return null;
          }
        }).filter(Objects::isNull)
        .collect(toCollection(ArrayList::new));
  }


  // 2. send email to certificate that is expiring
  private void sendNotification(Collection<InternetAddress> addresses) {
    String body = "random string";
    String subject = "random subject";
    ImmutableSet<InternetAddress> recipients = new ImmutableSet.Builder<InternetAddress>()
        .addAll(addresses).build();
    sendEmailService.sendEmail(
        EmailMessage.newBuilder()
            .setRecipients(addresses)
            .setFrom(gSuiteOutgoingEmailAddress)
            .setBody(body)
            .setSubject(subject)
            .setRecipients(recipients)
            .build());
  }
}
