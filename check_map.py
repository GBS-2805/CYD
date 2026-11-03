#!/usr/bin/env python3
"""Print which selftest branch pins which refusal, and keep the pairs honest.

Every checker passes on this tree, so its failure paths are only ever run by the
selftest cases that name them; reading eight files to learn whether a refusal has
a case is how a gap survives a refactor. This prints the map - per selftest, the
checker it exercises and how many branches assert a message - and refuses the two
pairings the map rests on: a checker with no selftest has refusals nothing runs,
and a selftest naming a checker that is gone is a fixture for a file nobody has.

It does not try to decide coverage from the text. A `want` string is a substring
of a formatted message, so a branch that stops refusing is caught by running it,
which `./check.sh --selftest` does; which refusal it pinned is what this adds.

  python3 check_map.py       exit 0 when every checker has a selftest
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def shown(path):
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def load(path):
    """A selftest module, read for its CASES; nothing in one runs at import."""
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def checkers():
    """Every gate that has a fixture, the shell gate included."""
    found = sorted(ROOT.glob("check_*.py"))
    shell = ROOT / "check.sh"
    if shell.is_file():
        found.append(shell)
    return found


def main():
    files = sorted(ROOT.glob("selftest_*.py"))
    if not files:
        print("error: no selftest_*.py found", file=sys.stderr)
        return 2

    problems = []
    covered = {}
    rows = []
    total = asserting = silent = 0
    for path in files:
        module = load(path)
        cases = module.CASES
        checker = getattr(module, "CHECK", None)
        if checker is None:
            problems.append("%s names no checker, so its branches pin no gate"
                            % shown(path))
            name = "-"
        else:
            name = shown(checker)
            if not Path(checker).is_file():
                problems.append("%s names %s, which this tree does not hold"
                                % (shown(path), name))
            else:
                covered.setdefault(Path(checker).resolve(), shown(path))
        pins = sum(1 for case in cases if case.get("want") or case.get("forbids"))
        total += len(cases)
        asserting += pins
        silent += len(cases) - pins
        rows.append((shown(path), name, len(cases), pins))

    for checker in checkers():
        if checker.resolve() not in covered:
            problems.append("%s has no selftest, so its refusals have no branch"
                            % shown(checker))

    print("%-24s %-26s %8s %8s" % ("selftest", "checker", "branches", "asserting"))
    for row in rows:
        print("%-24s %-26s %8d %8d" % row)
    print()
    print("%d branches across %d selftests: %d assert a message, %d assert only an "
          "exit code" % (total, len(files), asserting, silent))
    for line in problems:
        print("  - %s" % line)
    if problems:
        print("\n%d pairing problem(s): a checker nothing exercises is a rule "
              "nobody runs" % len(problems), file=sys.stderr)
        return 1
    print("every checker has a selftest (%d checked)" % len(checkers()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
