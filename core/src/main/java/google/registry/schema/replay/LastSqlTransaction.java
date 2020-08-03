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

package google.registry.schema.replay;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.ImmutableObject;

/**
 * Datastore entity to keep track of the last SQL transaction imported into the datastore.
 */
@Entity
public class LastSqlTransaction extends ImmutableObject {

  /** The key for this singleton. */
  public static final Key<LastSqlTransaction> KEY = Key.create(LastSqlTransaction.class, 1);

  @SuppressWarnings("unused")
  @Id
  private long id = 1;

  private long transactionId;

  LastSqlTransaction() {
    transactionId = -1;
  }

  void setTransactionId(long transactionId) {
    checkArgument(
        transactionId > this.transactionId,
        "New transaction id (%s) must be greater than the current transaction id (%s)",
        transactionId,
        this.transactionId);
    this.transactionId = transactionId;
  }

  long getTransactionId() {
    return transactionId;
  }

  /**
   * Loads the instance.
   *
   * <p>Must be called within a transaction.
   *
   * <p>Creates a new instance of the singleton if it is not already present in the datastore,
   */
  static LastSqlTransaction load() {
    LastSqlTransaction result = ofy().load().key(KEY).now();
    return result == null ? new LastSqlTransaction() : result;
  }

  /** Stores the instance in datastore.
   *
   * <p>Must be called within the same transaction as load().
   */
  void store() {
    ofy().saveWithoutBackup().entity(this);
  }
}
