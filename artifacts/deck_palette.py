#!/usr/bin/env python3
"""Draw one page per deck theme from the sketch's own THEMES table.

The nine frames in `artifacts/renders/` are the app. Nothing here has ever shown
what the board's glass looks like, so every claim about the deck's palette rests on
reading two tables. This reads those tables — both decks, both themes — and draws
the twelve slots with the ink each one actually carries, at the panel's own
320x240, so a colour that cannot be read is visible without a board.

It is a **palette proof, not a screenshot**: it draws no deck screen, only the
table. A real capture needs the deck's draw calls ported, which this does not do.

    python3 artifacts/deck_palette.py        # writes artifacts/decks/*.png
"""
import os
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
OUT = HERE / "decks"

SLOTS = ["bg", "tile", "tileHi", "edge", "text", "dim",
         "accent", "accentDim", "pink", "purple", "yellow", "green"]
JOBS = ["page", "panel", "lifted panel", "hairline", "ink", "dim ink",
        "accent", "accent shaded", "action fill", "marker", "count", "good"]

# What the drawing code puts on each surface, as (ink slot, surface slot, floor).
# A floor is 4.5 for a word, 3.0 for a hint or a status, 1.5 for a line.
PAIRS = [
    ("ink on the page", "text", "bg", 4.5),
    ("ink on a panel", "text", "tile", 4.5),
    ("hint on a panel", "dim", "tile", 3.0),
    ("label on the action fill", "bg", "pink", 4.5),
    ("label on the accent fill", "bg", "accent", 4.5),
    ("hairline on a panel", "edge", "tile", 1.5),
    ("marker on the page", "purple", "bg", 3.0),
    ("good on the page", "green", "bg", 3.0),
]


def channel(value):
    c = value / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(hexstr):
    r, g, b = (int(hexstr[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def ratio(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def themes(sketch):
    """The two rows of `THEMES[]`, as slot -> #rrggbb, read from the comments."""
    text = sketch.read_text(encoding="utf-8")
    start = text.find("THEMES[]")
    if start < 0:
        raise SystemExit("%s has no THEMES[] table" % sketch.name)
    block = text[start:text.find("};", start)]
    found = re.findall(r"0x[0-9A-Fa-f]{4}\s*/\*\s*(#[0-9a-fA-F]{6})\s*\*/", block)
    if len(found) < 24:
        raise SystemExit("%s: %d hex comments, wanted 24 for two themes"
                         % (sketch.name, len(found)))
    rows = {}
    for name, chunk in (("PAPER", found[:12]), ("INK", found[12:24])):
        rows[name] = dict(zip(SLOTS, chunk))
    return rows


def font(px, bold=False):
    import matplotlib
    root = os.path.join(os.path.dirname(matplotlib.__file__), "mpl-data", "fonts", "ttf")
    name = "DejaVuSansMono" + ("-Bold" if bold else "") + ".ttf"
    return ImageFont.truetype(os.path.join(root, name), px)


def page(deck, theme, slots):
    image = Image.new("RGB", (320, 240), slots["bg"])
    draw = ImageDraw.Draw(image)
    ink, dim, panel = slots["text"], slots["dim"], slots["tile"]
    draw.rectangle([0, 0, 320, 30], fill=panel)
    draw.line([0, 30, 320, 30], fill=slots["edge"])
    draw.text((10, 7), "%s" % deck, font=font(11, True), fill=ink)
    draw.text((10, 32), "%s  //  the twelve slots, on the panel" % theme,
              font=font(8), fill=dim)

    y = 48
    for slot, job in zip(SLOTS, JOBS):
        colour = slots[slot]
        draw.rectangle([10, y, 40, y + 13], fill=colour, outline=slots["edge"])
        draw.text((48, y + 2), "%-10s %-13s %s" % (slot, job, colour),
                  font=font(8), fill=dim)
        y += 16

    # the pairs that carry a word, drawn on their own surface at the panel's size
    x, y = 10, 196
    for label, ink_slot, surface, floor in PAIRS[:4]:
        got = ratio(slots[ink_slot], slots[surface])
        draw.rectangle([x, y, x + 146, y + 17], fill=slots[surface])
        draw.text((x + 5, y + 4), "Aa %s" % label, font=font(8),
                  fill=slots[ink_slot])
        draw.text((x + 5, y + 19), "%.2f:1 (%.1f)" % (got, floor), font=font(7),
                  fill=slots[ink_slot] if got >= floor else slots["pink"]
                  if luminance(slots["pink"]) > luminance(slots["bg"]) else ink)
        x += 152
        if x > 200:
            x, y = 10, y + 22
    return image


def main():
    OUT.mkdir(exist_ok=True)
    decks = sorted(p for p in ROOT.iterdir()
                   if p.is_dir() and (p / (p.name + ".ino")).is_file())
    if not decks:
        raise SystemExit("no deck folders found under %s" % ROOT)
    wrote = []
    for folder in decks:
        rows = themes(folder / (folder.name + ".ino"))
        for theme, slots in rows.items():
            image = page(folder.name, theme, slots)
            path = OUT / ("%s-%s.png" % (folder.name, theme.lower()))
            image.save(path)
            wrote.append((path, slots))
            print("%-46s %s  %s" % (path.relative_to(ROOT).as_posix(), theme,
                                    "  ".join("%s %s" % (ink_slot, "%.2f" % ratio(
                                        slots[ink_slot], slots[surface]))
                                        for _, ink_slot, surface, _ in PAIRS)))
    print("\n%d page%s written; each is the table drawn, not a deck screen"
          % (len(wrote), "" if len(wrote) == 1 else "s"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
