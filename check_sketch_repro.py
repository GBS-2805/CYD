#!/usr/bin/env python3
"""Compile each deck twice and refuse two images that do not match.

The APK's reproducibility is gated by `check_repro.py`; the sketches' was only
ever assumed. On 2026-11-03 both decks were compiled twice in a row and came back
byte for byte identical — application, bootloader and partition table — which is
what this pins down, for the same reason as the APK: one compile cannot say
whether a build reproduces, and a sketch's bytes are what a flash writes.

Two compiles per deck, and the decks compile at the same time: one compile is
single-core work, so proving four decks takes about as long as proving one. The
two runs of a *single* deck cannot overlap — they share one build path on purpose
— so the pooling is across decks, never inside one.

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
from concurrent.futures import ThreadPoolExecutor
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


def prove(cli, folder, tmp, timeout):
    """Compile one deck twice and say what differs, or why nothing could be compared.

    One build path for both runs, cleared between them. It is the same path for
    both because `--build-path` is written into the ELF's debug info: two different
    paths change the ELF and, with it, the digest the image carries, which would
    fail the check for a reason that has nothing to do with the sketch.
    """
    first = Path(tmp) / (folder.name + "-a")
    second = Path(tmp) / (folder.name + "-b")
    build = Path(tmp) / (folder.name + "-build")
    for out in (first, second):
        out.mkdir(exist_ok=True)
        shutil.rmtree(build, ignore_errors=True)
        try:
            done = compile_into(cli, folder, out, build, timeout)
        except subprocess.TimeoutExpired:
            return ("error: %s did not finish in %ds, so there is nothing "
                    "to compare" % (folder.name, timeout)), [], ""
        if done.returncode != 0 or not list(out.glob("*.bin")):
            lines = (done.stdout + done.stderr).strip().splitlines()[-6:]
            return ("error: %s did not compile, so there is nothing to compare\n%s"
                    % (folder.name,
                       "\n".join("      " + line for line in lines))), [], ""

    problems = []
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
    return "", problems, ("%s %s" % (folder.name, application)) if application else ""


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

    folders = list(headroom.find_sketches(args.decks))   # find_sketches yields

    # A deck is proved on its own thread and the decks run together. That is the
    # only place this can be parallelised: a deck's two runs must share one build
    # path — it is written into the ELF's debug info, so two different paths would
    # change the bytes — and one directory cannot hold two builds at once.
    problems = []
    summaries = []
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        workers = max(1, min(len(folders), os.cpu_count() or 1))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(
                lambda folder: prove(cli, folder, tmp, args.timeout), folders))

    for error, found, summary in results:
        if error:
            failures.append(error)
            continue
        problems.extend(found)
        if summary:
            summaries.append(summary)

    if failures:
        for text in failures:
            print(text, file=sys.stderr)
        return 2
    for text in problems:
        print(text)
    if problems:
        print("\n%d image(s) differ between two builds" % len(problems),
              file=sys.stderr)
        return 1
    print("%d deck(s) built twice with the same bytes: %s"
          % (len(summaries), ", ".join(summaries)))
    return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
