#!/usr/bin/env python3
"""Exercise check_screens.py on throwaway trees that claim the wrong count.

The tree agrees with itself today, so these refusals would only ever run when two
screens arrive and seven files keep saying seven. Each case builds a git repo
holding stand-in frames and a sentence that counts them — in words, in digits, with
a hyphen, in a paragraph that also names the count and in one that does not — so
the branches run in about a second with no toolchain.

A fixture says the wrong number on purpose, which is exactly why the checker skips
`selftest_*.py` while it scans; one case pins that boundary too.

  python3 selftest_screens.py      exit 0 when every branch still behaves
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_screens.py"
FRAMES = 9

AGREES = "The design page draws nine screens.\n"
STALE = "The design page draws seven screens.\n"


def tree(dest, docs=None, frames=FRAMES, stage_docs=True):
    """A repo of `frames` stand-in frames plus the files under test."""
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "check_screens.py").write_text(
        (ROOT / "check_screens.py").read_text(encoding="utf-8"), encoding="utf-8")
    renders = dest / "artifacts" / "renders"
    renders.mkdir(parents=True)
    for index in range(1, frames + 1):
        (renders / ("%02d.png" % index)).write_bytes(b"")
    for name, body in (docs if docs is not None else {"page.md": AGREES}).items():
        path = dest / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("GIT_")}
    subprocess.run(["git", "init", "-q"], cwd=str(dest), capture_output=True, env=env)
    # The frames and this checker are always staged; a document is staged unless the
    # case is about a file the index never got, which the gate's `tracked` step owns.
    staged = ["."] if stage_docs else ["artifacts", "check_screens.py"]
    subprocess.run(["git", "add", "--"] + staged, cwd=str(dest), capture_output=True,
                   env=env)


CASES = [
    dict(name="a file that counts the frames right passes", want_code=0,
         want=["claim(s) of a screen count"], forbids=["but this tree draws"]),
    dict(name="a file that counts seven while the tree holds nine is refused",
         want_code=1, docs={"page.md": STALE},
         want=["says 'seven screens', but this tree draws",
               "claim(s) of a screen count this tree does not draw"]),
    dict(name="a hyphenated claim is read the same way", want_code=1,
         docs={"page.md": "The strip is a seven-frame picture.\n"},
         want=["says 'seven-frame', but this tree draws"]),
    dict(name="digits are read the same as words", want_code=1,
         docs={"page.md": "The capture installs 7 captures.\n"},
         want=["says '7 captures', but this tree draws"]),
    dict(name="a claim that names the count today is a history, not a refusal",
         want_code=0,
         docs={"page.md": "The strip came to show seven frames a week after the app "
                          "had nine screens.\n"},
         want=["every one is 9"], forbids=["but this tree draws"]),
    dict(name="a right count in a later paragraph does not excuse the earlier one",
         want_code=1,
         docs={"page.md": "The old page drew seven screens.\n\nToday it is nine "
                          "screens.\n"},
         want=["page.md:1 says 'seven screens'"]),
    dict(name="the count comes from the frames, not from the sentence",
         want_code=1, frames=8, docs={"page.md": AGREES},
         want=["but this tree draws 8 screens"]),
    dict(name="a tree whose git holds no frame says there is nothing to compare",
         want_code=2, frames=0, docs={"page.md": AGREES},
         want=["no frame under artifacts/renders"]),
    dict(name="a count of one is a part of the app, not a count of it", want_code=0,
         docs={"page.md": "Scrolling from one screen to another keeps the row in "
                          "place, with one capture per press.\n"},
         want=["every one is 9"], forbids=["but this tree draws"]),
    dict(name="a file the index never got is not read", want_code=0, stage_docs=False,
         docs={"page.md": STALE},
         want=["claim(s) of a screen count in 1 file(s)"],
         forbids=["page.md", "but this tree draws"]),
    dict(name="the selftests are skipped, because a fixture says the wrong number",
         want_code=0,
         docs={"page.md": AGREES, "selftest_example.py": "CASE = 'seven screens'\n"},
         want=["every one is 9"], forbids=["but this tree draws"]),
]


def main():
    base_env = {key: value for key, value in os.environ.items()
                if not key.startswith("GIT_")}
    failed = 0

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest, case.get("docs"), case.get("frames", FRAMES),
                 case.get("stage_docs", True))

            done = subprocess.run(
                [sys.executable, str(dest / "check_screens.py")],
                capture_output=True, text=True, cwd=str(dest), env=base_env,
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
    print(f"all {len(CASES)} branches of check_screens.py behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
