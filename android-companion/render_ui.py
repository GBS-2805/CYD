#!/usr/bin/env python3
"""Draw the app's screens as PNGs, from the same numbers the views use.

Every colour and metric here is copied from `Ui.java` and the tab that draws the
screen, so a render is a check on the layout as written rather than an idea of
it. They are review artifacts: written under `artifacts/renders/`, linked in the
reply before a change ships, regenerated on the next change and committed with
the code that moved them. `../check_renders.py` compares a committed PNG against
what this draws today, so a forgotten regeneration fails instead of passing.

    python3 render_ui.py              draw every screen
    python3 render_ui.py home         draw one (home, home-open, projects,
                                      projects-open, paired, serial, settings)

A render is metric-accurate, not a device screenshot: type is DejaVu Mono at the
same size in sp, cards and gaps are the same dp, and the viewport is the 393 x
852 dp of the reference phone.
"""

import json
import math
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

# --- the viewport -----------------------------------------------------------

W_DP, H_DP = 393, 852
SCALE = 2

# MainActivity's top bar, which the host does not build: it needs a window, a
# permission request and a service. Everything under it is measured by the app.
BAR_DP = 52

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "artifacts", "renders")

# The owner's mark is drawn from the artwork the launcher icon is cut from, so its
# geometry keeps one home: the generator that sits beside the SVG.
_ARTIFACTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "artifacts")
if _ARTIFACTS not in sys.path:
    sys.path.insert(0, _ARTIFACTS)
import logo_icon  # noqa: E402  (the path above has to exist first)

# The deck's INK palette, straight from Ui.java.
BG = (0, 0, 0)
CARD = (14, 14, 14)
CARD_HI = (28, 28, 28)
EDGE = (51, 51, 51)
TEXT = (255, 255, 255)
DIM = (140, 140, 140)
ACCENT = (255, 255, 255)
VIOLET = (112, 112, 112)
PINK = (212, 212, 212)
GOOD = (160, 160, 160)

# A monospace face at the same sizes is what matters, not the exact file: the
# app sets Typeface.MONOSPACE. Look where a Linux image, matplotlib and a Mac
# keep DejaVu before giving up with the list it tried.
FONT_DIRS = [
    "/usr/share/fonts/truetype/dejavu",
    "/usr/share/fonts/TTF",
    "/usr/share/fonts",
    "/opt/freestyle/python/lib/python3.12/site-packages/matplotlib/mpl-data/fonts/ttf",
    "/Library/Fonts",
]


def _find(weight):
    names = ["DejaVuSansMono-Bold.ttf"] if weight else ["DejaVuSansMono.ttf"]
    for directory in FONT_DIRS:
        for name in names:
            path = os.path.join(directory, name)
            if os.path.isfile(path):
                return path
    for directory in FONT_DIRS:
        for _, _, files in os.walk(directory):
            for name in files:
                if name.lower().endswith(".ttf") and "mono" in name.lower():
                    return os.path.join(directory, name)
    raise SystemExit("no monospace TTF found in: " + ", ".join(FONT_DIRS))


MONO = _find(False)
BOLD = _find(True)

_fonts = {}


def px(dp):
    return int(round(dp * SCALE))


def font(sp, bold=False):
    key = (sp, bold)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(BOLD if bold else MONO, px(sp))
    return _fonts[key]


def line(sp, bold=False):
    """One line's height in dp, from the face's own ascent and descent.

    Android lays a `TextView` out on its font metrics, so this asks the TTF for the
    same numbers instead of assuming a multiplier: the 1.45 that used to sit in
    every block here was a guess, and a guess inside a gate is a gate that lies.
    """
    ascent, descent = font(sp, bold).getmetrics()
    return (ascent + descent) / float(SCALE)


