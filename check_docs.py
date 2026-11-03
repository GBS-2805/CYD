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

A percentage is judged only where its ceiling is on the same line — `| flash |
1,878,645 | **1,267,083** | 3,145,728 (59 % used) |` — and only when that line
names one ceiling and carries one percentage. Anything looser ("143 % of that
slot") would need the checker to decide which slot the sentence meant, and a
checker that guesses is one people learn to skip.

A relative link is the same failure in another form: a doc pointing at a file
somebody renamed describes a tree that no longer exists, and no number check can
see it. So every `](path)` in a doc has to resolve from that doc's own folder;
anchors and URLs are skipped.

A cached measurement keeps the commit gate cheap. Building both decks costs most
of a minute and a commit that only edits prose cannot change a size, so the last
figures are written to `.build_figures.json` beside the repo and reused only while
that deck's sketch hashes the same **and** the compiler behind it is unchanged —
the CLI version and every installed core, asked of `arduino-cli` itself. Anything
unknown means a build, never a guess. `--refresh` ignores the file, and every run
names which decks came from it, so a cached figure is never dressed up as a fresh
one.

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
import json
import re
import shutil
import subprocess
import sys
import time
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
    1164489: "a phone sketch with no link, quoted once against the Spotify "
             "build; all five committed phone sketches carry BLE, so it cannot "
             "be rebuilt and stays history only",
    1162797: "the phone sketch on core 2.0.17; the size follows the build root",
    1162813: "the phone sketch on core 2.0.17, the other end of that spread",
    1162877: "the 2026-10-30 phone sketch on 2.0.17, rebuilt at this repo's "
             "path: 64 B over the shorter path that first printed it",
    1167409: "today's phone sketch on 2.0.17 through ~/.arduino-cyd-2; the "
             "2.0.17 figures in the prose are the 2026-10-30 file",
    1168282: "~/.arduino-cyd/env.sh's phone build, PATH only, so ~/.arduino15's "
             "dirs",
    1163930: "an earlier 3.3.12 phone build on a shorter path",
    1165174: "the phone sketch through one arduino-cli root",
    1165222: "the same phone sketch through toolchain.sh's root",
    1400000: "the const table quoted as the test that overflows the slot",
    1668748: "what that 1,400,000-byte table actually linked to",
    1878581: "one run of the linked Spotify deck, 48 B under its sibling run",
    1878629: "the other side of that 48 B disagreement, quoted as a rule's example",
    1878613: "what ~/.arduino-cyd/env.sh prints: it sets only PATH, so that build "
             "goes through ~/.arduino15",
    1878661: "the linked Spotify sketch through ~/.arduino-cyd-3/env.sh; first seen "
             "as the top of a 2026-11-02 range, now the figure these docs quote",
    1168170: "the phone sketch through a second install root, 144 B under this one",
    1878469: "the linked Spotify sketch through that same second root: 192 B under "
             "today's 1,878,661, and 176 B under the 1,878,645 these docs quoted then",
    1125669: "the Spotify sketch through that same second root, 112 B under it",
    1267083: "not a build at all: the flash left beside an earlier 1,878,645 B "
             "Spotify build, its huge_app slot minus what the sketch used",
}

# Old sketch hashes, likewise labelled in the prose as past values.
HISTORICAL_MD5 = {
    "00bb1560": "the Spotify sketch before the Bluetooth link",
    "f234b68b": "the Spotify sketch before its header was rewritten",
    "eca02e93": "the Spotify sketch before the callback change",
    "49f29bcb": "the Spotify sketch before the one-line answer",
}

