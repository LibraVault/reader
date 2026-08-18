#!/usr/bin/env python3
"""Fail if docs/TEST_PLAN.md's module list has drifted from settings.gradle.kts.

Why this exists: TEST_PLAN.md is read as ground truth by humans and by the dev
agent, and it has been wrong before in ways nobody noticed. Its fabricated
metrics table survived for months describing 9 modules and "~77 unit tests"
against an actual 16 modules and 870 tests, and its per-module narrative called
core/database "100% COMPLETE" while four of six migrations had no test at all.

A doc cannot be gated on being *true*. It can be gated on being *complete*, and
a module that exists but appears nowhere in the plan is the single most likely
way for it to silently go stale again — a new module arrives with no tests and
no mention, and the plan still reads as if it covers the repo.

This is deliberately the weakest possible check that still catches that:

  - every module in settings.gradle.kts must be named somewhere in TEST_PLAN.md
  - every module named in TEST_PLAN.md's coverage table must still exist

It does NOT check coverage numbers or prose accuracy. Those go stale too, but
gating on them would mean regenerating the doc on every PR, which trains people
to bypass the check. Numbers come from `scripts/coverage-summary.py`, which
reads the real Kover reports and is printed in every CI run's summary.

Exit code 0 = in sync, 1 = drift (with the offending names listed).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SETTINGS = REPO_ROOT / "settings.gradle.kts"
TEST_PLAN = REPO_ROOT / "docs" / "TEST_PLAN.md"

# `include(":core:vaultcrypto")` -> core:vaultcrypto
INCLUDE_RE = re.compile(r'include\(\s*"(:[^"]+)"\s*\)')


def gradle_modules() -> set[str]:
    text = SETTINGS.read_text(encoding="utf-8")
    return {m.lstrip(":") for m in INCLUDE_RE.findall(text)}


def is_mentioned(module: str, plan: str) -> bool:
    """Whether `module` appears in the plan in backticked form, e.g. `core:ui`.

    Requiring the backticks keeps prose like "the library feature" from counting
    as a mention of `feature:library`. Testing each real module by name — rather
    than pattern-matching what a module *looks* like — is what makes this work
    for single-segment names: the first version used a regex requiring a colon
    and reported `app` as undocumented while it sat in the coverage table.
    """
    return f"`{module}`" in plan


def backticked_module_paths(plan: str) -> set[str]:
    """Colon-separated paths in backticks, used only to spot deleted modules."""
    return set(re.findall(r"`([a-z0-9]+(?::[a-z0-9]+)+)`", plan))


def main() -> int:
    if not SETTINGS.exists():
        print(f"error: {SETTINGS} not found", file=sys.stderr)
        return 1
    if not TEST_PLAN.exists():
        print(f"error: {TEST_PLAN} not found", file=sys.stderr)
        return 1

    plan = TEST_PLAN.read_text(encoding="utf-8")
    actual = gradle_modules()

    undocumented = sorted(m for m in actual if not is_mentioned(m, plan))
    # Only flag names that look like real module paths, so a reference to
    # something like `src/test` cannot trip this.
    phantom = sorted(
        m for m in backticked_module_paths(plan) - actual
        if m.split(":")[0] in {"core", "feature", "app"}
    )

    if not undocumented and not phantom:
        print(f"docs/TEST_PLAN.md is in sync with settings.gradle.kts ({len(actual)} modules).")
        return 0

    if undocumented:
        print("docs/TEST_PLAN.md does not mention these modules:", file=sys.stderr)
        for m in undocumented:
            print(f"  - {m}", file=sys.stderr)
        print(
            "\nA module absent from the test plan reads as though the plan covers the whole\n"
            "repo when it does not. Add it — even just to the coverage table — or say\n"
            "explicitly why it has no tests.",
            file=sys.stderr,
        )

    if phantom:
        print("\ndocs/TEST_PLAN.md names modules that no longer exist:", file=sys.stderr)
        for m in phantom:
            print(f"  - {m}", file=sys.stderr)
        print(
            "\nRemove them. A plan describing deleted modules is how core:licensing\n"
            "outlived its own deletion in the docs.",
            file=sys.stderr,
        )

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
