#!/usr/bin/env python3
"""Exercise check_fonts.py against the faults that broke the deck's type.

The shipped tables are correct, so the checker's refusals would only ever run
again if someone repacked a face — which is exactly what happened before the
first flash: rows padded to whole bytes, Adafruit_GFX reading one bitstream per
glyph, and every letter on the glass a block while the frames looked perfect.
Each case builds a throwaway tree with one fault put back and expects the gate to
name it.

  python3 selftest_fonts.py      exit 0 when every branch still behaves
"""

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_fonts.py"
SKETCHES = ("phone_deck_cyd24R/phone_deck_cyd24R.ino",
            "spotify_deck_cyd24R/spotify_deck_cyd24R.ino")
RENDERER = "artifacts/deck_screens.py"
BODY_BITS = r"(static const uint8_t DeckFontBodyBits\[\] PROGMEM = \{)(.*?)(\};)"
BODY_ROWS = r"(static const GFXglyph DeckFontBodyGlyphs\[\] PROGMEM = \{)(.*?)(\};)"
PADDED = ("            stride = (w + 7) // 8\n"
          "            for row in range(h):\n"
          "                base = row * stride\n"
          "                for col in range(w):\n"
          "                    if data[base + (col >> 3)] & (0x80 >> (col & 7)):\n")


def drop_a_byte(text):
    """One bitmap byte short: the chain cannot add up any more."""
    found = re.search(BODY_BITS, text, re.S)
    tokens = re.findall(r"0x[0-9A-F]{2}", found.group(2))
    tokens.pop(5)
    return text[:found.start(2)] + ", ".join(tokens) + text[found.end(2):]


def taller_glyph(text):
    """One glyph a row taller, which the next glyph's offset has to disagree with."""
    found = re.search(BODY_ROWS, text, re.S)
    rows = re.findall(r"\{\d+, \d+, \d+, \d+, -?\d+, -?\d+\}", found.group(2))
    numbers = [int(v) for v in re.findall(r"-?\d+", rows[1])]   # a real glyph, not the space
    numbers[2] += 1
    rows[1] = "{%d, %d, %d, %d, %d, %d}" % tuple(numbers)
    return text[:found.start(2)] + ", ".join(rows) + text[found.end(2):]


def fewer_glyphs(text):
    """One glyph row lost, so the face no longer covers 32..126."""
    found = re.search(BODY_ROWS, text, re.S)
    rows = re.findall(r"\{\d+, \d+, \d+, \d+, -?\d+, -?\d+\}", found.group(2))
    rows.pop()
    return text[:found.start(2)] + ", ".join(rows) + text[found.end(2):]


def face_dropped(text):
    """A sketch with no clock face at all."""
    return re.sub(r"static const uint8_t DeckFontClockBits\[\] PROGMEM = \{.*?\};\n\n"
                  r"static const GFXglyph DeckFontClockGlyphs\[\] PROGMEM = \{.*?\};\n\n"
                  r"const GFXfont DeckFontClock PROGMEM = \{.*?\};\n", "", text, flags=re.S)


def tree(dest, phone=None, spotify=None, renderer=None):
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_fonts.py")
    for index, path in enumerate(SKETCHES):
        text = (ROOT / path).read_text(encoding="utf-8")
        change = (phone, spotify)[index]
        if change:
            text = change(text)
        target = dest / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    target = dest / RENDERER
    target.parent.mkdir(parents=True, exist_ok=True)
    text = (ROOT / RENDERER).read_text(encoding="utf-8")
    target.write_text(renderer if renderer is not None else text, encoding="utf-8")


CASES = [
    dict(name="the shipped faces pass, both sketches and the renderer", want_code=0,
         want=["glyphs packed", "both sketches and the renderer agree"],
         forbids=["not packed the way"]),
    dict(name="a bitmap one byte short is refused", phone=drop_a_byte, want_code=1,
         want=["bitmap bytes", "glyphs need", "not packed the way drawChar() reads them"]),
    dict(name="a glyph a row taller is refused by the next offset", phone=taller_glyph,
         want_code=1, want=["starts at byte", "packing puts it at"]),
    dict(name="a face that lost a glyph row is refused", phone=fewer_glyphs,
         want_code=1, want=["glyph rows", "needs"]),
    dict(name="a sketch carrying only two faces is refused", phone=face_dropped,
         want_code=1, want=["carries", "of the", "faces"]),
    dict(name="two sketches with different faces are refused", spotify=taller_glyph,
         want_code=1, want=["different faces"]),
    dict(name="a renderer that unpacks rows again is refused", renderer=PADDED,
         want_code=1, want=["row-padded bytes"]),
    dict(name="a renderer with no continuous unpack is refused",
         renderer="# nothing unpacks anything here\n", want_code=1,
         want=["continuous unpack"]),
]


def main():
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest, case.get("phone"), case.get("spotify"), case.get("renderer"))
            done = subprocess.run([sys.executable, str(dest / "check_fonts.py")],
                                  capture_output=True, text=True, cwd=str(dest))
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
    print(f"all {len(CASES)} branches of check_fonts.py behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
