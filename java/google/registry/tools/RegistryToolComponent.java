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

import dagger.Component;
import google.registry.config.ConfigModule;
import google.registry.keyring.api.KeyModule;
import google.registry.keyring.api.VoidKeyringModule;
import google.registry.request.Modules.DatastoreServiceModule;
import google.registry.request.Modules.Jackson2Module;
import google.registry.request.Modules.URLFetchServiceModule;
import google.registry.util.SystemClock.SystemClockModule;

/**
 * Dagger component for Registry Tool.
 *
 * <p>Any command class with {@code @Inject} fields <i>must</i> be listed as a method here.
 * Otherwise {@link RegistryCli} will not be able to populate those fields after its instantiation.
 */
@Component(
    modules = {
        ConfigModule.class,
        DatastoreServiceModule.class,
        Jackson2Module.class,
        KeyModule.class,
        RegistryToolModule.class,
        SystemClockModule.class,
        URLFetchServiceModule.class,
        VoidKeyringModule.class,
    })
interface RegistryToolComponent {
  void inject(CreateAnchorTenantCommand command);
  void inject(CreateContactCommand command);
  void inject(EncryptEscrowDepositCommand command);
  void inject(GenerateApplicationsReportCommand command);
  void inject(GenerateDnsReportCommand command);
  void inject(GenerateEscrowDepositCommand command);
  void inject(GhostrydeCommand command);
  void inject(ListCursorsCommand command);
  void inject(PendingEscrowCommand command);
  void inject(SendEscrowReportToIcannCommand command);
  void inject(SetupOteCommand command);
  void inject(UpdateCursorsCommand command);
  void inject(ValidateEscrowDepositCommand command);
  void inject(WhoisQueryCommand command);
}
