#!/usr/bin/env bash
# Prints the crash-recoverable verdict (auto-merge|human-merge) from the
# most recent PR review/comment whose body carries the principal review
# agent's own `## Principal review — verdict: \`X\`` line. Prints nothing
# and exits 0 if none is found.
#
# Used by principal-review.yml's crash-recovery step so a review that
# completed and was already posted to GitHub — but crashed on a later
# turn before writing principal-verdict.txt (e.g. the org's weekly API
# rate cap hitting right after `gh pr review`, see issue #426) — isn't
# discarded to status:needs-info the way an actually-unwritten review is.
#
# Usage: extract_review_verdict.sh <pr-number>
# Requires GH_TOKEN in the environment (see gh-cli auth requirements).
set -uo pipefail

# Extracts the verdict from a single review/comment body passed on
# stdin. Echoes "auto-merge" or "human-merge" (lowercased) if the body
# contains a `verdict: \`X\`` line, nothing otherwise. Always returns 0 —
# "no verdict in this body" isn't an error, just a non-match, same as
# resolve_linked_issue.sh's resolve_from_body.
extract_verdict_from_body() {
  grep -ioE 'verdict: `(auto-merge|human-merge)`' \
    | tail -1 \
    | grep -ioE 'auto-merge|human-merge' \
    | tr '[:upper:]' '[:lower:]'
  return 0
}

# Only run the gh-backed lookup when executed directly —
# test_extract_review_verdict.sh sources this file to exercise
# extract_verdict_from_body() in isolation, without a live `gh` call or
# network access.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  PR_NUMBER="$1"

  # Reviews and comments are two separate GitHub objects — the persona
  # posts an auto-merge verdict via a plain `gh pr comment` and a
  # human-merge verdict via `gh pr review --request-changes` (or
  # sometimes also a plain comment), so both lists have to be checked,
  # merged, and re-sorted by time to find whichever the agent actually
  # posted last.
  LATEST_BODY=$(gh pr view "$PR_NUMBER" --json reviews,comments -q '
    ( [.reviews[] | {body, at: .submittedAt}] + [.comments[] | {body, at: .createdAt}] )
    | map(select(.body | test("verdict: `(auto-merge|human-merge)`"; "i")))
    | sort_by(.at)
    | last
    | .body // ""
  ')
  GH_EXIT=$?
  if [ "$GH_EXIT" -ne 0 ]; then
    echo "extract_review_verdict.sh: gh pr view failed for PR #$PR_NUMBER (exit $GH_EXIT)." >&2
    exit "$GH_EXIT"
  fi

  echo "$LATEST_BODY" | extract_verdict_from_body
  exit 0
fi
