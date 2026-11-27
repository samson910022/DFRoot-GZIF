#!/usr/bin/env python3
# Modified 2026-11-25 (DFRoot GZIF): add the fail-closed unit-test report check to the rebased PR #1 CI policy without a forbidden heredoc. See NOTICE.
"""Fail closed when JVM unit-test reports are absent, empty, failing or skipped."""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

report_dir = Path("app/build/test-results/testDebugUnitTest")
reports = sorted(report_dir.glob("TEST-*.xml"))
if not reports:
    sys.exit(f"::error::No TEST-*.xml files found in {report_dir}; refusing a NO-SOURCE green build.")

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
for report in reports:
    suite = ET.parse(report).getroot()
    for key in totals:
        totals[key] += int(suite.attrib.get(key, "0"))

if totals["failures"] or totals["errors"] or totals["skipped"]:
    sys.exit(f"::error::Unit-test reports contain failures, errors, or skipped tests: {totals}")

executed = totals["tests"] - totals["skipped"]
if executed <= 0:
    sys.exit(f"::error::No unit tests were executed: {totals}")

print(f"Verified {len(reports)} report(s): {executed} executed, {totals['skipped']} skipped.")
