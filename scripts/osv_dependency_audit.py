#!/usr/bin/env python3
"""Query OSV.dev for known vulnerabilities in every Maven dependency declared
in gradle/libs.versions.toml.

Replaces the one-off manual OSV query from issue #531's dependency audit
(23 production dependencies, checked by hand, zero known vulns at the time)
with a repeatable check — runnable locally and wired into CI on a schedule
by .github/workflows/dependency-audit.yml.

Usage:
    python3 scripts/osv_dependency_audit.py

Exits 1 (and prints a report) if OSV.dev reports a known vulnerability
against any declared library version, exit 0 otherwise. Network failures
(OSV.dev unreachable) also exit 1 rather than silently reporting "clean" —
see query_osv_batch()'s docstring on why.

No dependencies beyond the standard library (tomllib, Python 3.11+).
"""
from __future__ import annotations

import json
import os
import sys
import tomllib
import urllib.error
import urllib.request
from pathlib import Path

CATALOG_PATH = Path(__file__).resolve().parent.parent / "gradle" / "libs.versions.toml"
OSV_QUERYBATCH_URL = "https://api.osv.dev/v1/querybatch"
OSV_VULN_URL_TEMPLATE = "https://api.osv.dev/v1/vulns/{}"
ECOSYSTEM = "Maven"


def load_catalog(path: Path = CATALOG_PATH) -> dict:
    with path.open("rb") as f:
        return tomllib.load(f)


def maven_packages(catalog: dict) -> tuple[list[tuple[str, str, str]], list[str]]:
    """Return (packages, skipped) where packages is a list of (catalog key,
    "group:artifact", version) for every [libraries] entry with a resolvable
    Maven group/name/version, and skipped is the catalog keys that couldn't
    be resolved (each with a reason) — most commonly androidx.compose.*
    entries whose version is managed entirely by the compose-bom platform
    entry rather than declared per-artifact, so there's no version string
    to statically read out of the catalog. Reporting these explicitly
    rather than dropping them silently matters: guessing they share the
    BOM's own version string would be actively wrong (the BOM pins each
    artifact to its own, different, version) and would make the report
    falsely claim those artifacts were checked.

    Scans the whole catalog (test + production deps), a superset of the 23
    production deps the original manual audit covered — test dependencies
    run on CI machines too, so a known vuln there is still worth surfacing.
    """
    versions = catalog.get("versions", {})
    packages = []
    skipped = []
    for key, lib in catalog.get("libraries", {}).items():
        if not isinstance(lib, dict):
            skipped.append(key)
            continue
        group = lib.get("group")
        name = lib.get("name")
        if not group or not name:
            skipped.append(key)
            continue
        version = lib.get("version")
        if isinstance(version, dict):
            version = versions.get(version.get("ref"))
        if not isinstance(version, str):
            skipped.append(key)
            continue
        packages.append((key, f"{group}:{name}", version))
    return sorted(packages), sorted(skipped)


def query_osv_batch(packages: list[tuple[str, str, str]]) -> list[dict]:
    """POST to OSV.dev's batch endpoint. Returns one result dict per package,
    same order as `packages` (OSV's contract: `results[i]` answers
    `queries[i]`). A missing/empty "vulns" key means no known vulnerability.

    Raises on any network/HTTP failure rather than swallowing it — an
    unreachable OSV.dev must not be indistinguishable from "checked, found
    nothing," or this check silently stops meaning anything the first time
    the API has a bad day.
    """
    body = json.dumps({
        "queries": [
            {"package": {"name": name, "ecosystem": ECOSYSTEM}, "version": version}
            for _key, name, version in packages
        ]
    }).encode("utf-8")
    req = urllib.request.Request(
        OSV_QUERYBATCH_URL, data=body,
        headers={"Content-Type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)["results"]


def summarize_vuln(vuln_id: str) -> str:
    """Best-effort one-line summary for a vuln ID; falls back to the bare ID
    if the detail lookup fails (e.g. rate-limited) since the ID alone is
    still actionable via https://osv.dev/vulnerability/<id>."""
    try:
        req = urllib.request.Request(OSV_VULN_URL_TEMPLATE.format(vuln_id))
        with urllib.request.urlopen(req, timeout=15) as resp:
            summary = json.load(resp).get("summary")
        return f"{vuln_id} — {summary}" if summary else vuln_id
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError):
        return vuln_id


def build_report(packages: list[tuple[str, str, str]], results: list[dict], skipped: list[str] | None = None) -> tuple[str, bool]:
    lines = [f"Scanned {len(packages)} Maven dependencies from `gradle/libs.versions.toml` against OSV.dev.", ""]
    found_any = False
    for (key, coord, version), result in zip(packages, results):
        vulns = result.get("vulns") or []
        if not vulns:
            continue
        found_any = True
        lines.append(f"### `{key}` — {coord}:{version}")
        for v in vulns:
            lines.append(f"- {summarize_vuln(v['id'])}")
            lines.append(f"  https://osv.dev/vulnerability/{v['id']}")
        lines.append("")

    if not found_any:
        lines.append("No known vulnerabilities found.")

    if skipped:
        lines.append("")
        lines.append(
            f"Skipped {len(skipped)} catalog entries with no statically-resolvable Maven "
            "group:artifact:version (e.g. androidx.compose.* artifacts managed by the "
            "compose-bom platform entry — guessing a version for these would be actively "
            "wrong): " + ", ".join(f"`{k}`" for k in skipped)
        )

    return "\n".join(lines), found_any


def main() -> int:
    catalog = load_catalog()
    packages, skipped = maven_packages(catalog)
    if not packages:
        print("No Maven libraries found in gradle/libs.versions.toml — parser bug?", file=sys.stderr)
        return 1

    try:
        results = query_osv_batch(packages)
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as exc:
        print(f"OSV.dev query failed: {exc}", file=sys.stderr)
        return 1

    report, found_any = build_report(packages, results, skipped)
    print(report)

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a") as f:
            f.write(f"### OSV dependency audit\n\n{report}\n")

    return 1 if found_any else 0


if __name__ == "__main__":
    sys.exit(main())
