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

# `validate-actions` Action

## Purpose: Verify every action a repository uses is on the ASF Infra approved list

ASF Infra only allows GitHub Actions workflows in `apache/*` repositories to use third-party actions that appear on the
approved list maintained in [apache/infrastructure-actions](https://github.com/apache/infrastructure-actions). This action
scans the repository's workflow files and fails when any `uses:` entry does not comply with that policy, so a dependabot
or renovate bump to an action version that ASF has not (yet) approved is caught in the pull request rather than at run time.

It is a port of the `validateActions` Gradle task from `grails-core` so that any repository can use it without a Gradle build.

## Policy

For every `uses:` entry in every `*.yml` / `*.yaml` file directly inside the workflows directory:

1. Local composite actions (`./...`, `../...`) and `docker://` images are implicitly trusted.
2. Actions under the `actions/*`, `github/*` or `apache/*` namespaces are auto-approved.
3. Any other action must appear in `approved_patterns.yml`, either as an `@*` (any version) entry or with a full 40-character
   commit SHA that is on the list. A tag or branch reference for a SHA-only entry is a violation.
4. A SHA-pinned action should carry a trailing `# vX.Y.Z` comment that matches the tag ASF recorded for that SHA in
   `actions.yml`. A missing or mismatched comment produces a warning only.

Violations are reported as GitHub error annotations against the offending file and line, summarised in the job summary, and
fail the action. The approved list is fetched on every run, so a scheduled run catches ASF delisting a previously approved SHA.

## Inputs

* `workflows-dir` - optional directory containing the workflow files, relative to the repository root. Defaults to `.github/workflows`.
* `approved-patterns-url` - optional URL of the `approved_patterns.yml` allowlist. Defaults to the copy in `apache/infrastructure-actions`.
* `actions-url` - optional URL of the `actions.yml` SHA-to-tag mapping. Defaults to the copy in `apache/infrastructure-actions`.

The URL inputs exist so the action can be pointed at a mirror or a test fixture. Production usage should leave them at their defaults.

## Requirements

1. Requires `contents: read` so the workflow files can be checked out.
2. Requires outbound HTTPS access to `raw.githubusercontent.com` to fetch the approved list.

## Example Usage

Run on every change to the workflow files, on a weekly schedule to catch upstream delisting, and on demand:

```yaml
name: "Validate GitHub Actions"
on:
  push:
    paths:
      - '.github/workflows/**'
  pull_request:
    paths:
      - '.github/workflows/**'
  schedule:
    - cron: '0 6 * * 1' # weekly Monday 06:00 UTC - catches ASF delisting a pinned SHA
  workflow_dispatch:
permissions:
  contents: read
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - name: "📥 Checkout repository"
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - name: "🔎 Validate Actions against the ASF approved list"
        uses: apache/grails-github-actions/validate-actions@asf
```

Validating a non-standard workflows directory:

```yaml
      - name: "🔎 Validate Actions against the ASF approved list"
        uses: apache/grails-github-actions/validate-actions@asf
        with:
          workflows-dir: 'ci/workflows'
```
