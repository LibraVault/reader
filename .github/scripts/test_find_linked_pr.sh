#!/usr/bin/env bash
# Regression test for find_linked_pr.sh, run directly (no CI job wires
# this in yet -- same standalone-script pattern as
# test_resolve_linked_issue.sh in this directory). Stubs `gh` via a fake
# executable prepended to PATH -- no network access, no real GitHub PRs
# -- and exercises find_linked_pr.sh itself end to end, not just the
# regex matching it delegates to resolve_from_body(). That's the gap QA
# flagged on PR #318: the PR's own tests only fed new input shapes into
# an unchanged resolve_from_body(), so they'd have passed identically on
# the pre-fix script; they didn't exercise the new commit-fallback loop
# added to find_linked_pr.sh itself (which candidates it walks, how a
# match is decided, how a `gh` failure propagates).
#
# find_linked_pr.sh resolves its sibling script call
# (.github/scripts/resolve_linked_issue.sh) via a relative path, so these
# tests must run with the repo root as the working directory.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FIND_LINKED_PR="$REPO_ROOT/.github/scripts/find_linked_pr.sh"

FAILURES=0
STUB_DIR=$(mktemp -d)
trap 'rm -rf "$STUB_DIR"' EXIT

# Fake `gh` covering the exact invocations find_linked_pr.sh and
# resolve_linked_issue.sh make: `pr list --search ...` (body stage),
# `pr view --json body` (per-candidate body verification), `pr list`
# with no --search (commit-fallback candidate listing), and
# `pr view --json commits` (commit-fallback text). Behavior for each
# call is driven by env vars set per test case below.
cat > "$STUB_DIR/gh" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail

if [ "$1" = "pr" ] && [ "$2" = "list" ]; then
  if printf '%s\n' "$*" | grep -q -- '--search'; then
    [ "${GH_STUB_FAIL_SEARCH:-0}" = "1" ] && exit 9
    printf '%s\n' ${GH_STUB_SEARCH_CANDIDATES:-}
  else
    [ "${GH_STUB_FAIL_LIST_ALL:-0}" = "1" ] && exit 9
    printf '%s\n' ${GH_STUB_ALL_OPEN:-}
  fi
  exit 0
fi

if [ "$1" = "pr" ] && [ "$2" = "view" ]; then
  pr="$3"
  if printf '%s\n' "$*" | grep -q -- '--json body'; then
    [ "${GH_STUB_FAIL_VIEW_BODY:-}" = "$pr" ] && exit 9
    var="GH_STUB_BODY_$pr"
    printf '%s' "${!var:-}"
    exit 0
  fi
  if printf '%s\n' "$*" | grep -q -- '--json commits'; then
    [ "${GH_STUB_FAIL_VIEW_COMMITS:-}" = "$pr" ] && exit 9
    var="GH_STUB_COMMITS_$pr"
    printf '%s' "${!var:-}"
    exit 0
  fi
  echo "stub gh: unhandled 'pr view' args: $*" >&2
  exit 1
fi

echo "stub gh: unhandled invocation: $*" >&2
exit 1
STUB
chmod +x "$STUB_DIR/gh"

# args: description, expected stdout, expected exit code, issue number,
# max-search-attempts, then GH_STUB_* env assignments (VAR=value ...)
check() {
  local desc="$1" expected_out="$2" expected_exit="$3" issue="$4" attempts="$5"
  shift 5
  local actual_out actual_exit
  actual_out=$(cd "$REPO_ROOT" && env -i PATH="$STUB_DIR:$PATH" HOME="$HOME" "$@" \
    "$FIND_LINKED_PR" "$issue" "$attempts")
  actual_exit=$?
  if [ "$actual_out" != "$expected_out" ] || [ "$actual_exit" != "$expected_exit" ]; then
    echo "FAIL: $desc"
    echo "  expected: out='$expected_out' exit=$expected_exit"
    echo "  actual:   out='$actual_out' exit=$actual_exit"
    FAILURES=$((FAILURES + 1))
  else
    echo "PASS: $desc"
  fi
}

check "body stage finds a real closing line and short-circuits before the commit fallback" \
  "310" 0 309 1 \
  GH_STUB_SEARCH_CANDIDATES=310 \
  GH_STUB_BODY_310="Some prose.

Closes #309"

check "issue #317/PR #310 shape: body uses a loose phrase, commit fallback catches the real closing line" \
  "310" 0 309 1 \
  GH_STUB_SEARCH_CANDIDATES= \
  GH_STUB_ALL_OPEN=310 \
  GH_STUB_COMMITS_310="feat(ios): wire up Now Playing controls

Adds MPRemoteCommandCenter integration for lock screen playback.

fix(pipeline): correct throttle window

Closes #309"

check "no closing line anywhere (body or commits) prints nothing, exits 0" \
  "" 0 309 1 \
  GH_STUB_SEARCH_CANDIDATES= \
  GH_STUB_ALL_OPEN=310 \
  GH_STUB_COMMITS_310="feat(ios): wire up Now Playing controls

Implements #309 per the design doc."

check "gh failure listing all open PRs for the commit fallback propagates non-zero, not a silent empty result" \
  "" 9 309 1 \
  GH_STUB_SEARCH_CANDIDATES= \
  GH_STUB_FAIL_LIST_ALL=1

check "gh failure fetching a candidate's commits propagates non-zero, not a silent empty result" \
  "" 9 309 1 \
  GH_STUB_SEARCH_CANDIDATES= \
  GH_STUB_ALL_OPEN=310 \
  GH_STUB_FAIL_VIEW_COMMITS=310

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
