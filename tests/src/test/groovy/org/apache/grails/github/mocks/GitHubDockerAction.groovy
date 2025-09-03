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

import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import org.apache.grails.github.mocks.cli.CliMock
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.ToStringConsumer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.images.builder.ImageFromDockerfile

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@Slf4j
class GitHubDockerAction implements Closeable {

    File baseDir
    String actionName

    private GitHubVersion version
    GitHubApiMock apiMock

    GenericContainer container
    private ToStringConsumer containerLogBuffer = new ToStringConsumer()

    GitHubDockerAction(String actionName, GitHubVersion version, CliMock ... cliMocks) {
        baseDir = Files.createTempDirectory('github-action-workspace').toFile()

        // jgit will attempt to use the user.home for git configuration, use the test baseDir to ensure isolation
        System.setProperty('user.home', baseDir.absolutePath)
        this.actionName = actionName
        this.version = version
        getWorkspacePath().toFile().mkdirs()
        getWorkflowPath().toFile().mkdirs()

        // api mock must be started to know the url to include in the event json
        apiMock = new GitHubApiMock(version)
        apiMock.start()
        if(version.tagName) {
            getWorkflowPath().resolve('event.json').toFile().text = getEventJson()
        }

        getMockPath().toFile().mkdirs()
        for (CliMock cliMock : cliMocks) {
            def mockPath = getMockPath().resolve(cliMock.fileName)
            mockPath.toFile().text = cliMock.fileContents
            mockPath.toFile().executable = true
        }
    }

    Path getMockPath() {
        baseDir.toPath().resolve('path')
    }

    Path getWorkspacePath() {
        baseDir.toPath().resolve('workspace')
    }

    Path getWorkflowPath() {
        baseDir.toPath().resolve('workflow')
    }

    Map<String, String> getDefaultEnvironment() {
        Map<String, String> env = [:]

        env['GITHUB_USER_NAME'] = 'octocat'
        env['GITHUB_ACTOR'] = 'octocat_actor'
        env['GITHUB_WORKSPACE'] = '/github/workspace' // always the working directory
        env['GITHUB_ACTION_PATH'] = '/'
        env['GITHUB_REPOSITORY'] = version.repository
        env['GITHUB_TOKEN'] = 'ghp_randomtokenhere1234567890'
        env['GITHUB_EVENT_PATH'] = '/github/workflow/event.json'
        if(version.tagName) {
            env['GITHUB_REF'] = "refs/tags/${version.tagName}" as String
        }
        else {
            env['GITHUB_REF'] = "refs/heads/${version.targetBranch}" as String
        }
        env['GITHUB_OUTPUT'] = '/github/github-output.txt'
        env['GITHUB_ENV'] = '/github/github-env'
        env['PATH'] = '/github/path:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
        env['GITHUB_API_URL'] = apiMock.urlForContainer
        env['GITHUB_URL_BASE'] = 'github.com'

        env
    }

    void createContainer(Map<String, String> env, Network net) {
        container = new GenericContainer(createDockerImage())
                .withCreateContainerCmdModifier { cmd ->
                    cmd.hostConfig.withAutoRemove(false)
                }
                .withEnv(env)
                .withNetwork(net).withNetworkAliases('action-runner')
                .withWorkingDirectory('/github/workspace')
                .withStartupCheckStrategy(
                        new OneShotStartupCheckStrategy()
                                .withTimeout(Duration.ofMinutes(3))
                )
        .withAccessToHost(true)

        container.withFileSystemBind(baseDir.absolutePath, '/github')

        Testcontainers.exposeHostPorts(apiMock.githubApi.port())

        container.withLogConsumer(containerLogBuffer)
    }

    @Memoized
    String getActionLogs() {
        if(container == null) {
            throw new IllegalStateException('Container has not been created.')
        }

        if(container.running) {
            throw new IllegalStateException('Container is still running, logs are not available yet.')
        }

        containerLogBuffer.toUtf8String()
    }

    boolean isLogGroupPresent(String groupName) {
        String allLogs = actionLogs

        def markingString = "::group::$groupName" as String
        int idx = allLogs.indexOf(markingString)
        return idx >= 0
    }

    @Memoized
    String getActionGroupLogs(String groupName) {
        String allLogs = actionLogs

        def markingString = "::group::$groupName" as String
        int idx = allLogs.indexOf(markingString)
        if(idx < 0) {
            throw new IllegalStateException("No logs found for group: $groupName")
        }

        def startIndex =  idx + markingString.length()
        allLogs.substring(startIndex, allLogs.indexOf("::endgroup::", startIndex)).trim()
    }

