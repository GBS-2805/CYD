#!/usr/bin/env python3
"""Fail when a deck no longer fits its flash or its RAM with room to spare.

CONVENTIONS.md says the margin is a measurement, never an assumption, but
nothing catches the day a sketch grows into that margin: the compiler prints a
percentage and moves on, and the next feature lands on a board that will not take
the upload, or one that dies allocating its framebuffer. This compiles the decks
and reads the two lines the compiler already prints, so both ceilings come from
the build - the partition scheme and the chip - instead of a hard-coded number.

Run it in a shell that already holds the deck toolchain, so `arduino-cli` and the
python esptool needs are the ones the toolchain installed:

  source ~/.arduino-cyd-3/env.sh
  ./check_headroom.py          exit 0 when every deck fits with room to spare, 1 otherwise
  ./check_headroom.py phone_deck_cyd24R    only the decks you name

Point --arduino-cli at the root you actually build with: a second install of the
same core puts the sketch at a different build path, and that moves the count by
tens of bytes (1,165,174 here against 1,165,222 through toolchain.sh).
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FQBN = "esp32:esp32:esp32"

FLASH_RE = re.compile(
    r"Sketch uses (\d+) bytes \((\d+)%\) of program storage space\. "
    r"Maximum is (\d+) bytes"
)
RAM_RE = re.compile(
    r"Global variables use (\d+) bytes \(\d+%\) of dynamic memory, "
    r"leaving (\d+) bytes for local variables\. Maximum is (\d+) bytes"
)


def find_sketches(wanted):
    """Every top-level folder whose name matches its single .ino, per CONVENTIONS."""
    if wanted:
        folders = [ROOT / name for name in wanted]
    else:
        folders = sorted(ROOT.iterdir())
    for folder in folders:
        if folder.is_dir() and (folder / f"{folder.name}.ino").is_file():
            yield folder


def compile_sketch(cli, folder, timeout):
    return subprocess.run(
        [cli, "compile", "--fqbn", FQBN, str(folder)],
        capture_output=True,
        text=True,
        timeout=timeout,
    )


def env_kb(name, fallback):
    """Let the pre-commit hook tighten a floor without restating every flag."""
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return fallback
    try:
        return int(raw)
    except ValueError:
        print(f"error: {name} is not a number: {raw!r}", file=sys.stderr)
        raise SystemExit(2)


def main():
    parser = argparse.ArgumentParser(
        description="Compile each deck and check its flash and RAM headroom."
    )
    parser.add_argument(
        "decks",
        nargs="*",
        metavar="FOLDER",
        help="deck folders to check (default: every sketch folder)",
    )
    parser.add_argument(
        "--arduino-cli",
        default=None,
        help="arduino-cli to use (default: the one on PATH)",
    )
    parser.add_argument(
        "--min-free-kb",
        type=int,
        default=env_kb("HEADROOM_MIN_FREE_KB", 64),
        metavar="KB",
        help="free flash each deck must keep, in KiB (default: 64)",
    )
    parser.add_argument(
        "--min-free-ram-kb",
        type=int,
        default=env_kb("HEADROOM_MIN_RAM_KB", 160),
        metavar="KB",
        help="DRAM each deck must leave for the stack and its buffers, in KiB "
        "(default: 160, just over the 153,600 B a full framebuffer needs)",
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

    min_flash = args.min_free_kb * 1024
    min_ram = args.min_free_ram_kb * 1024
    failures = 0
    checked = 0

    print(
        f"{'deck':30s}{'flash':>10s}{'slot':>10s}{'free':>9s}"
        f"{'globals':>9s}{'ram free':>10s}  status"
    )
    for folder in find_sketches(args.decks):
        checked += 1
        blank = f"{folder.name:30s}{'':>10s}{'':>10s}{'':>9s}{'':>9s}{'':>10s}  "
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
        flash = FLASH_RE.search(output)
        ram = RAM_RE.search(output)
        if done.returncode != 0 or not flash or not ram:
            print(f"{blank}compile failed")
            for line in output.strip().splitlines()[-8:]:
                print(f"    {line}")
            failures += 1
            continue

        used = int(flash.group(1))
        percent = int(flash.group(2))
        slot = int(flash.group(3))
        flash_free = slot - used
        globals_used = int(ram.group(1))
        ram_free = int(ram.group(2))

        problems = []
        if flash_free < 0:
            problems.append(f"flash OVER by {-flash_free:,} B - this deck needs huge_app")
        elif flash_free < min_flash:
            problems.append(
                f"flash tight: {flash_free:,} B is under the {min_flash:,} B floor"
            )
        if ram_free < min_ram:
            problems.append(f"RAM tight: {ram_free:,} B is under the {min_ram:,} B floor")
        if problems:
            status = "; ".join(problems)
            failures += 1
        else:
            status = f"ok ({percent}% flash, {globals_used:,} B globals)"

        print(
            f"{folder.name:30s}{used:>10,}{slot:>10,}{flash_free:>9,}"
            f"{globals_used:>9,}{ram_free:>10,}  {status}"
        )

    if not checked:
        print("error: no sketch folders found", file=sys.stderr)
        return 2

    if failures:
        print(
            f"\n{failures} of {checked} deck(s) failed the headroom check",
            file=sys.stderr,
        )
        return 1
    if checked == 1:
        print(
            f"\n1 deck fits, with {min_flash:,} B of flash and "
            f"{min_ram:,} B of RAM to spare"
        )
        return 0
    print(
        f"\n{checked} decks fit, each with {min_flash:,} B of flash and "
        f"{min_ram:,} B of RAM to spare"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
