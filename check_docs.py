#!/usr/bin/env python3
"""Fail when a doc quotes a flash size or a sketch md5 the build no longer makes.

CONVENTIONS.md says a published size is a measurement, never a guess, and the
READMEs quote the build of the day: 1,168,314 B for the phone deck and
1,878,661 B for the Spotify one, plus the md5 that check_widget_assets.py
compares. Nothing kept those honest. A comment that cost 32 bytes, or a header
rewrite that changed the hash, left every doc a version behind - twice now, and
both times a reader noticed before a check did.

So this compiles the decks, reads the same two compiler lines check_headroom.py
reads, hashes each sketch, and holds the markdown to those numbers. A flash size
that no deck builds to any more is a failure, unless it is listed in HISTORICAL
with the reason it is legitimately still in the prose ("1,125,781, the build
before the Bluetooth link"). An md5 that is not one of today's sketches fails
the same way.

What counts as a flash size here: a grouped number of 7 digits or more, like
1,878,661. That is deliberately narrow. The APK, an mbedTLS margin and a
globals delta are all quoted in these docs as byte counts too, and flagging them
would bury the one number this check exists for.

A relative link is the same failure in another form: a doc pointing at a file
somebody renamed describes a tree that no longer exists, and no number check can
see it. So every `](path)` in a doc has to resolve from that doc's own folder;
anchors and URLs are skipped.

One rule more, the one that matters most: each widget's README has to carry its
own deck's current size, globals and md5. That is the page a person reads before
flashing, so a stale triple there is the bug this exists for.

Run it in a shell that already holds the deck toolchain, because it builds:

  source ~/.arduino-cyd-3/env.sh
  ./check_docs.py                    exit 0 when every quoted number is today's
  ./check_docs.py phone_deck_cyd24R  only build the decks you name; all docs read
  ./check_docs.py --arduino-cli ~/.arduino-cyd-3/bin/arduino-cli

Pair it with check_headroom.py: that one refuses a build that is too big, this
one refuses a doc that describes last week's build.
"""
import argparse
import hashlib
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_headroom as headroom  # noqa: E402  (same folder, same build rules)

ROOT = Path(__file__).resolve().parent
SKIP_DIRS = {".git", ".gradle", "build", "node_modules"}

# Numbers the docs quote on purpose because they are history: a build that no
# longer exists, kept as the "it used to be this" side of a comparison. Each one
# names why, so a new stale figure cannot hide in here by accident.
HISTORICAL = {
    1122249: "the Spotify sketch on 2.0.17 on its own path, before the 3.x jump",
    1122281: "the Spotify sketch on 2.0.17 through toolchain.sh's root",
    1114485: "an earlier note's Spotify figure, which did not reproduce",
    1123241: "the Spotify sketch on 3.3.12 from an earlier root",
    1125781: "the Spotify sketch without Bluetooth, on the default scheme",
    1164489: "the phone sketch without Bluetooth, measured the same day",
    1162797: "the phone sketch on core 2.0.17; the size follows the build root",
    1162813: "the phone sketch on core 2.0.17, the other end of that spread",
    1163930: "an earlier 3.3.12 phone build on a shorter path",
    1165174: "the phone sketch through one arduino-cli root",
    1165222: "the same phone sketch through toolchain.sh's root",
    1400000: "the const table quoted as the test that overflows the slot",
    1668748: "what that 1,400,000-byte table actually linked to",
    1878581: "one run of the linked Spotify deck, 48 B under its sibling run",
    1878629: "the other side of that 48 B disagreement, quoted as a rule's example",
    1878613: "a lower bound nobody measured, quoted in CONVENTIONS as what not to publish",
}

# Old sketch hashes, likewise labelled in the prose as past values.
HISTORICAL_MD5 = {
    "00bb1560": "the Spotify sketch before the Bluetooth link",
    "f234b68b": "the Spotify sketch before its header was rewritten",
    "eca02e93": "the Spotify sketch before the callback change",
    "49f29bcb": "the Spotify sketch before the one-line answer",
}

# `62c7e6c3…` in prose, and the full 32-hex form a build log prints.
MD5_SHORT = re.compile(r"(?<![0-9a-f])([0-9a-f]{8})\u2026")
MD5_LONG = re.compile(r"(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])")
# Grouped thousands, then a floor: 1,878,661 is a flash size, 967,413 is a
# baseline note and 45,244 is a globals count, and only the first is this
# check's business.
SIZE = re.compile(r"(?<![\d.,])(\d{1,3}(?:,\d{3})+)(?![\d,])")
MIN_SIZE = 1000000
# `[text](path)` and `![alt](path)`; the image form matters because the renders
# live in artifacts/renders/ and a stale name there is just as invisible.
LINK = re.compile(r"\]\(([^)\s]+)\)")


def md5(path):
    return hashlib.md5(path.read_bytes()).hexdigest()


def shown(path):
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def link_problems(path, text):
    """Relative links that point at nothing, read from this doc's folder."""
    out = []
    for number, line in enumerate(text.splitlines(), start=1):
        for match in LINK.finditer(line):
            href = match.group(1)
            if href.startswith(("http://", "https://", "mailto:", "#")):
                continue
            local = href.split("#")[0]
            if local and not (path.parent / local).exists():
                out.append(
                    "%s:%d links to %s, which is not there"
                    % (shown(path), number, href)
                )
    return out


