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
package org.apache.grails.github

import com.github.tomakehurst.wiremock.client.WireMock
import org.apache.grails.github.mocks.GitHubDockerAction
import org.apache.grails.github.mocks.GitHubVersion
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.Network
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class ValidateActionsSpec extends Specification {

    private static final String APPROVED_SHA = '0723195856401067f7a2779048b490ace7a47d7c'
    private static final String OTHER_APPROVED_SHA = '9c971963bec38e04b3d30dcc455b5382be2fdbfb'
    private static final String UNAPPROVED_SHA = 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef'

    // Mirrors the shape of https://github.com/apache/infrastructure-actions/blob/main/approved_patterns.yml
    private static final String APPROVED_PATTERNS = """\
# This file was generated from actions.yml by gateway/gateway.py.
# DO NOT UPDATE MANUALLY. Update /actions.yml instead.
- gradle/actions/setup-gradle@${APPROVED_SHA}
- gradle/actions/setup-gradle@${OTHER_APPROVED_SHA}
- softprops/action-gh-release@*
"""

    // Mirrors the shape of https://github.com/apache/infrastructure-actions/blob/main/actions.yml
    private static final String ACTIONS = """\
# Main configuration file. We manually add trusted actions here.
gradle/actions/setup-gradle:
  ${APPROVED_SHA}:
    tag: v5.0.2
    # Projects have deliberately chosen to not use v6 of the setup-gradle action.
  ${OTHER_APPROVED_SHA}:
    tag: 'v6.3.0'
    expires_at: 2026-12-31
softprops/action-gh-release:
  '*':
    keep: true
"""

    @Shared
    @AutoCleanup
    Network net = Network.newNetwork()

    @AutoCleanup
    GitHubDockerAction action

    /**
     * Creates the action, writes the given workflow files into the workspace and serves the allowlist files from the
     * WireMock server. Returns the container environment, pointed at the mocked allowlist. A null allowlist body is
     * served as a 404.
     */
    private Map<String, String> prepare(Map<String, String> workflows,
                                        String approvedPatterns = APPROVED_PATTERNS,
                                        String actions = ACTIONS,
                                        String workflowsDir = '.github/workflows') {
        GitHubVersion release = new GitHubVersion(version: '7.0.0', tagName: null, targetBranch: 'main', targetVersion: '7.0.0-SNAPSHOT')
        action = new GitHubDockerAction('validate-actions', release)

        Path dir = action.workspacePath.resolve(workflowsDir)
        Files.createDirectories(dir)
        workflows.each { String name, String content -> dir.resolve(name).toFile().text = content }

        stubFile('/approved_patterns.yml', approvedPatterns)
        stubFile('/actions.yml', actions)

        Map<String, String> env = action.defaultEnvironment
        env['APPROVED_PATTERNS_URL'] = "${action.apiMock.urlForContainer}/approved_patterns.yml" as String
        env['ACTIONS_URL'] = "${action.apiMock.urlForContainer}/actions.yml" as String
        env
    }

    private void stubFile(String path, String body) {
        def response = body == null ?
                WireMock.aResponse().withStatus(404) :
                WireMock.aResponse().withStatus(200).withHeader('Content-Type', 'text/plain').withBody(body)
        action.apiMock.githubApi.stubFor(WireMock.get(WireMock.urlEqualTo(path)).willReturn(response))
    }

    def 'success - every uses entry is compliant'() {
        given:
        String ci = '''\
name: "CI"
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6.0.2
      - uses: github/codeql-action/init@v3
      - uses: apache/infrastructure-actions/stash/save@v1
      - name: "Setup Gradle"
        uses: gradle/actions/setup-gradle@0723195856401067f7a2779048b490ace7a47d7c # v5.0.2
      - uses: "softprops/action-gh-release@v3.0.0"
      - uses: ./local-action
      - uses: docker://alpine:3
'''
        String release = '''\
name: "Release"
on: release
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: 'gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb' # v6.3.0 (pinned on purpose)
'''
        def env = prepare(['ci.yml': ci, 'release.yaml': release, 'README.md': '# not a workflow\n'])

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L

        and:
        action.getActionGroupLogs('Setup').contains('Found 2 workflow file(s)')
        action.getActionGroupLogs('Fetch the ASF approved list').contains('Loaded 2 approved action(s) and 2 recorded tag(s)')
        action.getActionGroupLogs('Validate workflow files').contains('Checking .github/workflows/ci.yml')
        action.getActionGroupLogs('Validate workflow files').contains('Checking .github/workflows/release.yaml')
        action.actionLogs.contains('Checked 2 workflow file(s) - all compliant.')
        !action.actionLogs.contains('::error')
        !action.actionLogs.contains('::warning')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'failure - every policy violation is reported as an error annotation'() {
        given:
        String bad = '''\
name: "Bad"
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6.0.2
      - uses: some-org/unlisted-action@v1
      - uses: gradle/actions/setup-gradle@v5.0.2
      - uses: gradle/actions/setup-gradle@deadbeefdeadbeefdeadbeefdeadbeefdeadbeef # v5.0.2
      - uses: some-org/no-ref
'''
        def env = prepare(['bad.yml': bad])

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        def e = thrown(ContainerLaunchException)
        e.message.contains('Container startup failed')

        and:
        String logs = action.actionLogs
        logs.contains("::error file=.github/workflows/bad.yml,line=8::'some-org/unlisted-action' is not on the ASF approved list")
        logs.contains("::error file=.github/workflows/bad.yml,line=9::'gradle/actions/setup-gradle@v5.0.2' must be pinned to a full SHA (allowlist has 2 approved SHA(s) for 'gradle/actions/setup-gradle')")
        logs.contains("::error file=.github/workflows/bad.yml,line=10::SHA for 'gradle/actions/setup-gradle' is not among the 2 approved SHA(s) in the allowlist")
        logs.contains("::error file=.github/workflows/bad.yml,line=11::unrecognised form 'some-org/no-ref' (missing @ref)")
        logs.contains('ERROR: 4 GitHub Actions policy violation(s)')
        !logs.contains('all compliant')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - a missing or mismatched trailing tag comment only warns'() {
        given:
        String ci = '''\
name: "CI"
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: gradle/actions/setup-gradle@0723195856401067f7a2779048b490ace7a47d7c # v5.0.1
      - uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb
'''
        def env = prepare(['ci.yml': ci])

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L

        and:
        String logs = action.actionLogs
        logs.contains("::warning file=.github/workflows/ci.yml,line=7::trailing comment '# v5.0.1' does not match the ASF-recorded tag '# v5.0.2' for this SHA")
        logs.contains("::warning file=.github/workflows/ci.yml,line=8::missing trailing '# v6.3.0' comment for SHA-pinned action")
        logs.contains('Checked 1 workflow file(s) - all compliant.')
        !logs.contains('::error')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'success - validates a custom workflows directory'() {
        given:
        String ci = '''\
name: "CI"
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: some-org/unlisted-action@v1
'''
        def env = prepare(['ci.yml': ci], APPROVED_PATTERNS, ACTIONS, 'ci/workflows')
        env['WORKFLOWS_DIR'] = 'ci/workflows/'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        thrown(ContainerLaunchException)

        and:
        action.getActionGroupLogs('Setup').contains('Workflows directory: ci/workflows')
        action.actionLogs.contains("::error file=ci/workflows/ci.yml,line=7::'some-org/unlisted-action' is not on the ASF approved list")

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'failure - the workflows directory does not exist'() {
        given:
        def env = prepare([:])
        env['WORKFLOWS_DIR'] = '.github/missing'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        thrown(ContainerLaunchException)

        and:
        action.actionLogs.contains("::error::Workflows directory '.github/missing' does not exist")

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'failure - the approved list cannot be fetched'() {
        given:
        String ci = '''\
name: "CI"
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6.0.2
'''
        def env = prepare(['ci.yml': ci], null)

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        thrown(ContainerLaunchException)

        and:
        action.actionLogs.contains('::error::Failed to fetch ')
        action.actionLogs.contains('/approved_patterns.yml')
        !action.actionLogs.contains('Checking ')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }

    def 'failure - an empty approved list is rejected rather than trusted'() {
        given:
        String ci = '''\
name: "CI"
on: push
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6.0.2
'''
        def env = prepare(['ci.yml': ci], '<html>not the allowlist</html>', '<html>not the mapping</html>')

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        thrown(ContainerLaunchException)

        and:
        action.actionLogs.contains('::error::No approved patterns were parsed from ')

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
    }
}
