#!/usr/bin/env python3
"""Exercise check_old_core.py with a canned compiler and a throwaway record.

The file agrees with the tree today, so its refusals would only ever run when a
sketch really did move: a 16-byte drift, a globals count that shifted, an md5
that no longer matches - or, without a compiler at all, a deck the record names
and the tree has lost, a row named twice, an md5 nobody can check out. Each case
writes a stand-in compiler that prints the compiler's two lines, a record beside
it and a one-file git repo, so every branch runs in about a second with no
toolchain installed.

  python3 selftest_old_core.py     exit 0 when every branch still behaves
"""
import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_old_core.py"
DECK = "phone_deck_cyd24R"
SKETCH = "/* stand-in sketch: nothing compiles, the build is canned */\n"
USED, SLOT, GLOBALS, RAM_FREE = 1200000, 1310720, 40000, 280000


def digest():
    return hashlib.md5(SKETCH.encode()).hexdigest()


def stub(used=USED, globals_used=GLOBALS, ram_free=RAM_FREE, slot=SLOT, code=0):
    return (
        "#!/bin/sh\n"
        f"echo 'Sketch uses {used} bytes (92%) of program storage space. "
        f"Maximum is {slot} bytes.'\n"
        f"echo 'Global variables use {globals_used} bytes (12%) of dynamic memory, "
        f"leaving {ram_free} bytes for local variables. Maximum is 327680 bytes.'\n"
        f"exit {code}\n"
    )


def row(**over):
    entry = {"md5": digest(), "used": USED, "globals": GLOBALS, "slot": SLOT}
    entry.update(over)
    return entry


def sample(decks=None):
    return {
        "core": "2.0.17",
        "root": "~/.arduino-cyd-2",
        "measured": "2026-11-03",
        "decks": decks if decks is not None else {DECK: row()},
    }


def twice():
    """A record that names the same deck twice, which json reads as one row."""
    entry = json.dumps(row())
    return (
        '{"core": "2.0.17", "decks": {"%s": %s, "%s": %s}}\n'
        % (DECK, entry, DECK, entry)
    )


def tree(dest, decks=None, text=None, staged=True, repo=True):
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("check_old_core.py", "check_headroom.py"):
        (dest / name).write_text((ROOT / name).read_text(encoding="utf-8"),
                                 encoding="utf-8")
    deck = dest / DECK
    deck.mkdir()
    (deck / (DECK + ".ino")).write_text(SKETCH, encoding="utf-8")
    body = text if text is not None else json.dumps(sample(decks), indent=2) + "\n"
    (dest / "old_core.json").write_text(body, encoding="utf-8")

    # The index is part of the record's meaning, so every case has a repo; one
    # leaves the sketch untracked on purpose. A git hook exports GIT_INDEX_FILE
    # for its own commit, which would point these commands at the outer repo's
    # index - and once wrote a stand-in sketch into it - so git here is handed a
    # clean environment.
    genv = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    if not repo:
        return              # a tree git cannot read: no .git anywhere above it
    subprocess.run(["git", "init", "-q"], cwd=str(dest), capture_output=True,
                   env=genv)
    if staged:
        subprocess.run(["git", "add", "--", DECK + "/" + DECK + ".ino"],
                       cwd=str(dest), capture_output=True, env=genv)


