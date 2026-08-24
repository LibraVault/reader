#!/usr/bin/env bash
# Regression test for resolve_linked_issue.sh's top-level, gh-backed CLI
# path (the block guarded by `[ "${BASH_SOURCE[0]}" = "${0}" ]`, which
# test_resolve_linked_issue.sh never exercises since it only sources the
# script to test resolve_from_body() in isolation). Stubs `gh` via a fake
# executable prepended to PATH, same pattern as test_find_linked_pr.sh.
#
# Covers issue #408: the script must expose the "gh pr view itself
# failed" and "body links more than one distinct issue" cases as
# distinct, non-colliding exit codes, so a caller can tell "genuine infra
# failure" apart from "ambiguous, but gh worked fine" instead of
# collapsing both into "no linked issue".
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVE_LINKED_ISSUE="$SCRIPT_DIR/resolve_linked_issue.sh"
AMBIGUOUS_EXIT=3

FAILURES=0
STUB_DIR=$(mktemp -d)
trap 'rm -rf "$STUB_DIR"' EXIT

cat > "$STUB_DIR/gh" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail

if [ "$1" = "pr" ] && [ "$2" = "view" ]; then
  [ "${GH_STUB_FAIL:-0}" = "1" ] && exit "${GH_STUB_FAIL_EXIT:-9}"
  printf '%s' "${GH_STUB_BODY:-}"
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

check "single linked issue resolves cleanly, exit 0" \
  "42" 0 7 \
  GH_STUB_BODY="Closes #42"

check "no linked issue prints nothing, exit 0" \
  "" 0 7 \
  GH_STUB_BODY="Just some prose, no closing keyword."

check "two distinct linked issues exits AMBIGUOUS_EXIT (3), not 1 and not gh's own code" \
  "" "$AMBIGUOUS_EXIT" 7 \
  GH_STUB_BODY="Closes #10
Fixes #12"

check "gh pr view failure propagates gh's own exit code, distinct from AMBIGUOUS_EXIT" \
  "" 9 7 \
  GH_STUB_FAIL=1 GH_STUB_FAIL_EXIT=9

check "gh pr view failure that happens to also be exit 1 is still not confusable with ambiguous (3)" \
  "" 1 7 \
  GH_STUB_FAIL=1 GH_STUB_FAIL_EXIT=1

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
