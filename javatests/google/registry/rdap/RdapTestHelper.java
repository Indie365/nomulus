// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.util.Clock;

public class RdapTestHelper {

  private static final Gson GSON = new GsonBuilder().create();

  static JsonElement createJson(String... lines) {
    return GSON.fromJson(Joiner.on("\n").join(lines), JsonElement.class);
  }

  enum ContactNoticeType {
    NONE,
    DOMAIN,
    CONTACT
  }

  private static JsonObject createTosNotice(String linkBase) {
    return new Gson().toJsonTree(
        ImmutableMap.of(
            "title", "RDAP Terms of Service",
            "description",
                ImmutableList.of(
                    "By querying our Domain Database, you are agreeing to comply with these terms"
                        + " so please read them carefully.",
                    "Any information provided is 'as is' without any guarantee of accuracy.",
                    "Please do not misuse the Domain Database. It is intended solely for"
                        + " query-based access.",
                    "Don't use the Domain Database to allow, enable, or otherwise support the"
                        + " transmission of mass unsolicited, commercial advertising or"
                        + " solicitations.",
                    "Don't access our Domain Database through the use of high volume, automated"
                        + " electronic processes that send queries or data to the systems of any"
                        + " ICANN-accredited registrar.",
                    "You may only use the information contained in the Domain Database for lawful"
                        + " purposes.",
                    "Do not compile, repackage, disseminate, or otherwise use the information"
                        + " contained in the Domain Database in its entirety, or in any substantial"
                        + " portion, without our prior written permission.",
                    "We may retain certain details about queries to our Domain Database for the"
                        + " purposes of detecting and preventing misuse.",
                    "We reserve the right to restrict or deny your access to the database if we"
                        + " suspect that you have failed to comply with these terms.",
                    "We reserve the right to modify this agreement at any time."),
            "links",
                ImmutableList.of(
                    ImmutableMap.of(
                        "value", linkBase + "help/tos",
                        "rel", "alternate",
                        "href", "https://www.registry.tld/about/rdap/tos.html",
                        "type", "text/html")))).getAsJsonObject();
  }

  static void addNonDomainBoilerplateNotices(JsonObject jsonObject, String linkBase) {
    if (!jsonObject.has("notices")) {
      jsonObject.add("notices", new JsonArray());
    }
    JsonArray notices = jsonObject.getAsJsonArray("notices");

    notices.add(createTosNotice(linkBase));
    notices.add(
        new Gson()
            .toJsonTree(
                ImmutableMap.of(
                    "description",
                    ImmutableList.of(
                        "This response conforms to the RDAP Operational Profile for gTLD"
                            + " Registries and Registrars version 1.0"))));
  }

  static void addDomainBoilerplateNotices(JsonObject jsonObject, String linkBase) {
    if (!jsonObject.has("notices")) {
      jsonObject.add("notices", new JsonArray());
    }
    JsonArray notices = jsonObject.getAsJsonArray("notices");

    notices.add(createTosNotice(linkBase));
    notices.add(
        new Gson()
            .toJsonTree(
                ImmutableMap.of(
                    "description",
                    ImmutableList.of(
                        "This response conforms to the RDAP Operational Profile for gTLD"
                            + " Registries and Registrars version 1.0"))));
    notices.add(
        new Gson()
            .toJsonTree(
                ImmutableMap.of(
                    "title",
                    "Status Codes",
                    "description",
                    ImmutableList.of(
                        "For more information on domain status codes, please visit"
                            + " https://icann.org/epp"),
                    "links",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "value", "https://icann.org/epp",
                            "rel", "alternate",
                            "href", "https://icann.org/epp",
                            "type", "text/html")))));
    notices.add(
        new Gson()
            .toJsonTree(
                ImmutableMap.of(
                    "description",
                    ImmutableList.of(
                        "URL of the ICANN Whois Inaccuracy Complaint Form:"
                            + " https://www.icann.org/wicf"),
                    "links",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "value", "https://www.icann.org/wicf",
                            "rel", "alternate",
                            "href", "https://www.icann.org/wicf",
                            "type", "text/html")))));
  }

  static RdapJsonFormatter getTestRdapJsonFormatter(Clock clock) {
    RdapJsonFormatter rdapJsonFormatter = new RdapJsonFormatter();
    rdapJsonFormatter.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    rdapJsonFormatter.fullServletPath = "https://example.tld/rdap/";
    rdapJsonFormatter.clock = clock;
    rdapJsonFormatter.rdapTos =
        ImmutableList.of(
            "By querying our Domain Database, you are agreeing to comply with these"
                + " terms so please read them carefully.",
            "Any information provided is 'as is' without any guarantee of accuracy.",
            "Please do not misuse the Domain Database. It is intended solely for"
                + " query-based access.",
            "Don't use the Domain Database to allow, enable, or otherwise support the"
                + " transmission of mass unsolicited, commercial advertising or"
                + " solicitations.",
            "Don't access our Domain Database through the use of high volume, automated"
                + " electronic processes that send queries or data to the systems of"
                + " any ICANN-accredited registrar.",
            "You may only use the information contained in the Domain Database for"
                + " lawful purposes.",
            "Do not compile, repackage, disseminate, or otherwise use the information"
                + " contained in the Domain Database in its entirety, or in any"
                + " substantial portion, without our prior written permission.",
            "We may retain certain details about queries to our Domain Database for the"
                + " purposes of detecting and preventing misuse.",
            "We reserve the right to restrict or deny your access to the database if we"
                + " suspect that you have failed to comply with these terms.",
            "We reserve the right to modify this agreement at any time.");
    rdapJsonFormatter.rdapTosStaticUrl = "https://www.registry.tld/about/rdap/tos.html";
    return rdapJsonFormatter;
  }

  static String getLinkToNext(JsonObject results) {
    JsonArray notices = results.getAsJsonArray("notices");
    assertThat(notices).isNotNull();
    return Streams.stream(notices)
        .map(notice -> notice.getAsJsonObject())
        .filter(notice -> notice.has("title"))
        .filter(notice -> notice.get("title").getAsString().equals("Navigation Links"))
        .flatMap(notice -> Streams.stream(notice.getAsJsonArray("links")))
        .map(link -> link.getAsJsonObject())
        .filter(link -> link.get("rel").getAsString().equals("next"))
        .map(link -> link.get("href").getAsString())
        .findAny().orElse(null);
  }

  static JsonObject wrapInSearchReply(String searchResultsName, JsonObject obj) {
    JsonArray searchResults = new JsonArray();
    searchResults.add(obj);
    JsonObject reply = new JsonObject();

    reply.add(searchResultsName, searchResults);
    reply.add("rdapConformance", obj.getAsJsonArray("rdapConformance"));
    obj.remove("rdapConformance");
    return reply;
  }
}