class Screen:
    """A 393 x 852 dp canvas with the card, text and button primitives."""

    #: every canvas the current build has made, so check_render_fit.py can audit
    #: them all — `projects()` builds two, a tall one and the bar pasted over it.
    instances = []

    def __init__(self, height_dp=H_DP):
        self.image = Image.new("RGB", (px(W_DP), px(height_dp)), BG)
        self.draw = ImageDraw.Draw(self.image)
        self.height_dp = height_dp
        #: (x, y, w, h, text) for every run painted, filled by text()
        self.runs = []
        Screen.instances.append(self)

    # -- primitives --------------------------------------------------------

    def text(self, x, y, value, sp, color, bold=False, spacing=0.0, clipped=False):
        """One run of text. `clipped` marks a run inside a scroller, where running
        off the edge is the app's own behaviour and not a layout fault."""
        face = font(sp, bold)
        self.runs.append((x, y, self.width(value, sp, bold, spacing), line(sp, bold),
                          value, clipped))
        if not spacing:
            self.draw.text((px(x), px(y)), value, font=face, fill=color)
            return
        cursor = px(x)
        for ch in value:
            self.draw.text((cursor, px(y)), ch, font=face, fill=color)
            cursor += face.getlength(ch) + px(spacing * sp)

    def width(self, value, sp, bold=False, spacing=0.0):
        face = font(sp, bold)
        width = face.getlength(value) / SCALE
        if spacing:
            width += spacing * sp * max(0, len(value) - 1)
        return width

    def logo(self, cx, y, width_dp, color=TEXT):
        """The owner's mark, centred on `cx`: the same ghost the launcher icon is cut around.

        Returns the height it drew, in dp, from the artwork's own aspect ratio, so a
        caller places what is under it without repeating that number.
        """
        image = logo_icon.mark_image(px(width_dp), color)
        self.image.paste(image, (int(round(px(cx) - image.width / 2.0)), px(y)), image)
        return image.height / float(SCALE)

    def centered(self, y, value, sp, color, bold=False, spacing=0.0):
        self.text((W_DP - self.width(value, sp, bold, spacing)) / 2.0, y,
                  value, sp, color, bold, spacing)

    def right(self, x_right, y, value, sp, color, bold=False, spacing=0.0):
        self.text(x_right - self.width(value, sp, bold, spacing), y, value, sp,
                  color, bold, spacing)

    def card(self, x, y, w, h, radius=14, fill=CARD, stroke=EDGE):
        self.draw.rounded_rectangle([px(x), px(y), px(x + w), px(y + h)],
                                    radius=px(radius), fill=fill,
                                    outline=stroke, width=max(1, px(0.5)))

    def button(self, x, y, w, label, primary=False, sp=13, h=35):
        self.card(x, y, w, h, 10, ACCENT if primary else CARD_HI,
                  ACCENT if primary else EDGE)
        face = font(sp)
        offset = (h - face.size / SCALE) / 2.0
        self.text(x + (w - face.getlength(label) / SCALE) / 2.0, y + offset,
                  label, sp, BG if primary else TEXT)

    def paragraph(self, x, y, value, sp, color, max_w, leading=2):
        for text_line in wrap(value, sp, max_w):
            self.text(x, y, text_line, sp, color)
            y += line(sp) + leading
        return y

    # -- chrome ------------------------------------------------------------

    def gear(self, cx, cy, color=DIM, radius=4.2):
        """A settings gear as a ring with eight teeth.

        Drawn rather than typed: U+2699 is an emoji on most phones and missing
        from some monospace faces, and the render should show what the phone
        shows, not a tofu box.
        """
        ring = px(radius - 1.2)
        self.draw.ellipse([px(cx) - ring, px(cy) - ring, px(cx) + ring, px(cy) + ring],
                          outline=color, width=max(1, px(0.8)))
        for tooth in range(8):
            angle = math.pi * 2 * tooth / 8 + math.pi / 8
            self.draw.line([
                px(cx + math.cos(angle) * (radius - 0.6)),
                px(cy + math.sin(angle) * (radius - 0.6)),
                px(cx + math.cos(angle) * (radius + 1.2)),
                px(cy + math.sin(angle) * (radius + 1.2)),
            ], fill=color, width=max(1, px(1.0)))

    def top_bar(self, crumb, gear=False, pill="IDLE"):
        pill_w, pill_h = 62, 26
        # The gear is the outermost thing in the bar: 2dp past the glyph, then the
        # 10dp it is padded with, a 10dp gap, and the pill ends there.
        pill_right = W_DP - 18 - 2 - 9 - 10 - 10
        self.text(18, 20, crumb, 12, TEXT if crumb.startswith("\u2039") else DIM,
                  spacing=0.12)
        self.card(pill_right - pill_w, 14, pill_w, pill_h, 9)
        self.text(pill_right - pill_w + 12, 20, pill, 11, DIM if pill == "IDLE" else GOOD,
                  bold=True, spacing=0.14)
        self.gear(W_DP - 20 - 5.4, 14 + pill_h / 2.0, TEXT if gear else DIM)

    def section_title(self, title, subtitle):
        self.text(16, 56, title, 20, TEXT)
        y = 56 + line(20) + 4
        self.text(16, y, subtitle, 12, DIM)
        return y + line(12) + 4

    def section_label(self, y, label):
        self.text(16, y + 18, label, 12, DIM, spacing=0.14)
        return y + 18 + line(12) + 8


