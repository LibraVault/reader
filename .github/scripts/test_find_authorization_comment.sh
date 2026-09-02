#!/usr/bin/env bash
# Regression test for find_authorization_comment.sh, run directly (no CI
# job wires this in yet — same standalone-script pattern as
# test_find_linked_pr.sh in this directory). Stubs `gh` via a fake
# executable prepended to PATH that emulates `gh api ... --jq '<filter>'`
# by running canned fixture JSON through the real `jq` binary — no
# network access, no real GitHub comments.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FIND_AUTH="$REPO_ROOT/.github/scripts/find_authorization_comment.sh"

FAILURES=0
STUB_DIR=$(mktemp -d)
trap 'rm -rf "$STUB_DIR"' EXIT

cat > "$STUB_DIR/gh" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
if [ "$1" = "api" ]; then
  [ "${GH_STUB_FAIL:-0}" = "1" ] && exit 9
  JQ_FILTER=""
  ARGS=("$@")
  for i in "${!ARGS[@]}"; do
    if [ "${ARGS[$i]}" = "--jq" ]; then
      JQ_FILTER="${ARGS[$((i+1))]}"
    fi
  done
  printf '%s' "${GH_STUB_COMMENTS_JSON:-[]}" | jq "$JQ_FILTER"
  exit 0
fi
echo "unhandled: $*" >&2
exit 1
STUB
chmod +x "$STUB_DIR/gh"

# args: description, expected stdout, expected exit code, issue number,
# then GH_STUB_* env assignments (VAR=value ...)
check() {
  local desc="$1" expected_out="$2" expected_exit="$3" number="$4"
  shift 4
  local actual_out actual_exit
  actual_out=$(cd "$REPO_ROOT" && env -i PATH="$STUB_DIR:$PATH" HOME="$HOME" "$@" \
    "$FIND_AUTH" "$number")
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

check "no comments at all prints nothing, exits 0" \
  "" 0 675 \
  GH_STUB_COMMENTS_JSON='[]'

check "marker from a non-authorized author (NONE) is ignored" \
  "" 0 675 \
  GH_STUB_COMMENTS_JSON='[{"author_association":"NONE","body":"AGENT-AUTHORIZE: should not count"}]'

check "marker from CONTRIBUTOR (below the COLLABORATOR bar) is ignored" \
  "" 0 675 \
  GH_STUB_COMMENTS_JSON='[{"author_association":"CONTRIBUTOR","body":"AGENT-AUTHORIZE: should not count either"}]'

check "single valid marker from a COLLABORATOR is returned in full, including surrounding text" \
  'Some preamble.

AGENT-AUTHORIZE: Android only for this pass.

more text' 0 526 \
  GH_STUB_COMMENTS_JSON='[{"author_association":"COLLABORATOR","body":"Some preamble.\n\nAGENT-AUTHORIZE: Android only for this pass.\n\nmore text"}]'

check "issue #526/#668/#670 shape: two valid authorizations over time, most recent one wins" \
  "AGENT-AUTHORIZE: iOS scope, matches PR #670." 0 526 \
  GH_STUB_COMMENTS_JSON='[
    {"author_association":"COLLABORATOR","body":"AGENT-AUTHORIZE: Android only for this pass, iOS as a coordinated follow-up."},
    {"author_association":"MEMBER","body":"AGENT-AUTHORIZE: iOS scope, matches PR #670."}
  ]'

check "a comment merely mentioning the marker word without the exact line-start form does not match" \
  "" 0 675 \
  GH_STUB_COMMENTS_JSON='[{"author_association":"OWNER","body":"we still need an AGENT-AUTHORIZE: comment before this can proceed"}]'

check "gh api failure propagates non-zero, not a silent empty result" \
  "" 9 675 \
  GH_STUB_FAIL=1

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
