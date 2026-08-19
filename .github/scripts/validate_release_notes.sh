#!/usr/bin/env bash
# Detects a dot-separated, domain-shaped numeric token (e.g. v0.4.6.1-alpha,
# but also plain decimals like "8.0" or an IP like "192.168.1.1") in
# Firebase App Distribution release notes text. Firebase's tester invite
# page auto-linkifies tokens shaped like this -- without validating a real
# TLD or requiring a scheme -- so a mention like "v0.4.6.1-alpha" renders as
# a dead link to http://0.4.6.1-alpha (see issue #290).
#
# We can't inspect Firebase's actual linkify implementation, so this
# deliberately errs broad: it also blocks ordinary numbers that happen to
# look version-shaped (an OS version, a percentage, an IP) even though
# those may not actually get linkified. A false-positive CI failure on a
# manual workflow_dispatch run just means editing the notes and re-running
# -- no data loss, no broken build -- versus a false negative shipping
# another dead link to testers. See principal review on PR #291 for the
# discussion; narrowing this to be more precise is a legitimate future
# improvement but requires knowing Firebase's real heuristic to do safely.
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
    echo "::error::release_notes contains a dot-separated, version-looking token" \
      "(e.g. v0.4.6.1-alpha -- but this also catches plain numbers like an OS version," \
      "a percentage, or an IP address). Firebase's tester invite page may auto-linkify" \
      "it into a dead link (issue #290), and we can't verify its exact heuristic, so this" \
      "errs on the side of blocking. If it's a version/build mention: remove it, Firebase's" \
      "own page heading already shows the version + build number. If it's an unrelated" \
      "number caught by mistake: rephrase to avoid a bare dotted token (e.g. spell it out)" \
      "and re-run."
    exit 1
  fi
  exit 0
fi
