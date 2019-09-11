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

import static org.joda.time.DateTimeZone.UTC;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceException;
import org.joda.time.DateTime;

/** Database implementation of {@link TransactionManager}. */
public class DatabaseTransactionManager implements TransactionManager {

  // EntityManagerFactory is thread safe.
  private final EntityManagerFactory emf;
  private final ThreadLocal<TransactionInfo> transactionInfo =
      ThreadLocal.withInitial(TransactionInfo::new);

  DatabaseTransactionManager(EntityManagerFactory emf) {
    this.emf = emf;
  }

  public EntityManager getEntityManager() {
    if (transactionInfo.get().entityManager == null) {
      throw new PersistenceException("No EntityManager has been initialized");
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
    TransactionInfo local = transactionInfo.get();
    local.entityManager = emf.createEntityManager();
    EntityTransaction txn = local.entityManager.getTransaction();
    if (txn.isActive()) {
      throw new PersistenceException(
          "Error starting the transaction as the previous one is still active");
    }
    try {
      txn.begin();
      local.inTransaction = true;
      local.transactionTime = DateTime.now(UTC);
      T result = work.run();
      txn.commit();
      return result;
    } catch (Throwable e) {
      txn.rollback();
      throw e;
    } finally {
      local.inTransaction = false;
      local.transactionTime = null;
      // Close this EntityManager just let the connection pool be able to reuse it, it doesn't close
      // the underlying database connection.
      local.entityManager.close();
      local.entityManager = null;
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
    // TODO(shicong): Implements read only transaction.
    return null;
  }

  @Override
  public void transactNew(Runnable work) {
    // TODO(shicong): Implements read only transaction.
  }

  @Override
  public <R> R transactNewReadOnly(Work<R> work) {
    // TODO(shicong): Implements read only transaction.
    return null;
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    // TODO(shicong): Implements read only transaction.
  }

  @Override
  public <R> R doTransactionless(Work<R> work) {
    if (inTransaction()) {
      throw new PersistenceException();
    }
    TransactionInfo local = transactionInfo.get();
    local.entityManager = emf.createEntityManager();
    try {
      return work.run();
    } finally {
      local.entityManager.close();
      local.entityManager = null;
    }
  }

  @Override
  public DateTime getTransactionTime() {
    assertInTransaction();
    TransactionInfo local = transactionInfo.get();
    if (local.transactionTime == null) {
      throw new PersistenceException("In a transaction but transactionTime is null");
    }
    return local.transactionTime;
  }

  private static class TransactionInfo {
    EntityManager entityManager;
    boolean inTransaction = false;
    DateTime transactionTime;
  }
}
