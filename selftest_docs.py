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
whole list finishes in about three seconds on a machine with no toolchain.

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


def stub(used=USED):
    """A stand-in compiler: the two lines check_docs reads, and nothing else.

    `used` exists so a case can be a deck whose build *is* one of the figures the
    checker has been told no sketch makes; everything else about the stand-in
    stays fixed, because nothing else is what such a case is about.
    """
    return (
        "#!/bin/sh\n"
        'case "$1" in\n'
        "  version) echo 'arduino-cli  Version: 1.1.1 Commit: stand-in'; exit 0;;\n"
        "  core) echo '[{\"id\":\"esp32:esp32\",\"installed\":\"3.3.12\"}]'; exit 0;;\n"
        "esac\n"
        f"echo 'Sketch uses {used} bytes (89%) of program storage space. "
        f"Maximum is {SLOT} bytes.'\n"
        f"echo 'Global variables use {GLOBALS} bytes (13%) of dynamic memory, "
        "leaving 282436 bytes for local variables. Maximum is 327680 bytes.'\n"
        "exit 0\n"
    )


def digest():
    return hashlib.md5(SKETCH.encode()).hexdigest()[:8]


def tree(dest, used=USED):
    """The smallest repo that passes: two checkers, one deck, its two docs."""
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_docs.py")
    shutil.copy2(HEADROOM, dest / "check_headroom.py")
    deck = dest / DECK
    deck.mkdir()
    (deck / (DECK + ".ino")).write_text(SKETCH)
    (dest / "README.md").write_text(
        "# Fixture\n\n"
        f"The deck builds to {used:,} B with {GLOBALS:,} B of globals, "
        f"md5 {digest()}\u2026.\n\n"
        f"See [the deck]({DECK}/README.md).\n"
    )
    (deck / "README.md").write_text(
        f"# {DECK}\n\n"
        f"Last build: {used:,} B, {GLOBALS:,} B of globals, md5 {digest()}\u2026.\n"
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


def invented_no_sketch(dest):
    """A NO_SKETCH entry that HISTORICAL does not keep: the two tables must agree.

    HISTORICAL is what lets a past figure stay in the prose; NO_SKETCH says no
    committed sketch makes it. They only work as a pair - so an entry added to one
    and not the other has to be refused where it is written, rather than found
    later by the doc that quotes it.
    """
    path = dest / "check_docs.py"
    text = path.read_text(encoding="utf-8")
    anchor = "NO_SKETCH = {\n"
    if text.count(anchor) != 1:
        raise SystemExit("the fixture could not find %r exactly once" % anchor)
    path.write_text(
        text.replace(
            anchor,
            anchor + '    1234567: "a figure this case invents, which no table keeps",\n',
        ),
        encoding="utf-8",
    )


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
        name="a slot percentage the bytes beside it do not make is refused",
        mutate=append_readme(
            "\n| flash | 1,168,314 | 142,406 | 1,310,720 (74 % used) |\n"
        ),
        want_code=1,
        want=["quotes 74 %, which none of the figures beside it makes of 1,310,720"],
    ),
    dict(
        name="a slot percentage that matches those bytes passes",
        mutate=append_readme(
            "\n| flash | 1,168,314 | 142,406 | 1,310,720 (89 % used) |\n"
        ),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="a percentage beside a build size with no ceiling is refused",
        mutate=append_readme("\nThe image is 1,168,314 B, which is 89 % of that slot.\n"),
        want_code=1,
        want=["quotes 89 % beside a build size and names no ceiling"],
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
        name="a hex run inside a URL is a hostname, not an md5",
        mutate=append_readme("\nRenders: https://site-c4870019b7c74299bae95043c45330f8"
                             ".freebuff.page/home.png\n"),
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
        name="a sentence calling a figure with no sketch rebuildable is refused",
        mutate=append_readme(
            "\nThe pair that rebuilds is 967,493 B against 1,162,877 B for the "
            "2026-10-30 phone file.\n"
        ),
        want_code=1,
        want=[
            "calls 1,162,877 B rebuildable with no denial within 12 words of it",
            "the 2026-10-30 phone sketch, which no commit here holds",
        ],
    ),
    dict(
        name="a denial thirty words from the figure does not excuse it",
        mutate=append_readme(
            "\n1,162,877 B is the 2026-10-30 phone file, and the deck draws a "
            "header, a panel, a track list, a progress bar, a battery line and "
            "a clock; that file cannot be rebuilt because nobody kept it.\n"
        ),
        want_code=1,
        want=["calls 1,162,877 B rebuildable with no denial within 12 words of it"],
    ),
    dict(
        name="that claim wrapped across two lines is still refused whole",
        mutate=append_readme(
            "\nBoth halves rebuild: 967,493 B on this path and\n"
            "1,162,877 B for the 2026-10-30 phone file, 195,384 B apart.\n"
        ),
        want_code=1,
        want=["calls 1,162,877 B rebuildable"],
    ),
    dict(
        name="a sentence that says such a figure cannot be rebuilt passes",
        mutate=append_readme(
            "\n1,162,877 B is the 2026-10-30 phone file, which no commit here "
            "holds, so it cannot be rebuilt.\n"
        ),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="a rebuild word beside a figure this tree makes is left alone",
        mutate=append_readme("\nA clean rebuild of the deck prints 1,168,314 B again.\n"),
        want_code=0,
        forbids=["  - "],
    ),
    dict(
        name="a NO_SKETCH figure a deck builds to is refused",
        mutate=None,
        used=1162877,
        want_code=1,
        want=[
            "NO_SKETCH lists 1,162,877, but a deck builds to it today, so it is "
            "rebuildable and the entry is wrong"
        ],
    ),
    dict(
        name="a NO_SKETCH entry HISTORICAL does not keep is refused",
        mutate=invented_no_sketch,
        want_code=1,
        want=[
            "NO_SKETCH lists 1,234,567, which is not in HISTORICAL, so the size "
            "check refuses it before this rule can"
        ],
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
            used = case.get("used", USED)
            tree(dest, used)
            if case["mutate"]:
                case["mutate"](dest)

            cli = dest / "stub-cli.sh"
            if case.get("missing_cli"):
                cli = dest / "no_such_arduino_cli"   # never created on purpose
            else:
                cli.write_text(stub(used))
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
