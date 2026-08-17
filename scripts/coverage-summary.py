#!/usr/bin/env python3
"""Summarise per-module Kover XML reports as one Markdown table.

Used by .github/workflows/jvm-tests.yml to put coverage in the run summary,
and runnable locally:

    ./gradlew koverXmlReportDebug \\
              :app:koverXmlReportFdroidDebug \\
              :feature:settings:koverXmlReportFdroidDebug
    python3 scripts/coverage-summary.py

Why per-module rather than a single number: one repo-wide percentage hides
exactly what matters here — a well-covered core:vaultcrypto and a 1,344-LOC
untested LibraryScreen average out to something unremarkable. See
docs/TEST_COVERAGE_PRD.md.

This reads each module's own report rather than a Kover aggregate. There is no
aggregate on purpose: Kover's cross-variant "total" pulls in release unit
tests, which cannot pass in this repo — androidx.compose.ui:ui-test-manifest is
declared `debugImplementation` everywhere, so `createComposeRule()` has no
Activity to launch in release. See the coverage block in build.gradle.kts.

A module that has sources but no report is an ERROR, not a zero and not a
silent omission. That distinction matters: flavored modules (:app,
:feature:settings) have task names like koverXmlReportFdroidDebug, so a bare
`koverXmlReportDebug` skips them without a word — the same trap that kept those
two modules out of the JVM test job for months (see jvm-tests.yml).
"""

from __future__ import annotations

import json
import os
import sys
import xml.etree.ElementTree as ET

# Kover writes reportDebug.xml for unflavored modules and report<Flavor>Debug.xml
# for flavored ones. Order matters: fdroid is checked first so a flavored module
# reports one deterministic variant rather than whichever task happened to run.
REPORT_NAMES = ("reportDebug.xml", "reportFdroidDebug.xml", "reportPlayDebug.xml")

# Modules whose coverage is gated rather than merely reported (PRD §5 R3).
# Deliberately narrow: gating everything invites tests written to move a number
# rather than to catch a bug. These three hold the encrypted-vault code, where a
# silent coverage regression is a security regression.
GATED_PREFIXES = ("core:vaultcrypto", "core:vaultstore", "core:vaultcontent")

BASELINE_PATH = "scripts/coverage-baseline.json"

# How far a gated module may fall before the build fails. Non-zero because
# line counts shift slightly with unrelated refactors; small enough that losing
# a test file cannot slip through.
TOLERANCE_PP = 1.0


def modules_with_sources() -> list[tuple[str, str]]:
    """(display label, module directory) for every module with Kotlin sources."""
    found: list[tuple[str, str]] = []
    for group in ("core", "feature"):
        if not os.path.isdir(group):
            continue
        for name in sorted(os.listdir(group)):
            d = os.path.join(group, name)
            if os.path.isdir(os.path.join(d, "src", "main", "kotlin")):
                found.append((f"{group}:{name}", d))
    if os.path.isdir(os.path.join("app", "src", "main", "kotlin")):
        found.append(("app", "app"))
    return found


def find_report(module_dir: str) -> str | None:
    for name in REPORT_NAMES:
        path = os.path.join(module_dir, "build", "reports", "kover", name)
        if os.path.exists(path):
            return path
    return None


def line_counter(node: ET.Element) -> tuple[int, int]:
    """(covered, missed) for the LINE counter on a Kover report root."""
    for c in node.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("covered", 0)), int(c.get("missed", 0))
    return 0, 0


