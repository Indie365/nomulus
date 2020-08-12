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

package google.registry.model.reporting;

import com.google.common.collect.ImmutableList;
import google.registry.persistence.transaction.JpaTransactionManager;
import org.joda.time.LocalDate;

/** Data access object for {@link google.registry.model.reporting.Spec11ThreatMatch}. */
public class Spec11ThreatMatchDao {

  /**
   * Delete all entries with the specified date from the database. A JpaTransactionManager is passed
   * in because this method is called from a BEAM pipeline and we don't know where it's coming from.
   */
  public static void deleteEntriesByDate(JpaTransactionManager jpaTm, LocalDate date) {
    jpaTm.assertInTransaction();
    jpaTm
        .getEntityManager()
        .createQuery("DELETE FROM Spec11ThreatMatch WHERE check_date = :date")
        .setParameter("date", date.toString())
        .executeUpdate();
  }

  /**
   * Query the database and return a list of Spec11ThreatMatches with the specified date. A
   * JpaTransactionManager is passed in because this method is called from a BEAM pipeline and we
   * don't know where it's coming from.
   */
  public static ImmutableList<String> loadEntriesByDate(
      JpaTransactionManager jpaTm, LocalDate date) {
    jpaTm.assertInTransaction();
    return ImmutableList.copyOf(
        jpaTm
            .getEntityManager()
            .createQuery(
                "SELECT domainName FROM Spec11ThreatMatch WHERE check_date = :date", String.class)
            .setParameter("date", date.toString())
            .getResultList());
  }
}
