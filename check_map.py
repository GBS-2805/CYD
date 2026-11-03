#!/usr/bin/env python3
"""Print which selftest branch pins which refusal, one checker at a time.

Every checker passes on this tree, so its failure paths are only ever run by the
selftest cases that name them; reading seven files to learn whether a refusal has
a case is how a gap survives a refactor. This prints the map: for each selftest,
how many assert a message and how many assert only an exit code.

It reports rather than refuses. A `want` string is a substring of a formatted
message, so deciding "this refusal is covered" from the text would be a guess;
the branch itself is the check, and `./check.sh --selftest` runs it. What this
adds is the index - when case 27 fails, it says which refusal that was.

  python3 check_map.py       the map, and a line worth reading in the gate
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def load(path):
    """A selftest module, read for its CASES; nothing in one runs at import."""
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    files = sorted(ROOT.glob("selftest_*.py"))
    if not files:
        print("error: no selftest_*.py found", file=sys.stderr)
        return 2

    print("%-24s %-26s %8s %8s" % ("selftest", "checker", "branches", "asserting"))
    total = asserting = silent = 0
    for path in files:
        module = load(path)
        cases = module.CASES
        checker = getattr(module, "CHECK", None)
        pins = sum(1 for case in cases if case.get("want") or case.get("forbids"))
        total += len(cases)
        asserting += pins
        silent += len(cases) - pins
        print("%-24s %-26s %8d %8d"
              % (path.name, checker.name if checker else "-", len(cases), pins))

    if not total:
        print("error: no branch in any selftest", file=sys.stderr)
        return 2
    print("\n%d branches across %d selftests: %d assert a message, %d assert only "
          "an exit code" % (total, len(files), asserting, silent))
    return 0


if __name__ == "__main__":
    sys.exit(main())
