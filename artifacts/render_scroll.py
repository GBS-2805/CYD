#!/usr/bin/env python3
"""Stack the seven screens into one tall picture that reads as a scroll on a phone.

Seven frames side by side shrink to a strip of thumbnails: the phone fits the
*width*, so each screen ends up narrower than a line of this docstring. This lays
them one under the other at their own 786 x 1704, each with its name and one line
of what it shows, so opening the file is a scroll through the app — front page to
settings — at a size a phone can actually read.

    python3 artifacts/render_scroll.py

The frames are the committed renders in `artifacts/renders/`, drawn by
`android-companion/render_ui.py`; the order is that script's `SCREENS` and the
mark is `artifacts/logo_icon.py`'s, so neither list nor logo is copied here. The
output is a composition of files git already has, so it is rebuilt, not tracked
(see `.gitignore`).
"""

import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "android-companion"))

import logo_icon  # noqa: E402  (the path above has to exist first)
import render_ui  # noqa: E402  the frames, their order, colours and fonts

OUT = os.path.join(HERE, "renders", "scroll.png")

WIDTH = 786                 # one screen's width at render_ui.SCALE
BAR = 78                    # the caption strip above each screen
PAD = 28
MARK_PX = 132               # the mark's width in the header, at twice its dp size

NOTES = {
    "home": "the drawer: PAIRED, BOARDS, SERIAL, and the owner's mark",
    "home-open": "BOARDS expands to the board alone",
    "projects": "the shelf, alphabetical and folded",
    "projects-open": "one project's body, log and actions",
    "paired": "the link pill, the deck card, the LAN block",
    "serial": "the USB flasher and its sweep",
    "settings": "off the gear, not a fourth row",
}


def main():
    frames = []
    for name in render_ui.SCREENS:
        path = os.path.join(render_ui.OUT, name + ".png")
        frames.append((name, Image.open(path).convert("RGB")))

    # The header is measured, not guessed: the mark, the wordmark under it, then one
    # line saying what these frames are, each placed from the one above.
    title = render_ui.font(19, bold=True)
    caption = render_ui.font(8)
    mark = logo_icon.mark_image(MARK_PX)
    title_y = 46 + mark.height + 26
    caption_y = title_y + 52
    head = caption_y + 34

    height = head + sum(BAR + frame.height for _, frame in frames)
    canvas = Image.new("RGB", (WIDTH, height), render_ui.BG)
    draw = ImageDraw.Draw(canvas)

    canvas.paste(mark, ((WIDTH - mark.width) // 2, 46), mark)
    draw.text(((WIDTH - draw.textlength("[OFF-SITE]", font=title)) / 2, title_y),
              "[OFF-SITE]", font=title, fill=render_ui.TEXT)

    line = "seven screens \u00b7 metric-accurate renders, not device screenshots"
    draw.text(((WIDTH - draw.textlength(line, font=caption)) / 2, caption_y),
              line, font=caption, fill=render_ui.DIM)

    number = render_ui.font(9, bold=True)
    name_face = render_ui.font(9.5, bold=True)
    note_face = render_ui.font(8)

    y = head
    for index, (name, frame) in enumerate(frames, 1):
        # A hairline over each caption, so a screen does not run into the strip
        # that names it: both are on the same black ground.
        draw.line([(0, y), (WIDTH, y)], fill=render_ui.EDGE, width=2)
        draw.text((PAD, y + 22), "%02d" % index, font=number, fill=render_ui.DIM)
        draw.text((PAD + 34, y + 17), name, font=name_face, fill=render_ui.TEXT)
        draw.text((PAD + 34, y + 45), NOTES.get(name, ""), font=note_face,
                  fill=render_ui.DIM)
        canvas.paste(frame, (0, y + BAR))
        y += BAR + frame.height

    canvas.save(OUT, optimize=True)
    print("%s  %d x %d" % (os.path.relpath(OUT, ROOT), canvas.width, canvas.height))
    return 0


if __name__ == "__main__":
    sys.exit(main())
