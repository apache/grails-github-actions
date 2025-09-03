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
import org.apache.grails.github.mocks.GitHubVersion
import org.apache.grails.github.mocks.GitHubRepoMock
import org.apache.grails.github.mocks.cli.GitHubCliMock
import org.testcontainers.containers.Network
import spock.lang.Specification

class DeployGithubPagesSpec extends Specification {

    private Map<String, String> getProjectFiles() {
        [
            'gradle.properties': 'projectVersion=7.0.0-SNAPSHOT',
            'docs/index.html': '<html><body>Welcome to the Grails Documentation</body></html>',
            'docs/ghpages.html': '<html><body>Welcome to the Grails GitHub Pages</body></html>',
        ]
    }
    private Map<String, String> getDefaultEnvironment(GitHubDockerAction action, GitHubRepoMock gitRepo) {
        def env = action.getDefaultEnvironment()

        env['GITHUB_USER_NAME'] = gitRepo.getUsername()
        env['GH_TOKEN'] = gitRepo.getToken()
        env['GIT_TRANSFER_PROTOCOL'] = 'http'
        env['GITHUB_URL_BASE'] = gitRepo.getInternalUrlBase()

        env
    }

    def "gh-pages branch is created if does not exist"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch').contains('Creating documentation branch gh-pages as it does not exist')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "ghpages_html is set as root index_html"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.createDivergedBranch([
                'index.html': 'will be replaced',
                'snapshot/index.html': 'will also be replaced'
        ], 'gh-pages')
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        !action.isLogGroupPresent('Creating documentation branch')
        action.getActionGroupLogs('Checkout documentation branch').contains('documentation branch found, cloning')

        and: 'ghpages copied'
        action.getActionGroupLogs('Staging root index.html')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        and: 'gh-pages replaced expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "snapshot - snapshot publishing disabled"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SKIP_SNAPSHOT_FOLDER'] = 'true'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        !gitRepo.branchExists('gh-pages')

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "snapshot - published with subfolder"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['TARGET_SUBFOLDER'] = 'nested'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFolders('snapshot', 'gh-pages') == ['nested']
        gitRepo.getFileContents('snapshot/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "snapshot - published without subfolder"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-SNAPSHOT'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFolders('snapshot', 'gh-pages') == []
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "snapshot - version is ignored on snapshot"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'false' // snapshot
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = 'BAD_VERSION_THAT_SHOULD_NOT_CAUSE_FAILURE'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and: 'gh-pages branch created'
        action.getActionGroupLogs('Creating documentation branch')
        gitRepo.branchExists('gh-pages')

        and: 'files published to snapshot'
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'
        gitRepo.getFolders('snapshot', 'gh-pages') == []
        gitRepo.getFileContents('snapshot/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        and: 'gh-pages added expected folders'
        gitRepo.getFolders('gh-pages') == ['snapshot']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "release - published without subfolder"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'true'
        env['SKIP_SNAPSHOT_FOLDER'] = 'true' // should be ignored because this is a release
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-RC1'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        !action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        action.getActionGroupLogs('Publishing Specific Release Version: 7.0.0-RC1')
        action.getActionGroupLogs('Publishing Generic Release Version: 7.0.x')
        action.getActionGroupLogs('Overwriting latest with the latest release documentation')

        and:
        gitRepo.branchExists('gh-pages')

        and:
        gitRepo.getFolders('gh-pages') == ['7.0.0-RC1', 'latest', '7.0.x']
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        and:
        gitRepo.getFileContents('latest/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.x/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.0-RC1/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "release - published with subfolder"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'true'
        env['SKIP_SNAPSHOT_FOLDER'] = 'true' // should be ignored because this is a release
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-RC1'
        env['TARGET_SUBFOLDER'] = 'nested'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        !action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        action.getActionGroupLogs('Publishing Specific Release Version: 7.0.0-RC1')
        action.getActionGroupLogs('Publishing Generic Release Version: 7.0.x')
        action.getActionGroupLogs('Overwriting latest with the latest release documentation')

        and:
        gitRepo.branchExists('gh-pages')

        and:
        gitRepo.getFolders('gh-pages') == ['7.0.0-RC1', 'latest', '7.0.x']
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        and:
        gitRepo.getFileContents('latest/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.x/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.0-RC1/nested/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }

    def "release - skip publishing to latest"() {
        given:
        Network net = Network.newNetwork()

        and:
        GitHubVersion release = new GitHubVersion(version: '7.0.0-RC1', tagName: 'rel-7.0.0-RC1', targetBranch: '7.0.x', targetVersion: '7.0.0-SNAPSHOT')
        GitHubDockerAction action = new GitHubDockerAction('deploy-github-pages', release, new GitHubCliMock())

        GitHubRepoMock gitRepo = new GitHubRepoMock(action.workspacePath, net)
        gitRepo.init()
        gitRepo.populateRepository('7.0.0-SNAPSHOT', null, [], getProjectFiles())
        gitRepo.stageRepositoryForAction('main', false)

        and:
        def env = getDefaultEnvironment(action, gitRepo)
        env['GRADLE_PUBLISH_RELEASE'] = 'true'
        env['SKIP_SNAPSHOT_FOLDER'] = 'true' // should be ignored because this is a release
        env['SKIP_RELEASE_FOLDER'] = 'true'
        env['SOURCE_FOLDER'] = 'docs'
        env['VERSION'] = '7.0.0-RC1'

        and:
        action.createContainer(env, net)

        when:
        action.runAction()

        then:
        action.actionExitCode == 0L
        action.actionLogs

        and:
        !action.actionLogs.contains('Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment.')

        and:
        action.getActionGroupLogs('Publishing Specific Release Version: 7.0.0-RC1')
        action.getActionGroupLogs('Publishing Generic Release Version: 7.0.x')
        !action.isLogGroupPresent('Overwriting latest with the latest release documentation')

        and:
        gitRepo.branchExists('gh-pages')

        and:
        gitRepo.getFolders('gh-pages') == ['7.0.0-RC1', '7.0.x']
        gitRepo.getFileContents('index.html', 'gh-pages') == '<html><body>Welcome to the Grails GitHub Pages</body></html>'

        and:
        gitRepo.getFileContents('7.0.x/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'
        gitRepo.getFileContents('7.0.0-RC1/index.html', 'gh-pages') == '<html><body>Welcome to the Grails Documentation</body></html>'

        and: 'main did not change'
        gitRepo.getFileContents('gradle.properties', 'main') == 'projectVersion=7.0.0-SNAPSHOT'

        and: 'main did not add any folders'
        gitRepo.getFolders( 'main') == ['docs']

        cleanup:
        System.out.println("Container logs:\n${action.actionLogs}" as String)
        gitRepo?.close()
        action.close()
    }
}
