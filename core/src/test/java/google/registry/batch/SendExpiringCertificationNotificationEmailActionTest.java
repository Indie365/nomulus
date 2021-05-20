package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;

import google.registry.flows.DaggerEppTestComponent;
import google.registry.flows.EppController;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.util.SendEmailService;
import java.util.Optional;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

public class SendExpiringCertificationNotificationEmailActionTest {
  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2021-05-24T20:21:22Z"));
  private final FakeResponse response = new FakeResponse();
  private InternetAddress address;
  private SendExpiringCertificationNotificationEmailAction action;
  @Mock
  private SendEmailService sendEmailService;

  @BeforeEach
  void beforeEach() throws AddressException {
    EppController eppController =
        DaggerEppTestComponent.builder()
            .fakesAndMocksModule(
                FakesAndMocksModule.create(clock, EppMetric.builderForRequest(clock)))
            .build()
            .startRequest()
            .eppController();
    address = new InternetAddress("test@example.com");
    action =
        new SendExpiringCertificationNotificationEmailAction(eppController, "id", clock, response, address, sendEmailService );
  }

  @Test
  void getEmailAddresses_success_returnsAListOfEmails () {

  }

  @Test
  void getEmailAddresses_success_returnsAnEmptyList() {

  }

  @Test
  void getEmailAddresses_failure_Exception() {

  }
  @Test
  void sendNotification_success () {

  }

  @Test
  void sendNotification_failure () {

  }

}
