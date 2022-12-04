// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.appengine.tools.remoteapi.RemoteApiInstaller;
import com.google.appengine.tools.remoteapi.RemoteApiOptions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.Domain;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.tools.CommandWithConnection;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import google.registry.tools.RemoteApiOptionsUtil;
import google.registry.tools.ServiceConnection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;

/**
 * Command that creates an additional synthetic history object for domains.
 *
 * <p>This is created to fix the issue identified in b/261192144. Part of the fix requires that
 * synthetic history be generated for specific domains.
 */
@Parameters(
    separators = " =",
    commandDescription = "Create synthetic domain history objects to fix RDE.")
public class CreateSyntheticDomainHistoriesByIdsCommand extends ConfirmingCommand
    implements CommandWithRemoteApi, CommandWithConnection {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String HISTORY_REASON =
      "Create synthetic domain histories to fix RDE for b/261192144";

  @Parameter(
      names = "--repo_ids_path",
      description = "Path to local file with contact repo_ids, one per line.")
  private String repoIdsPath;

  private static final ExecutorService executor = Executors.newFixedThreadPool(20);
  private static final AtomicInteger numDomainsProcessed = new AtomicInteger();

  private ServiceConnection connection;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminRegistrarId;

  @Inject @CredentialModule.LocalCredentialJson String localCredentialJson;

  private final ThreadLocal<RemoteApiInstaller> installerThreadLocal =
      ThreadLocal.withInitial(this::createInstaller);

  private ImmutableSet<String> domainRepoIds;

  @Override
  protected String prompt() {
    checkArgument(!Strings.isNullOrEmpty(repoIdsPath), "repo_ids_path must be present.");
    try {
      domainRepoIds = ImmutableSet.copyOf(Files.readAllLines(Paths.get(repoIdsPath), UTF_8));
      return String.format(
          "Attempt to create synthetic history entries for %d domains?", domainRepoIds.size());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected String execute() throws Exception {
    List<Future<?>> futures = new ArrayList<>();
    for (String domainRepoId : domainRepoIds) {
      futures.add(
          executor.submit(
              () -> {
                // Make sure the remote API is installed for ID generation
                installerThreadLocal.get();
                jpaTm()
                    .transact(
                        () -> {
                          Domain domain =
                              jpaTm().loadByKey(VKey.create(Domain.class, domainRepoId));
                          jpaTm()
                              .put(
                                  HistoryEntry.createBuilderForResource(domain)
                                      .setRegistrarId(registryAdminRegistrarId)
                                      .setBySuperuser(true)
                                      .setRequestedByRegistrar(false)
                                      .setModificationTime(jpaTm().getTransactionTime())
                                      .setReason(HISTORY_REASON)
                                      .setType(HistoryEntry.Type.SYNTHETIC)
                                      .build());
                        });
                int numProcessed = numDomainsProcessed.incrementAndGet();
                if (numProcessed % 1000 == 0) {
                  System.out.printf("Saved histories for %d domains%n", numProcessed);
                }
                return null;
              }));
    }
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Error");
      }
    }
    executor.shutdown();
    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    return String.format("Saved entries for %d domains", numDomainsProcessed.get());
  }

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  /**
   * Installs the remote API so that the worker threads can use Datastore for ID generation.
   *
   * <p>Lifted from the RegistryCli class
   */
  private RemoteApiInstaller createInstaller() {
    RemoteApiInstaller installer = new RemoteApiInstaller();
    RemoteApiOptions options = new RemoteApiOptions();
    options.server(connection.getServer().getHost(), getPort(connection.getServer()));
    if (RegistryConfig.areServersLocal()) {
      // Use dev credentials for localhost.
      options.useDevelopmentServerCredential();
    } else {
      try {
        RemoteApiOptionsUtil.useGoogleCredentialStream(
            options, new ByteArrayInputStream(localCredentialJson.getBytes(UTF_8)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    try {
      installer.install(options);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return installer;
  }

  private static int getPort(URL url) {
    return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
  }
}
