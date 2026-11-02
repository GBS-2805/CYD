#!/usr/bin/env python3
"""Exercise every refusal of check_docs.py without a compiler, a deck or a board.

check_docs.py only ever passes on this tree: both decks build, every doc quotes
today's figures, every link resolves. So its refusals were proved only when
somebody happened to break one — and the last three were found by a reader, not
by the check: a file named in prose that had been renamed, a source count that
had moved, and a reading of another tool ("the branch on it today") that was true
for one afternoon. A gate whose failure path nobody has run is a paragraph with a
syntax error in it.

Each case below builds a throwaway repo in a temp directory: the two checkers,
one deck, the docs that quote it, and a stand-in `arduino-cli` that prints a fixed
`Sketch uses` line and a fake core list. The case then changes one fact — a size,
an md5, a link, a name, a count — and asks what check_docs.py says about it.
Nothing compiles, nothing outside the temp directory is read or written, and the
whole list finishes in about two seconds on a machine with no toolchain.

  python3 selftest_docs.py        exit 0 when every branch still behaves

The numbers here are pinned rather than recomputed, so a change that moves a
message or a floor fails loudly instead of quietly agreeing with itself.
"""
import hashlib
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_docs.py"
HEADROOM = ROOT / "check_headroom.py"
DECK = "phone_deck_cyd24R"
SKETCH = "/* stand-in sketch: the build is canned, so this is never compiled */\n"
USED, SLOT, GLOBALS = 1168314, 1310720, 45244
APK = "android-companion/dist/PhoneDeckBridge-debug.apk"
HANDOFF = "android-companion/HANDOFF.md"


def stub():
    """A stand-in compiler: the two lines check_docs reads, and nothing else."""
    return (
        "#!/bin/sh\n"
        'case "$1" in\n'
        "  version) echo 'arduino-cli  Version: 1.1.1 Commit: stand-in'; exit 0;;\n"
        "  core) echo '[{\"id\":\"esp32:esp32\",\"installed\":\"3.3.12\"}]'; exit 0;;\n"
        "esac\n"
        f"echo 'Sketch uses {USED} bytes (89%) of program storage space. "
        f"Maximum is {SLOT} bytes.'\n"
        f"echo 'Global variables use {GLOBALS} bytes (13%) of dynamic memory, "
        "leaving 282436 bytes for local variables. Maximum is 327680 bytes.'\n"
        "exit 0\n"
    )


def digest():
    return hashlib.md5(SKETCH.encode()).hexdigest()[:8]


def tree(dest):
    """The smallest repo that passes: two checkers, one deck, its two docs."""
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_docs.py")
    shutil.copy2(HEADROOM, dest / "check_headroom.py")
    deck = dest / DECK
    deck.mkdir()
    (deck / (DECK + ".ino")).write_text(SKETCH)
    (dest / "README.md").write_text(
        "# Fixture\n\n"
        f"The deck builds to {USED:,} B with {GLOBALS:,} B of globals, "
        f"md5 {digest()}\u2026.\n\n"
        f"See [the deck]({DECK}/README.md).\n"
    )
    (deck / "README.md").write_text(
        f"# {DECK}\n\n"
        f"Last build: {USED:,} B, {GLOBALS:,} B of globals, md5 {digest()}\u2026.\n"
    )


def append_readme(text):
    def mutate(dest):
        path = dest / "README.md"
        path.write_text(path.read_text(encoding="utf-8") + text)
    return mutate


def deck_readme(text):
    def mutate(dest):
        (dest / DECK / "README.md").write_text(
            text.format(size=f"{USED:,}", globals=f"{GLOBALS:,}", digest=digest())
        )
    return mutate


def apk_case(size, claim, extra=None):
    def mutate(dest):
        apk = dest / APK
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"\0" * size)
        (dest / HANDOFF).write_text(f"The APK is {claim} bytes.\n")
        if extra:
            extra(dest)
    return mutate


def no_sketch(dest):
    shutil.rmtree(dest / DECK)