def wrap(value, sp, max_w):
    """Monospace wrap, on the advance the face actually uses.

    Every glyph advances the same width, so one measurement of `M` is the whole
    story — and measuring it beats assuming 0.6 em, which is what this used to do.
    """
    per_char = font(sp).getlength("M") / float(SCALE)
    limit = max(8, int(max_w / per_char))
    lines, current = [], ""
    for word in value.split():
        candidate = (current + " " + word).strip()
        if len(candidate) <= limit or not current:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def para_h(value, sp, max_w, leading=2):
    """Height of {@link Screen.paragraph}: wrapped lines at the same leading."""
    return len(wrap(value, sp, max_w)) * (line(sp) + leading)


# --- screens ---------------------------------------------------------------

# --- the frames the app measures --------------------------------------------

_HOST = None

#: the frames `render_host/` builds, in the order the scroll presents them
HOSTED = ("home", "home-open")


def _hex(value):
    return tuple(int(value[i:i + 2], 16) for i in (1, 3, 5))


def host_frames():
    """Compile and run the app's own builders, once per process.

    The dump is the geometry: `render_host` measures the real `DrawerHome`, and every
    position below it was decided by `Ui.dp`, a padding, a weight or a font metric in
    the app — never by this file. A missing JDK is a hard stop, because a render that
    fell back to a transcription would be a picture nobody can trust.
    """
    global _HOST
    if _HOST is not None:
        return _HOST

    here = os.path.dirname(os.path.abspath(__file__))
    host = os.path.join(here, "render_host")
    done = subprocess.run(["bash", os.path.join(host, "run.sh")],
                          capture_output=True, text=True)
    if done.returncode != 0:
        sys.stderr.write(done.stdout + done.stderr)
        raise SystemExit("render_host/run.sh failed; the frames it builds cannot be drawn")

    with open(os.path.join(here, "build", "render-host", "dump.json"), encoding="utf-8") as handle:
        _HOST = {frame["name"]: frame for frame in json.load(handle)["frames"]}
    return _HOST


