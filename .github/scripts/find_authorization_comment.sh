#!/usr/bin/env bash
# Prints the most recent retroactive authorization comment for the
# sensitive_issue_labels gate (see agent-policy.yml's
# sensitive_issue_labels_authorization_marker section), if one exists on
# the given issue or PR — prints nothing and exits 0 if none is found.
#
# A comment counts only if it:
#   - contains a line starting with exactly "AGENT-AUTHORIZE:" (the marker
#     from agent-policy.yml), and
#   - was posted by an author whose author_association is OWNER, MEMBER,
#     or COLLABORATOR (same bar as dev-agent.yml's trust gate).
#
# This script only *finds* a candidate comment — it does not (and can't,
# from a plain script) judge whether the scope it states actually covers
# a given piece of work. That judgment is left to whichever agent
# receives this output. See issue #675.
#
# Usage: find_authorization_comment.sh <issue-or-pr-number>
# Requires GH_TOKEN in the environment; must be run from inside the
# repo's working directory (gh resolves the repo from the local git
# remote). Works for both issues and PRs — GitHub's REST API serves a
# PR's general (non-review) comments from the same issues/comments
# endpoint issues use.
set -euo pipefail

NUMBER="$1"

MARKER="AGENT-AUTHORIZE:"

# Compact (one-JSON-object-per-line) output, not gh's own -q/--jq
# filtering: a comment body legitimately contains newlines, and this
# script needs to tell where one comment ends and the next begins, so
# each candidate is kept as a single JSON-encoded line (newlines
# escaped as \n) until it's decoded back out at the end.
#
# Written to a temp file rather than piped into the while/read loop via
# process substitution deliberately: a `gh api` failure inside
# `< <(...)` runs in a subshell whose exit status the outer `read` never
# sees, so `set -e` can't catch it and the script would silently report
# "no authorization found" instead of "couldn't check" — the same
# infra-failure-vs-empty-result distinction find_linked_pr.sh's own
# comments call out. Capturing the pipeline's real exit status via a
# file plus PIPESTATUS avoids that.
TMP_COMMENTS=$(mktemp)
trap 'rm -f "$TMP_COMMENTS"' EXIT

gh api "repos/{owner}/{repo}/issues/${NUMBER}/comments" --paginate \
  --jq '.[] | select(.author_association == "OWNER" or .author_association == "MEMBER" or .author_association == "COLLABORATOR")' \
  | jq -c '.' > "$TMP_COMMENTS"
GH_EXIT="${PIPESTATUS[0]}"
if [ "$GH_EXIT" -ne 0 ]; then
  echo "find_authorization_comment.sh: gh api failed (exit $GH_EXIT) fetching comments for #${NUMBER} — infra failure, not \"no authorization found\"." >&2
  exit "$GH_EXIT"
fi

LAST_MATCH=""
while IFS= read -r COMMENT_JSON; do
  BODY=$(printf '%s' "$COMMENT_JSON" | jq -r '.body')
  if printf '%s\n' "$BODY" | grep -q "^${MARKER}"; then
    LAST_MATCH="$BODY"
  fi
done < "$TMP_COMMENTS"

if [ -n "$LAST_MATCH" ]; then
  printf '%s' "$LAST_MATCH"
fi
