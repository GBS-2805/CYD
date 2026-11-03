#!/usr/bin/env python3
"""Build the APK twice and refuse two archives that do not match.

A build that reproduces is a claim, not a property: until 2026-11-03 this tree's
APK hashed differently on every run, because AGP restamped its Dependency Info
Block each time, and nothing noticed for weeks. One build can never answer the
question, so this runs the build twice and compares the two files.

It is slow by design — two full builds — and lives in `./check.sh --full` rather
than the fast gate, next to the APK step it is about.

  ./check_repro.py                        build twice and compare
  ./check_repro.py --artifact PATH        another file to compare
  ./check_repro.py --build-cmd "…"        another build command (the selftest's)

The artifact is copied out after each build, so the file left in `dist/` is the
second one either way: a disagreement is visible in `git status` as well as here.
"""
import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ARTIFACT = ROOT / "android-companion" / "dist" / "PhoneDeckBridge-debug.apk"
BUILD = "bash android-companion/toolchain.sh"


def shown(path):
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build(command, artifact, scratch, index):
    """Run the build once and copy what it shipped out of the way, or None."""
    # No OSError branch: the command goes through the shell, so a missing binary
    # is exit 127 and the refusal below names it. A path that could raise here
    # would be a branch nothing can reach.
    done = subprocess.run(command, shell=True, cwd=str(ROOT),
                          capture_output=True, text=True)
    if done.returncode != 0:
        print("error: the build command failed, so there is nothing to compare",
              file=sys.stderr)
        for line in (done.stdout + done.stderr).strip().splitlines()[-5:]:
            print("      " + line, file=sys.stderr)
        return None
    if not artifact.is_file():
        print("error: the build wrote no %s, so there is nothing to compare"
              % shown(artifact), file=sys.stderr)
        return None
    copy = scratch / ("build%d%s" % (index, artifact.suffix))
    shutil.copyfile(artifact, copy)
    return copy


def main():
    parser = argparse.ArgumentParser(
        description="Build twice and refuse two archives that do not match.")
    parser.add_argument("--artifact", default=str(ARTIFACT),
                        help="the file the build ships (default: %s)" % shown(ARTIFACT))
    parser.add_argument("--build-cmd", default=BUILD,
                        help="the build to run twice (default: %s)" % BUILD)
    args = parser.parse_args()

    artifact = Path(args.artifact)
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        scratch = Path(tmp)
        first = build(args.build_cmd, artifact, scratch, 1)
        if first is None:
            return 2
        second = build(args.build_cmd, artifact, scratch, 2)
        if second is None:
            return 2
        was, now = digest(first), digest(second)
        # Read inside the block: the copies live in the scratch directory, and the
        # temporary directory takes them with it.
        was_size, now_size = first.stat().st_size, second.stat().st_size
        if was_size != now_size:
            problems.append(
                "%s: two builds differ in size (%d B and %d B), so the archive is "
                "not reproducible" % (shown(artifact), was_size, now_size))
        elif was != now:
            problems.append(
                "%s: two builds of one tree gave %s and %s, so the archive is not "
                "reproducible" % (shown(artifact), was[:12], now[:12]))

    for text in problems:
        print(text)
    if problems:
        print("\n%d build(s) produced different bytes" % len(problems),
              file=sys.stderr)
        return 1
    print("two builds, same bytes: %s, %s, %d B"
          % (shown(artifact), was[:12], was_size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
