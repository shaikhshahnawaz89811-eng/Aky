#!/usr/bin/env python3
"""List EVERY test and lint failure of a CI run, and fail the job when there is one.

Run from the repo root after `testDebugUnitTest` and `lintDebug` (also after they failed).
Nothing is swallowed:
  - no test results at all, or 0 tests ran      -> FAIL (the test task did not really run)
  - a skipped test (@Ignore / Assume)           -> FAIL (a skip would hide a failure)
  - a failed / errored test                     -> FAIL, each one listed with its message
  - lint Fatal / Error                          -> FAIL, each one listed
  - lint report missing                         -> FAIL
Lint warnings are only counted per rule (they do not fail the job).
Output goes to the log, to $GITHUB_STEP_SUMMARY (run page) and as ::error:: annotations.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

TEST_DIR = "app/build/test-results/testDebugUnitTest"
LINT_XML = "app/build/reports/lint-results-debug.xml"
MAX_ANNOTATIONS = 50

lines = []          # markdown for the run summary
problems = 0        # number of things that make the job red
annotations = 0


def out(text=""):
    print(text)
    lines.append(text)


def annotate(msg, file=None, line=None):
    global annotations
    if annotations >= MAX_ANNOTATIONS:
        return
    annotations += 1
    msg = msg.strip().splitlines()[0][:400] if msg.strip() else "(no message)"
    msg = msg.replace("%", "%25").replace("\r", "").replace("\n", "")
    loc = ""
    if file:
        loc = f" file={file}" + (f",line={line}" if line else "")
    print(f"::error{loc}::{msg}")


def first_line(text, limit=300):
    text = (text or "").strip()
    return (text.splitlines()[0] if text else "(no message)")[:limit]


def check_tests():
    global problems
    out("## Unit tests")
    files = sorted(glob.glob(os.path.join(TEST_DIR, "TEST-*.xml")))
    if not files:
        out(f"- **FAIL**: no test results in `{TEST_DIR}` - the unit tests did not run (compile error earlier?).")
        annotate(f"No unit test results found in {TEST_DIR}")
        problems += 1
        return
    total = failed = skipped = 0
    bad = []
    for f in files:
        try:
            suite = ET.parse(f).getroot()
        except ET.ParseError as e:
            out(f"- **FAIL**: cannot read `{os.path.basename(f)}`: {e}")
            annotate(f"Cannot read test result {os.path.basename(f)}: {e}")
            problems += 1
            continue
        for case in suite.iter("testcase"):
            total += 1
            name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is not None:
                    failed += 1
                    bad.append((name, kind, first_line(node.get("message") or node.text)))
                    break
            else:
                if case.find("skipped") is not None:
                    skipped += 1
                    bad.append((name, "skipped", "skipped - a skipped test is treated as a failure"))
    out(f"- {total} tests: **{total - failed - skipped} passed, {failed} failed, {skipped} skipped**")
    if total == 0:
        out("- **FAIL**: 0 tests ran.")
        annotate("0 unit tests ran")
        problems += 1
    for name, kind, msg in bad:
        out(f"- **{kind.upper()}** `{name}` - {msg}")
        annotate(f"{kind.upper()} {name}: {msg}")
        problems += 1


def check_lint():
    global problems
    out("")
    out("## Lint (lintDebug)")
    if not os.path.isfile(LINT_XML):
        out(f"- **FAIL**: `{LINT_XML}` missing - lint did not finish.")
        annotate(f"Lint report missing: {LINT_XML}")
        problems += 1
        return
    try:
        issues = ET.parse(LINT_XML).getroot().findall("issue")
    except ET.ParseError as e:
        out(f"- **FAIL**: cannot read lint report: {e}")
        annotate(f"Cannot read lint report: {e}")
        problems += 1
        return
    errors = [i for i in issues if i.get("severity") in ("Fatal", "Error")]
    warnings = [i for i in issues if i.get("severity") == "Warning"]
    out(f"- {len(errors)} errors, {len(warnings)} warnings (full list: `ci-reports` artifact -> lint-results-debug.html)")
    for i in errors:
        loc = i.find("location")
        file = loc.get("file") if loc is not None else None
        line = loc.get("line") if loc is not None else None
        where = f"{file}:{line}" if file else "(no location)"
        msg = first_line(i.get("message"))
        out(f"- **{i.get('severity').upper()}** `{i.get('id')}` {where} - {msg}")
        annotate(f"lint {i.get('id')}: {msg}", file, line)
        problems += 1
    if warnings:
        counts = {}
        for i in warnings:
            counts[i.get("id")] = counts.get(i.get("id"), 0) + 1
        top = sorted(counts.items(), key=lambda kv: -kv[1])[:10]
        out("- warnings by rule (top 10): " + ", ".join(f"`{k}` x{v}" for k, v in top))


def main():
    check_tests()
    check_lint()
    out("")
    out(f"**Result: {'FAIL - ' + str(problems) + ' problem(s)' if problems else 'all tests and lint clean'}**")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