# `62c7e6c3…` in prose, and the full 32-hex form a build log prints.
# A path segment is a branch id — `freebuff/e9252a57…` — not a sketch.
MD5_SHORT = re.compile(r"(?<![0-9a-f/])([0-9a-f]{8})\u2026")
MD5_LONG = re.compile(r"(?<![0-9a-f])([0-9a-f]{32})(?![0-9a-f])")
# A URL is not a sketch. The render host's subdomain is a 32-hex run —
# `site-c4870019b7c74299bae95043c45330f8.freebuff.page` — so scanning a doc for
# hashes without blanking its URLs first fails the tree that publishes its own
# renders. That happened; this is the fix, and selftest_docs.py pins it.
URL = re.compile(r"\S*://\S+")
# Grouped thousands, then a floor: 1,878,661 is a flash size, 967,413 is a
# baseline note and 45,244 is a globals count, and only the first is this
# check's business.
SIZE = re.compile(r"(?<![\d.,])(\d{1,3}(?:,\d{3})+)(?![\d,])")
MIN_SIZE = 1000000
# A percentage that has to agree with the bytes beside it, and the DRAM ceiling
# the compiler prints, which no deck build here reports as a slot.
PERCENT = re.compile(r"(?<![\w.])(\d{1,3})\s?%")
COUNT = re.compile(r"(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d{2,})(?!\d)")
DRAM_CEILING = 327680
# `[text](path)` and `![alt](path)`; the image form matters because the renders
# live in artifacts/renders/ and a stale name there is just as invisible.
LINK = re.compile(r"\]\(([^)\s]+)\)")


# The last measurement of each deck, so a commit that edits prose rebuilds
# nothing. Garbage in this file means a build, never a wrong answer.
CACHE = ROOT / ".build_figures.json"


def toolchain_fingerprint(cli):
    """What a cached figure stays true across: this CLI and these cores.

    An empty dict means unknown, and unknown means rebuild: a size measured under
    a different core is a size that no longer exists, which is exactly what this
    check exists to stop passing quietly.
    """
    stamp = {}
    try:
        done = subprocess.run(
            [cli, "version"], capture_output=True, text=True, timeout=30
        )
    except (OSError, subprocess.SubprocessError):
        return {}
    lines = (done.stdout or done.stderr).strip().splitlines()
    if not lines:
        return {}
    stamp["cli"] = lines[0]
    try:
        done = subprocess.run(
            [cli, "core", "list", "--format", "json"],
            capture_output=True, text=True, timeout=120,
        )
        cores = []
        for entry in json.loads(done.stdout or "[]"):
            if isinstance(entry, dict):
                cores.append("%s %s" % (entry.get("id"), entry.get("installed")))
            else:
                cores.append(str(entry))
    except (OSError, subprocess.SubprocessError, ValueError):
        return {}
    if not cores:
        return {}
    stamp["cores"] = json.dumps(cores, sort_keys=True)
    return stamp


