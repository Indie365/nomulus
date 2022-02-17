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

package google.registry.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import google.registry.request.Modules;
import google.registry.request.UrlConnectionService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class FakeUrlConnectionService implements UrlConnectionService {

  private final HttpURLConnection connection;

  public FakeUrlConnectionService(HttpURLConnection connection) throws IOException {
    this.connection = connection;
  }

  @Override
  public HttpURLConnection createConnection(URL url) throws IOException {
    when(connection.getURL()).thenReturn(url);
    return connection;
  }
}
