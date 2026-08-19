#!/usr/bin/env bash
# Detects a bare, dotted version-like string (e.g. v0.4.6.1-alpha) in
# Firebase App Distribution release notes text. Firebase's tester invite
# page auto-linkifies any dot-separated, domain-shaped token in the
# release notes -- without validating a real TLD or requiring a scheme --
# so a mention like "v0.4.6.1-alpha" renders as a dead link to
# http://0.4.6.1-alpha (see issue #290). Firebase's own page heading
# already shows the version + build number, so restating it in the notes
# body is both redundant and broken.
#
# Usage as a library: source this file and pipe text into
# release_notes_contains_bare_version to test it in isolation (exit 0 =
# blocked/matches, exit 1 = passes) -- used by
# test_validate_release_notes.sh, no `gh` calls or network involved.
#
# Usage as a CI step: validate_release_notes.sh, reading RELEASE_NOTES
# from the environment (see android-firebase-distribution.yml). Exits 1
# and prints a ::error:: annotation if a bare version string is found.
set -uo pipefail

release_notes_contains_bare_version() {
  grep -qE '\bv?[0-9]+(\.[0-9]+){1,3}(-[A-Za-z0-9]+)?\b'
}

# Only run the CI-step behavior when executed directly --
# test_validate_release_notes.sh sources this file to exercise
# release_notes_contains_bare_version() in isolation.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  if echo "$RELEASE_NOTES" | release_notes_contains_bare_version; then
    echo "::error::release_notes contains a bare version string (e.g. v0.4.6.1-alpha)." \
      "Firebase's tester invite page auto-linkifies this into a dead link (issue #290)." \
      "Reference the build without restating the version -- Firebase's own page heading" \
      "already shows it -- and re-run."
    exit 1
  fi
  exit 0
fi
