# CircleCI parity Phase D: mirroring the agent-team pipeline itself

Status: **design only, not implemented**. This is the deliverable for
[issue #380](https://github.com/LibraVault/reader/issues/380) (Phase D of
the [CircleCI-parity plan](https://github.com/LibraVault/reader/issues/376)).
Phases A-C (test-workflow coverage, Android release/distribution, iOS
build/TestFlight — see that issue) shipped as working CircleCI config.
Phase D does not, on purpose: unlike A-C, which duplicate build/test/signing
credentials, this phase duplicates the credential that can act *as the
pipeline itself* — a GitHub App private key plus a Claude subscription
token — onto a second CI provider. That is a standing security decision,
not a mechanical port, and this doc exists so that decision can be made
deliberately, with the actual shape of the work in front of it, rather than
folded into a "yes, do CircleCI parity" answer given before this shape was
known.

**Nothing below should be implemented without a fresh, explicit go-ahead
that references this doc specifically** — not the general go-ahead that
authorized Phases A-C. See "The actual decision" below for why.

## Why Phase D is different from A-C

Phases A-C each duplicate one class of build/release credential (Gradle
cache keys, a release keystore, Firebase service account, Apple
certificates). Bad outcomes from those leaking are serious but bounded: an
attacker could sign a fake release APK, or upload a fake iOS build for
Apple's own review process to catch, or read test-fixture data. Recovery
is "rotate the credential."

`PIPELINE_APP_PRIVATE_KEY` is different in kind. Per
[docs/agent-team-pipeline.md](agent-team-pipeline.md#cross-workflow-triggering-github-app),
the `libravault-pipeline-bot` GitHub App it authenticates is installed on
this repo with **read/write on code, issues, and pull requests** — the
same power the automated pipeline itself uses to open PRs, apply labels
that trigger merges, and (via `principal-review.yml`) merge to `dev`
directly. A copy of this key living on a second platform means a second
platform can do all of that, independent of GitHub Actions. `CLAUDE_CODE_OAUTH_TOKEN`
is tied to this project's actual Claude subscription — a second copy
means a second, independent consumer of that subscription's usage.

Neither of these is "rotate and move on" if CircleCI's secret store is
ever compromised — it's "an attacker had repo-write access and could spend
your Claude subscription for however long it took to notice."

## The actual decision

Before any of the porting work below: **does this get its own GitHub App
installation, separate from `libravault-pipeline-bot`, or does it reuse the
same key?**

- **Separate App (recommended)**: a second App, e.g.
  `libravault-pipeline-bot-circleci`, installed on this repo with the same
  read/write permissions, its own private key stored only in CircleCI. Two
  independently-revocable credentials — compromising CircleCI's copy
  doesn't touch the GitHub Actions side's credential at all, and either can
  be individually revoked without breaking the other. Costs one more App
  registration + one more key to track, and both Apps' actions still show
  up in the audit log as different actors, which is arguably a feature (a
  PR opened by the CircleCI-side identity is instantly distinguishable from
  one opened by the normal pipeline).
- **Reuse the same key**: less setup, but a leak on either platform
  compromises both, and revoking it to respond to a leak on one platform
  takes down the other platform's pipeline too.

This doc assumes a separate App if greenlit, but the choice belongs to
whoever gives the go-ahead, not to whoever writes the YAML.

## What porting the pipeline actually means

Five components, each independently nontrivial. None of these have a
CircleCI orb or first-party integration to lean on — the GitHub Actions
side gets `claude-code-action`, `actions/create-github-app-token`, and
native `issues`/`pull_request` webhook triggers essentially for free;
CircleCI has none of the three.

### 1. Shared concurrency accounting

[`count_active_agent_runs.sh`](../.github/scripts/count_active_agent_runs.sh)
counts in-flight runs across `dev-agent.yml`/`qa-agent.yml`/`principal-review.yml`
via `gh run list`, against `MAX_CONCURRENT_AGENT_RUNS` (default 3). If a
CircleCI-side pipeline mirrors these three workflows, the cap needs to
become a **shared budget across both providers**, not two independent caps
of 3 each — otherwise the two providers could together run up to 6
concurrent agent sessions, which is exactly the resource-contention/
Claude-usage-burst scenario Phase 4 introduced this cap to prevent in the
first place. A CircleCI-side guard step would need to query *both*
`gh run list` (GitHub Actions) and the CircleCI v2 API's pipeline-list
endpoint (for its own three mirrored jobs) and sum both counts before
deciding whether to proceed — meaning `count_active_agent_runs.sh` itself
needs to grow a CircleCI-aware branch, used by both providers' guard steps,
not have a separate copy diverge on each side.

### 2. Label-driven triggering

GitHub Actions gets `on: issues: {types: [opened, labeled]}` /
`on: pull_request: {types: [labeled]}` natively — CircleCI has no
equivalent webhook trigger for GitHub label events. Something needs to
translate a label change into a CircleCI pipeline trigger. Two realistic
options:

- **Extend `pr-intake.yml` (or a new small relay workflow)** to also POST
  to CircleCI's `/pipeline/run` endpoint (same mechanism `circleci-dispatch.yml`
  already uses for Phase A) whenever a pipeline-relevant label lands,
  passing the issue/PR number as a pipeline parameter. This keeps the
  "translate GitHub event to CircleCI trigger" logic in one already-trusted
  place, but means the relay itself is a new single point of failure — if
  GitHub Actions is down (the actual scenario this whole parity effort
  exists for), the relay is down too, and CircleCI never gets triggered.
  **This defeats the point of Phase D specifically** — the other phases
  don't have this problem because they're manually/API-triggered directly,
  with no GitHub Actions dependency in the trigger path at all.
- **A GitHub webhook direct to CircleCI**, bypassing GitHub Actions
  entirely (GitHub supports custom webhooks on `issues`/`pull_request`
  events independent of Actions). This is the only option that actually
  survives a GitHub Actions outage, which is the whole premise of this
  plan — but it's also new infrastructure this repo has none of today (no
  existing webhook receiver/relay), and needs its own auth story (a
  webhook secret, validated on CircleCI's end or by whatever receives it).

**This is the component most likely to change Phase D's actual shape** —
worth resolving before estimating the rest, since "relay through GitHub
Actions" vs. "direct webhook" changes what components 3-5 below even need
to assume about their trigger context.

### 3. Claude Code invocation

`claude-code-action` (used by `qa-agent.yml`, `principal-review.yml`,
`human-merge-sweep.yml`) has no CircleCI equivalent — it's a GitHub Action
specifically. A CircleCI job would install and invoke the Claude Code CLI
directly, authenticated via the (separate, per "the actual decision" above)
`CLAUDE_CODE_OAUTH_TOKEN` copy. This also means re-solving, without the
action's help:

- The identity/`github_token:` gotchas [`docs/agent-team-pipeline.md`](agent-team-pipeline.md#claude-code-action-gotchas)
  documents at length (the action's own `github_token:` input, not an env
  var, controls the CLI's git/gh identity) — a direct CLI invocation needs
  its own equivalent flag/config, not necessarily the same one.
- Model/permission configuration `claude-code-action` currently sets up
  implicitly — needs to be replicated explicitly in whatever CircleCI step
  invokes the CLI.

### 4. GitHub-App-authenticated writes

PR creation, label changes that cross workflow boundaries, comments, and
merges currently go through `actions/create-github-app-token@v2` minting a
short-lived installation token from `PIPELINE_APP_PRIVATE_KEY`. CircleCI
has no equivalent action — this needs a manual JWT-exchange step (sign a
JWT with the App's private key, exchange it for an installation access
token via GitHub's REST API, same protocol `actions/create-github-app-token`
implements, just not pre-packaged). Straightforward to implement (it's a
documented, stable GitHub API), but is new code this repo doesn't have
today, and is exactly the code path that would hold the credential
described in "the actual decision" above.

### 5. Sensitive-path gate, unchanged

`.github/agent-policy.yml`'s `sensitive_paths` list and
`classify_pr_risk.py` stay authoritative regardless of which provider
produced a PR — nothing in this phase changes that a PR touching
`.github/workflows/**`, vault crypto, signing config, etc. always lands on
`status:needs-human-merge`. Worth stating explicitly since it's easy to
assume "new provider" implies "new policy surface" — it doesn't; the
policy is provider-agnostic already (it inspects the diff, not the CI
system that produced it).

## Open questions to resolve before implementation, beyond "the actual decision"

- **Relay vs. webhook** (component 2) — resolve this first; it changes the
  shape of everything else.
- **Does this get built at all, given what it actually buys?** The
  [pipeline health-check routine](https://github.com/LibraVault/reader/issues/383)
  already self-heals the specific "concurrency-cap skip" symptom that
  originally prompted this whole plan (issue #359) — re-applies the
  triggering label once GitHub Actions capacity frees up. Phase D's actual
  incremental value is redundancy against **GitHub Actions being
  unavailable entirely** (a real outage, a billing lockout, an Actions-wide
  incident) — a materially rarer event than routine concurrency pressure,
  which is already handled. Worth confirming that rarer scenario is still
  worth the credential-duplication cost above before starting component 2.
- **Cost**: unlike Phases A-C's Linux/macOS compute costs (bounded,
  estimable), a CircleCI-side agent pipeline would consume Claude Code CLI
  usage against the (separate, per "the actual decision") OAuth token on
  every triggered run — same consumption pattern as the GitHub Actions
  side today, just doubled if both providers are ever mirroring the same
  workload rather than one taking over for the other during an outage.

## If greenlit: recommended sequencing

1. Resolve "the actual decision" (separate App vs. shared key) and
   component 2 (relay vs. webhook) — both are prerequisites to estimating
   anything else honestly.
2. Component 4 (GitHub-App JWT exchange) in isolation first — it's the
   most self-contained, most reusable across the others, and the one
   holding the credential this whole doc is about, so it deserves to be
   built and reviewed on its own before anything depends on it.
3. Component 1 (shared concurrency accounting) — second, since components
   3/5 don't need to exist yet to test that the cap logic itself is
   correct against two providers' worth of `gh run list`/CircleCI-API
   data.
4. Components 2 + 3 together (trigger path + Claude Code invocation) —
   these are where the actual agent behavior lives, and are easiest to
   validate together against a real disposable test issue, the same way
   Phase 1 of the original pipeline validated against issue #182.
5. A dry-run period mirroring the original pipeline's own Phase 5 (see
   [`docs/agent-team-pipeline.md`](agent-team-pipeline.md#rollout-plan)) —
   pilot on a couple of low-risk issues with merges disabled before
   trusting this live, given it's a second copy of write access to the
   same repo.
