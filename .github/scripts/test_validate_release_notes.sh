#!/usr/bin/env bash
# Regression test for validate_release_notes.sh's
# release_notes_contains_bare_version(), run directly (no CI job wires
# this in yet -- same standalone-script pattern as
# test_resolve_linked_issue.sh in this directory). No `gh` calls, no
# network -- sources the script and feeds canned release-notes strings.
#
# Covers the issue #290 incident string (the actual RELEASE_NOTES value
# from the broken build-12 invite, run 32177679056 -- must block) and the
# workflow's own safe defaults / plain issue-reference text (must pass).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/validate_release_notes.sh"

FAILURES=0

# args: description, expected exit (0 = blocks/matches, 1 = passes), text
check() {
  local desc="$1" expected_exit="$2" text="$3"
  local actual_exit
  echo "$text" | release_notes_contains_bare_version
  actual_exit=$?
  if [ "$actual_exit" != "$expected_exit" ]; then
    echo "FAIL: $desc"
    echo "  expected exit=$expected_exit actual exit=$actual_exit"
    FAILURES=$((FAILURES + 1))
  else
    echo "PASS: $desc"
  fi
}

check "issue #290 incident string (real build-12 release notes) blocks" 0 \
  "Security fix (v0.4.6.1-alpha): creating an Encrypted Vault or setting a vault PIN could fail on some devices due to an Android Keystore compatibility bug introduced in 0.4.6-alpha."

check "workflow's own default release notes passes" 1 \
  "Beta build via GitHub Actions"

check "plain issue-reference text passes" 1 \
  "Fixes some bugs, see #290 and #285"

check "bare version without a v prefix still blocks" 0 \
  "Bumped to 1.2.3 for this release."

check "single-segment number with no dots passes" 1 \
  "Fixed 12 crashes reported by testers."

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
