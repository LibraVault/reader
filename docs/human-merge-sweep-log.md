# Human-merge sweep log

Every state-changing action performed unattended by
[`.github/workflows/human-merge-sweep.yml`](../.github/workflows/human-merge-sweep.yml)
(`.claude/agents/human-merge-sweep-agent.md`) — a merge, a stale/superseded
close, or a needs-info retry — appended right after the action by the
agent itself. Posting a comment does NOT get a row here (it's already
visible on the issue/PR itself); this log is specifically for the actions
that change pipeline state without a human directly triggering them. A
durable, human-readable audit trail, distinct from
[`auto-merge-log.md`](auto-merge-log.md), which records the fully
`risk:low` auto-merge path inside `principal-review.yml`. Every entry here
represents something Claude did a second time, on a schedule, standing in
for a human step at the end of `docs/agent-team-pipeline.md`'s state
machine — never a product/scope decision. Part 3's needs-info handling
only ever adds a row for a retry (a bounded, at-most-once, reversible
label change); a plain clarifying comment left for a human to answer does
not, since it changes nothing and is already visible in place.

| When (UTC) | Item | Title | Action | Reason |
|---|---|---|---|---|
