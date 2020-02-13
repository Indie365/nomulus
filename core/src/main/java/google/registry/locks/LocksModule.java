// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.locks;

import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import java.util.Optional;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

/** Dagger module for the locks package. */
@Module
public class LocksModule {

  public static final String RELOCK_DOMAIN_QUEUE_NAME = "relock-domain"; // See queue.xml.

  public static final String PARAM_OLD_UNLOCK_VERIFICATION_CODE = "oldUnlockVerificationCode";
  public static final String PARAM_FULLY_QUALIFIED_DOMAIN_NAME = "fullyQualifiedDomainName";
  public static final String PARAM_CLIENT_ID = "clientId";
  public static final String PARAM_REGISTRAR_POC_ID = "registrarPocId";
  public static final String PARAM_IS_ADMIN = "isAdmin";

  @Provides
  @Named(RELOCK_DOMAIN_QUEUE_NAME)
  static Queue provideRelockDomainQueue() {
    return QueueFactory.getQueue(RELOCK_DOMAIN_QUEUE_NAME);
  }

  @Provides
  @Parameter(PARAM_OLD_UNLOCK_VERIFICATION_CODE)
  static String provideOldUnlockVerificationCode(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_OLD_UNLOCK_VERIFICATION_CODE);
  }

  @Provides
  @Parameter(PARAM_FULLY_QUALIFIED_DOMAIN_NAME)
  static String provideFullyQualifiedDomainName(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_FULLY_QUALIFIED_DOMAIN_NAME);
  }

  @Provides
  @Parameter(PARAM_CLIENT_ID)
  static String provideClientId(HttpServletRequest req) {
    return extractRequiredParameter(req, PARAM_CLIENT_ID);
  }

  @Provides
  @Parameter(PARAM_REGISTRAR_POC_ID)
  static Optional<String> provideRegistrarPocId(HttpServletRequest req) {
    return extractOptionalParameter(req, PARAM_REGISTRAR_POC_ID);
  }

  @Provides
  @Parameter(PARAM_IS_ADMIN)
  static boolean provideIsAdmin(HttpServletRequest req) {
    return extractBooleanParameter(req, PARAM_IS_ADMIN);
  }
}
