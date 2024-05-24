// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registrar.RegistrarPocBase.Type.WHOIS;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import google.registry.groups.GmailClient;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarBase;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.FakeConsoleApiParams;
import google.registry.testing.FakeResponse;
import google.registry.testing.SystemPropertyExtension;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.registrar.ConsoleApiParams;
import google.registry.ui.server.registrar.RegistrarConsoleModule;
import google.registry.util.EmailMessage;
import google.registry.util.RegistryEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ConsoleUpdateRegistrarTest {
  private static final Gson GSON = GsonUtils.provideGson();

  private ConsoleApiParams consoleApiParams;
  private FakeResponse response;

  private Registrar registrar;

  private User user;

  private static String registrarPostData =
      "{\"registrarId\":\"%s\",\"allowedTlds\":[%s],\"registryLockAllowed\":%s}";

  private GmailClient gmailClient = mock(GmailClient.class);

  @RegisterExtension
  @Order(Integer.MAX_VALUE)
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("app");
    createTld("dev");
    registrar = persistNewRegistrar("registrarId");
    persistResource(
        registrar
            .asBuilder()
            .setType(RegistrarBase.Type.REAL)
            .setEmailAddress("testEmail@google.com")
            .build());
    user =
        new User.Builder()
            .setEmailAddress("user@registrarId.com")
            .setRegistryLockEmailAddress("registryedit@registrarId.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setGlobalRole(GlobalRole.FTE)
                    // .setRegistrarRoles(
                    //     ImmutableMap.of("registrarId", RegistrarRole.TECH_CONTACT))
                    .build())
            .build();
    consoleApiParams = createParams();
  }

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Test
  void testSuccess__updatesRegistrar() throws IOException {
    var action = createAction(String.format(registrarPostData, "registrarId", "app, dev", false));
    action.run();
    Registrar newRegistrar = Registrar.loadByRegistrarId("registrarId").get();
    assertThat(newRegistrar.getAllowedTlds()).containsExactly("app", "dev");
    assertThat(newRegistrar.isRegistryLockAllowed()).isFalse();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
  }

  @Test
  void testFails__missingWhoisContact() throws IOException {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    var action = createAction(String.format(registrarPostData, "registrarId", "app, dev", false));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat((String) ((FakeResponse) consoleApiParams.response()).getPayload())
        .contains("Cannot modify allowed TLDs if there is no WHOIS abuse contact set");
  }

  @Test
  void testSuccess__presentWhoisContact() throws IOException {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    RegistrarPoc contact =
        new RegistrarPoc.Builder()
            .setRegistrar(registrar)
            .setName("Test Registrar 1")
            .setEmailAddress("test.registrar1@example.com")
            .setPhoneNumber("+1.9999999999")
            .setFaxNumber("+1.9999999991")
            .setTypes(ImmutableSet.of(WHOIS))
            .setVisibleInWhoisAsAdmin(true)
            .setVisibleInWhoisAsTech(true)
            .setVisibleInDomainWhoisAsAbuse(true)
            .build();
    persistResource(contact);
    var action = createAction(String.format(registrarPostData, "registrarId", "app, dev", false));
    action.run();
    Registrar newRegistrar = Registrar.loadByRegistrarId("registrarId").get();
    assertThat(newRegistrar.getAllowedTlds()).containsExactly("app", "dev");
    assertThat(newRegistrar.isRegistryLockAllowed()).isFalse();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus())
        .isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
  }

  @Test
  void testSuccess__sendsEmail() throws AddressException, IOException {
    var action = createAction(String.format(registrarPostData, "registrarId", "app, dev", false));
    action.run();
    verify(gmailClient, times(1))
        .sendEmail(
            EmailMessage.create(
                "Registrar registrarId has been updated",
                "The following changes were made in registry UNITTEST environment to the registrar"
                    + " registrarId:/nAllowed TLDs: [] -> [app, dev]",
                new InternetAddress("testEmail@google.com")));
  }

  private ConsoleApiParams createParams() {
    AuthResult authResult = AuthResult.createUser(UserAuthInfo.create(user));
    return FakeConsoleApiParams.get(Optional.of(authResult));
  }

  ConsoleUpdateRegistrar createAction(String requestData) throws IOException {
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    doReturn(new BufferedReader(new StringReader(requestData)))
        .when(consoleApiParams.request())
        .getReader();
    Optional<Registrar> maybeRegistrarUpdateData =
        RegistrarConsoleModule.provideRegistrar(
            GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON));
    return new ConsoleUpdateRegistrar(consoleApiParams, gmailClient, maybeRegistrarUpdateData);
  }
}
