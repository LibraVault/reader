#!/usr/bin/env bash
# Regression test for classify_workflow_drift.sh, run directly (same
# standalone-script pattern as the other test_*.sh scripts in this
# directory). Builds real throwaway git repos under a temp dir and
# exercises the script's actual `git diff`/`git merge-base` calls — no
# stubbing, since the whole point of the script is real git plumbing.
#
# Covers the issue #401 distinction: a workflow file differing from the
# base branch because the base branch moved on (stale, rebase fixes it)
# versus differing because the PR branch's own commits touched the file
# directly (self, rebasing can never fix it — the diff persists no
# matter how current the branch is).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSIFY="$SCRIPT_DIR/classify_workflow_drift.sh"

FAILURES=0

# Builds a fresh repo with a base branch ("main") holding one workflow
# file, and a PR branch ("pr") diverging from it. Echoes the repo dir.
new_repo() {
  local dir
  dir=$(mktemp -d)
  git -C "$dir" init -q -b main
  git -C "$dir" config user.email "test@example.com"
  git -C "$dir" config user.name "Test"
  mkdir -p "$dir/.github/workflows"
  echo "version: 1" > "$dir/.github/workflows/qa-agent.yml"
  echo "unrelated" > "$dir/.github/workflows/other.yml"
  git -C "$dir" add -A
  git -C "$dir" commit -q -m "base"
  git -C "$dir" checkout -q -b pr
  echo "$dir"
}

# args: description, expected output, setup script (run with cwd in the
# repo, as a `bash -c` body — plain statements, not a function)
check() {
  local desc="$1" expected="$2" setup="$3"
  local dir actual
  dir=$(new_repo)
  (cd "$dir" && bash -c "$setup")
  actual=$(cd "$dir" && "$CLASSIFY" .github/workflows/qa-agent.yml main)
  if [ "$actual" != "$expected" ]; then
    echo "FAIL: $desc"
    echo "  expected: $expected"
    echo "  actual:   $actual"
    FAILURES=$((FAILURES + 1))
  else
    echo "PASS: $desc"
  fi
  rm -rf "$dir"
}

check "identical file on both branches is none" "none" \
  ""

check "PR branch untouched, base branch moved the file on is stale (ordinary rebase-fixes-it case)" "stale" \
  '
    git checkout -q main
    echo "version: 2" > .github/workflows/qa-agent.yml
    git commit -qam "dev moved the file on"
    git checkout -q pr
  '

check "PR branch itself modifies the file is self, even freshly rebased onto current base (issue #401 core case)" "self" \
  '
    echo "version: 2 (pr edit)" > .github/workflows/qa-agent.yml
    git commit -qam "PR modifies its own running workflow"
  '

check "PR branch modifies the file AND base also moved on since — still self, not stale (rebasing does not help)" "self" \
  '
    echo "version: 2 (pr edit)" > .github/workflows/qa-agent.yml
    git commit -qam "PR modifies its own running workflow"
    git checkout -q main
    echo "version: 3 (dev edit)" > .github/workflows/qa-agent.yml
    git commit -qam "dev also moved the file on"
    git checkout -q pr
  '

check "PR branch touches an unrelated file only is none — the workflow file itself is untouched" "none" \
  '
    echo "pr change" > .github/workflows/other.yml
    git commit -qam "unrelated change"
  '

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
