// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.Files;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.model.registry.label.PremiumListDualDao;
import java.io.File;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdatePremiumListCommand}. */
class UpdatePremiumListCommandTest<C extends UpdatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {
  Registry registry;

  @BeforeEach
  void beforeEach() {
    registry =
        newRegistry(
            TLD_TEST,
            Ascii.toUpperCase(TLD_TEST),
            ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY),
            TldType.TEST);
    persistPremiumList(TLD_TEST, "foo,USD 100");
    persistResource(registry);
  }

  @Test
  void testSuccess_stageNoEntityChange() throws Exception {
    File tmpFile = tmpDir.resolve("prime.txt").toFile();
    String premiumTermsCsv = "foo,USD 100";
    Files.asCharSink(tmpFile, UTF_8).write(premiumTermsCsv);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    command.name = TLD_TEST;
    command.init();
    assertThat(command.prompt()).contains("No entity changes to apply.");
  }

  @Test
  void testSuccess_updateList() throws Exception {
    // ensure that no premium list is created before running the command
    assertThat(PremiumListDualDao.exists(TLD_TEST)).isTrue();
    assertThat(PremiumListDualDao.getLatestRevision(TLD_TEST).isPresent()).isTrue();
    File tmpFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    String premiumTermsCsv = "foo,USD 9000";
    Files.asCharSink(tmpFile, UTF_8).write(premiumTermsCsv);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    ImmutableSet<String> entries = command.getExistingPremiumListEntry(TLD_TEST);
    assertThat(entries.size()).isEqualTo(1);
    assertThat(entries.contains("foo,USD 9000.00")).isTrue();
    System.out.println(entries.toString());
  }

  @Test
  void testFailure_updateEmptyList() throws Exception {
    // ensure that no premium list is created before running the command
    assertThat(PremiumListDualDao.exists(TLD_TEST)).isTrue();
    assertThat(PremiumListDualDao.getLatestRevision(TLD_TEST).isPresent()).isTrue();
    File tmpFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    String premiumTermsCsv = "";
    Files.asCharSink(tmpFile, UTF_8).write(premiumTermsCsv);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("The Cloud SQL schema requires exactly one currency");
  }

  @Test
  void testSuccess_updateMultiLineList() throws Exception {
    // ensure that no premium list is created before running the command
    assertThat(PremiumListDualDao.exists(TLD_TEST)).isTrue();
    assertThat(PremiumListDualDao.getLatestRevision(TLD_TEST).isPresent()).isTrue();
    File tmpFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    String premiumTermsCsv = "foo,USD 9000\ndoge,USD 100\nelon,USD 2021";
    Files.asCharSink(tmpFile, UTF_8).write(premiumTermsCsv);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    ImmutableSet<String> entries = command.getExistingPremiumListEntry(TLD_TEST);
    assertThat(entries.size()).isEqualTo(3);
    assertThat(entries.contains("foo,USD 9000.00")).isTrue();
    assertThat(entries.contains("doge,USD 100.00")).isTrue();
    assertThat(entries.contains("elon,USD 2021.00")).isTrue();
    System.out.println(entries.toString());
  }

  @Test
  void testSuccess_stageEntityChange() throws Exception {
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(premiumTermsPath);
    command.name = TLD_TEST;
    command.init();
    assertThat(command.prompt()).contains("Update PremiumList@");
  }

  @Test
  void testFailure_noPreviousVersion() {
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    String randomStr = "random";
    command.name = randomStr;
    Registry tmpRegistry =
        new Registry.Builder()
            .setTldStr(randomStr)
            .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
            .setDnsWriters(ImmutableSet.of(VoidDnsWriter.NAME))
            .setPremiumList(null)
            .build();
    tm().transact(() -> tm().put(tmpRegistry));
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", randomStr));
  }

  @Test
  void testFailure_noInputFile() {
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    assertThrows(NullPointerException.class, command::init);
  }

  @Test
  void testFailure_tldDoesNotExist_fileName() {
    String randomStr = "random";
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.name = randomStr;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", randomStr));
  }

  @Test
  void testFailure_tldDoesNotExist_name() {
    String randomStr = "random";
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpDir.resolve(randomStr + ".txt").toFile().getPath());
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", randomStr));
  }
}
