#!/usr/bin/env bash
# Regression test for extract_review_verdict.sh's extract_verdict_from_body(),
# run directly (no CI job wires this in yet — there's no shell-test harness
# in this repo, so this is a standalone `./test_extract_review_verdict.sh`
# script matching how the sibling scripts in this directory are used, e.g.
# test_resolve_linked_issue.sh). No `gh` calls, no network — sources the
# script and feeds canned review/comment bodies on stdin.
#
# Covers issue #426: recovering an already-posted verdict from a review or
# comment body when the agent crashed before writing principal-verdict.txt.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/extract_review_verdict.sh"

FAILURES=0

# args: description, expected stdout, body (stdin)
check() {
  local desc="$1" expected_out="$2" body="$3"
  local actual_out
  actual_out=$(echo "$body" | extract_verdict_from_body)
  if [ "$actual_out" != "$expected_out" ]; then
    echo "FAIL: $desc"
    echo "  expected: '$expected_out'"
    echo "  actual:   '$actual_out'"
    FAILURES=$((FAILURES + 1))
  else
    echo "PASS: $desc"
  fi
}

check "real PR #397 review body shape (human-merge)" \
  "human-merge" \
"## Principal review — verdict: \`human-merge\`

**Risk classification**: \`risk:high\` is correct and current."

check "auto-merge verdict, posted via a plain PR comment" \
  "auto-merge" \
"## Principal review — verdict: \`auto-merge\`

No CONFIRMED findings, risk:low."

check "case-insensitive verdict line" \
  "human-merge" \
"## principal review — VERDICT: \`Human-Merge\`

Some findings."

check "verdict line is not the first line" \
  "auto-merge" \
"Reviewing PR now.

## Principal review — verdict: \`auto-merge\`"

check "unrelated comment with no verdict line at all" \
  "" \
"Pipeline is at capacity (5/5 active runs) — skipping this review run."

check "mentions 'verdict' in prose without the exact backtick-wrapped form" \
  "" \
"My verdict is that this looks fine, going with auto-merge."

check "multiple verdict-shaped lines takes the last one (re-review overwriting an earlier round's body is not a realistic single-comment shape, but guards against accidental duplication)" \
  "auto-merge" \
"## Principal review — verdict: \`human-merge\`
## Principal review — verdict: \`auto-merge\`"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
