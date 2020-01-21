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

import static com.google.appengine.api.search.checkers.Preconditions.checkNotNull;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.model.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.common.Cursor.CursorType;
import google.registry.schema.cursor.Cursor.CursorId;
import java.util.List;

/** Data access object class for {@link Cursor}. */
public class CursorDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static void save(Cursor cursor) {
    jpaTm()
        .transact(
            () -> {
              jpaTm().getEntityManager().merge(cursor);
            });
  }

  public static void saveAll(ImmutableSet<Cursor> cursors) {
    jpaTm()
        .transact(
            () -> {
              for (Cursor cursor : cursors) {
                jpaTm().getEntityManager().merge(cursor);
              }
            });
  }

  public static Cursor load(CursorType type, String scope) {
    checkNotNull(scope, "The scope of the cursor to load cannot be null");
    checkNotNull(type, "The type of the cursor to load cannot be null");
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().find(Cursor.class, new CursorId(type, scope)));
  }

  /** If no scope is given, use {@link Cursor.GLOBAL} as the scope. */
  public static Cursor load(CursorType type) {
    checkNotNull(type, "The type of the cursor to load must be specified");
    return load(type, Cursor.GLOBAL);
  }

  public static List<Cursor> loadAll() {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery("SELECT cursor FROM Cursor cursor", Cursor.class)
                    .getResultList());
  }

  public static List<Cursor> loadByType(CursorType type) {
    checkNotNull(type, "The type of the cursors to load must be specified");
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery(
                        "SELECT cursor FROM Cursor cursor WHERE cursor.type = :type", Cursor.class)
                    .setParameter("type", type)
                    .getResultList());
  }

  /**
   * This writes the given cursor to Datastore. If the save to Datastore succeeds, then a new
   * Schema/Cursor object is created and attempted to save to Cloud SQL. If the save to Cloud SQL
   * fails, the exception is logged, but does not cause the method to fail.
   */
  public static void saveCursor(google.registry.model.common.Cursor cursor, String scope) {
    tm().transact(() -> ofy().save().entity(cursor));
    CursorType type = cursor.getType();
    try {
      Cursor cloudSqlCursor = Cursor.create(type, scope, cursor.getCursorTime());
      save(cloudSqlCursor);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Error saving cursor to Cloud SQL.");
    }
  }

  /**
   * This takes in multiple cursors and saves them to Datastore. If those saves succeed, it attempts
   * to save the cursors to Cloud SQL. If the save to Cloud SQL fails, the exception is logged, but
   * does not cause the method to fail.
   */
  public static void saveCursors(
      ImmutableMap<google.registry.model.common.Cursor, String> cursors) {
    // Save the cursors to Datastore
    tm().transact(
            () -> {
              for (google.registry.model.common.Cursor cursor : cursors.keySet()) {
                ofy().save().entity(cursor);
              }
            });
    // Try to save the cursors to Cloud SQL
    try {
      ImmutableSet.Builder<Cursor> cloudSqlCursors = new ImmutableSet.Builder<>();
      cursors
          .keySet()
          .forEach(
              cursor ->
                  cloudSqlCursors.add(
                      Cursor.create(
                          cursor.getType(), cursors.get(cursor), cursor.getCursorTime())));
      saveAll(cloudSqlCursors.build());
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Error saving cursor to Cloud SQL.");
    }
  }
}
