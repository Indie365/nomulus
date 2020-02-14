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

package google.registry.schema.cursor;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Cursor}. */
@RunWith(JUnit4.class)
public class CursorDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationWithCoverageRule();

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Test
  public void save_worksSuccessfullyOnNewCursor() {
    Cursor cursor = Cursor.create(CursorType.BRDA, "tld", fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor returnedCursor = CursorDao.load(CursorType.BRDA, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  public void save_worksSuccessfullyOnExistingCursor() {
    Cursor cursor = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc().plusDays(3));
    CursorDao.save(cursor2);
    Cursor returnedCursor = CursorDao.load(CursorType.RDE_REPORT, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
  }

  @Test
  public void save_worksSuccessfullyOnNewGlobalCursor() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  public void save_worksSuccessfullyOnExistingGlobalCursor() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.save(cursor);
    Cursor cursor2 =
        Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc().plusDays(3));
    CursorDao.save(cursor2);
    Cursor returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
  }

  @Test
  public void saveAll_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    ImmutableSet<Cursor> cursors = ImmutableSet.<Cursor>builder().add(cursor, cursor2).build();
    CursorDao.saveAll(cursors);
    assertThat(CursorDao.loadAll()).hasSize(2);
    assertThat(CursorDao.load(CursorType.RECURRING_BILLING).getCursorTime())
        .isEqualTo(cursor.getCursorTime());
  }

  @Test
  public void saveAll_worksSuccessfullyEmptySet() {
    CursorDao.saveAll(ImmutableSet.of());
    assertThat(CursorDao.loadAll()).isEmpty();
  }

  @Test
  public void load_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.saveAll(ImmutableSet.of(cursor, cursor2, cursor3, cursor4));
    Cursor returnedCursor = CursorDao.load(CursorType.RDE_REPORT, "tld");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor2.getCursorTime());
    returnedCursor = CursorDao.load(CursorType.BRDA, "foo");
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor4.getCursorTime());
    returnedCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    assertThat(returnedCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
  }

  @Test
  public void loadAll_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.saveAll(ImmutableSet.of(cursor, cursor2, cursor3, cursor4));
    List<Cursor> returnedCursors = CursorDao.loadAll();
    assertThat(returnedCursors.size()).isEqualTo(4);
  }

  @Test
  public void loadAll_worksSuccessfullyEmptyTable() {
    List<Cursor> returnedCursors = CursorDao.loadAll();
    assertThat(returnedCursors.size()).isEqualTo(0);
  }

  @Test
  public void loadByType_worksSuccessfully() {
    Cursor cursor = Cursor.createGlobal(CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    Cursor cursor2 = Cursor.create(CursorType.RDE_REPORT, "tld", fakeClock.nowUtc());
    Cursor cursor3 = Cursor.create(CursorType.RDE_REPORT, "foo", fakeClock.nowUtc());
    Cursor cursor4 = Cursor.create(CursorType.BRDA, "foo", fakeClock.nowUtc());
    CursorDao.saveAll(ImmutableSet.of(cursor, cursor2, cursor3, cursor4));
    List<Cursor> returnedCursors = CursorDao.loadByType(CursorType.RDE_REPORT);
    assertThat(returnedCursors.size()).isEqualTo(2);
  }

  @Test
  public void loadByType_worksSuccessfullyNoneOfType() {
    List<Cursor> returnedCursors = CursorDao.loadByType(CursorType.RDE_REPORT);
    assertThat(returnedCursors.size()).isEqualTo(0);
  }

  @Test
  public void saveCursor_worksSuccessfully() {
    createTld("tld");
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    CursorDao.saveCursor(cursor, "tld");
    Cursor createdCursor = CursorDao.load(CursorType.ICANN_UPLOAD_ACTIVITY, "tld");
    google.registry.model.common.Cursor dataStoreCursor =
        ofy()
            .load()
            .key(
                google.registry.model.common.Cursor.createKey(
                    CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("tld")))
            .now();
    assertThat(createdCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
    assertThat(cursor).isEqualTo(dataStoreCursor);
  }

  @Test
  public void saveCursor_worksSuccessfullyOnGlobalCursor() {
    google.registry.model.common.Cursor cursor =
        google.registry.model.common.Cursor.createGlobal(
            CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    CursorDao.saveCursor(cursor, Cursor.GLOBAL);
    Cursor createdCursor = CursorDao.load(CursorType.RECURRING_BILLING);
    google.registry.model.common.Cursor dataStoreCursor =
        ofy()
            .load()
            .key(google.registry.model.common.Cursor.createGlobalKey(CursorType.RECURRING_BILLING))
            .now();
    assertThat(createdCursor.getCursorTime()).isEqualTo(cursor.getCursorTime());
    assertThat(cursor).isEqualTo(dataStoreCursor);
  }

  @Test
  public void saveCursors_worksSuccessfully() {
    createTlds("tld", "foo");
    google.registry.model.common.Cursor cursor1 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("tld"));
    google.registry.model.common.Cursor cursor2 =
        google.registry.model.common.Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, fakeClock.nowUtc(), Registry.get("foo"));
    google.registry.model.common.Cursor cursor3 =
        google.registry.model.common.Cursor.createGlobal(
            CursorType.RECURRING_BILLING, fakeClock.nowUtc());
    ImmutableMap<google.registry.model.common.Cursor, String> cursors =
        ImmutableMap.<google.registry.model.common.Cursor, String>builder()
            .put(cursor1, "tld")
            .put(cursor2, "foo")
            .put(cursor3, Cursor.GLOBAL)
            .build();
    CursorDao.saveCursors(cursors);
    Cursor createdCursor1 = CursorDao.load(CursorType.ICANN_UPLOAD_ACTIVITY, "tld");
    google.registry.model.common.Cursor dataStoreCursor1 =
        ofy()
            .load()
            .key(
                google.registry.model.common.Cursor.createKey(
                    CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("tld")))
            .now();
    assertThat(createdCursor1.getCursorTime()).isEqualTo(cursor1.getCursorTime());
    assertThat(cursor1).isEqualTo(dataStoreCursor1);
    Cursor createdCursor2 = CursorDao.load(CursorType.ICANN_UPLOAD_ACTIVITY, "foo");
    google.registry.model.common.Cursor dataStoreCursor2 =
        ofy()
            .load()
            .key(
                google.registry.model.common.Cursor.createKey(
                    CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("foo")))
            .now();
    assertThat(createdCursor2.getCursorTime()).isEqualTo(cursor2.getCursorTime());
    assertThat(cursor2).isEqualTo(dataStoreCursor2);
    Cursor createdCursor3 = CursorDao.load(CursorType.RECURRING_BILLING);
    google.registry.model.common.Cursor dataStoreCursor3 =
        ofy()
            .load()
            .key(google.registry.model.common.Cursor.createGlobalKey(CursorType.RECURRING_BILLING))
            .now();
    assertThat(createdCursor3.getCursorTime()).isEqualTo(cursor3.getCursorTime());
    assertThat(cursor3).isEqualTo(dataStoreCursor3);
  }
}
