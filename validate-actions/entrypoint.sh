#!/bin/bash

#
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.
#

# Validates that every `uses:` entry in the repository's GitHub Actions workflow files complies
# with the ASF approved-actions policy (https://github.com/apache/infrastructure-actions):
#
#   * Actions under the actions/*, github/* or apache/* namespaces are auto-approved.
#   * Any other action must either match an @* (any-version) allowlist entry or be pinned to a
#     full-SHA commit that appears in approved_patterns.yml.
#
# Violations are reported as error annotations and fail the action. A trailing `# vX.Y.Z` comment
# that disagrees with the tag ASF recorded for the pinned SHA gives a warning annotation only.
#
# Inputs (environment variables, all optional):
#   WORKFLOWS_DIR          directory holding the workflow files, relative to the repository root
#   APPROVED_PATTERNS_URL  URL of approved_patterns.yml, the allowlist
#   ACTIONS_URL            URL of actions.yml, the SHA-to-tag mapping
#
# The YAML involved is simple enough to parse with bash alone, which keeps the image free of extra
# dependencies.

set -euo pipefail

WORKFLOWS_DIR="${WORKFLOWS_DIR:-.github/workflows}"
WORKFLOWS_DIR="${WORKFLOWS_DIR%/}"
APPROVED_PATTERNS_URL="${APPROVED_PATTERNS_URL:-https://raw.githubusercontent.com/apache/infrastructure-actions/main/approved_patterns.yml}"
ACTIONS_URL="${ACTIONS_URL:-https://raw.githubusercontent.com/apache/infrastructure-actions/main/actions.yml}"

readonly AUTO_APPROVED_OWNERS=(actions github apache)
# Matches `uses: <ref>` with an optional list dash, optional quotes and an optional trailing comment.
readonly USES_PATTERN="^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*[\"']?([^\"'[:space:]#]+)[\"']?[[:space:]]*(#[[:space:]]*(.*))?\$"
readonly SHA40_PATTERN='^[0-9a-f]{40}$'

declare -A allowed_refs=()   # identifier -> space-separated approved refs ('*' or full SHAs)
declare -A recorded_tags=()  # identifier@sha -> the tag ASF recorded for that SHA
errors=()
warnings=()

# Strips leading and trailing whitespace from $2 and stores the result in the variable named by $1.
trim_into() {
  local value="$2"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf -v "$1" '%s' "$value"
}

fetch() {
  local url="$1"
  local destination="$2"
  if ! curl --fail --silent --show-error --location \
       --connect-timeout 10 --max-time 60 --retry 3 --retry-delay 2 \
       --output "$destination" "$url"; then
    echo "::error::Failed to fetch ${url}"
    exit 1
  fi
}

# approved_patterns.yml is a YAML list of owner/repo[/subpath]@ref-or-star strings.
parse_approved_patterns() {
  local raw line entry identifier ref
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    trim_into line "${raw%$'\r'}"
    if [[ "$line" != '- '* ]]; then
      continue
    fi
    trim_into entry "${line#- }"
    if [[ "$entry" != *@* ]]; then
      continue
    fi
    identifier="${entry%@*}"
    ref="${entry##*@}"
    allowed_refs["$identifier"]+="${ref} "
  done < "$1"
}

