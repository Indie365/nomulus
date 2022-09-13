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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.ImmutableList;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TestCacheExtension;
import java.time.Duration;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ForeignKeyUtils}. */
class ForeignKeyUtilsTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  public final JpaIntegrationTestExtension jpaIntegrationTestExtension =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withForeignKeyCache(Duration.ofDays(1)).build();

  @BeforeEach
  void setUp() {
    createTld("com");
  }

  @Test
  void testLoadForNonexistentForeignKey_returnsNull() {
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testLoadForDeletedForeignKey_returnsNull() {
    Host host = persistActiveHost("ns1.example.com");
    persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testLoad_newerKeyHasBeenSoftDeleted() {
    Host host1 = persistActiveHost("ns1.example.com");
    fakeClock.advanceOneMilli();
    persistResource(host1.asBuilder().setDeletionTime(fakeClock.nowUtc()).build());
    assertThat(ForeignKeyUtils.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testBatchLoad_skipsDeletedAndNonexistent() {
    persistActiveHost("ns1.example.com");
    Host host = persistActiveHost("ns2.example.com");
    persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    assertThat(
            ForeignKeyUtils.load(
                    Host.class,
                    ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
                    fakeClock.nowUtc())
                .keySet())
        .containsExactly("ns1.example.com");
  }

  @Test
  void testDeadCodeThatDeletedScrapCommandsReference() {
    persistActiveHost("omg");
    VKey<Host> hostKey = ForeignKeyUtils.load(Host.class, "omg", fakeClock.nowUtc());
    assertThat(loadByKey(hostKey).getForeignKey()).isEqualTo("omg");
  }
}
