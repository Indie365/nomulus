// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.template.soy.data.SoyMapData;
import google.registry.tools.Command.GtechCommand;
import google.registry.tools.params.PhoneNumberParameter;
import google.registry.tools.soy.CreateContactSoyInfo;
import java.util.List;
import javax.inject.Inject;

/** A command to create a new contact via EPP. */
@Parameters(separators = " =", commandDescription = "Create a new contact via EPP.")
final class CreateContactCommand extends MutatingEppToolCommand implements GtechCommand {
  // TODO(b/19016175): Expand to allow full suite of contact flows.
  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar to execute the command as",
      required = true)
  String clientIdentifier;

  @Parameter(
      names = {"--id"},
      description = "Contact ID.")
  private String id;

  @Parameter(
      names = {"--name"},
      description = "Contact name.")
  private String name;

  @Parameter(
      names = {"--org"},
      description = "Organization")
  private String org;

  @Parameter(
      names = {"--street"},
      description = "Street lines of address. Can take up to 3 lines.",
      variableArity = true)
  private List<String> street;

  @Parameter(
      names = {"--city"},
      description = "City of address.")
  private String city;

  @Parameter(
      names = {"--state"},
      description = "State of address.")
  private String state;

  @Parameter(
      names = {"--zip"},
      description = "Postal code of address.")
  private String zip;

  @Parameter(
      names = {"--cc"},
      description = "Country code of address.")
  private String cc;

  @Parameter(
      names = "--phone",
      description = "E.164 phone number, e.g. +1.2125650666",
      converter = PhoneNumberParameter.class,
      validateWith = PhoneNumberParameter.class)
  String phone;

  @Parameter(
      names = "--fax",
      description = "E.164 fax number, e.g. +1.2125650666",
      converter = PhoneNumberParameter.class,
      validateWith = PhoneNumberParameter.class)
  String fax;

  @Parameter(
      names = {"--email"},
      description = "Email address.")
  private String email;

  @Parameter(
      names = {"--password"},
      description = "Password. Optional, randomly generated if not provided.")
  private String password;

  @Inject
  PasswordGenerator passwordGenerator;

  private static final int PASSWORD_LENGTH = 16;

  @Override
  protected void initMutatingEppToolCommand() {
    if (isNullOrEmpty(password)) {
      password = passwordGenerator.createPassword(PASSWORD_LENGTH);
    }

    checkArgument(street == null || street.size() <= 3,
        "Addresses must contain at most 3 street lines.");

    setSoyTemplate(CreateContactSoyInfo.getInstance(),
        CreateContactSoyInfo.CREATECONTACT);
    addSoyRecord(clientIdentifier, new SoyMapData(
        "id", id,
        "name", name,
        "org", org,
        "street", street,
        "city", city,
        "state", state,
        "zip", zip,
        "cc", cc,
        "phone", phone,
        "fax", fax,
        "email", email,
        "password", password));
  }
}