    void runAction() {
        if(container == null) {
            throw new IllegalStateException('Container has not been created.')
        }

        container.start()
    }

    void addCommandArgs(String ... args) {
        if(container == null) {
            throw new IllegalStateException('Container has not been created.')
        }

        container.withCommand(args)
    }

    Long getActionExitCode() {
        if (container == null) {
            throw new IllegalStateException('Container has not been created.')
        }
        return container.currentContainerInfo.state.exitCodeLong
    }

    private ImageFromDockerfile createDockerImage() {
        def gradleProjectDirectory = System.getProperty('GITHUB_ACTION_PROJECT_DIR') as String
        def dockerfile = Path.of(gradleProjectDirectory).resolve(actionName).resolve('Dockerfile')
        new ImageFromDockerfile()
                .withDockerfile(dockerfile)
    }

    void close() {
        try {
            container?.stop()
        }
        catch(e) {
            log.warn("Failed to stop container: ${e.message}", e)
        }

        try {
            apiMock?.close()
        }
        catch(e) {
            log.warn("Failed to close API mock: ${e.message}", e)
        }

        if (baseDir && baseDir.exists()) {
            baseDir.deleteDir()
        }
    }

    private String getEventJson() {
        """
{
  "action": "published",
  "release": {
    "url": "${apiMock.getUrlForContainer()}/repos/${version.repository}/releases/42",
    "assets_url": "${apiMock.getUrlForContainer()}/repos/${version.repository}/releases/42/assets",
    "upload_url": "https://uploads.github.com/repos/${version.repository}/releases/42/assets{?name,label}",
    "html_url": "https://github.com/${version.repository}/releases/tag/${version.tagName}",
    "id": 42,
    "node_id": "MDc6UmVsZWFzZTQy",
    "tag_name": "${version.tagName}",
    "target_commitish": "refs/heads/${version.tagName}",
    "name": "${version.version}",
    "draft": false,
    "prerelease": false,
    "created_at": "2025-08-14T12:00:00Z",
    "published_at": "2025-08-14T12:05:00Z",
    "author": {
      "login": "jdoe",
      "id": 1001,
      "node_id": "MDQ6VXNlcjEwMDE=",
      "html_url": "https://github.com/jdoe",
      "type": "User",
      "site_admin": false
    },
    "tarball_url": "${apiMock.getUrlForContainer()}/repos/${version.repository}/tarball/${version.tagName}",
    "zipball_url": "${apiMock.getUrlForContainer()}/repos/${version.repository}/zipball/${version.tagName}",
    "body": "Changelog goes here",
    "assets": [
      {
        "url": "${apiMock.getUrlForContainer()}/repos/${version.repository}/releases/assets/1",
        "browser_download_url": "https://github.com/${version.repository}/releases/download/${version.tagName}/widgets-${version.version}-linux-amd64.zip",
        "id": 1,
        "node_id": "MDEyOlJlbGVhc2VBc3NldDE=",
        "name": "widgets-${version.version}-linux-amd64.zip",
        "label": "Linux build",
        "state": "uploaded",
        "content_type": "application/zip",
        "size": 123456,
        "download_count": 0,
        "created_at": "2025-08-14T12:03:00Z",
        "updated_at": "2025-08-14T12:03:00Z",
        "uploader": {
          "login": "jdoe",
          "id": 1001,
          "type": "User",
          "site_admin": false
        }
      }
    ],
    "discussion_url": null
  },
  "repository": {
    "id": 123456789,
    "node_id": "MDEwOlJlcG9zaXRvcnkxMjM0NTY3ODk=",
    "name": "widgets",
    "full_name": "${version.repository}",
    "private": false,
    "html_url": "https://github.com/${version.repository}",
    "url": "${apiMock.getUrlForContainer()}/repos/${version.repository}",
    "default_branch": "main",
    "visibility": "public",
    "owner": {
      "login": "${version.organization}",
      "id": 42,
      "node_id": "MDEyOk9yZ2FuaXphdGlvbjQy",
      "type": "Organization",
      "site_admin": false
    }
  },
  "organization": {
    "login": "${version.organization}",
    "id": 42,
    "node_id": "MDEyOk9yZ2FuaXphdGlvbjQy",
    "url": "${apiMock.getUrlForContainer()}/orgs/${version.organization}"
  },
  "sender": {
    "login": "jdoe",
    "id": 1001,
    "node_id": "MDQ6VXNlcjEwMDE=",
    "url": "${apiMock.getUrlForContainer()}/users/jdoe",
    "type": "User",
    "site_admin": false
  }
}
"""
    }
}
