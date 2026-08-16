#!/usr/bin/env bash
# Counts currently in-progress runs across all three agent-pipeline
# workflows (dev-agent.yml, qa-agent.yml, principal-review.yml),
# excluding the current run itself.
#
# This is a system-wide guardrail, distinct from each workflow's own
# per-item concurrency group — those only prevent two runs from touching
# the SAME issue/PR at once, they don't bound total pipeline activity
# across every issue/PR at once. See docs/agent-team-pipeline.md's
# "Rollout plan" Phase 4.
#
# Usage: count_active_agent_runs.sh <current-run-id>
# Requires GH_TOKEN and GH_REPO (owner/repo) in the environment.
set -euo pipefail

CURRENT_RUN_ID="$1"
TOTAL=0
for WF in dev-agent.yml qa-agent.yml principal-review.yml; do
  COUNT=$(gh run list --workflow "$WF" --status in_progress --json databaseId \
    -q "[.[] | select(.databaseId != $CURRENT_RUN_ID)] | length")
  TOTAL=$((TOTAL + COUNT))
done
echo "$TOTAL"
