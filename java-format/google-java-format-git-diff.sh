#!/bin/bash
# Copyright 2019 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# This script applies Google Java format to modified regions in Java source
# files in a Git repository. It assumes that the repository has a 'master'
# branch that is only used for merging and is never directly worked on.
#
# If invoked on the master branch, this script will format the modified lines
# relative to HEAD. Otherwise, it uses the
# 'git merge-base --fork-point origin/master' command to find the latest
# fork point from origin/master, and formats the modified lines between
# the fork point and the HEAD of the current branch.

read -r -d '' USAGE << EOM
$(basename "$0") [--help] check|format|show
Incrementally format modified java lines in Git.

where:
    --help  show this help text
    check  check if formatting is necessary
    format format files in place
    show   show the effect of the formatting as unified diff
EOM

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

function callGoogleJavaFormatDiff() {
  local forkPoint
  forkPoint=$(git merge-base --fork-point origin/master) || exit $?

  local callResult
  case "$1" in
    "check")
      callResult=$(git diff -U0 ${forkPoint} | \
          ${SCRIPT_DIR}/google-java-format-diff.py -p1 | wc -l; \
          exit $((${PIPESTATUS[0]} | ${PIPESTATUS[1]}))) || exit $?
      ;;
    "format")
      callResult=$(git diff -U0 ${forkPoint} | \
          ${SCRIPT_DIR}/google-java-format-diff.py -p1 -i; \
          exit $((${PIPESTATUS[0]} | ${PIPESTATUS[1]}))) || exit $?
      ;;
    "show")
      callResult=$(git diff -U0 ${forkPoint} | \
          ${SCRIPT_DIR}/google-java-format-diff.py -p1; \
          exit $((${PIPESTATUS[0]} | ${PIPESTATUS[1]}))) || exit $?
      ;;
  esac
  echo "${callResult}"
  exit 0
}

function isJavaFormatNeededOnDiffs() {
  local modifiedLineCount
  modifiedLineCount=$(callGoogleJavaFormatDiff "check") || exit $?

  if [[ ${modifiedLineCount} -ne 0 ]]; then
    echo "true"
  else
    echo "false"
  fi
  exit 0
}

# The main function of this script:
if [[ $# -eq 1 && $1 == "check" ]]; then
  isJavaFormatNeededOnDiffs
elif [[ $# -eq 1 && $1 == "format" ]]; then
  callGoogleJavaFormatDiff "format"
elif [[ $# -eq 1 && $1 == "show" ]]; then
  callGoogleJavaFormatDiff "show"
else
  echo "${USAGE}"
fi
