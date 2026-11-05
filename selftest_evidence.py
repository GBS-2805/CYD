#!/usr/bin/env python3
"""Prove ``check_evidence.py`` still remembers only what it was given.

The whole value of a cache is that it refuses to answer for bytes it has not
seen, and every refusal lives on a path the real gate never takes: the store on
this tree is always written by a real run, so a key that forgot a file, a hit
that answered for a changed sketch, or a record of an empty measurement would go
unnoticed until the day it reused the wrong build.

Each case runs the checker in a throwaway repo with one small sketch, records a
measurement, and changes exactly one thing - a byte, the stamp, the store, the
subcommand, or the deck's own folder going away. Nothing here compiles anything.

  python3 selftest_evidence.py     exit 0 when the cache still refuses what it must
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_evidence.py"

SKETCH = b"void setup() { }\nvoid loop() { }\n"
MEASURED = "1 deck fits, with 65,536 B of flash to spare"


def tree(base, sketch=SKETCH):
    """A repo with one widget and the checker beside it, so the store lands there."""
    root = Path(base) / "repo"
    (root / "demo_deck").mkdir(parents=True)
    (root / "demo_deck" / "demo_deck.ino").write_bytes(sketch)
    shutil.copyfile(CHECK, root / "check_evidence.py")
    return root


def run(root, *args):
    return subprocess.run(
        [sys.executable, str(root / "check_evidence.py"), *args],
        capture_output=True, text=True, cwd=str(root),
    )


def keyed(root, stamp="core=one"):
    return run(root, "key", "--stamp", stamp, "--watch", "demo_deck").stdout.strip()


def record(root, stamp="core=one"):
    return run(root, "record", "--name", "headroom", "--of", keyed(root, stamp),
               "--watch", "demo_deck", "--summary", MEASURED)


def case_nothing_kept(base):
    root = tree(base)
    result = run(root, "hit", "--name", "headroom", "--of", keyed(root))
    return result, []


def case_round_trip(base):
    root = tree(base)
    record(root)
    return run(root, "hit", "--name", "headroom", "--of", keyed(root)), []


def case_byte_changed(base):
    root = tree(base)
    record(root)
    (root / "demo_deck" / "demo_deck.ino").write_bytes(SKETCH + b"// one line more\n")
    return run(root, "hit", "--name", "headroom", "--of", keyed(root)), []


def case_stamp_changed(base):
    root = tree(base)
    record(root)
    first, second = keyed(root, "core=one"), keyed(root, "core=two")
    note = ["the two roots key differently"] if first != second else []
    return run(root, "hit", "--name", "headroom", "--of", second), note


def case_no_watch(base):
    root = tree(base)
    return run(root, "key", "--stamp", "core=one"), []


def case_missing_watch(base):
    root = tree(base)
    return run(root, "key", "--watch", "nowhere_deck"), []


def case_empty_summary(base):
    root = tree(base)
    return run(root, "record", "--name", "headroom", "--of", keyed(root),
               "--summary", "   "), []


def case_corrupt_store(base):
    root = tree(base)
    record(root)
    (root / ".gate_evidence.json").write_text("{ not json at all\n", encoding="utf-8")
    return run(root, "hit", "--name", "headroom", "--of", keyed(root)), []


def case_unknown_command(base):
    root = tree(base)
    return run(root, "frobnicate"), []


def case_prune_deck_gone(base):
    root = tree(base)
    key = keyed(root)
    record(root)
    shutil.rmtree(root / "demo_deck")
    pruned = run(root, "prune")
    return run(root, "hit", "--name", "headroom", "--of", key), [pruned.stdout]


def case_prune_keeps_what_is_here(base):
    root = tree(base)
    record(root)
    pruned = run(root, "prune")
    return run(root, "hit", "--name", "headroom", "--of", keyed(root)), [pruned.stdout]


def case_prune_unreadable(base):
    root = tree(base)
    record(root)
    (root / ".gate_evidence.json").write_text("{ no json here\n", encoding="utf-8")
    return run(root, "prune"), []


def case_list_reports_stale(base):
    root = tree(base)
    record(root)
    shutil.rmtree(root / "demo_deck")
    return run(root, "list"), []


CASES = [
    dict(
        name="a fresh copy has nothing to reuse",
        build=case_nothing_kept,
        code=3,
        want=[],
        forbids=[MEASURED],
    ),
    dict(
        name="a recorded measurement comes back with the bytes it had",
        build=case_round_trip,
        code=0,
        want=[MEASURED],
        forbids=[],
    ),
    dict(
        name="a sketch byte changed since refuses to reuse",
        build=case_byte_changed,
        code=3,
        want=[],
        forbids=[MEASURED],
    ),
    dict(
        name="the same tree under another compiler root is another key",
        build=case_stamp_changed,
        code=3,
        want=["the two roots key differently"],
        forbids=[MEASURED],
    ),
    dict(
        name="a key with nothing watched refuses",
        build=case_no_watch,
        code=2,
        want=["nothing to key on"],
        forbids=[],
    ),
    dict(
        name="a watch the tree does not hold refuses",
        build=case_missing_watch,
        code=2,
        want=["which this tree does not hold"],
        forbids=[],
    ),
    dict(
        name="recording a step that said nothing refuses",
        build=case_empty_summary,
        code=2,
        want=["refuses to record"],
        forbids=[],
    ),
    dict(
        name="a store that cannot be read is a miss, not a pass",
        build=case_corrupt_store,
        code=3,
        want=[],
        forbids=[MEASURED],
    ),
    dict(
        name="a subcommand it does not know refuses",
        build=case_unknown_command,
        code=2,
        want=["is not a subcommand"],
        forbids=[],
    ),
    dict(
        name="a deck that left the tree loses its entry",
        build=case_prune_deck_gone,
        code=3,
        want=["demo_deck"],
        forbids=[MEASURED],
    ),
    dict(
        name="pruning a store that still describes this tree drops nothing",
        build=case_prune_keeps_what_is_here,
        code=0,
        want=[MEASURED],
        forbids=["pruned"],
    ),
    dict(
        name="a store it cannot read is refused, not overwritten",
        build=case_prune_unreadable,
        code=2,
        want=["refuses to prune"],
        forbids=[],
    ),
    dict(
        name="the store reports a stale entry before anything drops it",
        build=case_list_reports_stale,
        code=0,
        want=["stale"],
        forbids=[],
    ),
]


def main():
    failures = 0
    for case in CASES:
        with tempfile.TemporaryDirectory() as tmp:
            result, note = case["build"](tmp)
            said = result.stdout + result.stderr + "\n" + "\n".join(note)
            trouble = []
            if result.returncode != case["code"]:
                trouble.append("exit %d, wanted %d" % (result.returncode, case["code"]))
            for phrase in case["want"]:
                if phrase not in said:
                    trouble.append("did not say %r" % phrase)
            for phrase in case["forbids"]:
                if phrase in said:
                    trouble.append("said %r, which it should not" % phrase)
            if trouble:
                failures += 1
                print("FAIL  %s: %s" % (case["name"], "; ".join(trouble)))
                for line in said.strip().splitlines():
                    print("      %s" % line)
            else:
                print("ok    %s" % case["name"])
    if failures:
        print("%d of %d cases failed" % (failures, len(CASES)))
        return 1
    print("%d branches of check_evidence refuse what they exist for" % len(CASES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
