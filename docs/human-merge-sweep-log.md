# Human-merge sweep log

Every state-changing action performed unattended by
[`.github/workflows/human-merge-sweep.yml`](../.github/workflows/human-merge-sweep.yml)
(`.claude/agents/human-merge-sweep-agent.md`) — a merge, a stale/superseded
close, a needs-info retry, or a circuit-breaker escalation — appended
right after the action by the agent itself. Posting a comment does NOT
get a row here (it's already
visible on the issue/PR itself); this log is specifically for the actions
that change pipeline state without a human directly triggering them. A
durable, human-readable audit trail, distinct from
[`auto-merge-log.md`](auto-merge-log.md), which records the fully
`risk:low` auto-merge path inside `principal-review.yml`. Every entry here
represents something Claude did a second time, on a schedule, standing in
for a human step at the end of `docs/agent-team-pipeline.md`'s state
machine — never a product/scope decision. Part 3's needs-info handling
adds a row for a retry (a bounded, at-most-once, reversible label change)
and for a circuit-breaker escalation (`status:needs-info` → `status:
escalated` after 3+ lifetime occurrences — irreversible by this
automation, only a human undoes it); a plain clarifying comment left for
a human to answer does not get a row, since it changes nothing and is
already visible in place.

| When (UTC) | Item | Title | Action | Reason |
|---|---|---|---|---|
| 2026-08-20T07:07:58Z | [#332](https://github.com/LibraVault/reader/pull/332) | fix(ios): EPUB reader shows real page count, not chapter count | closed (superseded) | Issue #331 already closed by merged [#333](https://github.com/LibraVault/reader/pull/333), an identical `TextPaginator`-based fix. Backfilled: the run that closed this (first end-to-end `workflow_dispatch` test) predated this log-entry instruction existing. |
| 2026-08-20T07:08:33Z | [#295](https://github.com/LibraVault/reader/pull/295) | fix(ios-reader): guarantee an exit from Markdown reader when toolbar hides | closed (superseded) | Issue #293 already closed by merged [#296](https://github.com/LibraVault/reader/pull/296), identical `.simultaneousGesture` → `.highPriorityGesture` fix. Backfilled: the run that closed this (first end-to-end `workflow_dispatch` test) predated this log-entry instruction existing. |
| 2026-08-24T12:59:01Z | [#507](https://github.com/LibraVault/reader/pull/507) | feat(ios): let user pick a specific system TTS voice | closed (superseded) | Issue #506 already closed by merged [#510](https://github.com/LibraVault/reader/pull/510), same title/feature. This PR had gone `CONFLICTING`/`DIRTY` against `dev`. |
| 2026-08-24T12:59:01Z | [#511](https://github.com/LibraVault/reader/pull/511) | fix(player): stop lockscreen media tile from showing a duplicate Play button | retry (capacity skip) | `status:needs-review` re-applied — the most recent comment was a Phase 4 concurrency-cap skip (`3/3 active runs`) with no later progress/label change since, not a real question. |
| 2026-08-24T16:43:06Z | [#511](https://github.com/LibraVault/reader/pull/511) | fix(player): stop lockscreen media tile from showing a duplicate Play button | merged | Reviewed diff and regression test for real: `.remove(Player.COMMAND_PLAY_PAUSE)` matches the diagnosed root cause, new Robolectric test confirmed red pre-fix / green post-fix, no sensitive paths touched, `risk:low` correct, QA + principal review both passed. CI green, `mergeStateStatus: CLEAN`. |
| 2026-08-24T16:43:06Z | [#530](https://github.com/LibraVault/reader/issues/530) | Code review cleanup batch: 8 low-severity findings (L1-L8) | retry (capacity skip) | `status:ready-for-dev` re-applied — the most recent comment was a Phase 4 concurrency-cap skip (`3/3 active runs`) with no later progress/label change since, not a real question. |
| 2026-08-24T16:43:06Z | [#5](https://github.com/LibraVault/reader/issues/5) | [enhancement] Autoscroll for ePub and PDF | retry (capacity skip) | `status:ready-for-dev` re-applied — the most recent comment was a Phase 4 concurrency-cap skip (`3/3 active runs`) with no later progress/label change since, not a real question. |