def hosted(name):
    """Paint a frame the app measured. Nothing here computes a position.

    `BAR_DP` is the one number this file still owns: `MainActivity` builds the top bar
    and the host cannot run it, so the drawer's content is drawn under a bar of the
    same height the older frames used.
    """
    frame = host_frames()[name]
    s = Screen(int(round(frame["height"])) + BAR_DP)
    s.top_bar("OFF-SITE")

    for op in frame["ops"]:
        y = op["y"] + BAR_DP
        if op["op"] == "card":
            s.card(op["x"], y, op["w"], op["h"], op["r"],
                   _hex(op["fill"]), _hex(op["stroke"]))
        elif op["op"] == "fill":
            s.draw.rectangle([px(op["x"]), px(y), px(op["x"] + op["w"]),
                              px(y + op["h"])], fill=_hex(op["fill"]))
        elif op["op"] == "text":
            s.text(op["x"], y, op["text"], op["sp"], _hex(op["color"]),
                   op.get("bold", False), op.get("spacing", 0.0))
        elif op["op"] == "image":
            s.logo(op["x"] + op["w"] / 2.0, y, op["w"])

    # The dump's numbers are the ones the fit audit reads, so the painter's own copy
    # is dropped rather than given a second opinion.
    s.runs.clear()
    return s.image


