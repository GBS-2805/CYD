#!/usr/bin/env python3
"""A deck typeface is one bitstream per glyph, and the packing is checkable.

Both sketches carry three faces cut by artifacts/deck_font.py, and the frames in
artifacts/ blit the same bytes. Adafruit_GFX's drawChar() pulls a fresh byte
every eighth pixel and never pads to a row boundary, so a glyph is exactly
ceil(width * height / 8) bytes and the next glyph starts immediately after it.

Packing each row to a whole byte instead — what this repo did until the first
flash — leaves dead bits at the end of every row of a five- or six-pixel glyph,
so every row after the first shifts right, and on the wide faces a glyph's rows
run on into the next character. On glass the letters came out as blocks, and the
frames could not see it because the renderer unpacked with the same padding.
That is why this runs in the gate instead of living only in a comment.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SKETCHES = ("phone_deck_cyd24R/phone_deck_cyd24R.ino",
            "spotify_deck_cyd24R/spotify_deck_cyd24R.ino")
RENDERER = "artifacts/deck_screens.py"
FACES = ("Body", "Display", "Clock")
FIRST, LAST = 32, 126
COUNT = LAST - FIRST + 1
ROW = re.compile(r"\{(\d+), (\d+), (\d+), (\d+), (-?\d+), (-?\d+)\}")


def faces(text):
    """{face: (bitmap bytes, glyph rows)} for the faces a sketch carries."""
    out = {}
    for name in FACES:
        bits = re.search(r"static const uint8_t DeckFont%sBits\[\] PROGMEM = \{(.*?)\};"
                         % name, text, re.S)
        glyphs = re.search(r"static const GFXglyph DeckFont%sGlyphs\[\] PROGMEM = \{(.*?)\};"
                           % name, text, re.S)
        if not (bits and glyphs):
            continue
        out[name] = ([int(v, 16) for v in re.findall(r"0x([0-9A-Fa-f]{2})", bits.group(1))],
                     [tuple(int(v) for v in row) for row in ROW.findall(glyphs.group(1))])
    return out


def packed_problems(text, label="sketch"):
    """Every packing fault in one sketch, and the faces it did carry."""
    problems = []
    table = faces(text)
    if len(table) != len(FACES):
        problems.append("%s: carries %d of the %d faces"
                        % (label, len(table), len(FACES)))
    for name, (bits, rows) in table.items():
        if len(rows) != COUNT:
            problems.append("%s %s: %d glyph rows; the range %d..%d needs %d"
                            % (label, name, len(rows), FIRST, LAST, COUNT))
        want = 0
        for index, (offset, w, h, _advance, _xo, _yo) in enumerate(rows):
            if offset != want:
                problems.append("%s %s: glyph %r starts at byte %d, packing puts it at %d"
                                % (label, name, chr(FIRST + index), offset, want))
            want += (w * h + 7) // 8
        if len(bits) != want:
            problems.append("%s %s: %d bitmap bytes, but %d glyphs need %d"
                            % (label, name, len(bits), len(rows), want))
    return problems, table


def renderer_problems(text):
    """The frames have to unpack the way the glass does, or neither proves anything."""
    problems = []
    if "(w + 7) // 8" in text or "row * stride" in text:
        problems.append("%s: unpacks row-padded bytes, which is not what drawChar() reads"
                        % RENDERER)
    if "bit >> 3" not in text:
        problems.append("%s: no continuous unpack, so a frame is not the glass" % RENDERER)
    return problems


def main(argv):
    paths = argv[1:] or list(SKETCHES)
    problems = []
    reference, reference_path = None, None
    for path in paths:
        text = (ROOT / path).read_text()
        found, table = packed_problems(text, path)
        problems += found
        if reference is None:
            reference, reference_path = table, path
        elif table != reference:
            problems.append("%s carries different faces from %s" % (path, reference_path))
    renderer = ROOT / RENDERER
    if renderer.exists():
        problems += renderer_problems(renderer.read_text())
    if problems:
        for problem in problems:
            print("  - " + problem)
        print("the deck fonts are not packed the way drawChar() reads them")
        return 1
    total = 0
    for _name, (_bits, rows) in reference.items():
        total += len(rows)
    print("%d glyphs packed across %d faces; both sketches and the renderer agree"
          % (total, len(reference)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