# actions.yml maps each action to its approved SHAs and the tag each SHA was recorded under:
#   owner/repo[/subpath]:
#     <sha>:
#       tag: vX.Y.Z
#       expires_at: YYYY-MM-DD
#     '*':
#       keep: true
# Only the identifier, the SHA and the tag are of interest here.
parse_actions() {
  local raw line trimmed key tag
  local current_identifier='' current_sha=''
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    line="${raw%$'\r'}"
    line="${line%%#*}"
    trim_into trimmed "$line"
    if [[ -z "$trimmed" ]]; then
      continue
    fi
    if [[ "$line" != ' '* && "$trimmed" == *: ]]; then
      current_identifier="${trimmed%:}"
      current_sha=''
      continue
    fi
    if [[ -n "$current_identifier" && "$line" == '  '* && "$line" != '    '* && "$trimmed" == *: ]]; then
      key="${trimmed%:}"
      if [[ "$key" == \'*\' ]]; then
        key="${key#\'}"
        key="${key%\'}"
      fi
      current_sha="$key"
      continue
    fi
    if [[ -n "$current_identifier" && -n "$current_sha" && "$trimmed" == tag:* ]]; then
      trim_into tag "${trimmed#tag:}"
      if [[ "$tag" == \'*\' ]]; then
        tag="${tag#\'}"
        tag="${tag%\'}"
      fi
      if [[ "$tag" == \"*\" ]]; then
        tag="${tag#\"}"
        tag="${tag%\"}"
      fi
      recorded_tags["${current_identifier}@${current_sha}"]="$tag"
    fi
  done < "$1"
}

# Emits a GitHub annotation ($1 is error or warning) for $2:$3 and records it for the summary.
report() {
  local level="$1" file="$2" line_no="$3" message="$4"
  local escaped="${message//%/%25}"
  echo "::${level} file=${file},line=${line_no}::${escaped}"
  if [[ "$level" == error ]]; then
    errors+=("${file}:${line_no}: ${message}")
  else
    warnings+=("${file}:${line_no}: ${message}")
  fi
}

check_entry() {
  local file="$1" line_no="$2" ref="$3" comment="$4"
  local identifier ref_part owner owner_candidate refs expected_tag comment_tag
  local -a approved

  # Local composite actions (./..., ../...) are implicitly trusted
  if [[ "$ref" == ./* || "$ref" == ../* ]]; then
    return 0
  fi

  if [[ "$ref" != *@* ]]; then
    if [[ "$ref" == docker://* ]]; then
      return 0
    fi
    report error "$file" "$line_no" "unrecognised form '${ref}' (missing @ref)"
    return 0
  fi
  identifier="${ref%%@*}"
  ref_part="${ref#*@}"

  owner="${identifier%%/*}"
  for owner_candidate in "${AUTO_APPROVED_OWNERS[@]}"; do
    if [[ "$owner" == "$owner_candidate" ]]; then
      return 0
    fi
  done

  if [[ -z "${allowed_refs[$identifier]+set}" ]]; then
    report error "$file" "$line_no" "'${identifier}' is not on the ASF approved list"
    return 0
  fi
  refs="${allowed_refs[$identifier]}"
  if [[ " ${refs}" == *' * '* ]]; then
    return 0
  fi
  read -r -a approved <<< "$refs"

  if ! [[ "$ref_part" =~ $SHA40_PATTERN ]]; then
    report error "$file" "$line_no" "'${ref}' must be pinned to a full SHA (allowlist has ${#approved[@]} approved SHA(s) for '${identifier}')"
    return 0
  fi
  if [[ " ${refs}" != *" ${ref_part} "* ]]; then
    report error "$file" "$line_no" "SHA for '${identifier}' is not among the ${#approved[@]} approved SHA(s) in the allowlist"
    return 0
  fi

  # All hard checks passed. Comment handling is WARN-only.
  expected_tag="${recorded_tags[${identifier}@${ref_part}]:-}"
  if [[ -z "$expected_tag" ]]; then
    return 0
  fi
  trim_into comment "$comment"
  if [[ -z "$comment" ]]; then
    report warning "$file" "$line_no" "missing trailing '# ${expected_tag}' comment for SHA-pinned action"
    return 0
  fi
  comment_tag="${comment%%[[:space:]]*}"
  if [[ "$comment_tag" != "$expected_tag" ]]; then
    report warning "$file" "$line_no" "trailing comment '# ${comment_tag}' does not match the ASF-recorded tag '# ${expected_tag}' for this SHA"
  fi
}

write_step_summary() {
  if [[ -z "${GITHUB_STEP_SUMMARY:-}" ]]; then
    return 0
  fi
  {
    echo "### 🔎 ASF approved actions"
    echo
    if [[ ${#errors[@]} -gt 0 ]]; then
      echo "❌ ${#errors[@]} policy violation(s) in ${#workflow_files[@]} workflow file(s):"
      echo
      printf -- '- `%s`\n' "${errors[@]}"
    else
      echo "✅ Checked ${#workflow_files[@]} workflow file(s) - all compliant."
    fi
    if [[ ${#warnings[@]} -gt 0 ]]; then
      echo
      echo "⚠️ ${#warnings[@]} warning(s):"
      echo
      printf -- '- `%s`\n' "${warnings[@]}"
    fi
  } >> "$GITHUB_STEP_SUMMARY"
}

echo "::group::Setup"
echo "Workflows directory: ${WORKFLOWS_DIR}"
echo "Approved patterns URL: ${APPROVED_PATTERNS_URL}"
echo "Actions URL: ${ACTIONS_URL}"
if [[ ! -d "$WORKFLOWS_DIR" ]]; then
  echo "::error::Workflows directory '${WORKFLOWS_DIR}' does not exist"
  exit 1
fi
mapfile -t workflow_files < <(find "$WORKFLOWS_DIR" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
echo "Found ${#workflow_files[@]} workflow file(s)"
echo "::endgroup::"

echo "::group::Fetch the ASF approved list"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
fetch "$APPROVED_PATTERNS_URL" "${work_dir}/approved_patterns.yml"
fetch "$ACTIONS_URL" "${work_dir}/actions.yml"
parse_approved_patterns "${work_dir}/approved_patterns.yml"
parse_actions "${work_dir}/actions.yml"
echo "Loaded ${#allowed_refs[@]} approved action(s) and ${#recorded_tags[@]} recorded tag(s)"
if [[ ${#allowed_refs[@]} -eq 0 ]]; then
  echo "::error::No approved patterns were parsed from ${APPROVED_PATTERNS_URL}"
  exit 1
fi
echo "::endgroup::"

echo "::group::Validate workflow files"
for file in "${workflow_files[@]}"; do
  file="${file#./}"
  echo "Checking ${file}"
  line_no=0
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    line_no=$((line_no + 1))
    line="${raw%$'\r'}"
    if [[ "$line" =~ $USES_PATTERN ]]; then
      check_entry "$file" "$line_no" "${BASH_REMATCH[2]}" "${BASH_REMATCH[4]:-}"
    fi
  done < "$file"
done
echo "::endgroup::"

write_step_summary

if [[ ${#warnings[@]} -gt 0 ]]; then
  printf 'WARN %s\n' "${warnings[@]}"
fi
if [[ ${#errors[@]} -gt 0 ]]; then
  printf 'FAIL %s\n' "${errors[@]}"
  echo "ERROR: ${#errors[@]} GitHub Actions policy violation(s); see the annotations above." >&2
  exit 1
fi
echo "Checked ${#workflow_files[@]} workflow file(s) - all compliant."
