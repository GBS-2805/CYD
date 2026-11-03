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

A disagreement is not a broken tree: it is a measurement that moved, and the fix
is either the sketch or the record. The row says which figures moved and by how
much, so re-measuring is one edit.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_headroom as headroom  # noqa: E402  (same folder, same build rules)

ROOT = Path(__file__).resolve().parent
RECORD = ROOT / "old_core.json"


def shown(path):
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def load():
    """The record, or None after saying why there is nothing to compare against."""
    try:
        data = json.loads(RECORD.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print("error: %s does not read as JSON: %s" % (shown(RECORD), exc),
              file=sys.stderr)
        return None
    if not isinstance(data, dict) or not isinstance(data.get("decks"), dict) \
            or not data["decks"]:
        print("error: %s records no deck" % shown(RECORD), file=sys.stderr)
        return None
    return data


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


def main():
    parser = argparse.ArgumentParser(
        description="Compile each deck and compare it with old_core.json."
    )
    parser.add_argument(
        "decks", nargs="*", metavar="FOLDER",
        help="deck folders to compare (default: every deck the record names)",
    )
    parser.add_argument("--arduino-cli", default=None,
                        help="arduino-cli to use (default: the one on PATH)")
    parser.add_argument("--timeout", type=int, default=600, metavar="SECONDS",
                        help="per-compile timeout (default: 600)")
    args = parser.parse_args()

    cli = args.arduino_cli or shutil.which("arduino-cli")
    if not cli:
        print(
            "error: no arduino-cli found; source the old core's toolchain first "
            "(source ~/.arduino-cyd-2/env.sh) or pass --arduino-cli",
            file=sys.stderr,
        )
        return 2

    data = load()
    if data is None:
        return 2
    core = data.get("core") or "an unnamed core"
    wanted = set(args.decks)
    names = [name for name in sorted(data["decks"]) if not wanted or name in wanted]
    if not names:
        print("error: %s names no deck this tree holds" % shown(RECORD),
              file=sys.stderr)
        return 2

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
    checked = 0
    for name in names:
        entry = data["decks"][name]
        folder = ROOT / name
        sketch = folder / (name + ".ino")
        if not sketch.is_file():
            print(f"{name:24s}  record names a deck this tree does not hold")
            problems += 1
            continue
        if not isinstance(entry, dict):
            print(f"{name:24s}  record is not an object")
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
        digest = hashlib.md5(sketch.read_bytes()).hexdigest()
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
