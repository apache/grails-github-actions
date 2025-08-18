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

import org.apache.grails.github.mocks.GitHubDockerAction
import org.apache.grails.github.mocks.GitHubRepoMock
import org.apache.grails.github.mocks.GitHubVersion
import org.apache.grails.github.mocks.cli.GitHubCliMock
import org.testcontainers.containers.Network
import spock.lang.Specification

import java.nio.file.Files

class ExportGradlePropertiesSpec extends Specification {

    def "export gradle properties"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('export-gradle-properties', release, new GitHubCliMock())

        and:
        String gradleProperties = """
foo=testing
other=another
#buz=test
"""

        and:
        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], ['gradle.properties': gradleProperties])
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = action.getDefaultEnvironment()

        and:
        action.createContainer(env, net)


        and:
        action.addCommandArgs('./gradle.properties')

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        Files.exists(action.baseDir.toPath().resolve('github-env'))

        def fileContent = action.baseDir.toPath().resolve('github-env').toFile().text
        fileContent == 'foo=testing\nother=another\n'

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }
}
