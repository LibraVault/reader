#!/usr/bin/env python3
"""Classify a PR's merge risk per .github/agent-policy.yml.

This is a deterministic backstop, not an LLM judgment call: it runs as a
plain workflow step every time the dev agent pushes to a PR, and its
verdict overrides anything an agent claims about its own change. See
docs/agent-team-pipeline.md's "Risk classification" section.

Usage:
    classify_pr_risk.py <base-ref> <head-ref> [--apply-label <pr-number>]

Exits 0 and prints "risk:low" or "risk:high" to stdout. With --apply-label,
also sets that label on the given PR via `gh pr edit` (removing the other
risk:* label first) and writes a short reason to $GITHUB_STEP_SUMMARY if
set.
"""
from __future__ import annotations

import argparse
import fnmatch
import os
import subprocess
import sys
from pathlib import Path

import yaml

POLICY_PATH = Path(__file__).resolve().parent.parent / "agent-policy.yml"


def load_policy() -> dict:
    with POLICY_PATH.open() as f:
        return yaml.safe_load(f)


def changed_files(base: str, head: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}"],
        capture_output=True, text=True, check=True,
    )
    return [line for line in out.stdout.splitlines() if line]


def changed_line_count(base: str, head: str) -> int:
    out = subprocess.run(
        ["git", "diff", "--numstat", f"{base}...{head}"],
        capture_output=True, text=True, check=True,
    )
    total = 0
    for line in out.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        added, removed = parts[0], parts[1]
        # Binary files report "-" for both columns; nothing to count.
        for n in (added, removed):
            if n.isdigit():
                total += int(n)
    return total


def matches_sensitive_path(path: str, patterns: list[str]) -> str | None:
    for pattern in patterns:
        if fnmatch.fnmatch(path, pattern):
            return pattern
    return None


def classify(base: str, head: str, policy: dict) -> tuple[str, str]:
    """Returns (risk_label, human_readable_reason)."""
    patterns = policy.get("sensitive_paths", [])
    for path in changed_files(base, head):
        hit = matches_sensitive_path(path, patterns)
        if hit:
            return "risk:high", f"changed file `{path}` matches sensitive_paths pattern `{hit}`"

    limit = policy["max_auto_mergeable_diff_lines"]
    lines = changed_line_count(base, head)
    if lines > limit:
        return "risk:high", f"diff is {lines} changed lines, over the {limit}-line auto-merge threshold"

    return "risk:low", f"diff is {lines} changed lines and touches no sensitive path"


def apply_label(pr_number: str, risk_label: str, reason: str) -> None:
    other = "risk:low" if risk_label == "risk:high" else "risk:high"
    subprocess.run(["gh", "pr", "edit", pr_number, "--remove-label", other], check=False)
    subprocess.run(["gh", "pr", "edit", pr_number, "--add-label", risk_label], check=True)

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a") as f:
            f.write(f"### Risk classification: `{risk_label}`\n\n{reason}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("base_ref")
    parser.add_argument("head_ref")
    parser.add_argument("--apply-label", metavar="PR_NUMBER")
    args = parser.parse_args()

    policy = load_policy()
    risk_label, reason = classify(args.base_ref, args.head_ref, policy)
    print(risk_label)
    print(reason, file=sys.stderr)

    if args.apply_label:
        apply_label(args.apply_label, risk_label, reason)

    return 0


if __name__ == "__main__":
    sys.exit(main())
