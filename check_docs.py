#!/usr/bin/env python3
"""Fail when a doc quotes a flash size or a sketch md5 the build no longer makes.

CONVENTIONS.md says a published size is a measurement, never a guess, and the
READMEs quote the build of the day: 1,168,314 B for the phone deck and
1,880,101 B for the Spotify one, plus the md5 that check_widget_assets.py
compares. Nothing kept those honest. A comment that cost 32 bytes, or a header
rewrite that changed the hash, left every doc a version behind - twice now, and
both times a reader noticed before a check did.

So this compiles the decks, reads the same two compiler lines check_headroom.py
reads, hashes each sketch, and holds the markdown to those numbers. A flash size
that no deck builds to any more is a failure, unless it is listed in HISTORICAL
with the reason it is legitimately still in the prose ("1,125,781, the build
before the Bluetooth link"). An md5 that is not one of today's sketches fails
the same way.

An old core's figure cannot be rebuilt by a 3.3.12 gate at all, so it is not
a HISTORICAL row either: `old_core.json` carries it beside the md5 of the
sketch that printed it, and stops vouching for it the moment that sketch
moves - which is when the figure would have wanted re-measuring anyway.

What counts as a flash size here: a grouped number of 7 digits or more, like
1,880,101. That is deliberately narrow. The APK, an mbedTLS margin and a
globals delta are all quoted in these docs as byte counts too, and flagging them
would bury the one number this check exists for.

A percentage is judged only where its ceiling is on the same line — `| flash |
1,880,101 | **1,265,627** | 3,145,728 (59 % used) |` — and only when that line
names one ceiling and carries one percentage. Anything looser ("143 % of that
slot") would need the checker to decide which slot the sentence meant, and a
checker that guesses is one people learn to skip.

One claim needs the whole sentence rather than one line: a figure the prose says
can be rebuilt. "The pair that rebuilds" once named 967,493 B against 1,162,877 B
for a phone sketch no commit holds, and a reader who went looking for the file
found nothing to compile. So a sentence that pairs a rebuild word with a figure
in NO_SKETCH, and denies it nowhere within a dozen words of
that number, is refused: the prose may
keep such a number as history, but never as something to build again.

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
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_headroom as headroom  # noqa: E402  (same folder, same build rules)
import check_old_core  # noqa: E402  (the one reader for old_core.json)

ROOT = Path(__file__).resolve().parent
SKIP_DIRS = {".git", ".gradle", "build", "node_modules"}

# Numbers the docs quote on purpose because this gate cannot print them: a build
# that no longer exists, kept as the "it used to be this" side of a comparison, or
# the same sketch through a root installed under another home, which shifts the
# binary by a few bytes. Each one names why, so a new stale figure cannot hide in
# here by accident.
HISTORICAL = {
    1122249: "the Spotify sketch on 2.0.17 on its own path, before the 3.x jump",
    1122281: "the Spotify sketch on 2.0.17 through toolchain.sh's root",
    1114485: "an earlier note's Spotify figure, which did not reproduce",
    1123241: "the Spotify sketch on 3.3.12 from an earlier root",
    1125781: "the Spotify sketch without Bluetooth, on the default scheme",
    1164489: "a phone build with no link, quoted once; no committed phone "
             "sketch lacks BLE, so it cannot be rebuilt",
    1162797: "the phone sketch on core 2.0.17; the size follows the build root",
    1162813: "the phone sketch on core 2.0.17, the other end of that spread",
    1162877: "the 2026-10-30 phone sketch on 2.0.17 at this repo's path, 64 B "
             "over the shorter one that first printed it; no commit here holds that "
             "file, so the docs quote it as a record",
    1168282: "~/.arduino-cyd/env.sh's phone build while that script set only "
             "PATH, so ~/.arduino15's dirs",
    1163930: "an earlier 3.3.12 phone build on a shorter path",
    1165174: "the phone sketch through one arduino-cli root",
    1165222: "the same phone sketch through toolchain.sh's root",
    1400000: "the const table quoted as the test that overflows the slot",
    1668748: "what that 1,400,000-byte table actually linked to",
    1878581: "one run of the linked Spotify deck, 48 B under its sibling run",
    1878629: "the other side of that 48 B disagreement, quoted as a rule's example",
    1878613: "what ~/.arduino-cyd/env.sh printed while it set only PATH, so the "
             "build went through ~/.arduino15",
    1878661: "the linked Spotify sketch through the deck chat's "
             "~/.arduino-cyd-3/env.sh, 16 B over this root; first seen as the top "
             "of a 2026-11-02 range",
    1878645: "the linked Spotify sketch through ~/.arduino-cyd under this chat's "
             "26-character home id: 32 B over the same root under a 24-character "
             "one and 16 B under that tree's ~/.arduino-cyd-3. The figure the "
             "docs quote, and a probe on 2026-11-03 showed it does not move with "
             "the sketch's own path - only with where the root is installed",
    1168170: "the phone sketch through a second install root, 144 B under this one",
    1878469: "the linked Spotify sketch through /tmp/arduino-cyd-3: 176 B under "
             "the 1,880,101 these docs quote, and 192 B under the deck chat's "
             "1,878,661",
    1125669: "the Spotify sketch through that same second root, 112 B under it",
    1267083: "not a build at all: the flash left beside an earlier 1,880,101 B "
             "Spotify build, its huge_app slot minus what the sketch used",
    1880053: "the same linked Spotify sketch through ~/.arduino-cyd/env.sh, "
             "which sets only PATH so the build goes through ~/.arduino15: "
             "48 B under this root with the globals identical",
    1879489: "the Spotify sketch with exact-rect chips and a connect-only "
              "unpair, superseded the same day",
}

# Old sketch hashes, likewise labelled in the prose as past values.
HISTORICAL_MD5 = {
    "00bb1560": "the Spotify sketch before the Bluetooth link",
    "f234b68b": "the Spotify sketch before its header was rewritten",
    "eca02e93": "the Spotify sketch before the callback change",
    "49f29bcb": "the Spotify sketch before the one-line answer",
    "3b17f04a": "the Spotify sketch before the 2026-11-03 larger hit rects "
                "and the UNPAIR hold, quoted where a date matters",
}

# Figures no committed sketch produces: a build taken from a working tree nobody
# committed, so no checkout can rebuild it. The prose may keep one as history, but
# a sentence that pairs it with a rebuild word claims a reader can compile it
# again, and there is no file to compile. 1,162,877 B is the 2026-10-30 phone
# deck, overwritten the next day by `ef0681b`; 1,164,489 B is a Bluetooth-less
# phone build no committed sketch has ever made. Each names why here, so the
# table cannot grow into a place for stale numbers to hide.
NO_SKETCH = {
    1162877: "the 2026-10-30 phone sketch, which no commit here holds",
    1164489: "the phone build without a Bluetooth link, which no committed "
             "sketch makes",
}

# A claim that a figure can be had again by compiling something, and the words
# that take it back. Judged per sentence, because markdown wraps a size and the
# verb that calls it repeatable onto different lines.
REBUILD_WORD = re.compile(r"\b(rebuild\w*|rebuilt|reproduc\w*|re-creat\w*)\b", re.I)
REBUILD_DENIED = re.compile(
    r"\b(cannot|can not|can't|never|nobody|no|not|without|used to|gone|record)\b",
    re.I,
)

# How many words a denial may sit from the figure it corrects. Twelve keeps
# "which the docs used to rebuild at 1,162,877 B" legal and refuses a correction
# parked a clause away, where a reader skimming the number never meets it.
DENIAL_WORDS = 12
# A sentence ends at `.`, `?` or `!` followed by space and something that starts
# one — a capital, a backtick, a bold mark, an opening bracket. That leaves
# 1,135.6 and `.build_figures.json` alone.
SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9`*(\[\"])")

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


# One hash for every checker, so a quoted md5 and a compared copy are the same
# digest of the same bytes; see check_headroom.md5.
md5 = headroom.md5


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
    sketch = headroom.sketch_of(folder)
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


OLD_CORE = ROOT / "old_core.json"


def old_core_figures():
    """The old core's figures, tied to the sketch that printed them.

    The gate builds 3.3.12 only, so a 2.0.17 size can never be rebuilt here. It
    used to sit in HISTORICAL with a sentence and nothing behind it, which is the
    shape that let a figure outlive the sketch it described: a rewrite changed the
    build and the prose went on quoting the old number as history. old_core.json
    names each deck's md5 with its size, globals and slot, so a figure is quotable
    while that sketch is still this tree's and stops being quotable the moment it
    moves - which is exactly when it wants re-measuring.

    The file itself is read by check_old_core.read_record() and each row is judged
    by check_old_core.row_shape(), so this gate and the 2.0.17 one cannot disagree
    about what a valid record is; only what a figure is worth to the prose is
    decided here.

    Returns (sizes a doc may quote, problems with the file itself).
    """
    data, duplicates, error = check_old_core.read_record(OLD_CORE)
    if error:
        kind, detail = error
        if kind == "missing":
            return {}, [
                "%s is missing, so the 2.0.17 figures in the prose have nothing "
                "behind them" % shown(OLD_CORE)
            ]
        if kind == "not-object":
            return {}, ["%s does not hold an object" % shown(OLD_CORE)]
        if kind == "no-decks":
            return {}, ["%s records no deck" % shown(OLD_CORE)]
        if kind == "unreadable":
            return {}, ["%s cannot be read: %s" % (shown(OLD_CORE), detail)]
        return {}, ["%s does not parse: %s" % (shown(OLD_CORE), detail)]

    problems = []
    core = data.get("core")
    if not isinstance(core, str) or not core.strip():
        problems.append("%s names no core, so no figure in it names a build"
                        % shown(OLD_CORE))
        core = "an unnamed core"

    allowed = {}
    for name, entry in sorted(data["decks"].items()):
        where = "%s: %s" % (shown(OLD_CORE), name)
        found, figures = check_old_core.row_shape(name, entry)
        problems.extend("%s %s" % (where, reason) for reason in found)
        if figures is None:
            continue
        moved = check_old_core.moved_md5(name, entry)
        if moved:
            problems.append(
                "%s records md5 %s but the sketch is %s now; rebuild it on "
                "core %s and update the file"
                % (where, moved[0], moved[1][:8] + "\u2026", core)
            )
            continue
        allowed[figures["used"]] = "the %s build on core %s" % (name, core)
        allowed.setdefault(
            figures["slot"],
            "the app slot %s builds for on core %s" % (name, core),
        )
    for key in dict.fromkeys(duplicates):
        problems.append(
            "%s names %s twice, so a read keeps only one of the rows"
            % (shown(OLD_CORE), key)
        )
    return allowed, problems

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


def sentences(text):
    """Every sentence, with the line its first word sits on.

    A flash size and the verb calling it repeatable can land on different lines
    of one paragraph, which is what wrapping does to a README. Judging a line at
    a time would either miss that claim or refuse the half that carries only the
    number, so the paragraph is flattened and split into sentences, and each one
    reports the line a reader would scroll to.
    """
    lines = text.splitlines()
    starts = []
    offset = 0
    for number, line in enumerate(lines, start=1):
        starts.append((offset, number))
        offset += len(line) + 1
    flat = " ".join(lines)

    def line_of(index):
        found = 1
        for start, number in starts:
            if start > index:
                break
            found = number
        return found

    begin = 0
    stops = [match.start() for match in SENTENCE_END.finditer(flat)]
    stops.append(len(flat))
    for stop in stops:
        piece = flat[begin:stop]
        body = piece.strip()
        if body:
            lead = len(piece) - len(piece.lstrip())
            yield line_of(begin + lead), body
        begin = stop
        while begin < len(flat) and flat[begin].isspace():
            begin += 1


def word_tokens(sentence):
    r"""The words a person counts, which `\S+` is not.

    A dash between two clauses is a token and not a word, so counting it would
    spend the window on typography; and a path or a URL counts once however long
    it is, because reading `~/.arduino-cyd-2` is reading one name, not the dozen
    words its separators would make.
    """
    return [
        match.group(0)
        for match in re.finditer(r"\S+", sentence)
        if re.search(r"\w", match.group(0))
    ]


def rebuild_problems(path, text):
    """Refuse a sentence that calls a figure rebuildable when no sketch makes it.

    `ef0681b`'s Spotify file at 967,493 B against 1,162,877 B for the 2026-10-30
    phone file was written as a pair both halves of which rebuild, and one half
    never could: that phone sketch is in no commit, so a reader who believed the
    sentence found nothing to check out. A rebuild word beside a NO_SKETCH figure
    is therefore only allowed where a denial reaches the number itself and a
    rebuild verb, both within DENIAL_WORDS words: a correction parked a clause
    away is one a reader skimming the figure never sees, which is how the
    sentence read as true for as long as it did.
    """
    def window(index):
        low = max(0, index - DENIAL_WORDS)
        return " ".join(words[low:index + DENIAL_WORDS + 1])

    problems = []
    for number, sentence in sentences(text):
        if not REBUILD_WORD.search(sentence):
            continue
        words = word_tokens(sentence)
        verbs = [i for i, word in enumerate(words) if REBUILD_WORD.search(word)]
        verb_denied = any(REBUILD_DENIED.search(window(i)) for i in verbs)
        for index, token in enumerate(words):
            digits = re.sub(r"[^\d,]", "", token)
            if not re.fullmatch(r"\d{1,3}(?:,\d{3})+", digits):
                continue
            value = int(digits.replace(",", ""))
            if value not in NO_SKETCH:
                continue
            if verb_denied and REBUILD_DENIED.search(window(index)):
                continue
            problems.append(
                "%s:%d calls %s rebuildable with no denial within %d words of it "
                "(%s); say beside the figure that it cannot be rebuilt"
                % (
                    shown(path),
                    number,
                    "{:,} B".format(value),
                    DENIAL_WORDS,
                    NO_SKETCH[value],
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
    # A 2.0.17 figure is the file's to vouch for, not HISTORICAL's.
    old_sizes, old_problems = old_core_figures()
    sizes.update(old_sizes)
    problems.extend(old_problems)
    read = list(docs())

    for path in read:
        text = path.read_text(encoding="utf-8", errors="replace")
        problems.extend(link_problems(path, text))
        problems.extend(prose_problems(path, text))
        problems.extend(percentage_problems(path, text, ceilings))
        problems.extend(rebuild_problems(path, text))
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

    # The table is only honest while it names figures no build makes. One that a
    # deck prints today would label a rebuildable number unbuildable, and one
    # missing from HISTORICAL would be refused as stale before this rule ran.
    built_sizes = {deck["used"] for deck in built}
    for value in sorted(NO_SKETCH):
        if value not in HISTORICAL:
            problems.append(
                "NO_SKETCH lists %s, which is not in HISTORICAL, so the size check "
                "refuses it before this rule can: add it there with its reason"
                % "{:,}".format(value)
            )
        if value in built_sizes:
            problems.append(
                "NO_SKETCH lists %s, but a deck builds to it today, so it is "
                "rebuildable and the entry is wrong" % "{:,}".format(value)
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
