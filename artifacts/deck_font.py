"""The deck's typeface: two GFXfont faces cut from DejaVu Sans Mono.

The panel can only draw 1-bit glyphs, so Adafruit_GFX wants a bitmap font as
PROGMEM data: a byte array of rows and a table of glyph boxes. The bundled 5x7 is
the only one a sketch gets for free, and doubling it (setTextSize(2)) is what made
every heading 12x16 blocks of the same seven-pixel shapes. This cuts two faces from
one real typeface instead: DejaVu Sans Mono at 11 px for body text and the bold at
20 px for anything the eye should land on first.

Both faces are aligned by cap top, not by baseline: a glyph's yOffset is measured
from where a capital's ink begins, so text drawn at y starts its caps at y exactly,
the way the 5x7 font did. That is what lets a font swap keep the hand-placed
coordinates on nine pages. Descenders hang below y + cap height, which the line
pitches in artifacts/deck_screens.py and the sketches both allow for.

The sketch gets this data pasted in (a pasted .ino cannot include a header beside
it), and the renderer in artifacts/deck_screens.py blits the same bitmaps through
faces() — so a deck page and the glass it claims to show are the same pixels.
"""

from pathlib import Path

import matplotlib
from PIL import Image, ImageDraw, ImageFont

TTF_DIR = Path(matplotlib.__file__).parent / "mpl-data" / "fonts" / "ttf"
FIRST, LAST = 32, 126
# weight -> (file, pixel size, line pitch the sketches advance by)
FACES = {
    "body": ("DejaVuSansMono.ttf", 11, 13),
    "display": ("DejaVuSansMono-Bold.ttf", 20, 26),
    "clock": ("DejaVuSansMono-Bold.ttf", 30, 38),
}


def _bits(mask, box):
    """The glyph as one bitstream, most significant bit first, as GFXfont wants.

    Adafruit_GFX's drawChar() pulls a fresh byte every eighth pixel and never
    pads to a row boundary, so bits run straight on from the end of one row into
    the next. Packing each row to a whole byte instead put two dead bits at the
    end of every row of a five- or six-pixel glyph, which shifted every later
    row right and, on the wide faces, spilled one glyph's rows into the next
    character: on glass the letters were blocks, while the renderer here — which
    unpacked with the same padding — drew perfect text over them.
    """
    x0, y0, x1, y1 = box
    out = bytearray()
    byte = 0
    filled = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            if mask.getpixel((x, y)) > 127:
                byte |= 0x80 >> filled
            filled += 1
            if filled == 8:
                out.append(byte)
                byte = 0
                filled = 0
    if filled:
        out.append(byte)
    return bytes(out)


def face(name):
    filename, px, pitch = FACES[name]
    font = ImageFont.truetype(str(TTF_DIR / filename), px)
    ascent, descent = font.getmetrics()
    cap_box = font.getbbox("H")
    cap_height = cap_box[3] - cap_box[1]
    cap_offset = ascent - cap_height          # ink top of a capital, from line top
    glyphs = {}
    for cp in range(FIRST, LAST + 1):
        ch = chr(cp)
        advance = int(round(font.getlength(ch)))
        pad = 4
        canvas = Image.new("L", (advance * 2 + pad * 2, ascent + descent + pad * 2), 0)
        ImageDraw.Draw(canvas).text((pad, pad + ascent), ch, font=font, fill=255,
                                    anchor="ls")
        box = canvas.getbbox()
        if box is None:                        # space and friends: advance only
            glyphs[cp] = (0, 0, advance, 0, 0, b"")
            continue
        x0, y0, x1, y1 = box
        glyphs[cp] = (x1 - x0, y1 - y0, advance, x0 - pad, y0 - pad - cap_offset,
                      _bits(canvas, box))
    return dict(name=name, px=px, pitch=pitch, y_advance=ascent + descent,
                cap_height=cap_height, advance=int(round(font.getlength("M"))),
                glyphs=glyphs)


def width(text, name="body"):
    """The pixels a string covers: monospace, so one advance per character."""
    return len(text) * faces()[name]["advance"]


_CACHE = {}


def faces():
    if not _CACHE:
        for name in FACES:
            _CACHE[name] = face(name)
    return _CACHE


def glyph_rows(entry):
    """(row bytes, width) for one glyph, so a caller can set pixels itself."""
    w, h, advance, xoff, yoff, data = entry
    return data, w, h, advance, xoff, yoff


def c_block(name, f):
    """A self-contained GFXfont a sketch can paste in: no header, no library."""
    ident = "DeckFont" + name.capitalize()
    order = [(cp, f["glyphs"][cp]) for cp in range(FIRST, LAST + 1)]
    bitmap = bytearray()
    rows = []
    for cp, (w, h, advance, xoff, yoff, data) in order:
        rows.append((len(bitmap), w, h, advance, xoff, yoff))
        bitmap += data
    lines = ["static const uint8_t %sBits[] PROGMEM = {" % ident]
    for i in range(0, len(bitmap), 12):
        lines.append("    " + ", ".join("0x%02X" % b for b in bitmap[i:i + 12]) + ",")
    lines += ["};", ""]
    lines.append("static const GFXglyph %sGlyphs[] PROGMEM = {" % ident)
    for offset, w, h, advance, xoff, yoff in rows:
        lines.append("    {%d, %d, %d, %d, %d, %d}," % (offset, w, h, advance, xoff, yoff))
    lines += ["};", ""]
    lines.append("const GFXfont %s PROGMEM = {(uint8_t *)%sBits, "
                 "(GFXglyph *)%sGlyphs, %d, %d, %d};"
                 % (ident, ident, ident, FIRST, LAST, f["y_advance"]))
    return "\n".join(lines)


def main():
    body, display = faces()["body"], faces()["display"]
    clock = faces()["clock"]
    print("clock: %d glyphs, %d px, advance %d, cap %d" % (len(clock["glyphs"]),
          clock["px"], clock["advance"], clock["cap_height"]))
    print("body: %d glyphs, %d px, advance %d, cap %d, pitch %d"
          % (len(body["glyphs"]), body["px"], body["advance"],
             body["cap_height"], body["pitch"]))
    print("display: %d glyphs, %d px, advance %d, cap %d, pitch %d"
          % (len(display["glyphs"]), display["px"], display["advance"],
             display["cap_height"], display["pitch"]))
    if "--cpp" in __import__("sys").argv:
        print(c_block("body", body))
        print(c_block("display", display))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
