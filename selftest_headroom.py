#!/usr/bin/env python3
"""Exercise every branch of check_headroom.py with a canned compiler.

Neither deck has ever tripped this check - both fit - so the refusals only ever
ran when someone went looking: a 1.4 MB throwaway sketch proved the overflow path
once, by hand, and the scratch files went away with it. This writes a shell
script that prints the compiler's two lines and nothing else, which makes each
branch reachable in about a second, on a machine with no toolchain installed.

  python3 selftest_headroom.py     exit 0 when every branch still behaves

The deck folder named here only has to exist: the stand-in ignores its arguments,
so nothing compiles and nothing is flashed. The numbers are pinned rather than
recomputed, so a change that shifts a floor or a byte count fails loudly.
"""
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_headroom.py"
DECK = "phone_deck_cyd24R"

# The default partition on a 4 MB CYD, as the compiler prints it.
SLOT = 1310720
RAM_MAX = 327680
# The phone deck as measured on core 3.3.12, which leaves 145,546 B of flash.
FLASH_OK = 1165174
GLOBALS = 45060
RAM_FREE_OK = 282620


def report(used, percent, ram_free, extra="", code=0):
    """A stand-in arduino-cli that prints the two lines the check reads."""
    return (
        "#!/bin/sh\n"
        f"echo 'Sketch uses {used} bytes ({percent}%) of program storage space. "
        f"Maximum is {SLOT} bytes.'\n"
        f"echo 'Global variables use {GLOBALS} bytes (13%) of dynamic memory, "
        f"leaving {ram_free} bytes for local variables. Maximum is {RAM_MAX} bytes.'\n"
        f"{extra}"
        f"exit {code}\n"
    )


CASES = [
    dict(
        name="a deck that fits passes",
        stub=report(FLASH_OK, 88, RAM_FREE_OK),
        want_code=0,
        want=[f"ok (88% flash, {GLOBALS:,} B globals)", "1 deck fits"],
        forbids=["OVER", "tight", "failed"],
    ),
    dict(
        name="numbers past the slot overflow even when the compiler exits 0",
        stub=report(1400000, 106, RAM_FREE_OK),
        want_code=1,
        want=["flash OVER by 89,280 B - this deck needs huge_app",
              "1 of 1 deck(s) failed the headroom check"],
        forbids=["build failed"],
    ),
    dict(
        name="a failed link that printed its size says which way it overflowed",
        stub=report(
            1668748,
            127,
            RAM_FREE_OK,
            extra="echo 'Error during build: text section exceeds available space in board'\n",
            code=1,
        ),
        want_code=1,
        want=[
            "build failed; flash OVER by 358,028 B",
            "text section exceeds available space in board",
        ],
    ),
    dict(
        name="a floor from the environment refuses a deck that is only tight",
        stub=report(FLASH_OK, 88, RAM_FREE_OK),
        env={"HEADROOM_MIN_FREE_KB": "200"},
        want_code=1,
        want=["flash tight: 145,546 B is under the 204,800 B floor"],
        forbids=["OVER"],
    ),
    dict(
        name="DRAM under the floor is refused, because a framebuffer needs 153,600 B",
        stub=report(FLASH_OK, 88, 100000),
        want_code=1,
        want=["RAM tight: 100,000 B is under the 163,840 B floor"],
    ),
    dict(
        name="a build that never printed a size is a compile failure, not a fit",
        stub=(
            "#!/bin/sh\n"
            "echo \"fatal error: BLEDevice.h: No such file or directory\"\n"
            "exit 1\n"
        ),
        want_code=1,
        want=["compile failed", "No such file or directory"],
        forbids=["OVER"],
    ),
    dict(
        name="a compiler that cannot be run is an error, not a pass",
        missing_cli=True,
        want_code=2,
        want=["could not run"],
    ),
    dict(
        name="a machine with no arduino-cli is an error, not a pass",
        no_flag=True,
        want_code=2,
        want=["no arduino-cli found"],
    ),
    dict(
        name="a floor that is not a number is an error, not a default",
        stub=report(FLASH_OK, 88, RAM_FREE_OK),
        env={"HEADROOM_MIN_FREE_KB": "lots"},
        want_code=2,
        want=["is not a number"],
    ),
    dict(
        name="a folder that is not a deck is an error, not a silent pass",
        deck="no_such_deck_cyd24R",
        want_code=2,
        want=["no sketch folders found"],
    ),
]


def main():
    # Floors inherited from the caller would decide these cases for it.
    base_env = {k: v for k, v in os.environ.items() if not k.startswith("HEADROOM_")}
    failed = 0

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            cli = Path(tmp) / f"stub{index}.sh"
            if case.get("missing_cli"):
                cli = Path(tmp) / "no_such_arduino_cli"   # never created on purpose
            else:
                # Case 8 never reaches the compiler - the folder it names is not a
                # deck - but the path still has to be executable to get that far.
                cli.write_text(case.get("stub", "#!/bin/sh\nexit 0\n"))
                cli.chmod(cli.stat().st_mode | stat.S_IXUSR)

            argv = [sys.executable, str(CHECK)]
            env = dict(base_env, **case.get("env", {}))
            if case.get("no_flag"):
                env["PATH"] = ""      # nothing named arduino-cli can be found
            else:
                argv += ["--arduino-cli", str(cli)]
            argv.append(case.get("deck", DECK))

            done = subprocess.run(argv, capture_output=True, text=True,
                                  cwd=str(ROOT), env=env)
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [text for text in case["want"] if text not in output]
            if missing:
                problems.append("no " + ", ".join(repr(text) for text in missing))
            unexpected = [text for text in case.get("forbids", []) if text in output]
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
    print(f"all {len(CASES)} branches of check_headroom.py behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
