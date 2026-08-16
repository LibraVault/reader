#!/usr/bin/env bash
# Prints the number of the first OPEN PR whose body links to the given
# issue via a GitHub closing keyword (Closes/Fixes/Resolves #N), using the
# same word-boundary-anchored match as resolve_linked_issue.sh — so the
# two scripts can't drift on what counts as a link. Prints nothing and
# exits 0 if no such PR exists.
#
# Two-stage on purpose: GitHub's --search is a loose full-text index (it
# can match the keyword and the bare issue number appearing ANYWHERE in
# the body, not necessarily together as a real link, and can lag a
# just-created PR by several seconds) — used here only to narrow the
# candidate list cheaply. Each candidate is then re-verified through
# resolve_linked_issue.sh's precise regex before being trusted, so a PR
# that merely mentions the issue number in prose elsewhere in its body
# can't produce a false-positive match.
#
# Usage: find_linked_pr.sh <issue-number> [max-search-attempts, default 5]
# Requires GH_TOKEN in the environment.
set -uo pipefail

ISSUE_NUMBER="$1"
ATTEMPTS="${2:-5}"

for i in $(seq 1 "$ATTEMPTS"); do
  CANDIDATES=$(gh pr list --state open \
    --search "(Closes #${ISSUE_NUMBER} OR Fixes #${ISSUE_NUMBER} OR Resolves #${ISSUE_NUMBER}) in:body" \
    --json number -q '.[].number')
  GH_EXIT=$?
  if [ "$GH_EXIT" -ne 0 ]; then
    echo "find_linked_pr.sh: gh pr list failed (exit $GH_EXIT) — infra failure, not \"no linked PR\"." >&2
    exit "$GH_EXIT"
  fi

  for candidate in $CANDIDATES; do
    LINKED=$(.github/scripts/resolve_linked_issue.sh "$candidate") || exit $?
    if [ "$LINKED" = "$ISSUE_NUMBER" ]; then
      echo "$candidate"
      exit 0
    fi
  done

  # Only retry the empty-candidates case — that's the search-index-lag
  # scenario. A non-empty candidate list that verified to nothing real is
  # not a timing issue, so don't burn attempts on it.
  if [ -z "$CANDIDATES" ] && [ "$i" -lt "$ATTEMPTS" ]; then
    sleep 5
  fi
done

exit 0
