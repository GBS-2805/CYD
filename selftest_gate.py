#!/usr/bin/env python3
"""Prove ``check.sh`` still refuses a root whose env.sh never pinned its directories.

Both deck toolchains now write that file byte for byte identically, but a root set
up before they did keeps an env.sh that sets only ``PATH``. Sourcing it builds
through ``~/.arduino15`` and prints a size that belongs to no named root — the
confusion that put a 16-byte "spread" between two roots into five documents. The
refusal is four lines of shell in the middle of the gate, and nothing exercises
it: every default on this machine pins its directories, so it could be deleted in
a tidy-up and the gate would still pass.

  python3 selftest_gate.py     exit 0 when the gate still refuses a stale root

Each case copies ``check.sh`` into a temp directory and runs it against stand-in
roots, so no compiler is involved and the real repository is never read or
written. The copy fails its later steps, because there is no ``check_docs.py``
beside it; that is deliberate — the cases speak about the refusal and the exit
code, and never need the gate to pass.

Every ``ARDUINO_*`` variable inherited from the caller is dropped. A shell that
had sourced a pinned ``env.sh`` would otherwise leave ``ARDUINO_DIRECTORIES_DATA``
set, every case would look pinned, and the refusal would be unreachable.
"""
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check.sh"
GATE = ROOT / "check.sh"
REFUSAL = "does not pin the arduino-cli directories"

# The narrower form of the gate is scoped by naming decks, so the flag alone asks
# it to skip the app while proving no deck at all.
NARROW_REFUSAL = "--decks-only names no deck"

# Every run ends with its own total, which is how the durations in the gate's
# header are refreshed: a copy of the gate that fails its steps still times itself.
TIMED = "took "

# What both deck toolchains write: the three directories arduino-cli reads, so a
# build in this root cannot reach ~/.arduino15's core or ~/.arduino15's caches.
PINNED = """# stand-in for the file both deck toolchains write
export PATH="{root}/bin:$PATH"
export ARDUINO_DIRECTORIES_DATA="{root}/data"
export ARDUINO_DIRECTORIES_DOWNLOADS="{root}/staging"
export ARDUINO_DIRECTORIES_USER="{root}/user"
"""

# What a root set up before the toolchains pinned anything holds: PATH only, so
# arduino-cli falls back to the home directories and picks up whatever core is
# there. This file is the reason the guard exists.
STALE = """# what a root set up before the toolchains pinned anything holds
export PATH="{root}/bin:$PATH"
"""


def root(base, body, with_cli=True):
    """A stand-in arduino root: env.sh, and optionally an arduino-cli in bin/."""
    path = Path(base)
    (path / "bin").mkdir(parents=True, exist_ok=True)
    if with_cli:
        cli = path / "bin" / "arduino-cli"
        cli.write_text("#!/bin/sh\nexit 1\n")
        cli.chmod(cli.stat().st_mode | stat.S_IXUSR)
    (path / "env.sh").write_text(body.format(root=path))
    return path


def env_for(home, **extra):
    clean = {k: v for k, v in os.environ.items() if not k.startswith("ARDUINO_")}
    return dict(clean, HOME=str(home), **extra)


def run_gate(tmp, env, args=()):
    """Run a copy of the gate, so a refusal cannot touch this tree."""
    copy = Path(tmp) / "gate"
    copy.mkdir(parents=True)
    shutil.copy(GATE, copy / "check.sh")
    return subprocess.run(
        ["bash", str(copy / "check.sh"), *args],
        capture_output=True,
        text=True,
        cwd=str(copy),
        env=env,
    )


def case_pinned_home(tmp):
    """ARDUINO_HOME names a root that pins its directories: no refusal."""
    pinned = root(Path(tmp) / "roots" / "pinned", PINNED)
    return env_for(Path(tmp) / "home", ARDUINO_HOME=str(pinned))


def case_stale_home(tmp):
    """ARDUINO_HOME names a root whose env.sh sets only PATH: refused."""
    stale = root(Path(tmp) / "roots" / "legacy", STALE)
    return env_for(Path(tmp) / "home", ARDUINO_HOME=str(stale))


def case_pinned_default(tmp):
    """The default order finds ~/.arduino-cyd-3 first, so a stale sibling is moot."""
    home = Path(tmp) / "home"
    root(home / ".arduino-cyd-3", PINNED)
    root(home / ".arduino-cyd", STALE)
    return env_for(home)


def case_stale_default(tmp):
    """The only default root is the pre-pinning one: refused, and named."""
    home = Path(tmp) / "home"
    root(home / ".arduino-cyd", STALE)
    return env_for(home)


def case_decks_only_alone(tmp):
    """Any pinned root: --decks-only with no deck is refused before it runs."""
    return case_pinned_home(tmp)


CASES = [
    dict(
        name="a root that pins its directories gets past the guard",
        build=case_pinned_home,
        want_code=None,             # anything but the refusal's 2
        want=[TIMED],               # and it says how long it took
        forbids=[REFUSAL],
    ),
    dict(
        name="a root whose env.sh sets only PATH is refused",
        build=case_stale_home,
        want_code=2,
        want=[REFUSAL, "would use ~/.arduino15", "Re-run the toolchain.sh"],
        forbids=[],
    ),
    dict(
        name="a stale ~/.arduino-cyd is not reached while ~/.arduino-cyd-3 pins",
        build=case_pinned_default,
        want_code=None,
        want=[],
        forbids=[REFUSAL],
    ),
    dict(
        name="a stale default root is refused and named",
        build=case_stale_default,
        want_code=2,
        want=[REFUSAL, ".arduino-cyd/env.sh"],
        forbids=[],
    ),
    dict(
        name="--decks-only with no deck is refused rather than proving nothing",
        build=case_decks_only_alone,
        args=["--decks-only"],
        want_code=2,
        want=[NARROW_REFUSAL, "Name the decks to prove"],
        forbids=[REFUSAL],
    ),
]


def main():
    failed = 0
    started = time.time()

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            work = Path(tmp) / f"case{index}"
            work.mkdir()
            env = case["build"](work)
            done = run_gate(work, env, case.get("args", ()))
            output = done.stdout + done.stderr

            problems = []
            if case["want_code"] is None:
                if done.returncode == 2:
                    problems.append("exit 2, but this root should not be refused")
            elif done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [text for text in case["want"] if text not in output]
            if missing:
                problems.append("no " + ", ".join(repr(text) for text in missing))
            unexpected = [text for text in case["forbids"] if text in output]
            if unexpected:
                problems.append("unasked-for " + ", ".join(map(repr, unexpected)))

            if problems:
                failed += 1
                print(f"FAIL {index}. {case['name']}: {'; '.join(problems)}")
                for line in output.strip().splitlines()[:12]:
                    print(f"     | {line}")
            else:
                print(f"ok   {index}. {case['name']}")

    elapsed = time.time() - started
    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check.sh behave ({elapsed:.1f} s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
