#!/usr/bin/env bash
# Rejects release-notes text that contains a bare dotted version-like token
# (e.g. "0.4.6.1-alpha", "v0.4.6.1-alpha", "1.2.3"). Firebase App
# Distribution's tester invite page auto-linkifies dot-separated,
# domain-shaped tokens without validating a real TLD or requiring a scheme,
# turning a version mention in our own release-notes text into a dead
# "Server Not Found" link (confirmed live on the 0.4.6.1-alpha / build 12
# invite page, run 32177679056 — see issue #290). Firebase's own
# auto-generated page heading already shows "Release notes for X (build)",
# so restating the version in the notes body is redundant as well as
# broken.
#
# This only guards our own release-notes text — Firebase's auto-generated
# heading link is templated from the APK's versionName/versionCode by
# Firebase itself and is not fixable from this repo (see issue #290).
#
# Usage: printf '%s' "$RELEASE_NOTES" | check_release_notes_version_link.sh
# Exits 1 and prints the offending token to stderr if a bare version-like
# token is found; exits 0 silently otherwise.
set -uo pipefail

# A version-like token needs at least two dots (three numeric segments,
# e.g. "1.2.3") to avoid flagging ordinary prose like "section 2.3" or
# "iOS 17.4" (one dot). It may carry a trailing "-alpha"/"-rc1"-style
# suffix. Deliberately not anchored to a word boundary before the digits:
# the reported case ("v0.4.6.1-alpha") still linkifies starting at the
# first digit, with the leading "v" left outside the link, so a leading
# letter must not exempt the token.
VERSION_TOKEN_RE='[0-9]+(\.[0-9]+){2,}(-[A-Za-z0-9]+)?'

contains_bare_version_token() {
  grep -EoI "$VERSION_TOKEN_RE"
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  MATCH=$(cat | contains_bare_version_token | head -1)
  if [ -n "$MATCH" ]; then
    echo "check_release_notes_version_link.sh: release_notes contains a bare version-like token ('$MATCH')." >&2
    echo "Firebase's tester invite page auto-linkifies dot-separated tokens like this into a dead link (see issue #290) — the page's own auto-generated heading already shows the version and build number, so there's no need to restate it here." >&2
    exit 1
  fi
  exit 0
fi
