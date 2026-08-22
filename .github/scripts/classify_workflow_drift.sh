#!/usr/bin/env bash
# Classifies why a running workflow's own file differs between the
# checked-out PR branch and the base branch, distinguishing a genuinely
# stale branch (rebasing fixes it) from a PR that intentionally modifies
# the exact workflow file that's currently running (rebasing can NEVER
# fix this — the diff to that file is the whole point of the PR). See
# issue #401: PRs #382/#387 both rebased onto current dev and hit the
# identical "workflow-file drift" self-check failure immediately on the
# freshly-rebased tip, because the check only ever asked "does this file
# differ from dev", never "did *my own* commits cause that difference".
#
# Must be run from inside a checkout of the PR branch with the base ref
# already fetched locally (e.g. `git fetch origin dev` beforehand).
#
# Usage: classify_workflow_drift.sh <workflow-file-path> <base-ref>
# Prints exactly one of the following to stdout and exits 0:
#   none  - <workflow-file-path> is byte-identical on HEAD and <base-ref>
#   stale - differs, but this branch's own commits never touched the
#           file since diverging from <base-ref> — dev's copy moved on
#           after the branch point, so rebasing/merging dev in fixes it
#   self  - differs, and this branch's own commits (since diverging from
#           <base-ref>) touched the file directly — rebasing can never
#           fix this, since the PR's own diff to the file persists no
#           matter how current the branch is
set -euo pipefail

FILE="$1"
BASE_REF="$2"

if git diff --quiet "$BASE_REF" -- "$FILE"; then
  echo "none"
  exit 0
fi

MERGE_BASE=$(git merge-base "$BASE_REF" HEAD)
if git diff --quiet "$MERGE_BASE" HEAD -- "$FILE"; then
  echo "stale"
else
  echo "self"
fi
