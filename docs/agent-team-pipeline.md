# Agent-team pipeline: issue → dev → qa → principal review → merge

Status: **Phase 1 built** (`.github/workflows/dev-agent.yml`) but **not yet
proven live** — it needs an `ANTHROPIC_API_KEY` repository secret before
its first real run, and hasn't triaged a real issue yet. Phases 2-3 (QA,
principal review workflows) don't exist yet; everything past `status:needs-qa`
today needs a human.

Goal: scale development by moving triage and first-pass implementation off the
critical path of a human, while keeping a risk-based human checkpoint for
anything that isn't obviously safe to auto-merge.

## Roles

| Role | Who | Responsibility |
|---|---|---|
| Filer | you | Files issues with the bug/feature templates. |
| Dev agent | `.claude/agents/dev-agent.md` | Triages new issues, implements fixes/features on a branch, opens PRs. |
| QA agent | `.claude/agents/qa-agent.md` | Verifies the PR against the issue's acceptance criteria and runs the real path, not just mocks. |
| Principal review agent | `.claude/agents/principal-review-agent.md` | High-effort code review with a "verify against ground truth" mindset; classifies merge risk. |
| You | — | Final merge call on anything not auto-merged; can pause the whole pipeline at any time. |

## State machine (GitHub issue/PR labels)

```
issue opened
   │
   ▼
status:triage ──────────────────────────────► status:needs-info
   │  (dev agent triages; if underspecified        (dev agent asks for more
   │   or touches a sensitive area it can't          detail on the issue,
   │   confidently scope, it stops here)             stops — no PR opened)
   ▼
status:ready-for-dev
   │  (dev agent implements fix/feature + tests, opens PR referencing the issue)
   ▼
status:needs-qa
   │  (qa agent runs test suites + verifies acceptance criteria on the real path)
   ├─ fail (≤ 2 auto-retries) ──► status:in-progress ──► back to dev agent
   ├─ fail (retries exhausted) ─► status:needs-info
   ▼ pass
status:needs-review
   │  (principal review agent runs a high-effort review, reads the
   │   risk:* label already set by classify_pr_risk.py)
   ├─ risk:low  + no findings ──► status:approved-auto-merge ──► AUTO-MERGE
   └─ risk:high OR any finding ─► status:needs-human-merge ────► you decide
```

At any point, applying `status:blocked` to an issue or PR stops every agent
from acting on it — a per-item pause. For a repo-wide stop, every workflow
checks the `AGENTS_PAUSED` repository variable (Settings → Actions →
Variables) at job level; setting it to `true` stops every stage from
picking up any new work without touching any labels. (A label can express
"stop this item," not "stop everything," which is why the two mechanisms
are deliberately different knobs.)

## Risk classification

Defined in [`.github/agent-policy.yml`](../.github/agent-policy.yml) and
enforced by [`classify_pr_risk.py`](../.github/scripts/classify_pr_risk.py)
as a deterministic workflow step, not an LLM judgment call — it runs on
every push to a PR and sets `risk:low`/`risk:high` directly. A PR is
`risk:high` — and therefore always ends at `status:needs-human-merge`,
regardless of what QA or review conclude — if it touches any path on that
policy's sensitive list, or its diff exceeds the configured line threshold.
See the policy file itself for the exact patterns and threshold; it's the
single source of truth, not restated here. A CONFIRMED finding from the
principal review agent blocks auto-merge outright either way, independent
of risk tier.

## Non-negotiables every agent inherits

These are already binding project rules (see [`AGENTS.md`](../AGENTS.md) and
`CONTRIBUTING.md`); the agent personas reference them explicitly so CI runs
don't silently drift from what's expected of a human contributor:

- Feature/fix work happens on `feature/*` or `fix/*` branches off `dev`.
- Non-trivial changes ship with unit tests (see AGENTS.md for what counts as
  non-trivial and the test conventions).
- No hardcoded dependency versions — reference the version catalog.
- No new networking dependencies or network calls added without asking a
  human first, even test-only/zero-I/O ones.
- GitHub actions taken by agents never use a personal `gh auth switch` —
  that mechanism is for interactive sessions, not CI. Phase 1 uses the
  workflow-scoped default `GITHUB_TOKEN` (auto-rotated, permissions
  declared per-workflow, no new credential to store). Note this has a real
  limit: GitHub does not let actions taken with that token trigger further
  workflow runs, so a label it applies won't itself fire Phase 2/3 — that
  needs revisiting (likely a scoped bot PAT or GitHub App, deliberately
  *not* reusing the interactive `libravault-xyz` session token, which is
  broader than this needs) when those phases are actually wired up.

## Rollout plan

1. **Phase 0 (PR #146)** — issue templates, label taxonomy, agent-policy.yml,
   agent persona files, this doc. No live automation.
2. **Phase 1 (built, unproven)** — `dev-agent.yml` workflow (triage + fix),
   triggered on issue open / `status:ready-for-dev` label, or manually via
   `workflow_dispatch` for testing against a specific issue number. Needs
   an `ANTHROPIC_API_KEY` repo secret before it can run at all.
3. **Phase 2** — `qa-agent.yml` workflow, triggered on `status:needs-qa`.
4. **Phase 3** — `principal-review.yml` workflow, triggered on
   `status:needs-review`; implements the auto-merge/human-merge split.
5. **Phase 4** — guardrails: concurrency cap, `AGENTS_PAUSED` kill switch,
   auto-merge log.
6. **Phase 5** — pilot on 2-3 low-risk open `reader` issues with auto-merge
   forced off (dry run) before trusting it live. Only after that, consider
   extending beyond `reader`.
