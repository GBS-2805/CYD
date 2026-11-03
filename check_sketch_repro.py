#!/usr/bin/env python3
"""Compile each deck twice and refuse two images that do not match.

The APK's reproducibility is gated by `check_repro.py`; the sketches' was only
ever assumed. On 2026-11-03 both decks were compiled twice in a row and came back
byte for byte identical — application, bootloader and partition table — which is
what this pins down, for the same reason as the APK: one compile cannot say
whether a build reproduces, and a sketch's bytes are what a flash writes.

Two compiles per deck, so it is the full gate's `sketch repro` step.

  ./check_sketch_repro.py                    every sketch folder
  ./check_sketch_repro.py phone_deck_cyd24R  only the decks you name

Run it in a shell that holds the deck toolchain, so `arduino-cli` is the one the
figures in the docs came from:

  source ~/.arduino-cyd/env.sh && ./check_sketch_repro.py
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_headroom as headroom  # noqa: E402  (the decks and their FQBNs live there)

# The clock is the only thing two builds of one sketch disagree about. Measured on
# 2026-11-03 on the phone deck, two builds a minute apart differed in 69 bytes: the
# image's 32-byte copy of the ELF's SHA-256, four digits of "19:42" against
# "20:29" in .rodata, and the image's own trailing 33-byte hash and checksum. The
# ELF differed in exactly those four digits, so nothing else had moved. The core
# honours SOURCE_DATE_EPOCH, which removes the clock and leaves a comparison that
# means something; without it this check could only ever fail.
CLOCK = "1700000000"


def compile_into(cli, folder, out, build, timeout):
    """One compile, written out where it can be read.

    `--build-path` keeps the cache out of the sketch — `--output-dir` alone copies
    the images aside but still leaves an empty `<folder>/build/` behind — and it is
    the same path for both runs, because the path is written into the ELF's debug
    info: two different paths change the ELF, and with it the digest the image
    carries.
    """
    return subprocess.run(
        [cli, "compile", "--fqbn", headroom.fqbn_for(folder),
         "--build-path", str(build), "--output-dir", str(out), str(folder)],
        capture_output=True,
        text=True,
        timeout=timeout,
        env=dict(os.environ, SOURCE_DATE_EPOCH=CLOCK),
    )


def main():
    parser = argparse.ArgumentParser(
        description="Compile each deck twice and compare the images.")
    parser.add_argument("decks", nargs="*", metavar="FOLDER",
                        help="deck folders to check (default: every sketch folder)")
    parser.add_argument("--arduino-cli", default=None,
                        help="arduino-cli to use (default: the one on PATH)")
    parser.add_argument("--timeout", type=int, default=600, metavar="SECONDS",
                        help="per-compile timeout (default: 600)")
    args = parser.parse_args()

    cli = args.arduino_cli or shutil.which("arduino-cli")
    if not cli:
        print("error: no arduino-cli found; source a deck toolchain first "
              "(source ~/.arduino-cyd/env.sh) or pass --arduino-cli",
              file=sys.stderr)
        return 2

    problems = []
    summaries = []
    with tempfile.TemporaryDirectory() as tmp:
        for folder in headroom.find_sketches(args.decks):  # noqa: B007
            first = Path(tmp) / (folder.name + "-a")
            second = Path(tmp) / (folder.name + "-b")
            # One build path, cleared before each run: the second compile cannot be
            # answered from the first one's cache, so it is a build, not a lookup.
            build = Path(tmp) / (folder.name + "-build")
            for out in (first, second):
                out.mkdir()
                shutil.rmtree(build, ignore_errors=True)
                try:
                    done = compile_into(cli, folder, out, build, args.timeout)
                except subprocess.TimeoutExpired:
                    print("error: %s did not finish in %ds, so there is nothing "
                          "to compare" % (folder.name, args.timeout), file=sys.stderr)
                    return 2
                if done.returncode != 0 or not list(out.glob("*.bin")):
                    print("error: %s did not compile, so there is nothing to compare"
                          % folder.name, file=sys.stderr)
                    for line in (done.stdout + done.stderr).strip().splitlines()[-6:]:
                        print("      " + line, file=sys.stderr)
                    return 2

            application = None
            for image in sorted(first.glob("*.bin")):
                other = second / image.name
                if not other.is_file():
                    problems.append(
                        "%s: the second build wrote no %s, so the sketch does not "
                        "reproduce" % (folder.name, image.name))
                    continue
                was, now = headroom.md5(image), headroom.md5(other)
                if was != now:
                    problems.append(
                        "%s: %s differs between two builds (%s and %s), so the "
                        "sketch does not reproduce"
                        % (folder.name, image.name, was[:12], now[:12]))
                elif image.name.endswith(".ino.bin"):
                    application = was[:12]
            if application:
                summaries.append("%s %s" % (folder.name, application))

    for text in problems:
        print(text)
    if problems:
        print("\n%d image(s) differ between two builds" % len(problems),
              file=sys.stderr)
        return 1
    print("%d deck(s) built twice with the same bytes: %s"
          % (len(summaries), ", ".join(summaries)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
