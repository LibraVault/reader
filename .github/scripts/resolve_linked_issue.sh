#!/usr/bin/env bash
# Prints the issue number a PR's body links via a GitHub closing keyword
# (Closes/Fixes/Resolves #N, any tense/plural GitHub itself recognizes for
# auto-linking). Prints nothing and exits 0 if none is found — the caller
# decides what "no linked issue" means for it. Shared by dev-agent.yml and
# qa-agent.yml so the two workflows can't drift on what counts as a match.
#
# Usage: resolve_linked_issue.sh <pr-number>
# Requires GH_TOKEN in the environment (see gh-cli auth requirements).
set -euo pipefail

PR_NUMBER="$1"
gh pr view "$PR_NUMBER" --json body -q '.body' \
  | grep -ioE '(close[sd]?|fix(e[sd])?|resolve[sd]?) #[0-9]+' \
  | head -1 | grep -oE '[0-9]+' || true
