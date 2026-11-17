#!/usr/bin/env python3
"""
Keep only error-level findings in Android lint's SARIF report, for upload to GitHub code scanning.

Lint's warnings are acknowledged noise here (94 of 95 on 2026-11-17 were `LogNotTimber`, a nudge
toward a logging library this app does not use) and would fill the Security tab with alerts nobody
acts on. Errors are what fail the lint job, so they are what belongs next to the code.

A finding often carries no `level` of its own: SARIF then takes the rule's
`defaultConfiguration.level`, and failing that "warning" (SARIF 2.1.0 §3.27.10). The rules list is
left intact so every `ruleIndex` still points at the right rule.

Usage: sarif_errors_only.py IN.sarif OUT.sarif
"""
import json
import sys


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        sarif = json.load(f)
    kept = dropped = 0
    for run in sarif.get("runs", []):
        rules = run.get("tool", {}).get("driver", {}).get("rules", [])
        default_level = {
            rule.get("id"): rule.get("defaultConfiguration", {}).get("level", "warning") for rule in rules
        }
        results = []
        for result in run.get("results", []):
            level = result.get("level") or default_level.get(result.get("ruleId"), "warning")
            if level == "error":
                results.append(result)
            else:
                dropped += 1
        kept += len(results)
        run["results"] = results
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(sarif, f)
    # An empty upload is deliberate: it closes alerts for errors that have since been fixed.
    print(f"lint errors uploaded: {kept} (warnings and notes left out: {dropped})")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
