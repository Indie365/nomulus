// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.config.RegistryConfig.getHibernateConnectionIsolation;
import static google.registry.config.RegistryConfig.getHibernateHikariConnectionTimeout;
import static google.registry.config.RegistryConfig.getHibernateHikariIdleTimeout;
import static google.registry.config.RegistryConfig.getHibernateHikariMaximumPoolSize;
import static google.registry.config.RegistryConfig.getHibernateHikariMinimumIdle;
import static google.registry.config.RegistryConfig.getHibernateLogSqlQueries;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import google.registry.config.RegistryConfig;
import google.registry.gcs.GcsUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import org.hibernate.cfg.Environment;

/** Factory class to provide {@link EntityManagerFactory} instance. */
public class EntityManagerFactoryProvider {
  // This name must be the same as the one defined in persistence.xml.
  public static final String PERSISTENCE_UNIT_NAME = "nomulus";
  public static final String HIKARI_CONNECTION_TIMEOUT = "hibernate.hikari.connectionTimeout";
  public static final String HIKARI_MINIMUM_IDLE = "hibernate.hikari.minimumIdle";
  public static final String HIKARI_MAXIMUM_POOL_SIZE = "hibernate.hikari.maximumPoolSize";
  public static final String HIKARI_IDLE_TIMEOUT = "hibernate.hikari.idleTimeout";

  // Size of Google Cloud Storage client connection buffer in bytes.
  private static final int GCS_BUFFER_SIZE = 1024 * 1024;

  private static ImmutableMap<String, String> getDefaultProperties() {
    ImmutableMap.Builder<String, String> properties = ImmutableMap.builder();

    properties.put(Environment.DRIVER, "org.postgresql.Driver");
    properties.put(
        Environment.CONNECTION_PROVIDER,
        "org.hibernate.hikaricp.internal.HikariCPConnectionProvider");
    // Whether to automatically validate and export schema DDL to the database when the
    // SessionFactory is created. Setting it to 'none' to turn off the feature.
    properties.put(Environment.HBM2DDL_AUTO, "none");

    properties.put(Environment.ISOLATION, getHibernateConnectionIsolation());
    properties.put(Environment.SHOW_SQL, getHibernateLogSqlQueries());
    properties.put(HIKARI_CONNECTION_TIMEOUT, getHibernateHikariConnectionTimeout());
    properties.put(HIKARI_MINIMUM_IDLE, getHibernateHikariMinimumIdle());
    properties.put(HIKARI_MAXIMUM_POOL_SIZE, getHibernateHikariMaximumPoolSize());
    properties.put(HIKARI_IDLE_TIMEOUT, getHibernateHikariIdleTimeout());
    return properties.build();
  }

  /** Constructs the {@link EntityManagerFactory} instance. */
  public static EntityManagerFactory create(
      String jdbcUrl, String username, String password, ImmutableMap<String, String> overrides) {
    ImmutableMap.Builder<String, String> properties = ImmutableMap.builder();
    properties.putAll(getDefaultProperties());
    properties.putAll(overrides);
    properties.put(Environment.URL, jdbcUrl);
    properties.put(Environment.USER, username);
    properties.put(Environment.PASS, password);
    EntityManagerFactory emf =
        Persistence.createEntityManagerFactory(PERSISTENCE_UNIT_NAME, properties.build());
    checkState(
        emf != null,
        "Persistence.createEntityManagerFactory() returns a null EntityManagerFactory");
    return emf;
  }

  /** Constructs the {@link EntityManagerFactory} instance for the App Engine application. */
  public static EntityManagerFactory createForAppEngine() {

    GcsUtils gcsUtils =
        new GcsUtils(
            GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance()), GCS_BUFFER_SIZE);

    ByteString cipherText;
    try {
      cipherText =
          ByteString.readFrom(
              gcsUtils.openInputStream(
                  new GcsFilename(
                      RegistryConfig.getCloudSqlGcsBucketForCredential(),
                      RegistryConfig.getCloudSqlCredentialObjectName())));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    String[] credentialFields;
    try (KeyManagementServiceClient client = KeyManagementServiceClient.create()) {
      CryptoKeyName name =
          CryptoKeyName.of(
              RegistryConfig.getKmsKeyRingProjectId(),
              RegistryConfig.getKmsKeyRingLocation(),
              RegistryConfig.getKmsKeyRingName(),
              RegistryConfig.getKeyNameForCloudSql());

      // The credential uses the below format, the delimiter is a single space:
      // [instanceConnectionName] [username] [password]
      credentialFields =
          client.decrypt(name.toString(), cipherText).getPlaintext().toStringUtf8().split(" ");
      if (credentialFields.length != 3) {
        throw new IllegalArgumentException(
            "Number of fields in the credential file is not correct");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    String instanceConnectionName = credentialFields[0];
    String username = credentialFields[1];
    String password = credentialFields[2];

    ImmutableMap.Builder<String, String> overrides = ImmutableMap.builder();
    // For Java users, the Cloud SQL JDBC Socket Factory can provide authenticated connections.
    // See https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory for details.
    overrides.put("socketFactory", "com.google.cloud.sql.postgres.SocketFactory");
    overrides.put("cloudSqlInstance", instanceConnectionName);

    return create(RegistryConfig.getCloudSqlJdbcUrl(), username, password, overrides.build());
  }
}
