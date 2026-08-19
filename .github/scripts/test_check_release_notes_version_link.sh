#!/usr/bin/env bash
# Regression test for check_release_notes_version_link.sh's
# contains_bare_version_token(), run directly (no CI job wires this in yet
# — matching how test_resolve_linked_issue.sh, the sibling script, is used).
# No network, no gh calls — sources the script and feeds canned
# release-notes strings on stdin.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/check_release_notes_version_link.sh"

FAILURES=0

# args: description, expected match (first hit, "" if none), body (stdin)
check() {
  local desc="$1" expected="$2" body="$3"
  local actual
  actual=$(echo "$body" | contains_bare_version_token | head -1)
  if [ "$actual" != "$expected" ]; then
    echo "FAIL: $desc"
    echo "  expected: '$expected'"
    echo "  actual:   '$actual'"
    FAILURES=$((FAILURES + 1))
  else
    echo "PASS: $desc"
  fi
}

check "real-world case: v-prefixed version with alpha suffix (issue #290)" \
  "0.4.6.1-alpha" \
  "Security fix (v0.4.6.1-alpha): creating an Encrypted Vault or setting a vault PIN could fail on some devices."

check "bare three-segment semver with no suffix" \
  "1.2.3" \
  "Bumped a dependency to 1.2.3 under the hood."

check "no version mention at all" \
  "" \
  "Fixed a crash when opening large EPUB files."

check "two-segment decimal is not flagged (section reference, not a version)" \
  "" \
  "See section 2.3 of the design doc for details."

check "one-dot version like iOS point release is not flagged" \
  "" \
  "Matches the behaviour already shipped in iOS 17.4."

check "guidance text referencing the build without a version number is not flagged" \
  "" \
  "See the release notes shown above for what changed in this build."

check "version-like token embedded mid-word still linkifies, so still flagged" \
  "10.20.30" \
  "build10.20.30final"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
