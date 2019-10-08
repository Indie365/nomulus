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

package google.registry.model.transaction;

import com.google.common.flogger.FluentLogger;
import google.registry.util.Clock;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceException;
import org.joda.time.DateTime;

/** Implementation of {@link JpaTransactionManager} for JPA compatible database. */
public class JpaTransactionManagerImpl implements JpaTransactionManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // EntityManagerFactory is thread safe.
  private final EntityManagerFactory emf;
  private final Clock clock;
  // TODO(shicong): Investigate alternatives for managing transaction information. ThreadLocal adds
  //  an unnecessary restriction that each request has to be processed by one thread synchronously.
  private final ThreadLocal<TransactionInfo> transactionInfo =
      ThreadLocal.withInitial(TransactionInfo::new);

  public JpaTransactionManagerImpl(EntityManagerFactory emf, Clock clock) {
    this.emf = emf;
    this.clock = clock;
  }

  @Override
  public EntityManager getEntityManager() {
    if (transactionInfo.get().entityManager == null) {
      throw new PersistenceException(
          "No EntityManager has been initialized. getEntityManager() must be invoked in the scope"
              + " of a transaction");
    }
    return transactionInfo.get().entityManager;
  }

  @Override
  public boolean inTransaction() {
    return transactionInfo.get().inTransaction;
  }

  @Override
  public void assertInTransaction() {
    if (!inTransaction()) {
      throw new PersistenceException("Not in a transaction");
    }
  }

  @Override
  public <T> T transact(Work<T> work) {
    // TODO(shicong): Investigate removing transactNew functionality after migration as it may
    //  be same as this one.
    if (inTransaction()) {
      return work.run();
    }
    TransactionInfo txnInfo = transactionInfo.get();
    txnInfo.entityManager = emf.createEntityManager();
    EntityTransaction txn = txnInfo.entityManager.getTransaction();
    try {
      txn.begin();
      txnInfo.inTransaction = true;
      txnInfo.transactionTime = clock.nowUtc();
      T result = work.run();
      txn.commit();
      return result;
    } catch (Throwable transactionException) {
      String rollbackMessage;
      try {
        txn.rollback();
        rollbackMessage = "transaction rolled back";
      } catch (Throwable rollbackException) {
        logger.atSevere().withCause(rollbackException).log("Rollback failed, suppressing error");
        rollbackMessage = "transaction rollback failed";
      }
      throw new PersistenceException(
          "Error during transaction, " + rollbackMessage, transactionException);
    } finally {
      txnInfo.clear();
    }
  }

  @Override
  public void transact(Runnable work) {
    transact(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public <T> T transactNew(Work<T> work) {
    // TODO(shicong): Implements the functionality to start a new transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public void transactNew(Runnable work) {
    // TODO(shicong): Implements the functionality to start a new transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T transactNewReadOnly(Work<T> work) {
    // TODO(shicong): Implements read only transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    // TODO(shicong): Implements read only transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T doTransactionless(Work<T> work) {
    // TODO(shicong): Implements doTransactionless.
    throw new UnsupportedOperationException();
  }

  @Override
  public DateTime getTransactionTime() {
    assertInTransaction();
    TransactionInfo txnInfo = transactionInfo.get();
    if (txnInfo.transactionTime == null) {
      throw new PersistenceException("In a transaction but transactionTime is null");
    }
    return txnInfo.transactionTime;
  }

  private static class TransactionInfo {
    EntityManager entityManager;
    boolean inTransaction = false;
    DateTime transactionTime;

    private void clear() {
      inTransaction = false;
      transactionTime = null;
      if (entityManager != null) {
        // Close this EntityManager just let the connection pool be able to reuse it, it doesn't
        // close the underlying database connection.
        entityManager.close();
        entityManager = null;
      }
    }
  }
}
