#!/usr/bin/env python3
"""Stack the seven screens into one tall picture that reads as a scroll on a phone.

Seven frames side by side shrink to a strip of thumbnails: the phone fits the
*width*, so each screen ends up narrower than a line of this docstring. This lays
them one under the other at twice their width, each with its name and one line of
what it shows, so opening the file is a scroll through the app — front page to
settings — at a size a phone can actually read.

    python3 artifacts/render_scroll.py

The frames are the committed captures in `artifacts/renders/`, taken from the app
itself by `android-companion/capture.sh`: `TopBar` and each tab drawn by Android's
own renderer, so what is stacked here is what the phone draws. The mark is
`artifacts/logo_icon.py`'s, so the geometry is not copied. The output is a
composition of files git already has, so it is rebuilt, not tracked (see
`.gitignore`).
"""

import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import logo_icon  # noqa: E402  (the path above has to exist first)

UI = os.path.join(ROOT, "android-companion", "app", "src", "main", "java",
                  "dev", "phonedesk", "companion", "Ui.java")
OUT = os.path.join(HERE, "renders", "scroll.png")

ORDER = ["home", "home-open", "projects", "projects-open", "paired", "serial", "settings"]
SCALE = 2                   # a capture is 393 px wide; the scroll reads at 786
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


def colours():
    """The app's palette, read out of `Ui.java` so this file cannot disagree with it.

    Constants are ARGB: `0xFFD9D9D9` is opaque, `0xA3D9D9D9` is the band grey at
    64 %. A translucent ink is composited over the ground here, because a caption
    strip is a painted rectangle and the app's own draw does the same blend.
    """
    wanted = {"BG", "EDGE", "TEXT", "DIM"}
    found = {}
    with open(UI, encoding="utf-8") as handle:
        for name, argb in re.findall(
                r"static final int (\w+) = 0x([0-9A-Fa-f]{8});", handle.read()):
            if name in wanted:
                alpha = int(argb[0:2], 16) / 255.0
                rgb = tuple(int(argb[i:i + 2], 16) for i in (2, 4, 6))
                found[name] = (alpha, rgb)
    missing = set(wanted) - set(found)
    if missing:
        raise SystemExit("Ui.java no longer defines %s" % ", ".join(sorted(missing)))
    ground = found["BG"][1]
    return {name: tuple(round(alpha * channel + (1.0 - alpha) * base)
                        for channel, base in zip(rgb, ground))
            for name, (alpha, rgb) in found.items()}


def font(px, bold=False, mono=False):
    """DejaVu from matplotlib's bundle: the only TTFs this machine is guaranteed."""
    import matplotlib
    ttf = os.path.join(os.path.dirname(matplotlib.__file__), "mpl-data", "fonts", "ttf")
    name = "DejaVuSansMono" if mono else "DejaVuSans"
    return ImageFont.truetype(os.path.join(ttf, name + ("-Bold" if bold else "") + ".ttf"), px)


def main():
    ui = colours()
    frames = []
    for name in ORDER:
        path = os.path.join(HERE, "renders", name + ".png")
        frame = Image.open(path).convert("RGB")
        frames.append((name, frame.resize((frame.width * SCALE, frame.height * SCALE),
                                          Image.NEAREST)))

    width = frames[0][1].width
    title = font(19, bold=True)
    caption = font(8)
    mark = logo_icon.mark_image(MARK_PX)
    title_y = 46 + mark.height + 26
    caption_y = title_y + 52
    head = caption_y + 34

    height = head + sum(BAR + frame.height for _, frame in frames)
    canvas = Image.new("RGB", (width, height), ui["BG"])
    draw = ImageDraw.Draw(canvas)

    canvas.paste(mark, ((width - mark.width) // 2, 46), mark)
    draw.text(((width - draw.textlength("[OFF-SITE]", font=title)) / 2, title_y),
              "[OFF-SITE]", font=title, fill=ui["TEXT"])

    line = "seven screens \u00b7 captured from the app, not redrawn"
    draw.text(((width - draw.textlength(line, font=caption)) / 2, caption_y),
              line, font=caption, fill=ui["DIM"])

    number = font(9, bold=True)
    name_face = font(9, bold=True, mono=True)
    note_face = font(8)

    y = head
    for index, (name, frame) in enumerate(frames, 1):
        # A hairline over each caption, so a screen does not run into the strip
        # that names it: both are on the same black ground.
        draw.line([(0, y), (width, y)], fill=ui["EDGE"], width=2)
        draw.text((PAD, y + 22), "%02d" % index, font=number, fill=ui["DIM"])
        draw.text((PAD + 34, y + 17), name, font=name_face, fill=ui["TEXT"])
        draw.text((PAD + 34, y + 45), NOTES.get(name, ""), font=note_face, fill=ui["DIM"])
        canvas.paste(frame, (0, y + BAR))
        y += BAR + frame.height

    canvas.save(OUT, optimize=True)
    print("%s  %d x %d" % (os.path.relpath(OUT, ROOT), canvas.width, canvas.height))
    return 0


if __name__ == "__main__":
    sys.exit(main())