def load_cache():
    try:
        data = json.loads(CACHE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def save_cache(cache):
    try:
        CACHE.write_text(
            json.dumps(cache, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except OSError as exc:
        print(
            "note: could not write %s (%s); the next run rebuilds"
            % (shown(CACHE), exc),
            file=sys.stderr,
        )


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
            # `![home](…)` and `[x](…)` illustrate a shape rather than link
            if "\u2026" in href:
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


def measure(cli, folder, timeout, cache=None, fingerprint=None):
    """Compile one deck, or take its last measurement, and return the figures."""
    sketch = folder / (folder.name + ".ino")
    digest = md5(sketch)
    if cache is not None and fingerprint:
        entry = cache.get(folder.name)
        if (
            isinstance(entry, dict)
            and entry.get("md5") == digest
            and entry.get("fingerprint") == fingerprint
            and entry.get("fqbn") == headroom.fqbn_for(folder)
            and all(isinstance(entry.get(k), int) for k in ("used", "slot", "globals"))
        ):
            return {
                "folder": folder,
                "md5": digest,
                "used": entry["used"],
                "slot": entry["slot"],
                "globals": entry["globals"],
                "built": False,
            }

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

    deck = {
        "folder": folder,
        "md5": digest,
        "used": int(flash.group(1)),
        "slot": int(flash.group(3)),
        "globals": int(ram.group(1)),
        "built": True,
    }
    if cache is not None and fingerprint:
        cache[folder.name] = {
            "md5": digest,
            "fingerprint": fingerprint,
            "fqbn": headroom.fqbn_for(folder),
            "used": deck["used"],
            "slot": deck["slot"],
            "globals": deck["globals"],
            "measured": time.strftime("%Y-%m-%d"),
        }
    return deck


def allowed_sizes(built):
    """What a doc may quote as a flash size without being stale."""
    ok = {}
    for deck in built:
        ok[deck["used"]] = "the %s build" % deck["folder"].name
        ok[deck["slot"]] = "the app slot %s is built for" % deck["folder"].name
        ok[deck["slot"] - deck["used"]] = "free flash in %s" % deck["folder"].name
    ok.update(HISTORICAL)
    return ok


# Names this repo does not hold but prose legitimately mentions, each with its
# reason, like HISTORICAL: a tool the ROM protocol mirrors, a file a toolchain
# root creates. Anything else named in backticks has to exist.
ELSEWHERE = {
    "esptool.py": "the uploader this app's protocol imitates, never vendored here",
    "env.sh": "written into a toolchain root (~/.arduino-cyd, ~/.android-offsite)",
    "my_deck_cyd24R.ino": "the name the Adding-a-widget recipe tells you to create",
}

# `Foo.java`, `bar.py`, `toolchain.sh`: prose names a file rather than a path.
NAME_SHAPE = re.compile(r"`([\w-]+\.(?:java|ino|py|sh))`")
# "The current 30 sources are ..." — a count that moves whenever a class arrives.
SOURCE_CLAIM = re.compile(r"\b(\d+)\s+sources\b")
# "The branch on it today is ..." — a reading of check_branches, true only once.
UNDATED_READING = "branch on it today"

_TREE_NAMES = None
_SOURCE_COUNT = None


def tree_facts():
    """Basenames this tree holds, and how many app sources it has, read once."""
    global _TREE_NAMES, _SOURCE_COUNT
    if _TREE_NAMES is None:
        _TREE_NAMES = {path.name for path in ROOT.rglob("*") if path.is_file()}
        app = ROOT / "android-companion/app/src/main/java/dev/phonedesk/companion"
        _SOURCE_COUNT = len(list(app.glob("*.java"))) if app.is_dir() else 0
    return _TREE_NAMES, _SOURCE_COUNT


def prose_problems(path, text):
    """Claims a link and a figure cannot catch: a file that is gone, a count that
    moved, a reading of another tool that ages the moment anything changes."""
    out = []
    names, sources = tree_facts()
    for number, line in enumerate(text.splitlines(), start=1):
        for match in NAME_SHAPE.finditer(line):
            token = match.group(1)
            if token in ELSEWHERE or token in names:
                continue
            out.append(
                "%s:%d names %s, which this tree does not hold"
                % (shown(path), number, token)
            )
        for match in SOURCE_CLAIM.finditer(line):
            if int(match.group(1)) != sources:
                out.append(
                    "%s:%d says %s sources; there are %d"
                    % (shown(path), number, match.group(1), sources)
                )
        if UNDATED_READING in line:
            out.append(
                "%s:%d says which branch is %r; a reading ages, so date it"
                % (shown(path), number, UNDATED_READING)
            )
    return out


def apk_problem():
    """Exact bytes live in HANDOFF and nowhere else, so they have to be these."""
    apk = ROOT / "android-companion/dist/PhoneDeckBridge-debug.apk"
    handoff = ROOT / "android-companion/HANDOFF.md"
    if not apk.is_file() or not handoff.is_file():
        return []
    size = "{:,} bytes".format(apk.stat().st_size)
    text = handoff.read_text(encoding="utf-8", errors="replace")
    if size in text:
        return []
    return [
        "%s does not record %s, which is the APK in dist/; exact bytes live there "
        "and nowhere else, so a rebuild is one edit" % (shown(handoff), size)
    ]


def percentage_problems(path, text, ceilings):
    """Refuse a percentage whose ceiling is missing, or disagrees with its bytes.

    Every share of a size names that size inline — `143 % of the 1,310,720-byte
    slot`, never `143 % of that slot` — and once it is named the arithmetic has to
    work: the ceiling and a byte figure on the same line must divide to the
    percentage, floor or round. A percentage on a line that quotes a build size
    but names no ceiling is refused, because that is the shape that hid "142 % of
    that slot" until a reader found it: the number was checkable only by carrying
    a denominator across a sentence break, which nothing here can do without
    guessing which slot the writer meant. Percentages that are not shares of a
    size — 8% of a drawing's width, a 16 % wash, disk use — sit on lines with no
    million-byte figure and are left alone.
    """
    problems = []
    for number, line in enumerate(text.splitlines(), start=1):
        percents = [int(match.group(1)) for match in PERCENT.finditer(line)]
        if not percents:
            continue
        counts = [
            int(match.group(1).replace(",", "")) for match in COUNT.finditer(line)
        ]
        named = sorted({value for value in counts if value in ceilings})
        if not named:
            if any(value >= MIN_SIZE for value in counts):
                problems.append(
                    "%s:%d quotes %s %% beside a build size and names no ceiling; "
                    "write the slot it is a share of on that line"
                    % (shown(path), number, "/".join(str(p) for p in percents))
                )
            continue
        figures = [
            value for value in counts if value not in ceilings and value >= 1000
        ]
        for percent in percents:
            exact = [100.0 * value / ceiling for ceiling in named for value in figures]
            if any(percent in {int(v), int(round(v))} for v in exact):
                continue
            problems.append(
                "%s:%d quotes %d %%, which none of the figures beside it makes of %s"
                % (
                    shown(path),
                    number,
                    percent,
                    " or ".join("{:,}".format(c) for c in named),
                )
            )
    return problems


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
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="ignore .build_figures.json and rebuild every deck",
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

    cache = {} if args.refresh else load_cache()
    fingerprint = {} if args.refresh else toolchain_fingerprint(cli)
    built = []
    for folder in headroom.find_sketches(args.decks):
        deck = measure(cli, folder, args.timeout, cache, fingerprint)
        if deck is None:
            return 1
        built.append(deck)
    if any(deck["built"] for deck in built):
        save_cache(cache)

    if not built:
        print("error: no sketch folders found", file=sys.stderr)
        return 2

    print("%-24s %-9s %-10s %-11s %-10s %s"
        % ("deck", "md5", "flash", "globals", "slot", "from"))
    for deck in built:
        print(
            "%-24s %-9s %-10s %-11s %-10s %s"
            % (
                deck["folder"].name,
                deck["md5"][:8],
                "{:,}".format(deck["used"]),
                "{:,}".format(deck["globals"]),
                "{:,}".format(deck["slot"]),
                "built" if deck["built"] else "cache",
            )
        )

    sizes = allowed_sizes(built)
    ceilings = {deck["slot"] for deck in built} | {DRAM_CEILING}
    hashes = {deck["md5"][:8] for deck in built}
    full_hashes = {deck["md5"] for deck in built}
    problems = []
    read = list(docs())

    for path in read:
        text = path.read_text(encoding="utf-8", errors="replace")
        problems.extend(link_problems(path, text))
        problems.extend(prose_problems(path, text))
        problems.extend(percentage_problems(path, text, ceilings))
        for number, line in enumerate(text.splitlines(), start=1):
            for match in SIZE.finditer(line):
                value = int(match.group(1).replace(",", ""))
                if value < MIN_SIZE or value in sizes:
                    continue
                problems.append(
                    "%s:%d quotes %s, which is not a size any deck builds to now"
                    % (shown(path), number, match.group(1))
                )
            for match in MD5_SHORT.finditer(URL.sub(" ", line)):
                digest = match.group(1)
                if digest in hashes or digest in HISTORICAL_MD5:
                    continue
                problems.append(
                    "%s:%d quotes md5 %s\u2026, which is not a sketch here now"
                    % (shown(path), number, digest)
                )
            for match in MD5_LONG.finditer(URL.sub(" ", line)):
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

    problems.extend(apk_problem())

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

    rebuilt = sum(1 for deck in built if deck["built"])
    if rebuilt < len(built):
        print(
            "%d of %d deck(s) came from %s, unchanged since it was measured; "
            "--refresh rebuilds."
            % (len(built) - rebuilt, len(built), shown(CACHE))
        )

    print(
        "\n%d doc%s, every quoted number is today's and every link resolves"
        % (len(read), "" if len(read) == 1 else "s")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
