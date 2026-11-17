#!/usr/bin/env python3
"""
Check .github/test-areas.json against the test sources, and turn it into CI's unit-test matrix.

The guard: every top-level class in app/src/test must belong to exactly one area (or be listed as a
helper), and every class the file names must still exist. Without it, a new test file would match no
area and silently never run in CI, and a renamed one would leave a stale entry behind.

On success it writes the matrix to $GITHUB_OUTPUT as `test-matrix`: one entry per area with its
display name, a slug for artifact names, and the Gradle filter (`*.ClassName,...`) passed to
`-PtestInclude`.

Usage: test_areas.py [areas.json] [test source root]
Stdlib only.
"""
import glob
import json
import os
import re
import sys

# A class declared at the start of a line — top level. Classes declared inside a test function
# (indented) are fixtures of that test, not tests of their own.
CLASS = re.compile(r"^(?:(?:internal|private|public|open|abstract|data)\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)", re.M)


def main(areas_file, src_root):
    sys.stdout.reconfigure(encoding="utf-8")
    with open(areas_file, encoding="utf-8") as f:
        config = json.load(f)

    found = {}  # class -> file
    for path in sorted(glob.glob(os.path.join(src_root, "**", "*.kt"), recursive=True)):
        with open(path, encoding="utf-8") as f:
            for name in CLASS.findall(f.read()):
                found[name] = os.path.relpath(path, src_root).replace("\\", "/")

    errors = []
    owner = {}
    slugs = set()
    for a in config["areas"]:
        if a["slug"] in slugs:
            errors.append(f"area slug '{a['slug']}' is used twice")
        slugs.add(a["slug"])
        if not a["classes"]:
            errors.append(f"area '{a['area']}' has no classes")
        for c in a["classes"]:
            if c in owner:
                errors.append(f"{c} is in two areas: '{owner[c]}' and '{a['area']}'")
            owner[c] = a["area"]
            if c not in found:
                errors.append(f"{c} (area '{a['area']}') has no class in {src_root} — renamed or deleted?")
    helpers = set(config.get("helpers", []))
    for c, path in sorted(found.items()):
        if c not in owner and c not in helpers:
            errors.append(f"{c} ({path}) has no area — add it to an area in {areas_file}")

    for e in errors:
        print(f"::error file={areas_file},title=Unit test areas::{e}")
    if errors:
        print(f"{len(errors)} problem(s) with the unit test areas.")
        return 1

    matrix = [
        {"area": a["area"], "slug": a["slug"], "include": ",".join(f"*.{c}" for c in a["classes"])}
        for a in config["areas"]
    ]
    for a in config["areas"]:
        print(f"{a['area']}: {len(a['classes'])} classes")
    print(f"All {len(found)} test classes belong to exactly one of {len(matrix)} areas.")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"test-matrix={json.dumps(matrix)}\n")
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    sys.exit(main(args[0] if args else ".github/test-areas.json", args[1] if len(args) > 1 else "app/src/test"))
