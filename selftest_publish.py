#!/usr/bin/env python3
"""Exercise check_publish.py's refusals without building the real site.

The working tree currently has no handoff in its bundle, so the important branches
— a leaked file, a renamed copy, a future publisher that copies it, a publisher
that fails, and a missing bundle — have never run. Each case builds a throwaway
repo with a stand-in publisher and a canary handoff, then moves one thing.

  python3 selftest_publish.py     exit 0 when every branch still behaves
"""
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_publish.py"
HANDOFF = b"<!doctype html><title>owner handoff</title>\n"

STUB = """#!/usr/bin/env python3
from pathlib import Path
import shutil

here = Path(__file__).resolve().parent
site = here / "site"
public = site / "public"
public.mkdir(parents=True, exist_ok=True)
(public / "index.html").write_text("ok\\n")
{action}
"""


def tree(dest, action="", current=None, publisher=True):
    """A throwaway repo with a publisher, one render, and a handoff canary."""
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_publish.py")

    artifacts = dest / "artifacts"
    renders = artifacts / "renders"
    renders.mkdir(parents=True)
    (renders / "one.png").write_bytes(b"")

    site = artifacts / "site"
    site.mkdir()
    (site / "handoff.html").write_bytes(HANDOFF)

    if publisher:
        (artifacts / "publish_renders.py").write_text(STUB.format(action=action),
                                                      encoding="utf-8")
    if current:
        public = site / "public"
        public.mkdir()
        (public / current).write_bytes(HANDOFF)


CASES = [
    dict(
        name="a clean working bundle passes",
        action="",
        want_code=0,
        want=["public bundle carries no handoff"],
        forbids=["is in the public bundle", "a fresh build put"],
    ),
    dict(
        name="a handoff in the working bundle is refused by name",
        action="",
        current="handoff.html",
        want_code=1,
        want=["artifacts/site/public/handoff.html is in the public bundle; the "
              "handoff is owner-only and must not be published"],
        forbids=["the publisher failed"],
    ),
    dict(
        name="a renamed handoff in the working bundle is refused by its bytes",
        action="",
        current="report.html",
        want_code=1,
        want=["artifacts/site/public/report.html is in the public bundle; the "
              "handoff is owner-only and must not be published"],
        forbids=["the publisher failed"],
    ),
    dict(
        name="a future publisher that copies the handoff is refused",
        action="shutil.copyfile(site / 'handoff.html', public / 'handoff.html')",
        want_code=1,
        want=["a fresh build put handoff.html in the public bundle; the handoff is "
              "owner-only"],
    ),
    dict(
        name="a future publisher that renames the handoff is refused",
        action="shutil.copyfile(site / 'handoff.html', public / 'report.html')",
        want_code=1,
        want=["a fresh build put report.html in the public bundle; the handoff is "
              "owner-only"],
    ),
    dict(
        name="a publisher that fails is refused, not read as clean",
        action="raise SystemExit('stub publisher failed')",
        want_code=1,
        want=["the publisher failed, so a fresh bundle could not be checked"],
    ),
    dict(
        name="a publisher that writes no bundle is refused",
        action="shutil.rmtree(public)",
        want_code=1,
        want=["the publisher wrote no public bundle, so a fresh build was not "
              "checked"],
    ),
    dict(
        name="a tree with no publisher says so",
        publisher=False,
        want_code=1,
        want=["there is no publisher at artifacts/publish_renders.py, so the public "
              "bundle cannot be checked"],
    ),
]


def main():
    failed = 0
    started = time.time()

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest, case.get("action", ""), case.get("current"),
                 case.get("publisher", True))

            done = subprocess.run(
                [sys.executable, str(dest / "check_publish.py")],
                capture_output=True,
                text=True,
                cwd=str(dest),
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [text for text in case["want"] if text not in output]
            if missing:
                problems.append("no " + ", ".join(repr(text) for text in missing))
            unexpected = [text for text in case.get("forbids", []) if text in output]
            if unexpected:
                problems.append("unasked-for " + ", ".join(map(repr, unexpected)))

            if problems:
                failed += 1
                print(f"FAIL {index}. {case['name']}: {'; '.join(problems)}")
                for line in output.strip().splitlines():
                    print(f"     | {line}")
            else:
                print(f"ok   {index}. {case['name']}")

    elapsed = time.time() - started
    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check_publish.py behave ({elapsed:.1f} s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