def docs():
    """Every markdown file a person reads: repo root and one folder down."""
    for pattern in ("*.md", "*/*.md"):
        for path in sorted(ROOT.glob(pattern)):
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            if path.is_file():
                yield path


def measure(cli, folder, timeout):
    """Compile one deck and return what the compiler said, or None."""
    try:
        done = headroom.compile_sketch(cli, folder, timeout)
    except subprocess.TimeoutExpired:
        print("error: %s timed out after %ss" % (folder.name, timeout), file=sys.stderr)
        return None
    except OSError as exc:
        print("error: could not run %s: %s" % (cli, exc), file=sys.stderr)
        return None

    output = done.stdout + done.stderr
    flash = headroom.FLASH_RE.search(output)
    ram = headroom.RAM_RE.search(output)
    if not flash or not ram:
        print("error: %s did not print a size" % folder.name, file=sys.stderr)
        for line in output.strip().splitlines()[-8:]:
            print("    " + line, file=sys.stderr)
        return None
    if done.returncode != 0:
        # A refusal still names a size; say whose numbers these are, then fail.
        print("error: %s failed to build" % folder.name, file=sys.stderr)
        for line in output.strip().splitlines()[-3:]:
            print("    " + line, file=sys.stderr)
        return None

    sketch = folder / (folder.name + ".ino")
    return {
        "folder": folder,
        "md5": md5(sketch),
        "used": int(flash.group(1)),
        "slot": int(flash.group(3)),
        "globals": int(ram.group(1)),
    }


def allowed_sizes(built):
    """What a doc may quote as a flash size without being stale."""
    ok = {}
    for deck in built:
        ok[deck["used"]] = "the %s build" % deck["folder"].name
        ok[deck["slot"]] = "the app slot %s is built for" % deck["folder"].name
        ok[deck["slot"] - deck["used"]] = "free flash in %s" % deck["folder"].name
    ok.update(HISTORICAL)
    return ok


def main():
    parser = argparse.ArgumentParser(
        description="Check that docs quote the sizes and md5s of the current build."
    )
    parser.add_argument(
        "decks",
        nargs="*",
        metavar="FOLDER",
        help="deck folders to build (default: every sketch folder)",
    )
    parser.add_argument(
        "--arduino-cli",
        default=None,
        help="arduino-cli to use (default: the one on PATH)",
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

    built = []
    for folder in headroom.find_sketches(args.decks):
        deck = measure(cli, folder, args.timeout)
        if deck is None:
            return 1
        built.append(deck)
    if not built:
        print("error: no sketch folders found", file=sys.stderr)
        return 2

    print("%-24s %-9s %-10s %-11s %s" % ("deck", "md5", "flash", "globals", "slot"))
    for deck in built:
        print(
            "%-24s %-9s %-10s %-11s %s"
            % (
                deck["folder"].name,
                deck["md5"][:8],
                "{:,}".format(deck["used"]),
                "{:,}".format(deck["globals"]),
                "{:,}".format(deck["slot"]),
            )
        )

    sizes = allowed_sizes(built)
    hashes = {deck["md5"][:8] for deck in built}
    full_hashes = {deck["md5"] for deck in built}
    problems = []
    read = list(docs())

    for path in read:
        text = path.read_text(encoding="utf-8", errors="replace")
        problems.extend(link_problems(path, text))
        for number, line in enumerate(text.splitlines(), start=1):
            for match in SIZE.finditer(line):
                value = int(match.group(1).replace(",", ""))
                if value < MIN_SIZE or value in sizes:
                    continue
                problems.append(
                    "%s:%d quotes %s, which is not a size any deck builds to now"
                    % (shown(path), number, match.group(1))
                )
            for match in MD5_SHORT.finditer(line):
                digest = match.group(1)
                if digest in hashes or digest in HISTORICAL_MD5:
                    continue
                problems.append(
                    "%s:%d quotes md5 %s\u2026, which is not a sketch here now"
                    % (shown(path), number, digest)
                )
            for match in MD5_LONG.finditer(line):
                digest = match.group(1)
                if digest in full_hashes or digest[:8] in HISTORICAL_MD5:
                    continue
                problems.append(
                    "%s:%d quotes md5 %s, which is not a sketch here now"
                    % (shown(path), number, digest)
                )

    for deck in built:
        readme = deck["folder"] / "README.md"
        if not readme.is_file():
            problems.append("%s is missing" % shown(readme))
            continue
        text = readme.read_text(encoding="utf-8", errors="replace")
        if "{:,}".format(deck["used"]) not in text:
            problems.append(
                "%s does not quote the current %s B build, so a reader there is "
                "being told the size of some other build"
                % (shown(readme), "{:,}".format(deck["used"]))
            )
        if "{:,}".format(deck["globals"]) not in text:
            problems.append(
                "%s does not quote the current %s B of globals"
                % (shown(readme), "{:,}".format(deck["globals"]))
            )
        if deck["md5"][:8] not in text:
            problems.append(
                "%s does not quote the current md5 %s\u2026"
                % (shown(readme), deck["md5"][:8])
            )

    for line in problems:
        print("  - " + line)
    if problems:
        print(
            "\n%d place(s) describe a build that no longer exists. Update the "
            "number, or add it to HISTORICAL here with the reason it stays."
            % len(problems),
            file=sys.stderr,
        )
        return 1

    print(
        "\n%d doc%s, every quoted number is today's and every link resolves"
        % (len(read), "" if len(read) == 1 else "s")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
