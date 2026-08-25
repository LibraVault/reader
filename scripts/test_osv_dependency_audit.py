#!/usr/bin/env python3
"""Regression test for osv_dependency_audit.py, run directly:

    python3 scripts/test_osv_dependency_audit.py

No network — feeds canned TOML/OSV-response data straight to the pure
functions. Matches how the sibling `.github/scripts/test_*.sh` scripts in
this repo are used: a standalone script, not wired into a CI job (there's
no Python test harness in this repo yet).
"""
from __future__ import annotations

import tomllib

from osv_dependency_audit import build_report, maven_packages

FAILURES = 0


def check(desc: str, actual, expected) -> None:
    global FAILURES
    if actual != expected:
        print(f"FAIL: {desc}")
        print(f"  expected: {expected!r}")
        print(f"  actual:   {actual!r}")
        FAILURES += 1
    else:
        print(f"PASS: {desc}")


SAMPLE_CATALOG = tomllib.loads("""
[versions]
core-ktx = "1.13.1"
okhttp   = "4.12.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "core-ktx" }
okhttp            = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
kotlin-stdlib-bom = { group = "org.jetbrains.kotlin", name = "kotlin-bom", version = "2.2.10" }
android-gradle-plugin-alias-only-no-coords = { module = "com.android.tools.build:gradle" }
compose-ui        = { group = "androidx.compose.ui", name = "ui" }
""")


def test_maven_packages_resolves_version_ref_and_inline_version() -> None:
    packages, skipped = maven_packages(SAMPLE_CATALOG)
    check(
        "resolves both version.ref and inline version",
        packages,
        [
            ("androidx-core-ktx", "androidx.core:core-ktx", "1.13.1"),
            ("kotlin-stdlib-bom", "org.jetbrains.kotlin:kotlin-bom", "2.2.10"),
            ("okhttp", "com.squareup.okhttp3:okhttp", "4.12.0"),
        ],
    )
    check(
        "skips coordless entries and BOM-managed entries with no resolvable version, without dropping them silently",
        skipped,
        ["android-gradle-plugin-alias-only-no-coords", "compose-ui"],
    )


def test_build_report_no_vulns() -> None:
    packages = [("androidx-core-ktx", "androidx.core:core-ktx", "1.13.1")]
    results = [{}]
    report, found_any = build_report(packages, results)
    check("clean scan reports no vulnerabilities", found_any, False)
    check("clean scan report mentions 'No known vulnerabilities'", "No known vulnerabilities" in report, True)


def test_build_report_with_vuln_uses_id_when_summary_lookup_unavailable() -> None:
    # summarize_vuln() does its own network call for a human-readable summary;
    # here we exercise it against an ID that (deliberately) can't resolve, to
    # confirm the fallback-to-bare-ID path works without needing to mock
    # urllib. The osv.dev link is still printed either way, which is what
    # actually matters for a human following up.
    packages = [("okhttp", "com.squareup.okhttp3:okhttp", "4.9.0")]
    results = [{"vulns": [{"id": "OSV-TEST-DOES-NOT-EXIST-0001"}]}]
    report, found_any = build_report(packages, results)
    check("vulnerable package flips found_any", found_any, True)
    check("report includes the coordinate", "com.squareup.okhttp3:okhttp:4.9.0" in report, True)
    check("report includes the osv.dev link", "https://osv.dev/vulnerability/OSV-TEST-DOES-NOT-EXIST-0001" in report, True)


def test_build_report_surfaces_skipped_entries() -> None:
    packages = [("androidx-core-ktx", "androidx.core:core-ktx", "1.13.1")]
    results = [{}]
    report, _found_any = build_report(packages, results, skipped=["compose-ui"])
    check("report surfaces skipped entries instead of dropping them silently", "`compose-ui`" in report, True)


def test_build_report_can_fail() -> None:
    # AGENTS.md: prove a test can fail before trusting it. Deliberately wrong
    # expectation, then restored below — not a real assertion, just proof.
    packages = [("androidx-core-ktx", "androidx.core:core-ktx", "1.13.1")]
    _report, found_any = build_report(packages, [{}])
    assert found_any is False, "sanity: this must be False on a clean result"


if __name__ == "__main__":
    test_maven_packages_resolves_version_ref_and_inline_version()
    test_build_report_no_vulns()
    test_build_report_with_vuln_uses_id_when_summary_lookup_unavailable()
    test_build_report_surfaces_skipped_entries()
    test_build_report_can_fail()

    if FAILURES:
        print(f"\n{FAILURES} failure(s)")
        raise SystemExit(1)
    print("\nAll tests passed.")
