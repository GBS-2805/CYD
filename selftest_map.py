#!/usr/bin/env python3
"""Exercise check_map.py's pairing refusals against a throwaway repo.

check_map passes here because every checker really does have a fixture, so the
day one is renamed, or a checker lands without one, the report would go on
looking green. Each case builds a temp repo with a stand-in checker and its
selftest, then removes one half of the pair.

  python3 selftest_map.py     exit 0 when every branch still behaves
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_map.py"
GATE = "check_map.py"


def selftest(checker):
    """A stand-in fixture: one branch, naming the checker it exercises."""
    return (
        "from pathlib import Path\n\n"
        'CHECK = Path(__file__).resolve().parent / "%s"\n'
        'CASES = [{"name": "a branch", "want": ["x"]}]\n' % checker
    )


def tree(dest):
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_map.py")
    (dest / "selftest_map.py").write_text(selftest("check_map.py"))
    (dest / "check_a.py").write_text("# a checker\n")
    (dest / "selftest_a.py").write_text(selftest("check_a.py"))


def orphan_checker(dest):
    (dest / "check_b.py").write_text("# a gate nobody tests\n")


def ghost_checker(dest):
    (dest / "selftest_c.py").write_text(selftest("check_c.py"))


def nameless(dest):
    (dest / "selftest_d.py").write_text(
        'CASES = [{"name": "a branch", "want": ["x"]}]\n'
    )


CASES = [
    dict(name="a tree where every checker has a selftest passes", mutate=None,
         want_code=0, want=["every checker has a selftest (2 checked)"],
         forbids=["pairing problem"]),
    dict(name="a checker with no selftest is refused", mutate=orphan_checker,
         want_code=1,
         want=["check_b.py has no selftest", "pairing problem(s)"]),
    dict(name="a selftest naming a checker that is gone is refused",
         mutate=ghost_checker, want_code=1,
         want=["selftest_c.py names check_c.py, which this tree does not hold"]),
    dict(name="a selftest with no checker is refused", mutate=nameless,
         want_code=1, want=["selftest_d.py names no checker"]),
]


def main():
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest)
            if case["mutate"]:
                case["mutate"](dest)

            done = subprocess.run(
                [sys.executable, str(dest / "check_map.py")],
                capture_output=True, text=True, cwd=str(dest),
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [t for t in case["want"] if t not in output]
            if missing:
                problems.append("no " + ", ".join(repr(t) for t in missing))
            unexpected = [t for t in case.get("forbids", []) if t in output]
            if unexpected:
                problems.append("unasked-for " + ", ".join(map(repr, unexpected)))

            if problems:
                failed += 1
                print(f"FAIL {index}. {case['name']}: {'; '.join(problems)}")
                for line in output.strip().splitlines():
                    print(f"     | {line}")
            else:
                print(f"ok   {index}. {case['name']}")

    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check_map.py behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