CASES = [
    dict(name="a build that matches the record passes", want_code=0,
         want=["1,200,000", "agrees with old_core.json on core 2.0.17"],
         forbids=["->"]),
    dict(name="a flash figure that moved by 16 B is refused", want_code=1,
         decks={DECK: row(used=USED - 16)},
         want=["flash 1,199,984 -> 1,200,000 (+16)", "1 of 1 deck(s) disagree"]),
    dict(name="a globals count that moved is refused", want_code=1,
         decks={DECK: row(globals=GLOBALS - 1)},
         want=["globals 39,999 -> 40,000 (+1)"]),
    dict(name="a sketch whose md5 moved is refused", want_code=1,
         decks={DECK: row(md5="0" * 32)},
         want=["md5 00000000000000000000000000000000 -> "]),
    dict(name="a record naming a deck this tree lost is refused", want_code=1,
         decks={"gone_deck_cyd24R": row()},
         want=["names a deck this tree does not hold"]),
    dict(name="a compiler that cannot be run is an error, not a pass",
         missing_cli=True, want_code=2, want=["could not run"]),
    dict(name="no record at all is an error, not an empty comparison",
         record=True, remove_record=True, want_code=2, want=["is missing"]),
    dict(name="a record that is not an object is an error", record=True,
         text="[]\n", want_code=2, want=["does not hold an object"]),
    dict(name="a record with no decks is an error", record=True,
         text='{"core": "2.0.17"}\n', want_code=2, want=["records no deck"]),
    dict(name="a deck named here that the record does not hold",
         record=True, args=["nope_cyd24R"], want_code=2,
         want=["names no deck this tree holds"]),
    dict(name="a machine with no arduino-cli is an error, not a pass",
         no_flag=True, want_code=2, want=["no arduino-cli found"]),
    dict(name="a record that does not parse is an error, not a pass",
         text="{not json\n", want_code=2, want=["does not read as JSON"]),

    # --record: no compiler, no build, just the file and the index.
    dict(name="a record that is consistent passes without a compiler",
         record=True, want_code=0,
         want=["1 deck recorded consistently", "one row, a sketch in the index"],
         forbids=["->"]),
    dict(name="a record naming a deck this tree lost is refused without a compiler",
         record=True, want_code=1, decks={"gone_deck_cyd24R": row()},
         want=["names a deck this tree does not hold", "problem(s) in old_core.json"]),
    dict(name="a row that is not an object is refused", record=True, want_code=1,
         text='{"core": "2.0.17", "decks": {"%s": "a string"}}\n' % DECK,
         want=["is not an object"]),
    dict(name="a deck named twice is refused, because a read keeps one row",
         record=True, want_code=1, text=twice(),
         want=["is named twice"]),
    dict(name="a row with no md5 is refused", record=True, want_code=1,
         decks={DECK: {"used": USED, "globals": GLOBALS, "slot": SLOT}},
         want=["carries no 32-digit md5"], forbids=["records md5"]),
    dict(name="a row whose bytes exceed its slot is refused before any build",
         record=True, want_code=1, decks={DECK: row(used=SLOT + 1)},
         want=["puts 1,310,721 B in a 1,310,720 B slot, which no build does"]),
    dict(name="an md5 that is the right shape but not this sketch is refused",
         record=True, want_code=1, decks={DECK: row(md5="0" * 32)},
         want=["records md5 00000000000000000000000000000000 but the sketch is",
               "problem(s) in old_core.json"]),
    dict(name="an md5 for a sketch outside the index is refused", record=True,
         want_code=1, staged=False,
         want=["not in the index"]),
    dict(name="an inherited GIT_INDEX_FILE does not decide the answer", record=True,
         env={"GIT_INDEX_FILE": "/nonexistent/index"}, want_code=0,
         want=["recorded consistently"]),
    dict(name="a machine with no git cannot read the index, and says so",
         record=True, env={"PATH": ""}, want_code=1,
         want=["git could not be run here, so the index cannot be read"]),
    dict(name="a tree with no git repository says the index cannot be read",
         record=True, repo=False, want_code=1,
         want=["there is no git repository here, so the index cannot be read"]),
]


def main():
    base_env = {k: v for k, v in os.environ.items()
                if not k.startswith("HEADROOM_") and not k.startswith("GIT_")}
    failed = 0

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest, case.get("decks"), case.get("text"),
                 case.get("staged", True), case.get("repo", True))

            if case.get("record"):
                argv = ["--record"]
            else:
                cli = dest / "stub.sh"
                if case.get("missing_cli"):
                    cli = dest / "no_such_arduino_cli"   # never created on purpose
                else:
                    cli.write_text(stub())
                    cli.chmod(cli.stat().st_mode | stat.S_IXUSR)
                argv = [] if case.get("no_flag") else ["--arduino-cli", str(cli)]
            argv += case.get("args", [])
            env = dict(base_env, **case.get("env", {}))
            if case.get("no_flag"):
                env["PATH"] = ""      # nothing named arduino-cli can be found
            if case.get("remove_record"):
                (dest / "old_core.json").unlink()

            done = subprocess.run(
                [sys.executable, str(dest / "check_old_core.py")] + argv,
                capture_output=True, text=True, cwd=str(dest), env=env,
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [t for t in case["want"] if t not in output]
            if missing:
                problems.append("no " + ", ".join(repr(t) for t in missing))
            unexpected = [t for t in case.get("forbids", []) if t in output]
            if unexpected:
                problems.append("unasked-for " + ", ".join(map(repr, unexpected)))

            if problems:
                failed += 1
                print(f"FAIL {index}. {case['name']}: {'; '.join(problems)}")
                for line in output.strip().splitlines():
                    print(f"     | {line}")
            else:
                print(f"ok   {index}. {case['name']}")

    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check_old_core.py behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
