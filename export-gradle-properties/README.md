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

# Grails export-gradle-properties action

Exports `gradle.properties` as environment variables.

## Example usage

```yaml
- name: Export Gradle Properties
  uses: apache/grails-github-actions/export-gradle-properties@asf
- name: Use the property
  run:
    echo "${PROJECT_VERSION}"
  env:
    PROJECT_VERSION: ${{ env.projectVersion }}
```
