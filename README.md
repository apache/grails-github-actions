<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Apache Grails (incubating) - GitHub Actions

[![CI](https://github.com/apache/grails-github-actions/actions/workflows/ci.yml/badge.svg?event=push)](https://github.com/apache/grails-github-actions/actions/workflows/ci.yml)
[![Users Mailing List](https://img.shields.io/badge/Users_Mailing_List-feb571)](https://lists.apache.org/list.html?users@grails.apache.org)
[![Dev Mailing List](https://img.shields.io/badge/Dev_Mailing_List-feb571)](https://lists.apache.org/list.html?dev@grails.apache.org)
[![Slack](https://img.shields.io/badge/Join_Slack-e01d5a)](https://slack.grails.org/)

## Introduction

This repository hosts GitHub Actions that power the release workflows for Apache Grails & related projects. 

Actions include:
1. `pre-release` - handles setting the project version based on the Git tag & running various pre-release tasks.
2. `deploy-github-pages` - handles publishing documentation to GitHub Pages for both snapshots & releases.
3. `post-release` - assists in merging tagged changes back to the target branch & bumping to the next development version.
4. `export-gradle-properties` - exposes selected Gradle properties as environment variables.

## Who can use these actions

These actions are meant to assist in using the GitHub `Release` feature to produce a published release. They were primarily designed for Gradle projects that need to set a version & automate parts of the release process such as the changing of the project version & publishing documentation.  

## Usages

Used by: https://github.com/search?q=org%3Aapache+%22uses%3A+apache%2Fgrails-github-actions%2F%22+language%3Ayml&type=code

## Further information

To use these actions, please refer to the individual README files in each action's directory.

## Testing

To bootstrap the test project so it can be run against the source distribution, execute: 

    cd tests/gradle-bootstrap
    gradle bootstrap

Then to run the tests, execute:

    cd ..
    gradle check
