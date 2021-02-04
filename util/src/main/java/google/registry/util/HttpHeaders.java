// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

/** Utility class of HTTP header names. */
public final class HttpHeaders {

  /**
   * Header used to pass a full SSL certificate from the proxy to the backend.
   *
   * <p>This header contains the SSL certificate encoded to a string. It is used to pass the client
   * certificate used for login to the backend for validation.
   */
  public static final String FULL_CERTIFICATE = "X-SSL-Full-Certificate";

  /** Header used to pass the certificate hash from the proxy to the backend. */
  public static final String CERTIFICATE_HASH = "X-SSL-Certificate";

  /** Header used to pass the client IP address from the proxy to the backend. */
  public static final String IP_ADDRESS = "X-Forwarded-For";

  /** Header passed from backend to proxy to indicate that a client has successfully logged in. */
  public static final String LOGGED_IN = "Logged-In";

  /** Header passed from backend to proxy to indicate that an EPP session should be closed. */
  public static final String EPP_SESSION = "Epp-Session";

  private HttpHeaders() {}
}