def check_gates(rows: list[tuple[str, int, int, float]]) -> int:
    """Fail if a gated module dropped more than the tolerance below baseline."""
    if not os.path.exists(BASELINE_PATH):
        print(f"No baseline at {BASELINE_PATH} — run with --write-baseline first.",
              file=sys.stderr)
        return 1
    data = json.load(open(BASELINE_PATH))
    baseline = data["modules"]
    # The file is the source of truth for tolerance, not the constant — the
    # constant is only the value used when writing a fresh baseline. Reading it
    # back matters: a `tolerance_pp` in the file that the gate ignored would be
    # a knob that silently does nothing.
    tolerance = float(data.get("tolerance_pp", TOLERANCE_PP))

    failures, checked = [], 0
    for label, _cov, _tot, pct in rows:
        if not label.startswith(GATED_PREFIXES):
            continue
        checked += 1
        if label not in baseline:
            failures.append(f"  {label}: gated but absent from the baseline file")
            continue
        was = baseline[label]
        if pct < was - tolerance:
            failures.append(
                f"  {label}: {pct:.1f}% is {was - pct:.1f}pp below the "
                f"{was:.1f}% baseline (tolerance {tolerance}pp)"
            )

    # A gated module vanishing from the report is a failure, not a pass — that
    # is how a module silently drops out of CI.
    for label in baseline:
        if label not in {r[0] for r in rows}:
            failures.append(f"  {label}: in the baseline but produced no report")

    if failures:
        print("\nCoverage gate FAILED:\n" + "\n".join(failures) +
              "\n\nIf the drop is intentional, update " + BASELINE_PATH +
              " in the same commit so the decision is visible in review.",
              file=sys.stderr)
        return 1
    print(f"\nCoverage gate passed ({checked} gated modules within tolerance).")
    return 0


def write_baseline(rows: list[tuple[str, int, int, float]]) -> int:
    data = {
        "_comment": (
            "Line-coverage baseline for gated modules (docs/TEST_COVERAGE_PRD.md "
            "R3). Regenerate deliberately with: python3 scripts/coverage-summary.py "
            "--write-baseline. Raising these numbers is good; lowering one should "
            "be an explicit, reviewed decision."
        ),
        "tolerance_pp": TOLERANCE_PP,
        "modules": {
            label: round(pct, 1)
            for label, _c, _t, pct in sorted(rows)
            if label.startswith(GATED_PREFIXES)
        },
    }
    with open(BASELINE_PATH, "w") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")
    print(f"Wrote {BASELINE_PATH}: {data['modules']}")
    return 0


def main() -> int:
    rows: list[tuple[str, int, int, float]] = []
    missing: list[str] = []

    for label, directory in modules_with_sources():
        report = find_report(directory)
        if report is None:
            missing.append(label)
            continue
        covered, missed = line_counter(ET.parse(report).getroot())
        total = covered + missed
        pct = (100.0 * covered / total) if total else 0.0
        rows.append((label, covered, total, pct))

    if missing:
        print(
            "ERROR: no Kover report found for: " + ", ".join(missing) + "\n"
            "  These modules have Kotlin sources but produced no report.\n"
            "  Flavored modules (:app, :feature:settings) need their flavor-specific\n"
            "  task named explicitly — a bare koverXmlReportDebug skips them silently.",
            file=sys.stderr,
        )
        return 1

    rows.sort(key=lambda r: r[3])
    g_cov = sum(r[1] for r in rows)
    g_tot = sum(r[2] for r in rows)
    g_pct = (100.0 * g_cov / g_tot) if g_tot else 0.0

    out = [
        "## Line coverage",
        "",
        f"**Overall: {g_pct:.1f}%** ({g_cov:,} / {g_tot:,} lines)",
        "",
        "| Module | Covered | Total | Line coverage | |",
        "|---|---:|---:|---:|---|",
    ]
    for label, covered, total, pct in rows:
        lock = " 🔒" if label.startswith(GATED_PREFIXES) else ""
        bar = "🟩" if pct >= 80 else "🟨" if pct >= 50 else "🟥"
        out.append(f"| `{label}`{lock} | {covered:,} | {total:,} | {pct:.1f}% | {bar} |")
    out += [
        "",
        "Sorted lowest first. 🔒 = coverage-gated module (a drop there fails the "
        "build). Debug variant only. See `docs/TEST_COVERAGE_PRD.md`.",
    ]

    text = "\n".join(out)
    print(text)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as fh:
            fh.write(text + "\n")

    if "--write-baseline" in sys.argv:
        return write_baseline(rows)
    if "--gate" in sys.argv:
        return check_gates(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
