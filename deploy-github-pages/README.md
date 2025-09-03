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

# `deploy-github-pages` Action

## Purpose: Deploy Documentation to Github Pages

A GitHub Action to copy documentation files to a specified documentation branch. Works by creating a subfolder named the same as the documentation branch, checking out the documentation branch to that folder, staging files, and then pushing them. Action is configured via environment variables.

For releases, the following folders are published: 
- `latest` - a folder that's intended to be updated to the latest release documentation.
- `X.0.x` - where `X` is a major version number. For example, `1.0.x`.  This folder is intended to be updated to the latest version of a a major version.
- `X.X.X` - where `X.X.X` is a specific version number. For example, `1.0.0`. This folder is intended to store documentation for a specific version.

For snapshots, the following folders are published:
- `snapshot` - a folder that's intended to be updated to the latest snapshot documentation.

Note: snapshots ignore the VERSION parameter and always publish to the `snapshot` folder.

## Requirements
If using the default `GITHUB_TOKEN`, this action requires permission `contents: write`. Otherwise, the provided `GH_TOKEN` must be able to commit to the documentation branch.

## Environment Variables
* (required) `GRADLE_PUBLISH_RELEASE` - if this documentation is for a release set to `true`, otherwise set to `false`.
* (required) `SOURCE_FOLDER` - the folder in the action working directory that contains the documentation files to be copied. This should be a relative path from the root of the repository.
* (required for release) `VERSION` - the version of the documentation being deployed. Must be a [Semantic Version](https://semver.org/). This is required for release documentation publishing only.
* (optional) `DOCUMENTATION_BRANCH` - the branch to which the documentation files will be copied. Defaults to `gh-pages`.
* (optional) `GH_TOKEN` - the GitHub token to use for authentication. If not provided, the action will use the default GitHub token available in the environment.
* (optional) `TARGET_SUBFOLDER` - if specified, a nested subfolder will be created with this name in any documentation folder.
* (optional) `LAST_RELEASE_FOLDER` - the `latest` folder name for a release. Defaults to `latest`.
* (optional) `LAST_SNAPSHOT_FOLDER` - the `snapshot` folder name for a snapshot. Defaults to `snapshot`.
* (optional) `SKIP_RELEASE_FOLDER` - if set to `true`, the action will not create a specific release version folder. Defaults to `false`.
* (optional) `SKIP_SNAPSHOT_FOLDER` - if set to `true`, the action will not publish any snapshot documentation. Defaults to `false`.
* (optional) `TARGET_REPOSITORY` - the target repository to which the documentation files will be copied. If not provided, the action will use the repository from which it is run. Format of `owner/repository` is expected.
* (optional) `RELEASE_TAG_PREFIX` - the prefix used for release tags. Defaults to `v`.  Must have a value defined.

## Example Usage

Snapshot Usage:
```yaml
      - name: "🚀 Publish to Github Pages"
        uses: apache/grails-github-actions/deploy-github-pages@asf
        env:
          GRADLE_PUBLISH_RELEASE: 'false'
          SOURCE_FOLDER: build/docs
```

Release Usage:
```yaml
      - name: "🚀 Publish to Github Pages"
        uses: apache/grails-github-actions/deploy-github-pages@asf
        env:
          GRADLE_PUBLISH_RELEASE: 'true'
          SOURCE_FOLDER: build/docs
          VERSION: ${{ needs.publish.outputs.release_version }}
```