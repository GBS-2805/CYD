#!/usr/bin/env python3
"""Fail when a deck no longer fits its app partition with room to spare.

CONVENTIONS.md says the flash margin is a measurement, never an assumption, but
nothing catches the day a sketch grows into that margin: the compiler prints a
percentage and moves on, and the next feature lands on a board that will not
take the upload. This compiles every sketch folder and reads the same
"Sketch uses ... of program storage space. Maximum is ..." line, so the slot size
comes from the partition scheme the FQBN selects rather than a hard-coded number.

Run it in a shell that already holds the deck toolchain, so `arduino-cli` and the
python esptool needs are the ones the toolchain installed:

  source ~/.arduino-cyd-3/env.sh
  ./check_flash_headroom.py    exit 0 when every deck fits with room to spare, 1 otherwise

Point --arduino-cli at the root you actually build with: a second install of the
same core puts the sketch at a different build path, and that moves the count by
tens of bytes (1,165,174 here against 1,165,222 through toolchain.sh).
"""
import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FQBN = "esp32:esp32:esp32"

SIZE_RE = re.compile(
    r"Sketch uses (\d+) bytes \((\d+)%\) of program storage space\. "
    r"Maximum is (\d+) bytes"
)
GLOBALS_RE = re.compile(r"Global variables use (\d+) bytes")


def find_sketches():
    """Every top-level folder whose name matches its single .ino, per CONVENTIONS."""
    for folder in sorted(ROOT.iterdir()):
        if folder.is_dir() and (folder / f"{folder.name}.ino").is_file():
            yield folder


def compile_sketch(cli, folder, timeout):
    return subprocess.run(
        [cli, "compile", "--fqbn", FQBN, str(folder)],
        capture_output=True,
        text=True,
        timeout=timeout,
    )


def main():
    parser = argparse.ArgumentParser(
        description="Compile each deck and check its app partition still has room."
    )
    parser.add_argument(
        "--arduino-cli",
        default=None,
        help="arduino-cli to use (default: the one on PATH)",
    )
    parser.add_argument(
        "--min-free-kb",
        type=int,
        default=64,
        metavar="KB",
        help="free flash each deck must keep, in KiB (default: 64)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=600,
        metavar="SECONDS",
        help="per-compile timeout (default: 600)",
    )
    args = parser.parse_args()

    cli = args.arduino_cli or shutil.which("arduino-cli")
    if not cli:
        print(
            "error: no arduino-cli found; source a deck toolchain first "
            "(source ~/.arduino-cyd-3/env.sh) or pass --arduino-cli",
            file=sys.stderr,
        )
        return 2

    min_free = args.min_free_kb * 1024
    failures = 0
    checked = 0

    print(
        f"{'deck':30s}{'used':>10s}{'slot':>10s}{'free':>9s}{'globals':>9s}  status"
    )
    for folder in find_sketches():
        checked += 1
        blank = f"{folder.name:30s}{'':>10s}{'':>10s}{'':>9s}{'':>9s}  "
        try:
            done = compile_sketch(cli, folder, args.timeout)
        except subprocess.TimeoutExpired:
            print(f"{blank}timeout after {args.timeout}s")
            failures += 1
            continue
        except OSError as exc:
            print(f"error: could not run {cli}: {exc}", file=sys.stderr)
            return 2

        output = done.stdout + done.stderr
        size = SIZE_RE.search(output)
        if done.returncode != 0 or not size:
            print(f"{blank}compile failed")
            for line in output.strip().splitlines()[-8:]:
                print(f"    {line}")
            failures += 1
            continue

        used = int(size.group(1))
        percent = int(size.group(2))
        slot = int(size.group(3))
        free = slot - used
        globals_match = GLOBALS_RE.search(output)
        globals_used = int(globals_match.group(1)) if globals_match else 0

        if free < 0:
            status = f"OVER by {-free:,} B - this deck needs huge_app"
            failures += 1
        elif free < min_free:
            status = (
                f"tight: {free:,} B is under the {min_free:,} B floor - "
                "stop adding, or move the deck to huge_app"
            )
            failures += 1
        else:
            status = f"ok ({percent}% used)"

        print(
            f"{folder.name:30s}{used:>10,}{slot:>10,}{free:>9,}"
            f"{globals_used:>9,}  {status}"
        )

    if not checked:
        print("error: no sketch folders found", file=sys.stderr)
        return 2

    if failures:
        print(f"\n{failures} of {checked} decks need attention", file=sys.stderr)
        return 1
    print(f"\n{checked} decks fit, each with at least {min_free:,} B to spare")
    return 0


if __name__ == "__main__":
    sys.exit(main())
