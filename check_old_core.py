#!/usr/bin/env python3
"""Fail when a deck no longer builds to the figures old_core.json records.

The gate builds 3.3.12 only, so a 2.0.17 number cannot be rebuilt by the fast
check - which is why it lives in a file instead of in HISTORICAL. A file is only
as good as the run behind it, though: `old_core.json` says 1,167,409 B for the
phone deck, and nothing notices when the sketch grows and the file does not.
check_docs.py refuses the file the moment the sketch's md5 moves, but it cannot
say what the new figure is. This does: it builds each deck in the root that holds
the old core and compares size, globals, slot and md5 with the record.

Run it in a shell that holds the old core - `./check.sh --full` does this as its
`old core` step:

  source ~/.arduino-cyd-2/env.sh
  ./check_old_core.py                  exit 0 when every deck still builds to it
  ./check_old_core.py --record         the file alone: no compiler, no build

`--record` is the fast gate's `old record` step. It needs no toolchain, so it
runs on a machine that has none, and it answers a question the build cannot: is
the file shaped so that a build would mean anything? Every deck it names must be
a deck this tree holds with exactly one row, each row must carry a 32-digit md5,
and that sketch must be in the git index - an md5 of a file a clone would not
have describes bytes nobody can check out.

A disagreement is not a broken tree: it is a measurement that moved, and the fix
is either the sketch or the record. The row says which figures moved and by how
much, so re-measuring is one edit.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_headroom as headroom  # noqa: E402  (same folder, same build rules)

ROOT = Path(__file__).resolve().parent
RECORD = ROOT / "old_core.json"
MD5_RE = re.compile(r"^[0-9a-f]{32}$")


def shown(path):
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def read_record(path=RECORD):
    """(data, duplicates, error) for old_core.json, read one way for every checker.

    json keeps the last of two equal keys without a word, so a deck named twice
    reads as one deck and the other row is invisible; the hook below remembers it.
    `error` is a kind and a detail rather than a sentence, because the docs gate
    says a figure has nothing behind it while this one says the file will not
    read: the rules are shared, the wording belongs to whoever is speaking.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None, [], ("missing", str(path))
    except OSError as exc:
        return None, [], ("unreadable", str(exc))

    duplicates = []

    def hook(pairs):
        seen = {}
        for key, value in pairs:
            if key in seen:
                duplicates.append(key)
            seen[key] = value
        return seen

    try:
        data = json.loads(text, object_pairs_hook=hook)
    except ValueError as exc:
        return None, [], ("unparseable", str(exc))
    if not isinstance(data, dict):
        return None, [], ("not-object", None)
    if not isinstance(data.get("decks"), dict) or not data["decks"]:
        return None, [], ("no-decks", None)
    return data, duplicates, None


def row_shape(name, entry):
    """(reasons, figures) for one row, before its figures are compared with a build.

    One home for the two questions both readers ask - does this tree hold the deck,
    and does its row carry three positive numbers - so a rule cannot hold in the
    docs gate and not in this one.
    """
    sketch = headroom.sketch_of(ROOT / name)
    if not sketch.is_file():
        return ["names a deck this tree does not hold"], None
    if not isinstance(entry, dict):
        return ["is not an object"], None
    figures = {}
    for key in ("used", "globals", "slot"):
        value = entry.get(key)
        if not isinstance(value, int) or value <= 0:
            return ["records no %s" % key], None
        figures[key] = value
    found = []
    if figures["used"] > figures["slot"]:
        found.append(
            "puts %s B in a %s B slot, which no build does"
            % ("{:,}".format(figures["used"]), "{:,}".format(figures["slot"]))
        )
    return found, figures


def load():
    """The record and the keys it repeats, or None after saying why it will not read."""
    data, duplicates, error = read_record()
    if error:
        kind, detail = error
        if kind == "missing":
            print("error: %s is missing" % shown(RECORD), file=sys.stderr)
        elif kind in ("unparseable", "unreadable"):
            print("error: %s does not read as JSON: %s" % (shown(RECORD), detail),
                  file=sys.stderr)
        elif kind == "not-object":
            print("error: %s does not hold an object" % shown(RECORD),
                  file=sys.stderr)
        else:
            print("error: %s records no deck" % shown(RECORD), file=sys.stderr)
        return None
    return data, duplicates


def index_problem(sketch):
    """None when git tracks this sketch, else why a clone would not have its bytes."""
    rel = shown(sketch)
    # A git hook exports GIT_INDEX_FILE (and sometimes GIT_DIR) for its own
    # commit; the question here is this tree's committed index, so a caller's
    # git environment is dropped rather than trusted.
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("GIT_")}
    try:
        done = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "--error-unmatch", "--", rel],
            capture_output=True,
            text=True,
            env=env,
        )
    except OSError:
        return "git could not be run here, so the index cannot be read"
    if done.returncode == 0:
        return None
    if "not a git repository" in (done.stderr or ""):
        return "there is no git repository here, so the index cannot be read"
    return "the sketch is not in the index, so a clone would not hold the bytes its md5 describes"


def row_problems(name, entry, want_index):
    """What is wrong with one deck's row, before any build is compared with it."""
    found, _ = row_shape(name, entry)
    if found:
        return found
    sketch = headroom.sketch_of(ROOT / name)
    out = []
    digest = entry.get("md5")
    if not isinstance(digest, str) or not MD5_RE.match(digest):
        out.append("carries no 32-digit md5 for its sketch")
    if want_index:
        loose = index_problem(sketch)
        if loose:
            out.append(loose)
    return out