def projects(open_first=False):
    # The opened card sits several screens down, so that frame is drawn on a taller
    # canvas and cropped to the scroll position afterwards — the same thing the
    # shelf does when a thumb scrolls to it, with the top bar staying put.
    s = Screen(1800 if open_first else H_DP)
    open_top = None
    s.top_bar("\u2039  OFF-SITE")
    # Title, then which board this shelf belongs to, then the hint. The crumb no
    # longer leads with the section name: the title says it and the bar carries
    # the way back. Mirrors ProjectsTab.build().
    s.text(16, 56, "BOARDS", 20, TEXT)
    s.text(16, 56 + line(20) + 4, "2.4\" CYD (RESISTIVE)", 10, DIM, spacing=0.12)
    subtitle_y = 56 + line(20) + 4 + line(10) + 6
    s.text(16, subtitle_y, "Alphabetical. Tap a name for its notes.", 12, DIM)
    y = subtitle_y + line(12) + 4

    y = s.section_label(y, "LIBRARIES NEEDED")
    # Disclaimer card: hint, the banned library, one button per name, then Droid.
    hint_text = ("Install these once in ArduinoDroid before compiling a deck. "
                 "Tap a name to copy it.")
    warn_text = ("Never install ArduinoBLE: it shadows the ESP32 core's BLE "
                 "headers and every build fails.")
    libraries = ("Adafruit GFX Library", "Adafruit ILI9341", "XPT2046_Touchscreen")
    rows = (len(libraries) + 1) // 2
    grid_h = rows * 30 + (rows - 1) * 8
    card_h = (12 + para_h(hint_text, 11, W_DP - 60)
              + 8 + para_h(warn_text, 11, W_DP - 60)
              + 8 + grid_h + 2 + 35 + 12)
    s.card(16, y, W_DP - 32, card_h)
    inner = y + 12
    inner = s.paragraph(30, inner, hint_text, 11, DIM, W_DP - 60)
    inner = s.paragraph(30, inner + 8, warn_text, 11, PINK, W_DP - 60, 2)
    inner += 8
    # Two names to a row at 11sp, 8dp apart — the grid librariesCard() builds now.
    # An odd count leaves one name in the last row, and a weight-1 child with no
    # sibling takes the whole width, so it is drawn full width here too.
    col_w = (W_DP - 60 - 8) / 2.0
    for i, library in enumerate(libraries):
        if i % 2 == 0:
            row_y = inner
            inner += 30 + 8
        alone = i == len(libraries) - 1 and len(libraries) % 2 == 1
        s.button(30 + (i % 2) * (col_w + 8), row_y, W_DP - 60 if alone else col_w,
                 library, sp=11, h=30)
    s.button(30, inner - 6, W_DP - 60, "OPEN ARDUINODROID")
    y += card_h + 10

    # Search field.
    s.card(16, y + 14, W_DP - 32, 36, 8, CARD_HI, EDGE)
    s.text(28, y + 23, "Search projects", 13, DIM)
    y += 14 + 36 + 10

    # Status chips.
    # Status chips: a HorizontalScrollView in the app, so the row is as wide as its
    # chips need and the last one is reached by scrolling. `clipped=True` says so,
    # which is the one case where running past the canvas is not a fault.
    x = 16
    for label, on in (("ALL 5", True), ("WANT TO TRY 0", False),
                      ("FLASHED 0", False), ("WORKING 2", False),
                      ("PARKED 3", False)):
        w = s.width(label, 10, bold=True, spacing=0.1) + 18
        s.card(x, y + 10, w, 22, 8, ACCENT if on else CARD_HI,
               ACCENT if on else EDGE)
        s.text(x + 9, y + 13, label, 10, BG if on else DIM, bold=True, spacing=0.1,
               clipped=True)
        x += w + 6
    y += 10 + 22 + 4

    projects_list = [
        ("IMAGE", "PARKED", "Bruce",
         "A launcher that swaps firmware from a menu instead of reflashing: pick an "
         "app, and it hands the board over to it."),
        ("IMAGE", "WANT TO TRY", "ESP32 Marauder",
         "Wi-Fi and Bluetooth survey suite. Prebuilt image, so there is nothing to "
         "compile for it."),
        ("IMAGE", "PARKED", "Launcher",
         "A menu of the images already on the board, booted without a cable."),
        ("INSIDE APP", "WORKING", "Phone Deck · 2.4\" CYD (resistive)",
         "A desk dashboard for your phone: clock, battery and notifications, set "
         "like a woodblock print in two monochrome themes."),
        ("INSIDE APP", "WORKING", "Spotify Deck · 2.4\" CYD (resistive)",
         "What you are playing on Spotify, with previous / play / next on the "
         "screen."),
    ]
    for index, (kind, status, name, body) in enumerate(projects_list):
        # The network deck is the one with a remote face worth opening in a render.
        open_card = open_first and name.startswith("Spotify Deck")
        if open_card:
            open_top = y
        # ProjectsTab puts the name in its own TextView under the header row — kind,
        # status and the fold glyph — so it wraps at the card's width instead of
        # sharing a line with the `+`.
        name_lines = wrap(name, 16, W_DP - 60)
        name_y = y + 12 + 16 + 6
        head_h = 12 + 16 + 6 + len(name_lines) * line(16) + 12
        if open_card:
            head_h += 2 * (line(12) + 2) + 8         # body, two short lines
            head_h += 4 + line(10) + 8               # DECK label
            head_h += line(13) + 2 + line(11)        # the deck's name and detail
            head_h += 8 + 35                           # SIGN IN / FORGET
            head_h += 10 + 35                          # PAIR A DECK
            head_h += 8 + 2 * (line(11) + 2)          # the tag hint, two lines
            head_h += 6 + 2 * (line(11) + 2)          # needs, two lines
            head_h += 4 + line(11) + 2                # the log line
            head_h += 8 + 35 + 8 + 35 + 12             # both action rows
        s.card(16, y, W_DP - 32, head_h)
        tx = 30
        kind_w = s.width(kind, 10, spacing=0.12) + 12
        s.card(tx, y + 12, kind_w, 16, 6, CARD_HI, EDGE)
        s.text(tx + 6, y + 14, kind, 10, DIM, spacing=0.12)
        s.text(tx + kind_w + 8, y + 14, status, 10,
               GOOD if status == "WORKING" else VIOLET, bold=True, spacing=0.12)
        s.right(W_DP - 30, y + 12, "-" if open_card else "+", 16, DIM)
        for offset, name_line in enumerate(name_lines):
            s.text(30, name_y + offset * line(16), name_line, 16, TEXT)

        if open_card:
            inner = name_y + len(name_lines) * line(16) + 6
            inner = s.paragraph(30, inner, body, 12, DIM, W_DP - 60)
            # ProjectsTab.deckCard(): a paired board is two lines and then its own
            # row of buttons, because two buttons beside a name clip the detail.
            s.text(30, inner + 4, "DECK", 10, DIM, spacing=0.14)
            row_y = inner + 4 + line(10) + 8
            s.text(30, row_y, "Spotify Deck \u00b7 A1B2C3", 13, TEXT)
            s.text(30, row_y + line(13) + 2,
                   "A1B2C3  \u00b7  192.168.1.50  \u00b7  signed in  \u00b7  4m ago",
                   11, GOOD)
            button_y = row_y + line(13) + 2 + line(11) + 8
            s.button(30, button_y, s.width("SIGN IN", 13) + 28, "SIGN IN")
            s.button(30 + s.width("SIGN IN", 13) + 36, button_y,
                     s.width("FORGET", 13) + 28, "FORGET")
            s.button(30, button_y + 35 + 10, W_DP - 60, "PAIR A DECK")
            inner = s.paragraph(30, button_y + 35 + 10 + 35 + 8,
                                "One record per board: a deck's address can move, "
                                "its tag cannot.", 11, DIM, W_DP - 60)
            inner = s.paragraph(30, inner + 6, "NEEDS  Adafruit GFX Library, Adafruit "
                                "ILI9341, XPT2046_Touchscreen", 11, ACCENT,
                                W_DP - 60)
            inner = s.paragraph(30, inner + 4, "TOUCH: WORKS   ·   BUILD: 2432S024R-v2.1"
                                "   ·   2 days ago", 11, GOOD, W_DP - 60)
            s.button(30, inner + 8, s.width("COPY DROID", 13) + 28,
                     "COPY DROID", True)
            s.button(30 + s.width("COPY DROID", 13) + 36, inner + 8,
                     s.width("SAVE", 13) + 28, "SAVE")
            inner += 8 + 35 + 8
            s.button(30, inner, s.width("LOG", 13) + 28, "LOG")
            s.button(30 + s.width("LOG", 13) + 36, inner, s.width("STATUS", 13) + 28,
                     "STATUS")
        y += head_h + 8

    if open_first and open_top is not None:
        scroll = max(0, open_top - 70)
        image = s.image.crop((0, px(scroll), px(W_DP), px(scroll + H_DP)))
        bar = Screen()
        bar.top_bar("\u2039  OFF-SITE")
        image.paste(bar.image.crop((0, 0, px(W_DP), px(70))), (0, 0))
        return image
    return s.image


