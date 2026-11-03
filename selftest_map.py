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


CHECKER = (
    "def check(problems, digest):\n"
    '    problems.append("%s is %dx%d but the app draws %dx%d; run capture.sh"\n'
    '                    % ("a.png", 6, 4, 7, 4))\n'
    '    problems.append("quotes %d %% of the slot" % 74)\n'
    '    problems.append("%s quotes md5 %s\\u2026, which is not a sketch here now"\n'
    '                    % ("README.md", digest))\n'
)

WANTS = [
    "a.png is 6x4 but the app draws 7x4; run capture.sh",
    "quotes 74 % of the slot",
    "quotes md5 deadbeef\u2026, which is not a sketch here now",
    # check_map.py is parsed too, so the stub speaks its refusals as well.
    "selftest_x.py names no checker, so its branches pin no gate",
    "selftest_x.py names check_x.py, which this tree does not hold",
    "check_x.py has no selftest, so its refusals have no branch",
    "error: no selftest_*.py found",
    "pairing problem(s): a checker nothing exercises is a rule nobody runs",
    "message(s) no branch asserts: write a branch's `want` from the checker's "
    "own line, or the refusal is a rule nobody runs",
]


def selftest(checker, wants=WANTS):
    """A stand-in fixture: one branch, naming the checker it exercises."""
    return (
        "from pathlib import Path\n\n"
        'CHECK = Path(__file__).resolve().parent / "%s"\n'
        "CASES = [dict(name=\"a branch\", want=%r)]\n" % (checker, wants)
    )


def tree(dest):
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_map.py")
    (dest / "selftest_map.py").write_text(selftest("check_map.py"))
    (dest / "check_a.py").write_text(CHECKER)
    (dest / "selftest_a.py").write_text(selftest("check_a.py"))


def no_selftests(dest):
    """No selftest file at all, so there is no map to build."""
    for path in dest.glob("selftest_*.py"):
        path.unlink()


def orphan_checker(dest):
    (dest / "check_b.py").write_text("# a gate nobody tests\n")


def unasserted(dest):
    """A message the checker can print that no branch repeats."""
    path = dest / "check_a.py"
    path.write_text(path.read_text() +
                    '    problems.append("a sentence nothing repeats")\n')


def ghost_checker(dest):
    (dest / "selftest_c.py").write_text(selftest("check_c.py"))


def helper_return(dest):
    """A refusal a shared helper returns, with the caller naming the row.

    `row_shape` answers `["names a deck this tree does not hold"], None`: the
    sentence is written in the helper, so a map that only reads `append` never
    sees it, and a branch that is not written cannot pin it.
    """
    path = dest / "check_a.py"
    path.write_text(path.read_text(encoding="utf-8") + (
        "\n\ndef row_reason(name):\n"
        '    return ["a row shape nothing repeats"], None\n'
    ), encoding="utf-8")


def helper_return_asserted(dest):
    helper_return(dest)
    (dest / "selftest_a.py").write_text(
        selftest("check_a.py", WANTS + ["a row shape nothing repeats"]))


def formatted_message(dest):
    """A message built with str.format, which used to read as a call, not a literal."""
    path = dest / "check_a.py"
    path.write_text(
        path.read_text(encoding="utf-8") +
        '    problems.append("a {:,} B floor nothing repeats".format(204800))\n',
        encoding="utf-8",
    )


def nameless(dest):
    (dest / "selftest_d.py").write_text(
        'CASES = [{"name": "a branch", "want": ["x"]}]\n'
    )


CASES = [
    dict(name="a tree where every checker has a selftest passes", mutate=None,
         want_code=0,
         want=["every checker has a selftest (2 checked)",
               "check_a.py: every appended message is repeated by a branch"],
         forbids=["pairing problem"]),
    dict(name="a message no branch asserts is refused", mutate=unasserted,
         want_code=1,
         want=["check_a.py: 1 message(s) no branch repeats",
               "a sentence nothing repeats", "no branch asserts"],
         forbids=["pairing problem"]),
    dict(name="a tree with no selftest at all is an error", mutate=no_selftests,
         want_code=2, want=["no selftest_*.py found"]),
    dict(name="a checker with no selftest is refused", mutate=orphan_checker,
         want_code=1,
         want=["check_b.py has no selftest", "pairing problem(s)"]),
    dict(name="a selftest naming a checker that is gone is refused",
         mutate=ghost_checker, want_code=1,
         want=["selftest_c.py names check_c.py, which this tree does not hold"]),
    dict(name="a selftest with no checker is refused", mutate=nameless,
         want_code=1, want=["selftest_d.py names no checker"]),
    dict(name="a message a shared helper returns is refused like an appended one",
         mutate=helper_return, want_code=1,
         want=["check_a.py: 1 message(s) no branch repeats",
               "a row shape nothing repeats"],
         forbids=["pairing problem"]),
    dict(name="a helper's returned message passes when a branch asserts it",
         mutate=helper_return_asserted, want_code=0,
         want=["check_a.py: every appended message is repeated by a branch"],
         forbids=["no branch repeats", "pairing problem"]),
    dict(name="a message built with str.format is seen, not skipped",
         mutate=formatted_message, want_code=1,
         want=["a … B floor nothing repeats"],
         forbids=["pairing problem"]),
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
