/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.grails.github.mocks

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration

class GitHubApiMock implements Closeable {

    WireMockServer githubApi
    GitHubVersion version

    GitHubApiMock(GitHubVersion version) {
        githubApi = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        this.version = version
    }

    private void mockDefault() {
        githubApi.stubFor(WireMock.patch(WireMock.urlEqualTo("/repos/${version.repository}/releases/42"))
                .withHeader("Authorization", WireMock.matching("(?i)Bearer\\s+.+")) // accept any bearer token
                .withHeader("Content-Type", WireMock.containing("application/json"))
                .withRequestBody(WireMock.equalToJson("{\"draft\": false}", true, true)) // ignore spacing/field order
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":123,\"draft\":false}")))


        githubApi.stubFor(WireMock.patch(WireMock.urlEqualTo("/repos/${version.repository}/milestones/1"))
                .withHeader("Authorization", WireMock.matching("(?i)Bearer\\s+.+")) // accept any bearer token
                .withHeader("Content-Type", WireMock.containing("application/json"))
                .withRequestBody(WireMock.equalToJson("{\"state\": \"closed\"}", true, true)) // ignore spacing/field order
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"state\":closed}")))

        githubApi.stubFor(WireMock.get(WireMock.urlPathEqualTo("/repos/${version.repository}/milestones"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
[
  {
    "url": "https://api.github.com/repos/${version.repository}/milestones/1",
    "html_url": "https://github.com/${version.repository}/milestone/1",
    "labels_url": "https://api.github.com/repos/${version.repository}/milestones/1/labels",
    "id": 13156954,
    "node_id": "MI_kwDOJ-ZrdM4AyMJa",
    "number": 1,
    "title": "${version.version}",
    "description": null,
    "creator": {
      "login": "jamesfredley",
      "id": 6969748,
      "node_id": "MDQ6VXNlcjY5Njk3NDg=",
      "avatar_url": "https://avatars.githubusercontent.com/u/6969748?v=4",
      "gravatar_id": "",
      "url": "https://api.github.com/users/jamesfredley",
      "html_url": "https://github.com/jamesfredley",
      "followers_url": "https://api.github.com/users/jamesfredley/followers",
      "following_url": "https://api.github.com/users/jamesfredley/following{/other_user}",
      "gists_url": "https://api.github.com/users/jamesfredley/gists{/gist_id}",
      "starred_url": "https://api.github.com/users/jamesfredley/starred{/owner}{/repo}",
      "subscriptions_url": "https://api.github.com/users/jamesfredley/subscriptions",
      "organizations_url": "https://api.github.com/users/jamesfredley/orgs",
      "repos_url": "https://api.github.com/users/jamesfredley/repos",
      "events_url": "https://api.github.com/users/jamesfredley/events{/privacy}",
      "received_events_url": "https://api.github.com/users/jamesfredley/received_events",
      "type": "User",
      "user_view_type": "public",
      "site_admin": false
    },
    "open_issues": 0,
    "closed_issues": 1,
    "state": "open",
    "created_at": "2025-06-27T13:35:49Z",
    "updated_at": "2025-06-27T15:00:12Z",
    "due_on": null,
    "closed_at": null
  }
]
""")))
    }

    String getUrlForContainer() {
        "http://host.testcontainers.internal:${githubApi.port()}" as String
    }

    void start(boolean mockDefaultBehavior = true) {
        githubApi.start()
        if (mockDefaultBehavior) {
            mockDefault()
        }
    }

    void close() {
        githubApi.stop()
    }
}
