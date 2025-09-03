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

# Action will publish the specified documentation to the specified documentation branch

set_value_or_error() {
  local value="$1"
  local defaultValue="$2"
  local variableName="$3"
  local validValues="${4:-}"  # optional argument (if empty, skip validation)

  if [[ -z "$value" && -z "$defaultValue" ]]; then
    echo "ERROR: A value for $variableName is required." >&2
    exit 1
  fi

  if [[ -n "$value" ]]; then    
    decidedValue="$value"
  else
    echo "${variableName}: Using default value: ${defaultValue}"
    decidedValue="$defaultValue"
  fi

  if [[ -n "$validValues" ]]; then
    local match=false
    local v
    for v in $validValues; do
      if [[ "$decidedValue" == "$v" ]]; then
        match=true
        break
      fi
    done

    if ! $match; then
      echo "ERROR: Invalid value for '$variableName': '$decidedValue'." >&2
      echo "       Must be one of: $validValues" >&2
      return 1
    fi
  fi

  eval "$variableName=\"\$decidedValue\""
}

set_path_value_or_error() {
  local variableName="$3"
  set_value_or_error "$@"

  if [[ "${decidedValue}" == /* || "${decidedValue}" == ./* ]]; then
    echo "ERROR: ${variableName} must not start with '/' or './'. Got: '${decidedValue}'"
    exit 1
  fi
}

publish_artifacts() {
  if [[ -z "${SOURCE_FOLDER}" || -z "${PUBLISH_PATH}" || -z "${BASE_PUBLISH_PATH}" ]]; then
    echo "ERROR: SOURCE_FOLDER(${SOURCE_FOLDER}), PUBLISH_PATH(${PUBLISH_PATH}), BASE_PUBLISH_PATH(${BASE_PUBLISH_PATH}) must be set."
    return 1
  fi

  if [[ "${PURGE_EXISTING}" == "true" ]]; then
    local purgePath
    if [[ "${PURGE_BY_BASE_PATH}" == "false" ]]; then
      purgePath="${PUBLISH_PATH}"
    else
      purgePath="${BASE_PUBLISH_PATH}"
    fi

    if [[ -d "${purgePath}" ]]; then
      if [[ "$(ls -A "${purgePath}" 2>/dev/null)" ]]; then
        echo "Removing existing directory at ${purgePath}"
        git rm -rf "${purgePath}"
      fi
    elif [[ -e "${purgePath}" ]]; then
      echo "Removing existing file at ${purgePath}"
      git rm -rf "${purgePath}"
    fi
  fi

  echo "Publishing ${SOURCE_FOLDER} to ${DOCUMENTATION_BRANCH}:${PUBLISH_PATH}"
  mkdir -p "${PUBLISH_PATH}"
  cp -r "../${SOURCE_FOLDER}/." "${PUBLISH_PATH}"
  git add --verbose "${PUBLISH_PATH}"/*
}

is_highest_version() {
  local new_folder="$1"   # e.g. "7.0.x"
  
  # Strip the trailing ".x" → "7.0", then parse into major/minor
  local new_major new_minor
  local folder_no_x="${new_folder%.x}"  # "7.0"
  IFS="." read -r new_major new_minor <<< "$folder_no_x"

  # Loop over all folders that look like 7.0.x
  for folder in [0-9]*.x; do
    [[ -d "$folder" ]] || continue  # skip if not a real folder

    # Use a regex match to parse existing folders into major/minor
    if [[ "$folder" =~ ^([0-9]+)\.([0-9]+)\.x$ ]]; then
      local existing_major="${BASH_REMATCH[1]}"
      local existing_minor="${BASH_REMATCH[2]}"

      # Compare numeric major/minor
      if (( existing_major > new_major )); then
        # Found a folder with a higher major
        return 1
      elif (( existing_major == new_major && existing_minor > new_minor )); then
        # Found a folder with same major but higher minor
        return 1
      fi
    fi
  done

  return 0
}

set -e

set_value_or_error "${DOCUMENTATION_BRANCH}" 'gh-pages' 'DOCUMENTATION_BRANCH'

# GH_TOKEN - the token to access the github repository, can be GITHUB_TOKEN if the same repo and permissions are set correctly
set_value_or_error "${GH_TOKEN}" "${GITHUB_TOKEN}" "GH_TOKEN"

# GITHUB_USER_NAME - the username to commit to documentation branch, defaults to GITHUB_ACTOR (assumes permissions are set correctly)
set_value_or_error "${GITHUB_USER_NAME}" "${GITHUB_ACTOR}" "GITHUB_USER_NAME"

# LAST_RELEASE_FOLDER - when a release is performed, instead of just copying it to a version number, copy it to this static folder name
set_value_or_error "${LAST_RELEASE_FOLDER}" "latest" "LAST_RELEASE_FOLDER"

# SKIP_RELEASE_FOLDER - if copying to the release folder should be skipped
set_value_or_error "${SKIP_RELEASE_FOLDER}" "false" "SKIP_RELEASE_FOLDER" "true false"

# LAST_SNAPSHOT_FOLDER - when a snapshot is performed, instead of just copying it a version number, copy it to this static folder name
set_path_value_or_error "${LAST_SNAPSHOT_FOLDER}" "snapshot" "LAST_SNAPSHOT_FOLDER"

# SKIP_SNAPSHOT_FOLDER - if copying to the snapshot folder should be skipped
set_path_value_or_error "${SKIP_SNAPSHOT_FOLDER}" "false" "SKIP_SNAPSHOT_FOLDER" "true false"

# GRADLE_PUBLISH_RELEASE - Whether the documents being published is a release or not, expects 'true', 'false' values
set_value_or_error "${GRADLE_PUBLISH_RELEASE}" "" "GRADLE_PUBLISH_RELEASE" "true false"

# TARGET_REPOSITORY - the document repository to commit to, including owner
set_value_or_error "${TARGET_REPOSITORY}" "${GITHUB_REPOSITORY}" "TARGET_REPOSITORY"
if [[ ! "$TARGET_REPOSITORY" =~ ^[^/]+/[^/]+$ ]]; then
  echo "ERROR: TARGET_REPOSITORY must be in the format 'owner/repo'" >&2
  exit 1
fi

# SOURCE_FOLDER - the relative path of the source documentation folder from the root of the repo
set_path_value_or_error "${SOURCE_FOLDER}" "" "SOURCE_FOLDER"

# TARGET_SUBFOLDER - an optional sub folder to publish to
set_path_value_or_error "${TARGET_SUBFOLDER}" "." "TARGET_SUBFOLDER"
if [ "${TARGET_SUBFOLDER}" == "." ]; then
  unset TARGET_SUBFOLDER;
fi

# PURGE_EXISTING - whether to remove the files before upload
set_value_or_error "${PURGE_EXISTING}" "true" "PURGE_EXISTING" "true false"

# PURGE_BY_BASE_PATH - sometimes it's useful to purge the base version folder, instead of the targeted nested sub folder
set_value_or_error "${PURGE_BY_BASE_PATH}" "false" "PURGE_BY_BASE_PATH" "true false"

# GITHUB_WORKSPACE - the safe directory to checkout to
set_value_or_error "${GITHUB_WORKSPACE}" "" "GITHUB_WORKSPACE"

if [[ "$SKIP_SNAPSHOT_FOLDER" == "true" && "$GRADLE_PUBLISH_RELEASE" == "false" ]]; then
  echo "Snapshot detected and snapshot publishing is disabled. Skipping documentation deployment."
  exit 0
fi

set_value_or_error "${GITHUB_URL_BASE}" "github.com" "GITHUB_URL_BASE"
set_value_or_error "${GIT_TRANSFER_PROTOCOL}" "https" "GIT_TRANSFER_PROTOCOL"
GIT_REPO_URL="${GIT_TRANSFER_PROTOCOL}://${GITHUB_USER_NAME}:${GH_TOKEN}@${GITHUB_URL_BASE}/${TARGET_REPOSITORY}.git"

# Initialize a Git Repository under a separate location from the existing checkout that will be the documentation branch
cd "${GITHUB_WORKSPACE}"
git init
git config --global user.email "${GITHUB_USER_NAME}@users.noreply.github.com"
git config --global user.name "${GITHUB_USER_NAME}"
git config --global http.version HTTP/1.1
git config --global http.postBuffer 157286400

# Create or checkout the documentation branch
if git ls-remote --heads "${GIT_REPO_URL}" "${DOCUMENTATION_BRANCH}" | grep -q "refs/heads/${DOCUMENTATION_BRANCH}"; then
  echo "::group::Checkout documentation branch"
  echo "documentation branch found, cloning"
  git clone "${GIT_REPO_URL}" "${DOCUMENTATION_BRANCH}" --branch "${DOCUMENTATION_BRANCH}" --single-branch
  cd ${DOCUMENTATION_BRANCH}
  echo "::endgroup::"
else
  echo "::group::Creating documentation branch"
  echo "Creating documentation branch ${DOCUMENTATION_BRANCH} as it does not exist"
  mkdir "${DOCUMENTATION_BRANCH}"
  cd "${DOCUMENTATION_BRANCH}"
  git init
  git checkout -b "${DOCUMENTATION_BRANCH}"
  git remote add origin "${GIT_REPO_URL}"
  echo "::endgroup::"
fi

# grails repos have a convention that they create a ghpages.html to replace the root index.html
if [[ -f "../${SOURCE_FOLDER}/ghpages.html" ]]; then
  echo "::group::Staging root index.html"
  echo "${SOURCE_FOLDER}/ghpages.html detected, replacing root index.html"
  cp "../${SOURCE_FOLDER}/ghpages.html" index.html
  git add index.html
  echo "::endgroup::"
fi

# stage the documents
if [[ "$GRADLE_PUBLISH_RELEASE" == "false" ]]; then
  echo "::group::Publishing Snapshot"
  echo "Snapshot detected"

  # Subfolder support
  BASE_PUBLISH_PATH="./${LAST_SNAPSHOT_FOLDER}"
  if [ -n "${TARGET_SUBFOLDER}" ]; then
    PUBLISH_PATH="./${LAST_SNAPSHOT_FOLDER}/${TARGET_SUBFOLDER}"
  else    
    PUBLISH_PATH="./${LAST_SNAPSHOT_FOLDER}"
  fi

  publish_artifacts
  echo "::endgroup::"
else
  echo "Release detected"

  # RELEASE_TAG_PREFIX - the tag prefix to use for a release version, defaults to 'v'
  set_value_or_error "${RELEASE_TAG_PREFIX}" "v" "RELEASE_TAG_PREFIX"

  # VERSION - the version number of this snapshot or release, v7.0.2 will be `7.0.2`, 7.0.x will be 7.0.x
  set_value_or_error "${VERSION}" "${GITHUB_REF_NAME}" "VERSION"
  if [[ ! "${VERSION}" =~ ^(${RELEASE_TAG_PREFIX})?[^.]+\.[^.]+\.[^.]+$ ]]; then
    echo "ERROR: VERSION must be in the format 'X.X.X' or '${RELEASE_TAG_PREFIX}X.X.X'. Got: '${VERSION}'"
    exit 1
  fi
  if [[ $VERSION == "${RELEASE_TAG_PREFIX}"* ]]; then
    VERSION=${VERSION:${#RELEASE_TAG_PREFIX}}
  fi

  # Publish to the specific version folder
  echo "::group::Publishing Specific Release Version: ${VERSION}"
  BASE_PUBLISH_PATH="./${VERSION}"
  if [ -n "${TARGET_SUBFOLDER}" ]; then
    PUBLISH_PATH="./${VERSION}/${TARGET_SUBFOLDER}"
  else    
    PUBLISH_PATH="./${VERSION}"
  fi
  publish_artifacts
  echo "Published release documentation to ${PUBLISH_PATH}"
  echo "::endgroup::"

  # Publish to the generic version folder
  genericVersionFolder="${VERSION%.*}"
  genericVersionFolder="${genericVersionFolder}.x"
  echo "::group::Publishing Generic Release Version: ${genericVersionFolder}"
  BASE_PUBLISH_PATH="./${genericVersionFolder}"
  if [ -n "${TARGET_SUBFOLDER}" ]; then
    PUBLISH_PATH="./${genericVersionFolder}/${TARGET_SUBFOLDER}"
  else
    PUBLISH_PATH="./${genericVersionFolder}"
  fi
  publish_artifacts
  echo "Published release documentation to ${genericVersionFolder}"
  echo "::endgroup::"

  # Publish to the latest release folder if needed 
  if [[ "$SKIP_RELEASE_FOLDER" == "false" ]]; then
    if is_highest_version "${genericVersionFolder}"; then
      echo "::group::Overwriting ${LAST_RELEASE_FOLDER} with the latest release documentation"
      BASE_PUBLISH_PATH="./${LAST_RELEASE_FOLDER}"
      if [ -n "${TARGET_SUBFOLDER}" ]; then
        PUBLISH_PATH="./${LAST_RELEASE_FOLDER}/${TARGET_SUBFOLDER}"
      else    
        PUBLISH_PATH="./${LAST_RELEASE_FOLDER}"
      fi
      publish_artifacts
      echo "Published a copy of documentation to ${PUBLISH_PATH}"
      echo "::endgroup::"
    else
      echo "::group::Skipped Latest Release Documentation - not highest"
      echo "Skipping documentation copy to '${LAST_RELEASE_FOLDER}' because ${genericVersionFolder} is NOT the highest."
      echo "::endgroup::"
    fi
  else
    echo "::group::Skipped Latest Release Documentation"
    echo "Skipping documentation copy to ${LAST_RELEASE_FOLDER}"
    echo "::endgroup::"
  fi
fi

echo "::group::Committing Changes"
echo "Detected the following delta for commit:"
git status

echo "Committing changes."
git commit -m "Deploying to documentation branch - $(date +"%T")" --quiet --allow-empty
git push "${GIT_REPO_URL}" "${DOCUMENTATION_BRANCH}"
echo "Deployment successful!"
echo "::endgroup::"