def moves(entry, used, globals_used, slot, digest):
    """Every figure the record and this build disagree about, recorded -> built."""
    out = []
    # The record's keys, read as the words the decks' READMEs use for them.
    for key, word, built in (("used", "flash", used),
                             ("globals", "globals", globals_used),
                             ("slot", "slot", slot)):
        was = entry.get(key)
        if not isinstance(was, int):
            out.append("%s is not recorded" % word)
        elif was != built:
            out.append("%s %s -> %s (%+d)" % (word, "{:,}".format(was),
                                             "{:,}".format(built), built - was))
    if entry.get("md5") != digest:
        out.append("md5 %s -> %s" % (entry.get("md5"), digest[:8] + "\u2026"))
    return out


def record_main(names, duplicates, data):
    """The record's shape alone: no compiler, no build, no toolchain."""
    print(f"{'deck':24s}  what the record says")
    problems = 0
    for name in names:
        found = row_problems(name, data["decks"][name], want_index=True)
        problems += 1 if found else 0
        print(f"{name:24s}  {'; '.join(found) or 'one row, a sketch in the index, an md5'}")
    for key in dict.fromkeys(duplicates):
        # One top-level repeat costs the same as a deck named twice: a read keeps
        # one of them and the other row is gone without a word.
        problems += 1
        print(f"{key:24s}  is named twice, so a read keeps only one of the rows")
    if problems:
        print("\n%d problem(s) in %s" % (problems, shown(RECORD)), file=sys.stderr)
        return 1
    print(
        "\n%d deck%s recorded consistently: one row each, a sketch in the index, "
        "an md5 for its bytes"
        % (len(names), "" if len(names) == 1 else "s")
    )
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Compile each deck and compare it with old_core.json."
    )
    parser.add_argument(
        "decks", nargs="*", metavar="FOLDER",
        help="deck folders to compare (default: every deck the record names)",
    )
    parser.add_argument(
        "--record", action="store_true",
        help="check the record alone - one row per deck, an md5 in the index - "
             "without compiling anything",
    )
    parser.add_argument("--arduino-cli", default=None,
                        help="arduino-cli to use (default: the one on PATH)")
    parser.add_argument("--timeout", type=int, default=600, metavar="SECONDS",
                        help="per-compile timeout (default: 600)")
    args = parser.parse_args()

    loaded = load()
    if loaded is None:
        return 2
    data, duplicates = loaded
    wanted = set(args.decks)
    names = [name for name in sorted(data["decks"]) if not wanted or name in wanted]
    if not names:
        print("error: %s names no deck this tree holds" % shown(RECORD),
              file=sys.stderr)
        return 2
    if args.record:
        return record_main(names, duplicates, data)

    cli = args.arduino_cli or shutil.which("arduino-cli")
    if not cli:
        print(
            "error: no arduino-cli found; source the old core's toolchain first "
            "(source ~/.arduino-cyd-2/env.sh) or pass --arduino-cli",
            file=sys.stderr,
        )
        return 2
    core = data.get("core") or "an unnamed core"
    min_flash = headroom.env_kb(
        "HEADROOM_MIN_FREE_KB", headroom.DEFAULT_MIN_FREE_KB
    ) * 1024
    min_ram = headroom.env_kb(
        "HEADROOM_MIN_RAM_KB", headroom.DEFAULT_MIN_RAM_KB
    ) * 1024

    print(
        f"{'deck':24s}{'recorded':>10s}{'built':>10s}{'delta':>10s}"
        f"{'globals':>10s}{'ram free':>11s}  status"
    )
    problems = 0
    for key in dict.fromkeys(duplicates):
        print(f"{key:24s}  is named twice, so a read keeps only one of the rows")
        problems += 1
    checked = 0
    for name in names:
        entry = data["decks"][name]
        folder = ROOT / name
        sketch = headroom.sketch_of(folder)
        loose = row_problems(name, entry, want_index=False)
        if loose:
            print(f"{name:24s}  {'; '.join(loose)}")
            problems += 1
            continue
        checked += 1
        try:
            done = headroom.compile_sketch(cli, folder, args.timeout)
        except subprocess.TimeoutExpired:
            print(f"{name:24s}  timeout after {args.timeout}s")
            problems += 1
            continue
        except OSError as exc:
            print("error: could not run %s: %s" % (cli, exc), file=sys.stderr)
            return 2

        output = done.stdout + done.stderr
        flash = headroom.FLASH_RE.search(output)
        ram = headroom.RAM_RE.search(output)
        if done.returncode != 0 or not flash or not ram:
            print(f"{name:24s}  did not build on core {core}")
            for line in output.strip().splitlines()[-4:]:
                print("    " + line)
            problems += 1
            continue

        used, slot = int(flash.group(1)), int(flash.group(3))
        globals_used = int(ram.group(1))
        ram_free = int(ram.group(2))
        digest = headroom.md5(sketch)
        found = headroom.verdict(used, slot, ram_free, min_flash, min_ram)
        found += moves(entry, used, globals_used, slot, digest)
        status = "; ".join(found) or "agrees with the record"
        recorded = entry.get("used")
        rec_text = "{:,}".format(recorded) if isinstance(recorded, int) else "-"
        delta_text = "{:+,}".format(used - recorded) if isinstance(recorded, int) else "-"
        print(
            f"{name:24s}{rec_text:>10s}{used:>10,}{delta_text:>10s}"
            f"{globals_used:>10,}{ram_free:>11,}  {status}"
        )
        if found:
            problems += 1

    if problems:
        print(
            "\n%d of %d deck(s) disagree with %s on core %s"
            % (problems, checked, shown(RECORD), core),
            file=sys.stderr,
        )
        return 1
    print(
        "\n%d deck%s agree%s with %s on core %s"
        % (checked, "" if checked == 1 else "s",
           "s" if checked == 1 else "", shown(RECORD), core)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
