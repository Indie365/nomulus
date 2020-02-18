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

import static google.registry.request.RequestParameters.extractIntParameter;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

/** Dagger module for the locks package. */
@Module
public class LocksModule {

  public static final String RELOCK_DOMAIN_QUEUE_NAME = "relock-domain"; // See queue.xml.
  public static final String PARAM_OLD_UNLOCK_REVISION_ID = "oldUnlockRevisionId";

  @Provides
  @Named(RELOCK_DOMAIN_QUEUE_NAME)
  static Queue provideRelockDomainQueue() {
    return QueueFactory.getQueue(RELOCK_DOMAIN_QUEUE_NAME);
  }

  @Provides
  @Parameter(PARAM_OLD_UNLOCK_REVISION_ID)
  static Long provideOldUnlockRevisionId(HttpServletRequest req) {
    return (long) extractIntParameter(req, PARAM_OLD_UNLOCK_REVISION_ID);
  }
}
