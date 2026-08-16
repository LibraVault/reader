#!/usr/bin/env bash
# Counts currently active (queued, requested, or in-progress) runs across
# all three agent-pipeline workflows (dev-agent.yml, qa-agent.yml,
# principal-review.yml), excluding the current run itself.
#
# This is a system-wide guardrail, distinct from each workflow's own
# per-item concurrency group — those only prevent two runs from touching
# the SAME issue/PR at once, they don't bound total pipeline activity
# across every issue/PR at once. See docs/agent-team-pipeline.md's
# "Rollout plan" Phase 4.
#
# Best-effort, not a hard lock: this is check-then-act with no locking,
# so several triggers firing within the same few seconds (e.g. bulk-
# labeling issues) can each see a count under the cap and all proceed —
# acceptable for this project's scale, not a guarantee.
#
# Prints "<active-count> <max>" to stdout (max defaults to 3 if the
# second argument is empty/omitted, so the default lives here once
# rather than being duplicated in every calling workflow).
#
# Usage: count_active_agent_runs.sh <current-run-id> [max]
# Requires GH_TOKEN in the environment; must be run from inside the
# repo's working directory (gh resolves the repo from the local git
# remote — no GH_REPO override needed for how this is actually called).
set -euo pipefail

CURRENT_RUN_ID="$1"
MAX="${2:-}"
: "${MAX:=3}"

TOTAL=0
for WF in dev-agent.yml qa-agent.yml principal-review.yml; do
  COUNT=$(gh run list --workflow "$WF" --json databaseId,status \
    -q "[.[] | select(.databaseId != $CURRENT_RUN_ID and (.status == \"in_progress\" or .status == \"queued\" or .status == \"requested\"))] | length")
  TOTAL=$((TOTAL + COUNT))
done
echo "$TOTAL $MAX"