def deck_row(s, y, name, detail, action, primary=False):
    """DecksTab.addNearbyRow / addKnownRow: labels in a weighted column, then the
    badge or the LINK button. Ui.space(row, 8) is the gap above each row, and the
    detail wraps inside the column the badge or button leaves rather than running
    underneath it.
    """
    x, w, pad = 16, W_DP - 32, 14
    reserve = 44 if action == "LINKED" else 62
    lines = wrap(detail, 12, w - 2 * pad - reserve)
    h = 10 + line(15) + 2 + len(lines) * line(12) + 10
    s.card(x, y + 8, w, h, 12)
    s.text(x + pad, y + 8 + 10, name, 15, TEXT)
    line_y = y + 8 + 10 + line(15) + 2
    for text_line in lines:
        s.text(x + pad, line_y, text_line, 12, DIM)
        line_y += line(12)
    if action == "LINKED":
        s.right(x + w - pad, y + 8 + 10, "LINKED", 11, GOOD, bold=True)
    else:
        s.button(x + w - pad - 52, y + 8 + 10, 52, "LINK", primary)
    return y + 8 + h


def paired():
    """DecksTab.build(): the link's telemetry, the board it is linked to, what is in
    range, and the boards this phone has met before.

    A saved row is keyed by the deck's id (`<widget>-<TAG>`) from its identity
    document; the MAC printed under the name is only where the board was last
    seen. That is why two boards of one widget are two rows.
    """
    s = Screen()
    s.top_bar("\u2039  OFF-SITE", pill="LINKED")
    x, w = 16, W_DP - 32
    inner = w - 28                      # Ui.card padding is 14 either side
    y = 56

    # The bridge card. Every line here is a TextView in a 333dp column, so a long
    # one wraps instead of running off the card — the render wraps it the same way,
    # and the card is measured before it is filled so the text is never painted
    # over.
    live_text = "linked 2m 14s  \u00b7  -58 dBm  \u00b7  412 packets  \u00b7  7 notices"
    status_text = "Linked to Phone Deck A1B2C3"
    live_lines = wrap(live_text, 14, inner)
    status_lines = wrap(status_text, 12, inner)
    card_h = (12 + line(11) + 6
              + len(live_lines) * (line(14) + 2) + 4
              + len(status_lines) * line(12) + 12)
    s.card(x, y, w, card_h)
    s.text(x + 14, y + 12, "BRIDGE ACTIVE", 11, GOOD, spacing=0.14)
    line_y = y + 12 + line(11) + 6
    for text_line in live_lines:
        s.text(x + 14, line_y, text_line, 14, TEXT)
        line_y += line(14) + 2
    line_y += 4
    for text_line in status_lines:
        s.text(x + 14, line_y, text_line, 12, DIM)
        line_y += line(12)
    y += card_h + 10

    # The linked board: its name, then tag, address and the last packet it took.
    detail = "A1B2C3 \u00b7 24:0A:C4:11:22:33 \u00b7 packet 2s ago"
    lines = wrap(detail, 12, inner)
    linked_h = 12 + line(18) + 4 + len(lines) * line(12) + 12
    s.card(x, y, w, linked_h)
    s.text(x + 14, y + 12, "Phone Deck A1B2C3", 18, TEXT)
    line_y = y + 12 + line(18) + 4
    for text_line in lines:
        s.text(x + 14, line_y, text_line, 12, DIM)
        line_y += line(12)
    y += linked_h

    y = s.section_label(y, "NEARBY")
    y = deck_row(s, y, "Phone Deck A1B2C3",
                 "24:0A:C4:11:22:33  \u00b7  -58 dBm  \u00b7  2s ago", "LINKED")

    y = s.section_label(y, "SAVED DECKS")
    y = deck_row(s, y, "Phone Deck A1B2C3", "A1B2C3  \u00b7  linked 2m ago", "LINKED")
    y = deck_row(s, y, "Phone Deck D4E5F6", "D4E5F6  \u00b7  linked 3d ago", "LINK")

    hint = ("One deck is linked at a time. Linking here pins the bridge to that "
            "board, so a second CYD in range never steals the connection.")
    s.paragraph(16, y + 18, hint, 12, DIM, w)
    return s.image


