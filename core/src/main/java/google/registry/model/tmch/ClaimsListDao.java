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

package google.registry.model.tmch;

import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.flogger.FluentLogger;
import google.registry.schema.tmch.ClaimsList;
import javax.persistence.EntityManager;

/** Data access object for {@link ClaimsList}. */
public class ClaimsListDao {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static void save(ClaimsList claimsList) {
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(claimsList));
  }

  /**
   * Try to save the given {@link ClaimsList} into Cloud SQL. If the save fails, the error will be
   * logged but no exception will be thrown.
   *
   * <p>This method is used during the dual-write phase of database migration as Datastore is still
   * the authoritative database.
   */
  public static void trySave(ClaimsList claimsList) {
    try {
      ClaimsListDao.save(claimsList);
      logger.atInfo().log(
          "Inserted %,d claims into Cloud SQL, created at %s",
          claimsList.getLabelsToKeys().size(), claimsList.getTmdbGenerationTime());
    } catch (Throwable e) {
      logger.atSevere().withCause(e).log("Error inserting claims into Cloud SQL");
    }
  }

  /**
   * Returns the current revision of the {@link ClaimsList} in Cloud SQL. Throws exception if there
   * is no claims in the table.
   */
  public static ClaimsList getCurrent() {
    return jpaTm()
        .transact(
            () -> {
              EntityManager em = jpaTm().getEntityManager();
              Long revisionId =
                  em.createQuery("SELECT MAX(revisionId) FROM ClaimsList", Long.class)
                      .getSingleResult();
              return em.createQuery(
                      "FROM ClaimsList cl LEFT JOIN FETCH cl.labelsToKeys WHERE cl.revisionId ="
                          + " :revisionId",
                      ClaimsList.class)
                  .setParameter("revisionId", revisionId)
                  .getSingleResult();
            });
  }

  private ClaimsListDao() {}
}
