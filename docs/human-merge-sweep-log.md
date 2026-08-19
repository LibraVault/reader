# Human-merge sweep log

Every merge and every stale/superseded close performed unattended by
[`.github/workflows/human-merge-sweep.yml`](../.github/workflows/human-merge-sweep.yml)
(`.claude/agents/human-merge-sweep-agent.md`), appended right after the
action by the agent itself. This is a durable, human-readable audit trail
of everything this specific automation has ever done without a human
directly clicking the button — distinct from
[`auto-merge-log.md`](auto-merge-log.md), which records the fully
`risk:low` auto-merge path inside `principal-review.yml`. Every entry here
represents a `risk:high` (or otherwise human-merge-routed) PR that Claude
reviewed a second time, on a schedule, and judged safe — standing in for
the human step at the end of `docs/agent-team-pipeline.md`'s state
machine.

| When (UTC) | PR | Title | Action | Reason |
|---|---|---|---|---|