def serial():
    s = Screen()
    s.top_bar("\u2039  OFF-SITE")
    y = s.section_title("SERIAL", "Connect over OTG, then flash an image.")

    # Board picker.
    s.card(16, y, W_DP - 32, 62)
    s.text(30, y + 12, "TARGET BOARD", 10, DIM, spacing=0.14)
    s.text(30, y + 30, "ESP32-2432S024R  (2432S024R)", 14, TEXT)
    y += 62

    s.button(16, y + 14, (W_DP - 32 - 8) / 2.0, "FLASH", True, h=36)
    s.button(16 + (W_DP - 32 - 8) / 2.0 + 8, y + 14, (W_DP - 32 - 8) / 2.0,
             "MONITOR", h=36)
    y += 14 + 36 + 14

    # USB card.
    status_text = "Plug the CYD in with an OTG cable, then tap PROBE."
    sweep_text = ("PROBE sweeps four wirings by itself — normal and swapped "
                  "lines, each with the reset driven here and with EN pulsed "
                  "while BOOT is held — and stops at the first that answers.")
    card_h = (12 + para_h(status_text, 12, W_DP - 60) + 10 + 5 * (35 + 8)
              + 6 + para_h(sweep_text, 11, W_DP - 60) + 12)
    s.card(16, y, W_DP - 32, card_h)
    inner = y + 12
    inner = s.paragraph(30, inner, status_text, 12, DIM, W_DP - 60)
    inner += 10
    s.button(30, inner, W_DP - 60, "PROBE USB", True)
    inner += 35 + 8
    for label in ("SYNC ONLY", "RESTART BOARD", "MANUAL BOOT: OFF", "LINES: NORMAL"):
        s.button(30, inner, W_DP - 60, label)
        inner += 35 + 8
    inner = s.paragraph(30, inner + 6, sweep_text, 11, DIM, W_DP - 60)
    y += card_h + 10

    y = s.section_label(y, "PROBE LOG")
    log_text = ("Nothing yet. PROBE and FLASH write each handshake step here, "
                "and a quiet ROM reports the raw bytes it did send.")
    s.card(16, y, W_DP - 32, 12 + para_h(log_text, 11, W_DP - 60) + 12)
    s.paragraph(30, y + 12, log_text, 11, DIM, W_DP - 60)
    return s.image


