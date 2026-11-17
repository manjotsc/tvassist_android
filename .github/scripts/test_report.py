#!/usr/bin/env python3
"""
Turn the unit-test matrix's JUnit XML into one readable summary on the workflow run page.

Each matrix job uploads its `TEST-*.xml` as an artifact named `test-results-<area>`; the "Test
report" job downloads them all into one folder per area and runs this. It writes a Markdown table
to $GITHUB_STEP_SUMMARY (totals, one row per area, then every failing test with its message) and an
error annotation per failure, and exits non-zero if anything failed or nothing was found — so the
report box is red exactly when the tests are, and an empty matrix cannot pass as green.

Stdlib only, so the job needs nothing beyond the runner's own Python.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def area_of(path, root):
    """`results/test-results-maps/TEST-x.xml` -> `maps`."""
    rel = os.path.relpath(path, root).replace("\\", "/")
    top = rel.split("/", 1)[0]
    return top.removeprefix("test-results-")


def main(root):
    # The summary uses ✅/❌; a console that is not UTF-8 (Windows' cp1252) would crash on them.
    sys.stdout.reconfigure(encoding="utf-8")
    areas = {}
    failures = []
    for path in sorted(glob.glob(os.path.join(root, "**", "TEST-*.xml"), recursive=True)):
        suite = ET.parse(path).getroot()
        a = areas.setdefault(area_of(path, root), {"tests": 0, "failed": 0, "skipped": 0, "time": 0.0, "classes": 0})
        a["classes"] += 1
        a["time"] += float(suite.get("time") or 0)
        for case in suite.iter("testcase"):
            a["tests"] += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                a["failed"] += 1
                message = (problem.get("message") or problem.text or "").strip().splitlines()
                failures.append((case.get("classname", "?"), case.get("name", "?"), message[0][:300] if message else ""))
            elif case.find("skipped") is not None:
                a["skipped"] += 1

    total = sum(a["tests"] for a in areas.values())
    failed = sum(a["failed"] for a in areas.values())
    skipped = sum(a["skipped"] for a in areas.values())

    out = []
    if not areas:
        out.append("## ❌ Unit tests: no results found\n\nNo `TEST-*.xml` was uploaded by the matrix jobs.")
    else:
        icon = "❌" if failed else "✅"
        out.append(f"## {icon} Unit tests: {total - failed - skipped} passed, {failed} failed, {skipped} skipped\n")
        out.append("| Area | Classes | Tests | Failed | Skipped | Time |")
        out.append("|---|---:|---:|---:|---:|---:|")
        for name in sorted(areas):
            a = areas[name]
            mark = " ❌" if a["failed"] else ""
            out.append(f"| {name}{mark} | {a['classes']} | {a['tests']} | {a['failed']} | {a['skipped']} | {a['time']:.1f}s |")
        if failures:
            out.append("\n### Failures\n")
            for cls, name, msg in failures:
                out.append(f"- **{cls.rsplit('.', 1)[-1]}** › `{name}`  \n  {msg}")
                # One annotation per failure, shown on the run page without opening any log.
                print(f"::error title={cls.rsplit('.', 1)[-1]} › {name}::{msg}")

    text = "\n".join(out) + "\n"
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)
    print(text)
    return 1 if failed or not areas else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "results"))
