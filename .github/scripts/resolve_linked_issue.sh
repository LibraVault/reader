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

PR_NUMBER="$1"

BODY=$(gh pr view "$PR_NUMBER" --json body -q '.body')
GH_EXIT=$?
if [ "$GH_EXIT" -ne 0 ]; then
  echo "resolve_linked_issue.sh: gh pr view failed for PR #$PR_NUMBER (exit $GH_EXIT) — infra failure, not \"no linked issue\"." >&2
  exit "$GH_EXIT"
fi

# \b anchors the keyword so it only matches at a real word start — without
# it, "the fix was disclosed #45" or "workaround enclosed #123" would
# match "closed #N" as a substring of "disclosed"/"enclosed" and resolve
# to a completely unrelated issue.
echo "$BODY" | grep -ioE '\b(close[sd]?|fix(e[sd])?|resolve[sd]?) #[0-9]+' | head -1 | grep -oE '[0-9]+'
exit 0
