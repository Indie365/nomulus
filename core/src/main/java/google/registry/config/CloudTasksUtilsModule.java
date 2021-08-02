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

package google.registry.config;

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.CloudTasksUtils;
import google.registry.util.Retrier;
import javax.inject.Singleton;

/**
 * A {@link Module} that provides {@link CloudTasksUtils}.
 *
 * <p>The class itself cannot be annotated as {@code Inject} because its dependencies uses the
 * {@link Config} qualifier which is not available in the {@code utils} package.
 */
@Module
public abstract class CloudTasksUtilsModule {

  @Singleton
  @Provides
  public static CloudTasksUtils provideCloudTasksUtils(
      @Config("projectId") String projectId,
      @Config("locationId") String locationId,
      Retrier retrier) {
    return new CloudTasksUtils(retrier, projectId, locationId, null);
  }
}