CASES = [
    dict(
        name="a tree whose docs match the build passes",
        mutate=None,
        want_code=0,
        want=["every quoted number is today's"],
        forbids=["  - "],
    ),
    dict(
        name="a flash size no deck builds to is refused",
        mutate=append_readme("\nAn older build was 1,999,999 B.\n"),
        want_code=1,
        want=["quotes 1,999,999, which is not a size any deck builds to now"],
    ),
    dict(
        name="a past build named in HISTORICAL stays allowed",
        mutate=append_readme("\nIt used to build to 1,878,661 B.\n"),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="a link to a renamed file is refused",
        mutate=append_readme("\nRead [the old page](gone.md).\n"),
        want_code=1,
        want=["links to gone.md, which is not there"],
    ),
    dict(
        name="an ellipsis link is a shape, not a path",
        mutate=append_readme("\n![home](\u2026) and [x](\u2026) show the shape.\n"),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="a backticked file this tree does not hold is refused",
        mutate=append_readme("\n`Missing.java` is not here any more.\n"),
        want_code=1,
        want=["names Missing.java, which this tree does not hold"],
    ),
    dict(
        name="a file listed in ELSEWHERE is allowed by name",
        mutate=append_readme("\n`esptool.py` does the upload, mirrored here.\n"),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="a source count that moved is refused",
        mutate=append_readme("\nThe app has 42 sources.\n"),
        want_code=1,
        want=["says 42 sources; there are 0"],
    ),
    dict(
        name="a source count that is right passes",
        mutate=append_readme("\nThe app has 0 sources.\n"),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="an undated reading of another tool is refused",
        mutate=append_readme("\nThe branch on it today is freebuff/abc.\n"),
        want_code=1,
        want=["a reading ages, so date it"],
    ),
    dict(
        name="a short md5 that is not a sketch here is refused",
        mutate=append_readme("\nThat one was deadbeef\u2026.\n"),
        want_code=1,
        want=["quotes md5 deadbeef\u2026, which is not a sketch here now"],
    ),
    dict(
        name="a full md5 that is not a sketch here is refused",
        mutate=append_readme("\nFull hash 0123456789abcdef0123456789abcdef.\n"),
        want_code=1,
        want=["quotes md5 0123456789abcdef0123456789abcdef"],
    ),
    dict(
        name="a widget README without the current size is refused",
        mutate=deck_readme("{globals} B of globals, md5 {digest}\u2026.\n"),
        want_code=1,
        want=["does not quote the current 1,168,314 B build"],
    ),
    dict(
        name="a widget README without the current globals is refused",
        mutate=deck_readme("{size} B, md5 {digest}\u2026.\n"),
        want_code=1,
        want=["does not quote the current 45,244 B of globals"],
    ),
    dict(
        name="a widget README without the current md5 is refused",
        mutate=deck_readme("{size} B, {globals} B of globals.\n"),
        want_code=1,
        want=["does not quote the current md5"],
    ),
    dict(
        name="an APK whose bytes HANDOFF does not record is refused",
        mutate=apk_case(123, "124"),
        want_code=1,
        want=["does not record 123 bytes, which is the APK in dist/"],
    ),
    dict(
        name="an APK whose bytes HANDOFF records passes",
        mutate=apk_case(123, "123"),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="a compiler that cannot be run is an error, not a pass",
        mutate=None,
        missing_cli=True,
        want_code=1,
        want=["could not run"],
    ),
    dict(
        name="a tree with no deck is an error, not a silent pass",
        mutate=no_sketch,
        want_code=2,
        want=["no sketch folders found"],
    ),
]


def main():
    # Floors inherited from the caller would decide cases for it.
    base_env = {k: v for k, v in os.environ.items() if not k.startswith("HEADROOM_")}
    failed = 0
    started = time.time()

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest)
            if case["mutate"]:
                case["mutate"](dest)

            cli = dest / "stub-cli.sh"
            if case.get("missing_cli"):
                cli = dest / "no_such_arduino_cli"   # never created on purpose
            else:
                cli.write_text(stub())
                cli.chmod(cli.stat().st_mode | stat.S_IXUSR)

            done = subprocess.run(
                [sys.executable, str(dest / "check_docs.py"), "--arduino-cli", str(cli)],
                capture_output=True,
                text=True,
                cwd=str(dest),
                env=base_env,
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [text for text in case.get("want", []) if text not in output]
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

    elapsed = time.time() - started
    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check_docs.py behave ({elapsed:.1f} s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