def settings():
    s = Screen()
    s.top_bar("\u2039  OFF-SITE", gear=True)
    y = s.section_title("SETTINGS", "")

    s.card(16, y, W_DP - 32, 46)
    s.text(30, y + 14, "Bridge stopped — start it below", 14, ACCENT)
    y += 46 + 4

    y = s.section_label(y, "PERMISSIONS")
    s.card(16, y, W_DP - 32, 84)
    s.text(30, y + 12, "Notification access: not granted", 13, PINK)
    s.button(30, y + 38, W_DP - 60, "NOTIFICATION ACCESS")
    y += 84 + 10
    s.card(16, y, W_DP - 32, 84)
    s.text(30, y + 12, "Bluetooth: granted", 13, GOOD)
    s.button(30, y + 38, W_DP - 60, "BLUETOOTH PERMISSIONS")
    y += 84 + 4

    y = s.section_label(y, "NOTIFICATIONS")
    s.card(16, y, W_DP - 32, 118)
    s.text(30, y + 12, "Apps allowed: all of them", 13, DIM)
    s.paragraph(30, y + 34, "The deck sits where other people can read it. Pick the "
                "apps it may show.", 11, DIM, W_DP - 60)
    s.button(30, y + 74, (W_DP - 60 - 8) / 2.0, "CHOOSE APPS")
    s.button(30 + (W_DP - 60 - 8) / 2.0 + 8, y + 74, (W_DP - 60 - 8) / 2.0,
             "ALL APPS")
    y += 118 + 4

    y = s.section_label(y, "BACKGROUND")
    background_text = ("Android may stop the bridge overnight. Exempting it from "
                       "battery optimisation keeps the deck updating with the "
                       "screen off.")
    background_h = 12 + para_h(background_text, 13, W_DP - 60) + 12 + 35 + 12
    s.card(16, y, W_DP - 32, background_h)
    inner = s.paragraph(30, y + 12, background_text, 13, DIM, W_DP - 60)
    s.button(30, inner + 12, W_DP - 60, "BATTERY SETTINGS")
    y += background_h + 4

    y = s.section_label(y, "BRIDGE")
    s.button(16, y, W_DP - 32, "START BRIDGE", True, h=38)
    return s.image


SCREENS = {
    "home": lambda: hosted("home"),
    "home-open": lambda: hosted("home-open"),
    "projects": lambda: projects(False),
    "projects-open": lambda: projects(True),
    "paired": paired,
    "serial": serial,
    "settings": settings,
}


def main(argv):
    wanted = argv[1:] or list(SCREENS)
    os.makedirs(OUT, exist_ok=True)
    for name in wanted:
        if name not in SCREENS:
            sys.stderr.write("unknown screen %r\n" % name)
            return 2
        path = os.path.join(OUT, name + ".png")
        # optimize=True is lossless and about 3% smaller on these flat-colour
        # screens; the render is a review artifact, so there is no reason to
        # commit the bytes a default save leaves behind.
        SCREENS[name]().save(path, optimize=True)
        print("%-14s %s" % (name, os.path.relpath(path)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
