# Agent-team pipeline: issue → dev → qa → principal review → merge

Status: **Phases 1-3 proven live**, full issue → dev → qa → principal
review → merge cycle. Disposable issue #182 went through triage,
implementation (PR #187), QA verification, principal review, and merge —
all three workflows firing automatically off label changes, no manual
intervention beyond filing the issue and one final human-merge call (CI
queue congestion from same-day repeated testing pushed a CI-wait past its
20-minute budget; the pipeline correctly deferred to a human rather than
guess). Five real bugs were found and fixed live in the process — see
`.claude/agents/*.md` and the three workflow files' own comments for the
specifics (PR creation needing the App token not GITHUB_TOKEN,
claude-code-action's non-human-actor guard needing `allowed_bots`, an
identity collision between PR-authoring and PR-reviewing once both used
the App token, GITHUB_TOKEN being unable to *approve* PRs at all
independent of identity, and a stale opposite-outcome label not being
cleared on a second review round).

**Auth: subscription OAuth token, not an API key.** The workflow runs on
GitHub-hosted (ephemeral) runners and authenticates via
`CLAUDE_CODE_OAUTH_TOKEN`, generated once by running `claude setup-token`
interactively (it's tied to a Claude subscription — Pro/Max/Team — not a
separate pay-per-token API key) and stored as a repo secret. This was a
deliberate choice over both a raw `anthropic_api_key` (needs separate API
billing) and running the agent directly on `rob-dev` (which also hosts
production-adjacent infrastructure — a self-hosted runner or local cron
poller there would give an agent, triggered by public-repo issue content,
Bash access to that same machine; GitHub's ephemeral runners keep the
blast radius of a bad or injected run contained to a throwaway VM).

**Trust boundary**: `reader` is a public repo, so `dev-agent.yml`'s
`issues: opened` trigger would otherwise let any anonymous GitHub user hand
a prompt to an agent holding repo-write permissions and a Bash tool, just by
filing an issue. The workflow gates on `github.event.issue.author_association`
being `OWNER`/`MEMBER`/`COLLABORATOR` — only people with at least write
access to the repo can trigger it. Keep this gate if the trigger surface
ever expands (e.g. `issue_comment`).

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

Two more repository variables, added in Phase 4:

- **`MAX_CONCURRENT_AGENT_RUNS`** (default `3` if unset) — a system-wide
  cap on how many pipeline runs (across all three workflows, all
  issues/PRs) can be active at once, checked in each workflow's guard
  step via [`.github/scripts/count_active_agent_runs.sh`](../.github/scripts/count_active_agent_runs.sh).
  Distinct from each workflow's own per-item `concurrency:` group, which
  only stops two runs touching the *same* issue/PR — this bounds total
  activity, guarding against a Claude-subscription usage burst, CI runner
  contention, or several PRs racing each other into `dev` at once. Over
  the cap, a run skips itself with a comment explaining how to retry
  (re-apply the triggering label, or `workflow_dispatch` manually) —
  there's no automatic requeue.
- **`AUTO_MERGE_DISABLED`** — when `true`, `principal-review.yml` runs its
  full judgment (including posting real findings) but never actually
  merges: an `auto-merge` verdict is overridden to `human-merge` with a
  comment noting what the real verdict was. This is the Phase 5 dry-run
  switch — see the rollout plan below.

Every `auto-merge` outcome that actually merges is appended to
[`docs/auto-merge-log.md`](auto-merge-log.md) by `principal-review.yml`
itself, right after the merge — a durable, human-readable audit trail of
everything this pipeline has ever auto-merged without a human's direct
sign-off.

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
  workflow runs, so a label it applies won't itself fire Phase 2/3 — see
  "Cross-workflow triggering" below for how that's resolved when those
  phases are wired up.

## Cross-workflow triggering (GitHub App)

Any Phase 2/3 step that needs to take an action capable of triggering
another workflow run (e.g. applying a label that fires the next stage)
must mint a token from the `libravault-pipeline-bot` GitHub App instead of
using the default `GITHUB_TOKEN`, which can't do this:

```yaml
- uses: actions/create-github-app-token@v2
  id: app-token
  with:
    app-id: ${{ vars.PIPELINE_APP_ID }}
    private-key: ${{ secrets.PIPELINE_APP_PRIVATE_KEY }}
- run: gh pr edit "$PR" --add-label status:needs-qa
  env:
    GH_TOKEN: ${{ steps.app-token.outputs.token }}
```

Deliberately a dedicated org-owned App, not the interactive `libravault-xyz`
session identity (broader than this needs) and not a personal-account PAT
(tied to a human, no expiry-free rotation). Installed on `LibraVault`,
scoped to the `reader` repository only, with read/write on code, issues,
and pull requests. Credentials: `PIPELINE_APP_ID` (repo variable) and
`PIPELINE_APP_PRIVATE_KEY` (repo secret).

**Not every label transition uses the App token, on purpose.** A workflow
that self-applies, with the App token, a label matching its *own* trigger
condition would fire a duplicate run of itself (`labeled` events aren't
deduped by content, only suppressed when the token can't trigger runs at
all). Concretely: `dev-agent.yml` self-applies `status:ready-for-dev` to
the issue it's already processing — that must stay on `GITHUB_TOKEN`, or it
would re-trigger itself mid-run. Each workflow below uses the App token
*only* for the specific label(s) that need to reach a *different* workflow,
via a deterministic step that runs after the agent itself is done (the
agent's own step always keeps the safe default token):

| Workflow | Safe (`GITHUB_TOKEN`) | Crosses phases (App token) |
|---|---|---|
| `dev-agent.yml` | `status:ready-for-dev`, `status:in-progress` (issue), `status:needs-info`, comments | `status:needs-qa` (→ qa-agent.yml) |
| `qa-agent.yml` | `status:needs-info`, comments | `status:needs-review` (→ principal-review.yml), `status:in-progress` on the PR (→ dev-agent.yml) |
| `principal-review.yml` | everything — both outcomes are terminal (auto-merge happens inline in the same job; human-merge waits for a person) | *(none needed)* |

## PR intake backstop (manually-opened PRs)

`dev-agent.yml`'s risk-classification + `status:needs-qa` handoff (above)
only runs as the last step of its own triage-and-implement job — i.e. only
for PRs it opens itself, via the pipeline App token, in that same job run.
A PR opened any other way (a human, or a Claude Code session working an
issue directly with a personal PAT rather than going through
`status:ready-for-dev`) never enters that job at all, so it carries no
`risk:*`/`status:*` label and nothing downstream ever picks it up — the
dev-agent.yml comment on this explicitly says "PR creation itself doesn't
trigger any workflow this pipeline cares about (ours all trigger on
`labeled`, not `opened`)", which was true for pipeline-opened PRs but left
this gap for everything else. Confirmed live: issue #223's actual fix, PR
#224, was opened this way and sat with no pipeline label until a human
noticed and hand-applied `risk:low`/`status:needs-qa`.

[`pr-intake.yml`](../.github/workflows/pr-intake.yml) closes this gap: it
triggers on `pull_request: opened`/`reopened`, skips PRs authored by
`libravault-pipeline-bot[bot]` (those are already handled synchronously by
`dev-agent.yml` itself) and PRs already carrying a pipeline status label
(idempotent against `reopened` re-firing), then runs the exact same
deterministic classify-and-label step `dev-agent.yml`'s tail runs for its
own PRs. It runs no agent — this is a pure backstop, not a fourth triage
path.

## `claude-code-action` gotchas

Read this before adding a fourth pipeline workflow, or any other workflow
in this repo that uses `anthropics/claude-code-action`. Filed as
[#195](https://github.com/LibraVault/reader/issues/195) after costing
real debugging time across PRs #184/#189/#191/#192.

**A `GH_TOKEN` env var on the step does NOT control the agent's own git/gh
identity.** With `claude_code_oauth_token` set (this repo's auth method —
see above), `claude-code-action` defaults to authenticating its own
internal git/gh operations as Claude's own `claude[bot]` App, regardless
of any `GH_TOKEN` env var present in the step's environment. Confirmed
empirically: PR #162 (opened by `dev-agent.yml`, which already set
`GH_TOKEN: secrets.GITHUB_TOKEN` at the time) shows author `app/claude`,
not `github-actions[bot]`.

**The actual control is the action's own `github_token:` input**, set
alongside `claude_code_oauth_token:` in the step's `with:` block — not an
`env:` entry. Every `claude-code-action` step in this pipeline sets it
explicitly now; if you add a new one, do the same and pick the identity
deliberately (see the token table above for which identity — GITHUB_TOKEN
vs the pipeline App — is safe for what).

**A related, separate restriction, not about identity**: this repo's
"Allow GitHub Actions to create and approve pull requests" setting
(Settings → Actions → General) is off, so `GITHUB_TOKEN` can neither open
nor approve a PR, *regardless* of which identity it resolves to. That's
why `dev-agent.yml`'s PR creation uses the App token (a real GitHub App
token isn't subject to that GITHUB_TOKEN-specific restriction) while
`principal-review.yml` posts findings via `gh pr comment` /
`--request-changes` rather than `--approve` (there's no branch protection
on `dev` requiring a formal approval, so a plain comment does the job
without hitting the restriction at all).

## Rollout plan

1. **Phase 0 (PR #146)** — issue templates, label taxonomy, agent-policy.yml,
   agent persona files, this doc. No live automation.
2. **Phase 1 (built, proven live)** — `dev-agent.yml` workflow (triage +
   fix), triggered on issue open / `status:ready-for-dev` label, or
   manually via `workflow_dispatch` for testing against a specific issue
   number. Needs a `CLAUDE_CODE_OAUTH_TOKEN` repo secret to run at all.
3. **Phase 2 (built, proven live)** — `qa-agent.yml` workflow, triggered
   on PRs labeled `status:needs-qa`, or manually via `workflow_dispatch`
   for testing against a specific PR number.
4. **Phase 3 (built, proven live)** — `principal-review.yml` workflow,
   triggered on PRs labeled `status:needs-review`; implements the
   auto-merge/human-merge split, including waiting for CI before merging.
5. **Phase 4 (built)** — guardrails: `MAX_CONCURRENT_AGENT_RUNS`
   system-wide concurrency cap, `AGENTS_PAUSED` kill switch (already built
   in Phase 1-3), `AUTO_MERGE_DISABLED` dry-run switch, `docs/auto-merge-log.md`
   audit trail. See the two-variable list above.
6. **Phase 5 (dry-run pilot underway)** — pilot on 2-3 low-risk open
   `reader` issues with `AUTO_MERGE_DISABLED=true` before trusting it
   live. Only after that, consider extending beyond `reader`, or turning
   `AUTO_MERGE_DISABLED` back off.
