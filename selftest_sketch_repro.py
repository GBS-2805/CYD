#!/usr/bin/env python3
"""Exercise check_sketch_repro.py with a stand-in compiler.

Both decks reproduce today, so this checker's refusals would only run the day one
stopped: an image that moves between two compiles, an image the second build did
not write at all, a compile that fails, one that hangs, and a machine with no
`arduino-cli` to ask. Each case hands it a shell script that answers the compile
command the checker sends — `compile --fqbn … --output-dir DIR folder`.

  python3 selftest_sketch_repro.py      exit 0 when every branch still behaves
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_sketch_repro.py"
DECK = "phone_deck_cyd24R"

# Writes one image per compile: the same bytes every time.
STEADY = (
    "#!/bin/sh\n"
    "out=''\n"
    "while [ $# -gt 0 ]; do\n"
    "  case \"$1\" in --output-dir) out=\"$2\"; shift 2;; *) shift;; esac\n"
    "done\n"
    "mkdir -p \"$out\"\n"
    "printf 'same image\\n' > \"$out/deck.ino.bin\"\n"
    "printf 'same bootloader\\n' > \"$out/deck.ino.bootloader.bin\"\n"
)

# Counts its runs, so the second compile can differ by one byte.
DRIFTS = (
    "#!/bin/sh\n"
    "out=''\n"
    "while [ $# -gt 0 ]; do\n"
    "  case \"$1\" in --output-dir) out=\"$2\"; shift 2;; *) shift;; esac\n"
    "done\n"
    "n=$(cat count 2>/dev/null || echo 0)\n"
    "n=$((n + 1))\n"
    "echo \"$n\" > count\n"
    "mkdir -p \"$out\"\n"
    "printf 'image %s\\n' \"$n\" > \"$out/deck.ino.bin\"\n"
)

FAILS = "#!/bin/sh\necho 'error: something in the sketch' >&2\nexit 2\n"
HANGS = "#!/bin/sh\nsleep 10\n"

# The second compile writes the application but not the bootloader: the images
# that do exist must be compared, and the one that went missing named.
HALF = (
    "#!/bin/sh\n"
    "out=''\n"
    "while [ $# -gt 0 ]; do\n"
    "  case \"$1\" in --output-dir) out=\"$2\"; shift 2;; *) shift;; esac\n"
    "done\n"
    "n=$(cat count 2>/dev/null || echo 0)\n"
    "n=$((n + 1))\n"
    "echo \"$n\" > count\n"
    "mkdir -p \"$out\"\n"
    "printf 'image\\n' > \"$out/deck.ino.bin\"\n"
    "[ \"$n\" -eq 1 ] && printf 'bootloader\\n' > \"$out/deck.ino.bootloader.bin\"\n"
    "exit 0\n"
)


def tree(dest, script):
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("check_sketch_repro.py", "check_headroom.py"):
        (dest / name).write_text((ROOT / name).read_text(encoding="utf-8"),
                                 encoding="utf-8")
    deck = dest / DECK
    deck.mkdir()
    (deck / (DECK + ".ino")).write_text("/* stand-in sketch */\n", encoding="utf-8")
    cli = dest / "stub-arduino-cli.sh"
    cli.write_text(script, encoding="utf-8")
    cli.chmod(0o755)      # a compiler the checker cannot exec is not a compiler
    return cli


CASES = [
    dict(name="two compiles of one deck with the same bytes pass", script=STEADY,
         want_code=0, want=["built twice with the same bytes"],
         forbids=["does not reproduce"]),
    dict(name="an image that moves between two compiles is refused", script=DRIFTS,
         want_code=1, want=["differs between two builds", "does not reproduce",
                            "image(s) differ between two builds"]),
    dict(name="an image the second build never wrote is refused", script=HALF,
         want_code=1, want=["the second build wrote no deck.ino.bootloader.bin"]),
    dict(name="a compile that fails is an error, not a pass", script=FAILS,
         want_code=2, want=["did not compile, so there is nothing to compare"]),
    dict(name="a compile that hangs is an error, not a pass", script=HANGS,
         timeout="1", want_code=2, want=["did not finish in 1s"]),
    dict(name="a machine with no arduino-cli is an error, not a pass",
         script=STEADY, no_cli=True, want_code=2,
         want=["no arduino-cli found"]),
]


def main():
    base_env = {key: value for key, value in os.environ.items()
                if not key.startswith("ARDUINO_")}
    failed = 0

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            cli = tree(dest, case["script"])
            argv = [sys.executable, str(dest / "check_sketch_repro.py")]
            env = dict(base_env)
            if case.get("no_cli"):
                env["PATH"] = ""          # nothing named arduino-cli can be found
            else:
                argv += ["--arduino-cli", str(cli)]
            if case.get("timeout"):
                argv += ["--timeout", case["timeout"]]

            done = subprocess.run(argv, capture_output=True, text=True,
                                  cwd=str(dest), env=env)
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
    print(f"all {len(CASES)} branches of check_sketch_repro.py behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
