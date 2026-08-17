#!/usr/bin/env bash
# Regression test for resolve_linked_issue.sh's resolve_from_body(), run
# directly (no CI job wires this in yet — there's no shell-test harness
# in this repo, so this is a standalone `./test_resolve_linked_issue.sh`
# script matching how the sibling scripts in this directory are used).
# No `gh` calls, no network — sources the script and feeds canned PR
# bodies on stdin.
#
# Covers both the #228/#230 line-start-anchoring fix (the previous
# word-boundary-only regex matched "which fixes #223" inside a sentence
# describing a *different* PR's history and silently misrouted PR #225
# to issue #223) and the follow-up: rejecting bodies with more than one
# distinct closing-issue line instead of silently taking the first via
# `head -1`.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/resolve_linked_issue.sh"

FAILURES=0

# args: description, expected stdout, expected exit code, body (stdin)
check() {
  local desc="$1" expected_out="$2" expected_exit="$3" body="$4"
  local actual_out actual_exit
  actual_out=$(echo "$body" | resolve_from_body)
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

check "incidental prose mentioning another PR's closing keyword is NOT a match (#228, real PR #225 body)" \
  "" 0 \
"## Summary

Related to #223 / #224, not closing either — this is a pipeline-infra fix.
I found that PR #224 (which fixes #223) was opened directly."

check "dedicated closing line with trailing period (real PR #224 body convention)" \
  "223" 0 \
"## Summary

Closes #223.

Settings had no delete option."

check "dedicated closing line, no period" \
  "42" 0 \
  "Fixes #42"

check "bulleted closing line" \
  "99" 0 \
  "- Closes #99"

check "heading closing line" \
  "77" 0 \
  "## Fixes #77"

check "indented plain-text closing line" \
  "88" 0 \
  "  Resolves #88"

check "blockquote closing line" \
  "12" 0 \
  "> Closes #12"

check "keyword used mid-sentence still does not match" \
  "" 0 \
  "This change (which fixes #7) does other things."

check "case-insensitive, past tense" \
  "5" 0 \
  "closed #5"

check "disclosed/enclosed false-substring guard still holds" \
  "" 0 \
  "The workaround enclosed #123 in the attached zip."

check "bare issue mention with no closing keyword at all" \
  "" 0 \
  "Just some prose about #123 with no keyword."

check "two distinct closing lines is ambiguous (rejected, not head -1'd)" \
  "10
12" 1 \
"Closes #10
Fixes #12"

check "same issue linked twice is not ambiguous" \
  "10" 0 \
"Closes #10
Fixes #10"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
