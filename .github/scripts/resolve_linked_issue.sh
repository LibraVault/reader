#!/usr/bin/env bash
# Prints the issue number a PR's body links via a GitHub closing keyword
# (Closes/Fixes/Resolves #N, any tense/plural GitHub itself recognizes for
# auto-linking). Prints nothing and exits 0 if none is found — the caller
# decides what "no linked issue" means for it. Shared by dev-agent.yml,
# qa-agent.yml, and principal-review.yml so they can't drift on what
# counts as a match.
#
# A genuine `gh pr view` failure (auth, rate limit, transient 5xx) is NOT
# treated the same as "no linked issue" — it exits non-zero instead, so
# callers running under `set -e` fail loudly with a distinct message
# rather than silently behaving as if the PR just has no linked issue.
#
# Usage: resolve_linked_issue.sh <pr-number>
# Requires GH_TOKEN in the environment (see gh-cli auth requirements).
set -uo pipefail

# Resolves the linked issue number(s) from a PR body passed on stdin.
#
# The keyword must be the first thing on its (trimmed) line, not just
# present anywhere in the body — deliberately stricter than GitHub's own
# auto-linker. Confirmed live (issue #228) that GitHub's real
# closingIssuesReferences is just as naive as a bare anywhere-in-body
# match: PR #225's body mentioned, in passing prose, "PR #224 (which
# fixes #223)" — background context, not PR #225's own closing keyword —
# and GitHub linked it as a real closing reference anyway. That's a
# footgun in GitHub's parser we can't fix, but this script's actual job
# (telling dev-agent/qa-agent/principal-review which issue's acceptance
# criteria a PR should be judged against) is a different, stricter
# question than "what would GitHub technically auto-close" — so matching
# GitHub's looser behavior here isn't a feature worth keeping. Anchoring
# to line-start (after optional list/heading markers) matches how every
# real PR in this pipeline already writes it ("Closes #N" alone on its
# own line) and rejects mid-sentence mentions like the one above.
#
# \b still anchors the keyword itself so it only matches at a real word
# start — without it, "the fix was disclosed #45" or "workaround enclosed
# #123" would match "closed #N" as a substring of "disclosed"/"enclosed".
#
# Echoes nothing and returns 0 if no closing line is found. Echoes the
# single issue number and returns 0 if exactly one distinct issue is
# linked. Echoes the distinct issue numbers (space-separated) and returns
# 1 if more than one is linked — ambiguous, since every downstream caller
# only supports a single linked issue; the caller decides how to surface
# that rather than this function silently picking one (the old `head -1`
# behavior this replaces — a body with two genuine-looking closing lines
# for different issues was previously resolved to whichever came first
# with no signal that the other was dropped).
resolve_from_body() {
  local body matches issue_numbers distinct
  body=$(cat)
  matches=$(echo "$body" | sed -E 's/^[[:space:]]*([#>*+-]+[[:space:]]*)*//' \
    | grep -ioE '^\b(close[sd]?|fix(e[sd])?|resolve[sd]?) #[0-9]+')
  issue_numbers=$(echo "$matches" | grep -oE '[0-9]+' | sort -un)
  distinct=$(echo "$issue_numbers" | grep -c . || true)

  echo "$issue_numbers"
  [ "$distinct" -gt 1 ] && return 1
  return 0
}

# Only run the gh-backed lookup when executed directly — test_resolve_linked_issue.sh
# sources this file to exercise resolve_from_body() in isolation, without a
# live `gh` call or network access.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  PR_NUMBER="$1"

  BODY=$(gh pr view "$PR_NUMBER" --json body -q '.body')
  GH_EXIT=$?
  if [ "$GH_EXIT" -ne 0 ]; then
    echo "resolve_linked_issue.sh: gh pr view failed for PR #$PR_NUMBER (exit $GH_EXIT) — infra failure, not \"no linked issue\"." >&2
    exit "$GH_EXIT"
  fi

  ISSUE_NUMBERS=$(echo "$BODY" | resolve_from_body)
  RESOLVE_EXIT=$?
  if [ "$RESOLVE_EXIT" -ne 0 ]; then
    echo "resolve_linked_issue.sh: PR #$PR_NUMBER's body has multiple distinct closing-issue lines ($(echo "$ISSUE_NUMBERS" | tr '\n' ' ')) — ambiguous, not resolving one automatically. A human needs to sort this out (single-issue linkage is all downstream callers support)." >&2
    exit 1
  fi

  echo "$ISSUE_NUMBERS"
  exit 0
fi
