#!/usr/bin/env bash
# Prints the number of the first OPEN PR that links to the given issue via
# a GitHub closing keyword (Closes/Fixes/Resolves #N) — checked in the PR
# body first, then (if that finds nothing) in the PR's commit messages,
# using the same word-boundary-anchored match as
# resolve_linked_issue.sh's resolve_from_body() so every call site agrees
# on what counts as a link. Prints nothing and exits 0 if no such PR
# exists.
#
# Body stage is two-stage on purpose: GitHub's --search is a loose
# full-text index (it can match the keyword and the bare issue number
# appearing ANYWHERE in the body, not necessarily together as a real
# link, and can lag a just-created PR by several seconds) — used here
# only to narrow the candidate list cheaply. Each candidate is then
# re-verified through resolve_linked_issue.sh's precise regex before
# being trusted, so a PR that merely mentions the issue number in prose
# elsewhere in its body can't produce a false-positive match.
#
# Commit-message stage (issue #317): a PR whose body uses a looser phrase
# like "Implements #309:" instead of a real closing line is invisible to
# the body stage above even if one of its commits does say "Closes #309"
# — this happened for real on PR #310. GitHub's --search only indexes PR
# bodies, not commit messages, so there's no equivalent cheap-narrowing
# search here; this stage walks every open PR's commits directly instead.
#
# Usage: find_linked_pr.sh <issue-number> [max-search-attempts, default 5]
# Requires GH_TOKEN in the environment.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/resolve_linked_issue.sh"

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

# Fallback: the issue's closing line lives in a commit message instead of
# the PR body. No search index to lean on here, so this walks every open
# PR's commits directly rather than retrying — a plain listing isn't
# subject to the same index-lag as --search.
ALL_OPEN=$(gh pr list --state open --json number -q '.[].number')
GH_EXIT=$?
if [ "$GH_EXIT" -ne 0 ]; then
  echo "find_linked_pr.sh: gh pr list failed (exit $GH_EXIT) while listing open PRs for the commit-message fallback — infra failure, not \"no linked PR\"." >&2
  exit "$GH_EXIT"
fi

for candidate in $ALL_OPEN; do
  COMMIT_TEXT=$(gh pr view "$candidate" --json commits \
    -q '.commits[] | (.messageHeadline // "") + "\n" + (.messageBody // "")')
  GH_EXIT=$?
  if [ "$GH_EXIT" -ne 0 ]; then
    echo "find_linked_pr.sh: gh pr view --json commits failed for PR #$candidate (exit $GH_EXIT) — infra failure, not \"no linked PR\"." >&2
    exit "$GH_EXIT"
  fi

  # resolve_from_body's exit code signals "more than one distinct issue
  # closed in this text" — irrelevant here, since this stage only asks
  # "does candidate's commit history close ISSUE_NUMBER", not "what's the
  # single issue this PR closes". A PR whose commits close #100 and #309
  # should still match a lookup for #309.
  MATCHED_ISSUES=$(echo "$COMMIT_TEXT" | resolve_from_body)
  if echo "$MATCHED_ISSUES" | grep -qx "$ISSUE_NUMBER"; then
    echo "$candidate"
    exit 0
  fi
done

exit 0
