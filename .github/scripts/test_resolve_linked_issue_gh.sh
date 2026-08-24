#!/usr/bin/env bash
# Regression test for resolve_linked_issue.sh's *direct-execution* block
# (the `gh pr view`-backed lookup at the bottom of the file) — run
# directly, same standalone-script pattern as the sibling test_*.sh files
# in this directory. test_resolve_linked_issue.sh only exercises the
# sourced resolve_from_body() function in isolation; it never runs the
# script's own `if [ "${BASH_SOURCE[0]}" = "${0}" ]` branch, so it never
# would have caught issue #546: pr-intake.yml's guard step captured this
# script's stdout via `$(...)`, but on the ambiguous-issue path the real
# stdout was empty (the numbers only ever reached the dropped stderr
# message) — so a caller trying to report *which* issues were ambiguous
# had nothing to read. Stubs `gh` via a fake executable prepended to
# PATH — no network access, no real GitHub PRs.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVE_LINKED_ISSUE="$SCRIPT_DIR/resolve_linked_issue.sh"

FAILURES=0
STUB_DIR=$(mktemp -d)
trap 'rm -rf "$STUB_DIR"' EXIT

# Fake `gh` covering the one invocation this script's direct-execution
# block makes: `pr view <n> --json body -q .body`. Body and failure mode
# are driven by env vars set per test case below.
cat > "$STUB_DIR/gh" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail

if [ "$1" = "pr" ] && [ "$2" = "view" ]; then
  pr="$3"
  [ "${GH_STUB_FAIL_VIEW:-}" = "$pr" ] && exit 9
  var="GH_STUB_BODY_$pr"
  printf '%s' "${!var:-}"
  exit 0
fi

echo "stub gh: unhandled invocation: $*" >&2
exit 1
STUB
chmod +x "$STUB_DIR/gh"

# args: description, expected stdout, expected exit code, pr number, then
# GH_STUB_* env assignments (VAR=value ...)
check() {
  local desc="$1" expected_out="$2" expected_exit="$3" pr="$4"
  shift 4
  local actual_out actual_exit
  actual_out=$(env -i PATH="$STUB_DIR:$PATH" HOME="$HOME" "$@" \
    "$RESOLVE_LINKED_ISSUE" "$pr")
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

check "single closing line resolves cleanly" \
  "309" 0 225 \
  GH_STUB_BODY_225="Closes #309"

check "no closing line prints nothing, exits 0" \
  "" 0 225 \
  GH_STUB_BODY_225="Just some prose, no closing keyword."

# The actual #546 regression: two distinct closing lines must still land
# both numbers on real stdout (not just the stderr log a `$(...)` caller
# never sees) so a guarded caller (`... || { ... }`) can report which
# issues were ambiguous, instead of silently capturing an empty string.
check "multiple distinct closing lines: both numbers reach stdout, exit 1" \
  "525
526" 1 539 \
  GH_STUB_BODY_539="Fixes #525
Fixes #526"

check "a genuine gh pr view failure propagates its exit code with empty stdout, distinct from ambiguity" \
  "" 9 539 \
  GH_STUB_FAIL_VIEW=539 \
  GH_STUB_BODY_539="Fixes #525
Fixes #526"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
