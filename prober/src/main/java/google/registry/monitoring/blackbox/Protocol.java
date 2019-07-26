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

package google.registry.monitoring.blackbox;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.netty.channel.ChannelHandler;
import io.netty.util.AttributeKey;
import javax.inject.Provider;

/**
 * {@link AutoValue} class that stores all unchanged variables necessary for type of connection.
 */
@AutoValue
public abstract class Protocol {

  /** {@link AttributeKey} that lets channel reference {@link Protocol} that created it. */
  public static final AttributeKey<Protocol> PROTOCOL_KEY = AttributeKey.valueOf("PROTOCOL_KEY");

  abstract String name();

  public abstract int port();

  /** The {@link ChannelHandler} providers to use for the protocol, in order. */
  abstract ImmutableList<Provider<? extends ChannelHandler>> handlerProviders();

  /** Boolean that notes if connection associated with Protocol is persistent.*/
  abstract boolean persistentConnection();

  public static Builder builder() {
    return new AutoValue_Protocol.Builder();
  }

  @AutoValue.Builder
  public static abstract class Builder {

    public abstract Builder setName(String value);

    public abstract Builder setPort(int num);

    public abstract Builder setHandlerProviders(ImmutableList<Provider<? extends ChannelHandler>> providers);

    public abstract Builder setPersistentConnection(boolean value);

    public abstract Protocol build();
  }

  @Override
  public String toString() {
    return String.format(
        "Protocol with name: %s, port: %d, providers: %s, and persistent connection: %s",
        name(),
        port(),
        handlerProviders(),
        persistentConnection()
    );
  }
}

