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
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FQBN = "esp32:esp32:esp32"

# The two floors, in KiB. They live here so `--help` and the environment agree,
# and check_old_core.py applies the same words to an old-core build.
DEFAULT_MIN_FREE_KB = 64
DEFAULT_MIN_RAM_KB = 160

# A deck that has outgrown the default scheme says so here, and the ceiling still
# comes from the compiler rather than this table: with huge_app it prints
# 3,145,728, so the same floors are applied to a bigger slot. Only the Spotify
# deck is listed, because Bluetooth does not fit beside WiFi, TLS and its setup
# server in 1,310,720 bytes.
PARTITION = {"spotify_deck_cyd24R": "PartitionScheme=huge_app"}


def fqbn_for(folder):
    extra = PARTITION.get(folder.name)
    return f"{FQBN}:{extra}" if extra else FQBN

FLASH_RE = re.compile(
    r"Sketch uses (\d+) bytes \((\d+)%\) of program storage space\. "
    r"Maximum is (\d+) bytes"
)
RAM_RE = re.compile(
    r"Global variables use (\d+) bytes \(\d+%\) of dynamic memory, "
    r"leaving (\d+) bytes for local variables\. Maximum is (\d+) bytes"
)


def sketch_of(folder):
    """The one file a deck folder's sketch can be, per CONVENTIONS.

    One home for that rule: the docs gate, the 2.0.17 gate and this one all ask
    here, so no two of them can decide a deck is a different file.
    """
    folder = Path(folder)
    return folder / f"{folder.name}.ino"


def md5(path):
    """A file's md5, the digest every checker quotes and compares."""
    return hashlib.md5(Path(path).read_bytes()).hexdigest()


def find_sketches(wanted):
    """Every top-level folder whose name matches its single .ino, per CONVENTIONS."""
    if wanted:
        folders = [ROOT / name for name in wanted]
    else:
        folders = sorted(ROOT.iterdir())
    for folder in folders:
        if folder.is_dir() and sketch_of(folder).is_file():
            yield folder


def compile_sketch(cli, folder, timeout):
    return subprocess.run(
        [cli, "compile", "--fqbn", fqbn_for(folder), str(folder)],
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


def verdict(used, slot, ram_free, min_flash, min_ram):
    """Every way a deck misses its margin, in the order a reader wants them.

    One home for the two floors: the gate's `old core` step builds on 2.0.17 and
    compares against old_core.json instead of against today's core, but a deck
    that no longer fits is a deck that no longer fits on either.
    """
    problems = []
    flash_free = slot - used
    if flash_free < 0:
        problems.append(
            "flash OVER by {:,} B - this deck needs huge_app".format(-flash_free)
        )
    elif flash_free < min_flash:
        problems.append(
            "flash tight: {:,} B is under the {:,} B floor".format(
                flash_free, min_flash
            )
        )
    if ram_free < min_ram:
        problems.append(
            "RAM tight: {:,} B is under the {:,} B floor".format(ram_free, min_ram)
        )
    return problems


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
        default=env_kb("HEADROOM_MIN_FREE_KB", DEFAULT_MIN_FREE_KB),
        metavar="KB",
        help="free flash each deck must keep, in KiB (default: 64)",
    )
    parser.add_argument(
        "--min-free-ram-kb",
        type=int,
        default=env_kb("HEADROOM_MIN_RAM_KB", DEFAULT_MIN_RAM_KB),
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

    # The slot column is wider than it looks: huge_app prints 3,145,728, and at
    # nine characters it used to run straight into the free column beside it.
    print(
        f"{'deck':30s}{'flash':>10s}{'slot':>11s}{'free':>11s}"
        f"{'globals':>11s}{'ram free':>12s}  status"
    )
    for folder in find_sketches(args.decks):
        checked += 1
        blank = (
            f"{folder.name:30s}{'':>10s}{'':>11s}{'':>11s}{'':>11s}{'':>12s}  "
        )
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
        if not flash or not ram:
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
        if done.returncode != 0:
            # A link that fails still prints the size it could not place, so read
            # the numbers and say what happened instead of a bare "compile failed".
            problems.append("build failed")
        problems.extend(verdict(used, slot, ram_free, min_flash, min_ram))
        if problems:
            status = "; ".join(problems)
            failures += 1
        else:
            status = f"ok ({percent}% flash, {globals_used:,} B globals)"

        print(
            f"{folder.name:30s}{used:>10,}{slot:>11,}{flash_free:>11,}"
            f"{globals_used:>11,}{ram_free:>12,}  {status}"
        )
        if done.returncode != 0:
            for line in output.strip().splitlines()[-3:]:
                print(f"    {line}")

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
