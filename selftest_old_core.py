#!/usr/bin/env python3
"""Exercise check_old_core.py with a canned compiler and a throwaway record.

The file agrees with the tree today, so its refusals would only ever run when a
sketch really did move: a 16-byte drift, a globals count that shifted, an md5
that no longer matches. Each case writes a stand-in compiler that prints the
compiler's two lines and a record beside it, so every branch runs in about a
second with no toolchain installed.

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


def record(decks=None):
    entry = {"md5": digest(), "used": USED, "globals": GLOBALS, "slot": SLOT}
    return {
        "core": "2.0.17",
        "root": "~/.arduino-cyd-2",
        "measured": "2026-11-03",
        "decks": decks if decks is not None else {DECK: entry},
    }


def tree(dest, decks=None, text=None):
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("check_old_core.py", "check_headroom.py"):
        (dest / name).write_text((ROOT / name).read_text(encoding="utf-8"),
                                 encoding="utf-8")
    deck = dest / DECK
    deck.mkdir()
    (deck / (DECK + ".ino")).write_text(SKETCH, encoding="utf-8")
    body = text if text is not None else json.dumps(record(decks), indent=2) + "\n"
    (dest / "old_core.json").write_text(body, encoding="utf-8")


CASES = [
    dict(name="a build that matches the record passes", want_code=0,
         want=["1,200,000", "agrees with old_core.json on core 2.0.17"],
         forbids=["->"]),
    dict(name="a flash figure that moved by 16 B is refused", want_code=1,
         decks={DECK: {"md5": digest(), "used": USED - 16, "globals": GLOBALS,
                       "slot": SLOT}},
         want=["flash 1,199,984 -> 1,200,000 (+16)", "1 of 1 deck(s) disagree"]),
    dict(name="a globals count that moved is refused", want_code=1,
         decks={DECK: {"md5": digest(), "used": USED, "globals": GLOBALS - 1,
                       "slot": SLOT}},
         want=["globals 39,999 -> 40,000 (+1)"]),
    dict(name="a sketch whose md5 moved is refused", want_code=1,
         decks={DECK: {"md5": "0" * 32, "used": USED, "globals": GLOBALS,
                       "slot": SLOT}},
         want=["md5 00000000000000000000000000000000 -> "]),
    dict(name="a record naming a deck this tree lost is refused", want_code=1,
         decks={"gone_deck_cyd24R": {"md5": digest(), "used": USED,
                                     "globals": GLOBALS, "slot": SLOT}},
         want=["record names a deck this tree does not hold"]),
    dict(name="a compiler that cannot be run is an error, not a pass",
         missing_cli=True, want_code=2, want=["could not run"]),
    dict(name="a record that does not parse is an error, not a pass",
         text="{not json\n", want_code=2, want=["does not read as JSON"]),
]


def main():
    base_env = {k: v for k, v in os.environ.items() if not k.startswith("HEADROOM_")}
    failed = 0

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest, case.get("decks"), case.get("text"))

            cli = dest / "stub.sh"
            if case.get("missing_cli"):
                cli = dest / "no_such_arduino_cli"      # never created on purpose
            else:
                cli.write_text(stub())
                cli.chmod(cli.stat().st_mode | stat.S_IXUSR)

            done = subprocess.run(
                [sys.executable, str(dest / "check_old_core.py"),
                 "--arduino-cli", str(cli)],
                capture_output=True, text=True, cwd=str(dest), env=base_env,
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
