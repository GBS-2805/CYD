#!/usr/bin/env python3
"""Render what the deck firmware paints on the CYD's 320x240 panel.

Every coordinate in this file is copied from cyd_phone_deck.ino -- the layout
rects and the draw* functions -- and the palette is parsed straight out of it, so
the images are a faithful
transcription of the drawing code rather than an artist's impression. Even the
typeface is the panel's own: Adafruit's 5x7 GLCD font, fetched from
Adafruit-GFX-Library at render time and drawn into the same 6x8 cell that
tft.print() uses, so letterforms, spacing and line pitch match the hardware.

Run from the repository root:  python3 cyd_phone_deck/renders/make_renders.py
Add --check to validate the palettes and write no images.
"""

import argparse
import os
import re
import urllib.request

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))

W, H = 320, 240

# ---------------------------------------------------------------- palette ---
# The palette is not written here. It is parsed out of cyd_phone_deck.ino: every
# entry in its THEMES[] table carries the hex it was derived from in the
# comment beside it, and the render uses the theme THEME_INDEX selects, so
# and the renderer paints with that hex rather than the 565 value, so the images
# keep the full 8-bit tone the panel can only approximate. If a constant stops
# agreeing with its comment, or a colour named below disappears from the sketch,
# this file exits with the mismatch instead of rendering a palette the firmware
# no longer has.

SKETCH = os.path.normpath(os.path.join(HERE, "..", "cyd_phone_deck.ino"))

THEME_FIELDS = ("C_BG", "C_TILE", "C_TILE_HI", "C_EDGE", "C_TEXT", "C_DIM",
                "C_ACCENT", "C_ACCENT_DIM", "C_PINK", "C_PURPLE", "C_YELLOW",
                "C_GREEN")
THEMES_RE = re.compile(r"Palette\s+THEMES\[\]\s*=\s*\{(.*?)\n\};", re.S)
ENTRY_RE = re.compile(r"0x([0-9A-Fa-f]{4})\s*/\*\s*#([0-9A-Fa-f]{6})\s*\*/")
THEME_INDEX_RE = re.compile(r"THEME_INDEX\s*=\s*(\d+)\s*;")


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def rgb565(rgb):
    r, g, b = rgb
    return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)


def load_palette(path=SKETCH):
    """Every theme in the sketch, checked, then the one THEME_INDEX selects."""
    try:
        with open(path, encoding="utf-8") as handle:
            source = handle.read()
    except OSError as exc:
        raise SystemExit("cannot read the sketch palette from %s: %s" % (path, exc))

    found = THEMES_RE.search(source)
    if not found:
        raise SystemExit(
            "no Palette THEMES[] table in %s -- expected\n"
            "  static constexpr Palette THEMES[] = {\n"
            "    { 0x10A1 /* #14150f */, ... },\n"
            "  };" % path)

    entries = ENTRY_RE.findall(found.group(1))
    width = len(THEME_FIELDS)
    if not entries or len(entries) % width:
        raise SystemExit("%s holds %d palette entries, which is not a whole number "
                         "of %d-colour themes" % (path, len(entries), width))

    problems = []
    themes = []
    for index in range(len(entries) // width):
        theme = {}
        for name, (packed, hex6) in zip(THEME_FIELDS,
                                        entries[index * width:(index + 1) * width]):
            actual = rgb565(hex_rgb(hex6))
            if actual != int(packed, 16):
                problems.append("  theme %d %-14s #%s packs to 0x%04X, sketch says 0x%s"
                                % (index, name, hex6, actual, packed.upper()))
            theme[name] = hex_rgb(hex6)
        themes.append(theme)
    if problems:
        raise SystemExit("palette in %s disagrees with the hex comments beside it:\n%s"
                         % (path, "\n".join(problems)))

    chosen = THEME_INDEX_RE.search(source)
    if not chosen:
        raise SystemExit("%s declares no THEME_INDEX" % path)
    index = int(chosen.group(1))
    if index >= len(themes):
        raise SystemExit("%s picks THEME_INDEX %d but only %d themes exist"
                         % (path, index, len(themes)))
    global THEME_INDEX_NUM
    THEME_INDEX_NUM = index
    return themes[index], themes


THEME_INDEX_NUM = 0  # rewritten by load_palette() from the sketch's THEME_INDEX
PALETTE, THEMES = load_palette()


def colour(name):
    if name not in PALETTE:
        raise SystemExit("the renderer draws with %s, but %s no longer declares it"
                         % (name, SKETCH))
    return PALETTE[name]


# Both shipping palettes are monochrome: 0 PAPER is black on white, 1 INK is
# white on black. Each accent slot keeps its job and becomes a step on the grey
# ramp that replaced hue.
TAG = "A1B2C3"                      # the render board's efuse MAC tag
C_BG = colour("C_BG")               # black ground
C_TILE = colour("C_TILE")           # panel
C_TILE_HI = colour("C_TILE_HI")     # panel, lifted again
C_EDGE = colour("C_EDGE")           # slate hairline
C_TEXT = colour("C_TEXT")           # cream ink
C_DIM = colour("C_DIM")             # mid sand
C_ACCENT = colour("C_ACCENT")       # lit sage
C_ACCENT_DIM = colour("C_ACCENT_DIM")  # sage in shadow
C_PINK = colour("C_PINK")           # vermilion
C_PURPLE = colour("C_PURPLE")       # copper
C_YELLOW = colour("C_YELLOW")       # acorn cream
C_GREEN = colour("C_GREEN")         # olive


# ------------------------------------------------------------ layout rects ---


class Rect:
    def __init__(self, x, y, w, h):
        self.x, self.y, self.w, self.h = x, y, w, h

    @property
    def box(self):
        return [self.x, self.y, self.x + self.w - 1, self.y + self.h - 1]


HEADER_H = 34                       # sketch: 1164
GEAR_BTN = Rect(284, 5, 32, 24)     # 1166
LINK_PILL = Rect(204, 6, 76, 22)    # 1167
SET_LINK_PILL = Rect(234, 6, 76, 22)  # same pill, header with no gear
BACK_BTN = Rect(6, 3, 34, 28)       # 1168
STATUS_CARD = Rect(8, 40, 304, 76)  # 1170
NOTIF_ROW = [Rect(8, 134, 304, 32), Rect(8, 168, 304, 32), Rect(8, 202, 304, 32)]

DETAIL_DISMISS = Rect(12, 202, 148, 30)
DETAIL_BACK = Rect(168, 202, 140, 30)

SET_BRI_DOWN = Rect(12, 64, 146, 28)
SET_BRI_UP = Rect(162, 64, 146, 28)
SET_CLEAR = Rect(232, 106, 72, 26)
SET_CAL = Rect(8, 146, 304, 32)
SET_CLOSE = Rect(8, 186, 304, 34)

# Front page, transcribed from the sketch's layout block.
HEADER_BACK = Rect(6, 3, 34, 28)
HOME_TAB = [Rect(10, 84, 300, 30), Rect(10, 118, 300, 30),
            Rect(10, 152, 300, 30), Rect(10, 186, 300, 30)]
HOME_PILL_A = Rect(10, 52, 96, 18)
HOME_PILL_B = Rect(114, 52, 96, 18)

CAL_INSET = 26
CAL_TARGET_X = [CAL_INSET, W - 1 - CAL_INSET, CAL_INSET]
CAL_TARGET_Y = [CAL_INSET, CAL_INSET, H - 1 - CAL_INSET]

CORNER_CARD = 10
CORNER_BUTTON = 8

# ------------------------------------------------------------------- type ---

# The exact table Adafruit_GFX hands to tft.print(): 256 glyphs, five column
# bytes each, bit 0 of a byte is the top row. Cached outside the repo so the
# firmware folder does not carry a downloaded file.
GLCD_URL = ("https://raw.githubusercontent.com/adafruit/Adafruit-GFX-Library/"
            "master/glcdfont.c")
GLCD_PATH = "/tmp/glcdfont.c"


def load_glcd(path=GLCD_PATH):
    if not os.path.exists(path):
        with urllib.request.urlopen(GLCD_URL, timeout=30) as response:
            with open(path, "wb") as out:
                out.write(response.read())
    with open(path, "r", errors="replace") as handle:
        source = handle.read()
    body = source[source.index("{"):]
    values = [int(token, 16) for token in re.findall(r"0[xX][0-9a-fA-F]{2}", body)]
    if len(values) < 1280:
        raise SystemExit("glcdfont.c is truncated: %d bytes" % len(values))
    return values


_glcd = None


def glcd():
    """The font table, fetched on first use so --check needs no network."""
    global _glcd
    if _glcd is None:
        _glcd = load_glcd()
    return _glcd


_masks = {}


def _glyph(ch, size):
    key = (ch, size)
    if key not in _masks:
        code = ord(ch) & 0xFF
        columns = glcd()[code * 5:code * 5 + 5]
        mask = Image.new("L", (5 * size, 7 * size), 0)
        draw = ImageDraw.Draw(mask)
        for col, bits in enumerate(columns):
            for row in range(7):
                if (bits >> row) & 1:
                    x0, y0 = col * size, row * size
                    draw.rectangle([x0, y0, x0 + size - 1, y0 + size - 1], fill=255)
        _masks[key] = mask
    return _masks[key]


def text_width(value, size):
    """5x7 glyph in a 6x8 cell: 6*size per character, one spare column."""
    return 6 * size * len(value) - size


def text(img, value, x, y, size, fg):
    value = str(value)
    for i, ch in enumerate(value):
        if ch == " ":
            continue
        mask = _glyph(ch, size)
        px, py = x + i * 6 * size, y
        img.paste(fg, (px, py, px + mask.width, py + mask.height), mask)


def text_right(img, value, right, y, size, fg):
    text(img, value, right - text_width(str(value), size), y, size, fg)


def text_center(img, value, rect, size, fg):
    value = str(value)
    x = rect.x + (rect.w - text_width(value, size)) // 2
    y = rect.y + (rect.h - 8 * size) // 2
    text(img, value, x, y, size, fg)


# ------------------------------------------------------------- primitives ---


def card(img, rect, fill, outline, radius=CORNER_CARD):
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(rect.box, radius=radius, fill=fill, outline=outline)


def hline(img, y, x0=0, x1=W - 1, color=C_EDGE):
    ImageDraw.Draw(img).line([(x0, y), (x1, y)], fill=color)


def button(img, rect, label, size, accent):
    fill = C_PINK if accent else C_TILE
    edge = C_PINK if accent else C_EDGE
    card(img, rect, fill, edge, CORNER_BUTTON)
    text_center(img, label, rect, size, C_BG if accent else C_TEXT)


def gear(img, cx, cy, color):
    draw = ImageDraw.Draw(img)
    draw.ellipse([cx - 5, cy - 5, cx + 5, cy + 5], outline=color)
    spokes = [(0, -1), (1, -1), (1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1)]
    for dx, dy in spokes:
        draw.line([(cx + dx * 5, cy + dy * 5), (cx + dx * 7, cy + dy * 7)], fill=color)


def crosshair(img, x, y):
    draw = ImageDraw.Draw(img)
    draw.line([(x - 11, y), (x + 11, y)], fill=C_TEXT)
    draw.line([(x, y - 11), (x, y + 11)], fill=C_TEXT)
    draw.ellipse([x - 7, y - 7, x + 7, y + 7], outline=C_ACCENT)
    draw.ellipse([x - 1, y - 1, x + 1, y + 1], fill=C_ACCENT)


def wrap(value, width, size, max_lines):
    """Port of drawWrappedText(): greedy fill, one line per 8*size+3 pixels."""
    per_line = max(1, width // (6 * size))
    lines, current = [], ""
    for word in value.split():
        candidate = (current + " " + word).strip()
        if len(candidate) > per_line and current:
            lines.append(current)
            current = word
        else:
            current = candidate
        if len(lines) == max_lines:
            break
    if current and len(lines) < max_lines:
        lines.append(current)
    return lines[:max_lines]


# ----------------------------------------------------------------- screens ---


def link_pill(img, rect, linked):
    """One link pill everywhere: a ring offline, a filled dot when linked."""
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(rect.box, radius=11, fill=C_TILE,
                           outline=C_ACCENT if linked else C_EDGE)
    cx, cy = rect.x + 12, rect.y + 11
    if linked:
        draw.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], fill=C_ACCENT)
    else:
        draw.ellipse([cx - 3, cy - 3, cx + 3, cy + 3], outline=C_DIM)
    text(img, "LINKED" if linked else "OFFLINE", rect.x + 20, rect.y + 8, 1,
         C_ACCENT if linked else C_DIM)


def draw_header(img, linked):
    ImageDraw.Draw(img).rectangle([0, 0, W - 1, HEADER_H - 1], fill=C_BG)
    hline(img, HEADER_H - 1)

    draw = ImageDraw.Draw(img)
    chip = HEADER_BACK
    draw.rounded_rectangle(chip.box, radius=7, outline=C_EDGE)
    text_center(img, "<", chip, 2, C_TEXT)
    text(img, "PHONE DECK", 48, 5, 2, C_TEXT)
    text(img, "esp32 // #%s" % TAG, 48, 21, 1, C_DIM)

    link_pill(img, LINK_PILL, linked)

    draw.rounded_rectangle(GEAR_BTN.box, radius=6, fill=C_TILE, outline=C_EDGE)
    gear(img, GEAR_BTN.x + GEAR_BTN.w // 2, GEAR_BTN.y + GEAR_BTN.h // 2, C_TEXT)


def battery_colour(level, charging):
    """Blue while charging, pink under 30%, green at or above it."""
    if level < 0:
        return C_DIM
    if charging:
        return C_ACCENT
    return C_PINK if level < 30 else C_GREEN


def draw_status_card(img, linked, clock, battery, date_label, bound=True, charging=False):
    r = STATUS_CARD
    card(img, r, C_TILE, C_EDGE)
    draw = ImageDraw.Draw(img)

    text(img, "$ sys.status --", r.x + 12, r.y + 7, 1, C_DIM)
    if linked:
        state = "READY // ONLINE"
    else:
        state = "STANDBY // OFFLINE" if bound else "BIND // FIRST PHONE"
    right = r.x + r.w - 12
    state_x = right - text_width(state, 1)
    text(img, state, state_x, r.y + 7, 1, C_ACCENT if linked else C_DIM)
    dot = C_ACCENT if linked else C_PINK
    draw.ellipse([state_x - 12, r.y + 8, state_x - 6, r.y + 14], fill=dot)

    hline(img, r.y + 20, r.x + 12, r.x + r.w - 12)

    if clock:
        hhmm, seconds = clock
        text(img, hhmm, r.x + 12, r.y + 24, 4, C_TEXT)
        text(img, seconds, r.x + 134, r.y + 36, 1, C_ACCENT)
        text(img, date_label, r.x + 12, r.y + 60, 1, C_DIM)
    else:
        text(img, "--:--", r.x + 12, r.y + 24, 4, C_DIM)
        text(img, "no time yet  //  waiting", r.x + 12, r.y + 60, 1, C_DIM)

    batt_x = r.x + 236
    text(img, "BATTERY", batt_x, r.y + 26, 1, C_DIM)
    text(img, "%d%%" % battery if battery >= 0 else "--", batt_x, r.y + 36, 2,
         battery_colour(battery, charging))

    bar = Rect(batt_x, r.y + 56, 58, 6)
    draw.rounded_rectangle(bar.box, radius=3, outline=C_EDGE)
    if battery >= 0:
        fill = (bar.w - 2) * battery // 100
        fill = max(2, fill)
        draw.rounded_rectangle([bar.x + 1, bar.y + 1, bar.x + fill, bar.y + bar.h - 2],
                               radius=2, fill=battery_colour(battery, charging))
    if charging:
        text(img, "CHG", batt_x, r.y + 66, 1, battery_colour(battery, charging))


def draw_section_header(img, unread):
    draw = ImageDraw.Draw(img)
    draw.ellipse([9, 122, 15, 128], fill=C_PURPLE)
    text(img, "01 // NOTIFICATIONS", 20, 121, 1, C_TEXT)
    label = "%d NEW" % unread if unread else "DIRECT ACCESS"
    text_right(img, label, W - 8, 121, 1, C_YELLOW if unread else C_DIM)


def draw_notice_row(img, slot, notice, linked):
    r = NOTIF_ROW[slot]
    draw = ImageDraw.Draw(img)
    if notice is None:
        # Only the first empty slot earns an outline, mirroring the sketch.
        if slot > 0:
            return
        card(img, r, C_BG, C_EDGE)
        text_center(img, "no notifications" if linked else "waiting for companion",
                    r, 1, C_DIM)
        return

    app, title, age, unread = notice
    card(img, r, C_TILE, C_ACCENT_DIM if unread else C_EDGE)
    icon = Rect(r.x + 8, r.y + 6, 20, 20)
    draw.rounded_rectangle(icon.box, radius=6, fill=C_TILE_HI,
                           outline=C_ACCENT_DIM if unread else C_EDGE)
    text_center(img, app[0], icon, 2, C_YELLOW if unread else C_DIM)
    text(img, app, r.x + 36, r.y + 4, 1, C_ACCENT if unread else C_DIM)
    text_right(img, age, r.x + r.w - 8, r.y + 4, 1, C_DIM)
    text(img, title, r.x + 36, r.y + 14, 2, C_TEXT if unread else C_DIM)


# ------------------------------------------------------------- front page ---

TAB_LABELS = ["MESSAGES", "SETTINGS", "THEME", "CALIBRATE"]
THEME_NAMES = ["PAPER", "INK"]


def screen_home(linked=True, unread=3, clock=("10:42",), battery=86):
    img = Image.new("RGB", (W, H), C_BG)
    left = "SYNC %s // PHONE TIME" % clock[0] if clock else "NO TIME YET // WAITING"
    text(img, left, 10, 6, 1, C_DIM)
    text_right(img, "BATTERY %d%%" % battery if battery >= 0 else "BATTERY --",
               GEAR_BTN.x - 6, 6, 1, battery_colour(battery, False))

    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(GEAR_BTN.box, radius=6, fill=C_TILE, outline=C_EDGE)
    gear(img, GEAR_BTN.x + GEAR_BTN.w // 2, GEAR_BTN.y + GEAR_BTN.h // 2, C_TEXT)

    text(img, "PHONE DECK", 10, 20, 3, C_TEXT)

    state = "LINKED" if linked else "OFFLINE"
    badge_right = W - 10
    dot_x = badge_right - text_width(state, 1) - 12
    if linked:
        draw.ellipse([dot_x - 4, 32, dot_x + 4, 40], fill=C_ACCENT)
    else:
        draw.ellipse([dot_x - 4, 32, dot_x + 4, 40], outline=C_DIM)
    text_right(img, state, badge_right, 32, 1, C_ACCENT if linked else C_DIM)

    draw.rounded_rectangle(HOME_PILL_A.box, radius=9, outline=C_EDGE)
    label = "%d NEW" % unread if unread else "INBOX CLEAR"
    draw.ellipse([HOME_PILL_A.x + 9, HOME_PILL_A.y + 7,
                  HOME_PILL_A.x + 13, HOME_PILL_A.y + 11],
                 fill=C_YELLOW if unread else C_ACCENT)
    text(img, label, HOME_PILL_A.x + 20, HOME_PILL_A.y + 5, 1, C_TEXT)

    draw.rounded_rectangle(HOME_PILL_B.box, radius=9, fill=C_TEXT)
    text_center(img, "THEME %02d" % THEME_INDEX_NUM, HOME_PILL_B, 1, C_BG)

    hline(img, 78, 10, W - 10)
    for i, tab in enumerate(TAB_LABELS):
        r = HOME_TAB[i]
        text(img, "%02d" % (i + 1), r.x + 4, r.y + 11, 1, C_ACCENT_DIM)
        text(img, tab, r.x + 26, r.y + 8, 2, C_TEXT)

    hline(img, 224, 10, W - 10)
    return img


def screen_theme():
    img = Image.new("RGB", (W, H), C_BG)
    hline(img, HEADER_H - 1)
    card(img, BACK_BTN, C_TILE, C_EDGE, 7)
    text_center(img, "<", BACK_BTN, 2, C_TEXT)
    text(img, "THEMES", 48, 8, 2, C_TEXT)
    text_right(img, "%02d BAKED IN" % len(THEMES), W - 12, 13, 1, C_DIM)

    draw = ImageDraw.Draw(img)
    for i, theme in enumerate(THEMES):
        r = Rect(8, 56 + i * 30, 304, 26)
        draw.rectangle([r.x, r.y, r.x + r.w - 1, r.y + r.h - 1],
                       fill=theme["C_BG"],
                       outline=theme["C_ACCENT"] if i == THEME_INDEX_NUM
                       else theme["C_EDGE"])
        text(img, "%02d" % i, r.x + 8, r.y + 9, 1,
             theme["C_ACCENT"] if i == THEME_INDEX_NUM else theme["C_DIM"])
        text(img, THEME_NAMES[i], r.x + 30, r.y + 5, 2, theme["C_TEXT"])
        draw.ellipse([r.x + r.w - 20, r.y + 9, r.x + r.w - 12, r.y + 17],
                     fill=theme["C_PINK"])

    text(img, "READ ONLY: SET THEME_INDEX, THEN REFLASH", 10, 212, 1, C_DIM)
    text(img, "12 SLOTS A THEME, ONE GREY RAMP", 10, 224, 1, C_ACCENT_DIM)
    return img


SAMPLE = [
    ("Signal", "Ada Reyes", "now", True),
    ("Gmail", "Build passed on main", "12m", True),
    ("Calendar", "Standup in 10 min", "1h", False),
]


def screen_board(linked=True, notices=SAMPLE, clock=("10:42", "17"), battery=86,
                 date_label="THU 30 OCT  //  phone time", charging=False):
    img = Image.new("RGB", (W, H), C_BG)
    draw_header(img, linked)
    draw_status_card(img, linked, clock, battery, date_label, charging=charging)
    draw_section_header(img, sum(1 for n in notices if n and n[3]))
    for slot in range(3):
        draw_notice_row(img, slot, notices[slot] if slot < len(notices) else None, linked)
    return img


def screen_board_offline():
    return screen_board(linked=False, notices=[], clock=None, battery=-1, date_label="")


def screen_detail():
    img = Image.new("RGB", (W, H), C_BG)
    hline(img, HEADER_H - 1)
    card(img, BACK_BTN, C_TILE, C_EDGE, 7)
    text_center(img, "<", BACK_BTN, 2, C_TEXT)

    app, age = "Signal", "now"
    text(img, app, 48, 8, 2, C_TEXT)
    text_right(img, age, W - 12, 13, 1, C_DIM)

    title = "Ada Reyes"
    text(img, title, 12, 44, 2, C_TEXT)
    hline(img, 88, 12, W - 12)

    body = ("Can you look at the render of the main screen before the call? "
            "I moved the clock up a row and want a second pair of eyes on the "
            "battery bar so it does not crowd the date line.")
    y = 98
    for line in wrap(body, W - 24, 1, 9):
        text(img, line, 12, y, 1, C_DIM)
        y += 11

    button(img, DETAIL_DISMISS, "DISMISS", 2, True)
    button(img, DETAIL_BACK, "BACK", 2, False)
    return img


def screen_settings():
    img = Image.new("RGB", (W, H), C_BG)
    hline(img, HEADER_H - 1)
    card(img, BACK_BTN, C_TILE, C_EDGE, 7)
    text_center(img, "<", BACK_BTN, 2, C_TEXT)
    text(img, "SETTINGS", 48, 8, 2, C_TEXT)
    link_pill(img, SET_LINK_PILL, True)

    card(img, Rect(8, 38, 304, 58), C_TILE, C_EDGE)
    text(img, "BRIGHTNESS", 18, 44, 1, C_DIM)
    text_right(img, "80%", 300, 46, 2, C_ACCENT)
    button(img, SET_BRI_DOWN, "-", 3, False)
    button(img, SET_BRI_UP, "+", 3, False)

    card(img, Rect(8, 102, 304, 38), C_TILE, C_EDGE)
    text(img, "NOTIFICATIONS", 18, 108, 1, C_DIM)
    text(img, "3 waiting", 18, 120, 1, C_TEXT)
    button(img, SET_CLEAR, "CLEAR", 1, False)

    button(img, SET_CAL, "CALIBRATE TOUCH", 1, False)
    button(img, SET_CLOSE, "CLOSE", 2, True)
    text(img, "A1:B2:C3:D4:E5:F6", 12, 226, 1, C_PURPLE)
    text_right(img, "hold gear = forget", W - 12, 226, 1, C_DIM)
    return img


def screen_calibrate(index=0, raw=(1874, 3412)):
    img = Image.new("RGB", (W, H), C_BG)
    crosshair(img, CAL_TARGET_X[index], CAL_TARGET_Y[index])
    text(img, "CALIBRATE %d/3" % (index + 1), 10, 10, 2, C_ACCENT)
    text(img, "Tap the dot in this corner", 10, 36, 1, C_TEXT)
    text(img, "order: TL TR BL, then done", 10, 48, 1, C_DIM)
    text(img, "raw", 10, 212, 1, C_DIM)
    text(img, "raw %4d %4d" % raw, 34, 212, 1, C_DIM)
    text(img, "hold 2s anywhere = cancel", 10, 226, 1, C_DIM)

    draw = ImageDraw.Draw(img)
    for i in range(3):
        x = 250 + i * 18
        if i < index:
            draw.ellipse([x - 5, 17, x + 5, 27], fill=C_ACCENT)
        else:
            draw.ellipse([x - 5, 17, x + 5, 27], outline=C_EDGE)
    return img


# ------------------------------------------------------------- board shot ---


def board_render(screen, scale=2):
    """The CYD at roughly 15 px/mm: 86 x 50 mm board, 42.7 x 32 mm active area."""
    panel = screen.resize((W * scale, H * scale), Image.NEAREST)
    board_w, board_h = 1290, 750
    pad = 60
    canvas = Image.new("RGB", (board_w + 2 * pad, board_h + 2 * pad), hex_rgb("1c1d26"))
    draw = ImageDraw.Draw(canvas)

    ox, oy = pad, pad

    # board outline with a touch of edge highlight
    draw.rounded_rectangle([ox, oy, ox + board_w, oy + board_h], radius=26,
                           fill=hex_rgb("2b2c3a"), outline=hex_rgb("44475a"), width=3)

    # mounting holes
    for hx, hy in [(34, 34), (board_w - 34, 34), (34, board_h - 34),
                   (board_w - 34, board_h - 34)]:
        draw.ellipse([ox + hx - 9, oy + hy - 9, ox + hx + 9, oy + hy + 9],
                     fill=hex_rgb("0a0d13"), outline=hex_rgb("39424f"), width=2)

    module = Rect(305, 100, 680, 520)
    # panel glass, slightly larger than the active area
    draw.rounded_rectangle([ox + module.x, oy + module.y,
                            ox + module.x + module.w, oy + module.y + module.h],
                           radius=10, fill=hex_rgb("05070b"), outline=hex_rgb("1b2230"),
                           width=2)
    px, py = ox + (board_w - panel.width) // 2, oy + 120
    canvas.paste(panel, (px, py))

    # a few vias so the front of the PCB does not read as an empty rectangle
    for vx in range(120, board_w - 100, 74):
        for vy in (72, board_h - 74):
            if abs(vx - board_w // 2) < 360:
                continue
            draw.ellipse([ox + vx - 3, oy + vy - 3, ox + vx + 3, oy + vy + 3],
                         fill=hex_rgb("0b0f16"), outline=hex_rgb("2b3341"))

    # Everything else — module, TF, USB-C, CH340, buttons, battery — is on the
    # back, so this face stays what it is: glass, panel, status LED, and the
    # backlight switch that sits at the side edge.
    side_bl = Rect(board_w - 14, 262, 16, 58)
    draw.rounded_rectangle([ox + side_bl.x, oy + side_bl.y,
                            ox + side_bl.x + side_bl.w, oy + side_bl.y + side_bl.h],
                           radius=6, fill=hex_rgb("20262f"),
                           outline=hex_rgb("6b7280"), width=2)
    draw.line([(ox + side_bl.x + 4, oy + side_bl.y + 16),
               (ox + side_bl.x + 12, oy + side_bl.y + 16)],
              fill=hex_rgb("8b93a3"), width=2)
    text_right(canvas, "BACKLIGHT", ox + board_w - 26, oy + 334, 2, hex_rgb("8b93a3"))

    # RGB status LED: three discrete channels, active low, pins 4 / 16 / 17.
    for i, ink in enumerate([(255, 90, 110), (110, 230, 140), (110, 200, 245)]):
        lx = 132 + i * 20
        draw.rectangle([ox + lx, oy + board_h - 96, ox + lx + 12,
                        oy + board_h - 72], fill=ink, outline=hex_rgb("1c1d26"))
    text(canvas, "RGB", 132 + ox, oy + board_h - 122, 2, hex_rgb("6f7889"))

    # silkscreen: only what the repo states about the board itself
    silk = hex_rgb("c3cad6")
    text(canvas, "ESP32-2432S024R", ox + 300, oy + 34, 2, silk)
    text_right(canvas, '2.4" 320x240  //  CYD', ox + 990, oy + 34, 2,
               hex_rgb("7c8494"))

    return canvas


def rear_render():
    """The back of the board, traced from the vendor's labelled photograph.

    Proportions come from that photo, so part placement is nominal in the sense
    the rest of this file means it: it is a diagram of the photo, not a measured
    drawing. The firmware touches none of it — no SD, no speaker, no battery
    sense — so only the buttons and the CH340 involve anything the sketch knows.
    """
    board_w, board_h = 1290, 750
    pad = 60
    canvas = Image.new("RGB", (board_w + 2 * pad, board_h + 2 * pad), hex_rgb("1c1d26"))
    draw = ImageDraw.Draw(canvas)
    ox, oy = pad, pad

    draw.rounded_rectangle([ox, oy, ox + board_w, oy + board_h], radius=26,
                           fill=hex_rgb("2b2c3a"), outline=hex_rgb("44475a"), width=3)
    for hx, hy in [(34, 34), (board_w - 34, 34), (34, board_h - 34),
                   (board_w - 34, board_h - 34)]:
        draw.ellipse([ox + hx - 9, oy + hy - 9, ox + hx + 9, oy + hy + 9],
                     fill=hex_rgb("0a0d13"), outline=hex_rgb("39424f"), width=2)

    metal = hex_rgb("8e97a4")
    white = hex_rgb("c3cad6")
    dark = hex_rgb("0b0f16")
    cap = hex_rgb("343c48")
    body = hex_rgb("20262f")
    silk = hex_rgb("8b93a3")

    def box(rect, fill=body, outline=hex_rgb("39424f"), radius=3, width=2):
        draw.rounded_rectangle([ox + rect.x, oy + rect.y,
                                ox + rect.x + rect.w, oy + rect.y + rect.h],
                               radius=radius, fill=fill, outline=outline, width=width)

    # ESP-WROOM-32: antenna end at the left edge, shield over the rest
    box(Rect(24, 196, 424, 336), fill=hex_rgb("151b26"), radius=4)
    box(Rect(52, 224, 368, 280), fill=hex_rgb("0f141d"), radius=2)
    for pad_index in range(10):
        pad_y = oy + 212 + pad_index * 31
        draw.rectangle([ox + 396, pad_y, ox + 446, pad_y + 18], fill=metal)
    text(canvas, "ESP-WROOM-32", ox + 96, oy + 326, 2, silk)
    text(canvas, "no PSRAM", ox + 96, oy + 356, 1, hex_rgb("6f7889"))

    # TF card slot, top centre
    box(Rect(548, 26, 330, 196), fill=metal, outline=hex_rgb("6b7280"), radius=6)
    box(Rect(572, 56, 282, 136), fill=dark, radius=3, width=1)
    text(canvas, "TF", ox + 888, oy + 84, 2, silk)

    # the two small headers along the top-left edge
    for rect, name in [(Rect(136, 24, 158, 66), "EXT IO"),
                       (Rect(348, 24, 130, 66), "T/H")]:
        box(rect, fill=white, outline=hex_rgb("8e97a4"))
        for pin in range(4):
            pad_x = ox + rect.x + 14 + pin * (rect.w - 28) // 4
            draw.rectangle([pad_x, oy + rect.y + 10, pad_x + 22, oy + rect.y + 56],
                           fill=dark)
        text(canvas, name, ox + rect.x, oy + rect.y - 16, 1, silk)

    # USB-C and the 4-pin JST on the right edge
    usb = Rect(1176, 236, 114, 84)
    box(usb, fill=metal, outline=hex_rgb("6b7280"), radius=10)
    box(Rect(1196, 258, 74, 40), fill=hex_rgb("2b303a"), radius=6, width=1)
    text_right(canvas, "USB-C", ox + 1160, oy + 264, 2, silk)

    plug = Rect(1196, 466, 94, 84)
    box(plug, fill=white, outline=hex_rgb("8e97a4"), radius=4)
    for pin in range(4):
        draw.rectangle([ox + 1208 + pin * 20, oy + 486, ox + 1222 + pin * 20,
                        oy + 530], fill=dark)
    text_right(canvas, "4P-1.25", ox + 1182, oy + 492, 1, silk)

    # CH340, the same bridge the docs name for flashing
    chip = Rect(908, 200, 112, 132)
    box(chip, fill=hex_rgb("141a24"), radius=2)
    for pin in range(8):
        pin_y = oy + 212 + pin * 15
        draw.line([(ox + 898, pin_y), (ox + 908, pin_y)], fill=hex_rgb("4a5464"),
                  width=2)
        draw.line([(ox + 1020, pin_y), (ox + 1030, pin_y)], fill=hex_rgb("4a5464"),
                  width=2)
    text(canvas, "CH340", ox + 916, oy + 252, 1, silk)

    # the two buttons the flashing docs tell you to press
    for rect, name in [(Rect(188, 654, 48, 68), "RESET"),
                       (Rect(262, 654, 48, 68), "BOOT")]:
        box(rect)
        box(Rect(rect.x + 8, rect.y + 10, 32, 32), fill=cap, radius=2, width=1)
        text(canvas, name, ox + rect.x - 6, oy + rect.y - 18, 1, silk)

    # speaker, battery switch, battery connector along the bottom-right edge
    box(Rect(752, 682, 84, 50), fill=white, outline=hex_rgb("8e97a4"))
    box(Rect(766, 694, 56, 26), fill=dark, radius=2, width=1)
    text(canvas, "SPK", ox + 774, oy + 654, 1, silk)

    box(Rect(930, 672, 104, 60))
    box(Rect(950, 682, 64, 40), fill=cap, radius=2, width=1)
    text(canvas, "BAT SW", ox + 950, oy + 646, 1, silk)

    box(Rect(1048, 676, 96, 56), fill=white, outline=hex_rgb("8e97a4"))
    box(Rect(1062, 688, 68, 32), fill=dark, radius=2, width=1)
    text(canvas, "BATT", ox + 1062, oy + 646, 1, silk)

    text(canvas, "ESP32-2432S024R", ox + 30, oy + 566, 1, hex_rgb("6f7889"))
    return canvas


def badge(canvas, x, y, number):
    """A numbered marker: the legend below carries the claim, not the drawing."""
    radius = 15
    ImageDraw.Draw(canvas).ellipse([x - radius, y - radius, x + radius, y + radius],
                                   fill=C_ACCENT, outline=hex_rgb("1c1d26"), width=2)
    label = str(number)
    text(canvas, label, x - text_width(label, 2) // 2, y - 7, 2, C_BG)


def dashed(canvas, start, end, color, dash=9, gap=7):
    draw = ImageDraw.Draw(canvas)
    (x0, y0), (x1, y1) = start, end
    span = max(1, int(((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5))
    for offset in range(0, span, dash + gap):
        a = offset / span
        b = min(1.0, (offset + dash) / span)
        draw.line([(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a),
                   (x0 + (x1 - x0) * b, y0 + (y1 - y0) * b)], fill=color, width=2)


# Numbered against the legend below. Every entry is a fact this repo states or a
# part the vendor's photo labels; the marker's position is nominal, because the
# repo records pins, and the back is traced rather than measured. The leader,
# when present, is the point on the board the marker is talking about.
FRONT_BADGES = [
    (278, 360, 1, (303, 360)),
    (278, 470, 2, (303, 470)),
    (1015, 360, 3, (987, 360)),
    (1330, 290, 8, (1294, 290)),
    (215, 664, 5, None),
]

REAR_BADGES = [
    (120, 690, 4, (184, 690)),
    (860, 262, 6, (902, 262)),
    (500, 364, 7, (452, 364)),
    (1116, 636, 9, (1116, 672)),
    (940, 96, 10, (884, 96)),
    (700, 690, 11, (748, 700)),
]

LEGEND = [
    "1  ILI9341 320x240 - CS 15, DC 2, backlight 27, no own reset",
    "2  XPT2046 resistive touch - CS 33, shares the SPI bus",
    "3  SPI - SCK 14, MISO 12, MOSI 13",
    "4  Three buttons - RESET, BOOT and BAT SW, all on the back",
    "5  RGB status LED - R 4, G 16, B 17, active low",
    "6  CH340 bridge - 1a86:7523, USB-OTG; DTR/RTS reset the ESP32",
    "7  ESP-WROOM-32, no PSRAM - sits behind the panel",
    "8  Backlight - its own switch at the side edge, not a button",
    "9  Battery - JST connector and slide switch; the sketch reads neither",
    "10 TF card slot - no SD code in the sketch",
    "11 Speaker, EXT IO, temp/humidity headers - unused by the sketch",
]


def board_annotated(screen):
    """Both faces, numbered against what the firmware and docs confirm."""
    canvas = Image.new("RGB", (2200, 2300), hex_rgb("1c1d26"))
    canvas.paste(board_render(screen, scale=2), (395, 90))
    canvas.paste(rear_render(), (395, 1010))

    text(canvas, "FRONT - the glass side, what the deck draws", 60, 100, 2,
         hex_rgb("8d93b0"))
    text(canvas, "BACK - module, connectors, all three buttons", 60, 1020, 2,
         hex_rgb("8d93b0"))

    for bx, by, number, leader in FRONT_BADGES:
        cx, cy = 455 + bx, 150 + by
        if leader:
            dashed(canvas, (cx, cy), (455 + leader[0], 150 + leader[1]),
                   hex_rgb("4a5464"))
        badge(canvas, cx, cy, number)

    for bx, by, number, leader in REAR_BADGES:
        cx, cy = 455 + bx, 1070 + by
        if leader:
            dashed(canvas, (cx, cy), (455 + leader[0], 1070 + leader[1]),
                   hex_rgb("4a5464"))
        badge(canvas, cx, cy, number)

    text(canvas, "WHAT THIS BOARD SHOWS, AND WHERE IT IS CONFIRMED", 60, 1930, 3,
         hex_rgb("e9eaf6"))
    for index, line in enumerate(LEGEND):
        col, row = (0, index) if index < 6 else (1, index - 6)
        text(canvas, line, 60 + col * 1100, 1980 + row * 42, 2, hex_rgb("c3cad6"))

    text(canvas,
         "pins: cyd_phone_deck.ino 67-82 / cyd_board_check.ino 32-41   //   "
         "CH340, DTR-RTS, buttons and backlight: measured 2026-10-30",
         60, 2240, 2, hex_rgb("8d93b0"))
    text(canvas,
         "rear layout traced from the vendor photo; front positions nominal",
         60, 2266, 2, hex_rgb("8d93b0"))
    return canvas


def sheet(images, labels, cols=3, scale=2, gap=24, caption=64):
    cell_w = W * scale + gap
    cell_h = H * scale + caption
    width = cols * cell_w + gap
    rows = (len(images) + cols - 1) // cols
    height = rows * cell_h + gap
    out = Image.new("RGB", (width, height), hex_rgb("0b0e14"))
    draw = ImageDraw.Draw(out)
    for i, (img, label) in enumerate(zip(images, labels)):
        cx = gap + (i % cols) * cell_w
        cy = gap + (i // cols) * cell_h
        text(out, label, cx + 2, cy, 2, hex_rgb("c3cad6"))
        out.paste(img.resize((W * scale, H * scale), Image.NEAREST), (cx, cy + 20))
        draw.rectangle([cx - 1, cy + 19, cx + W * scale, cy + 20 + H * scale],
                       outline=hex_rgb("242b38"))
    return out


# The annotated board is the image the landing page uses: it answers both "what
# does the screen look like" and "which pin is which". The screen files are the
# clean ones, one per screen at 2x, and they are what the RENDERS folder on Drive
# mirrors. CHARGING and LOWBAT exist so the sage and olive battery states appear
# somewhere in the set; the rest exercise vermilion, copper and acorn cream.
SCREENS = [
    ("screen-home.png", lambda: screen_home()),
    ("screen-theme.png", screen_theme),
    ("screen-board.png", lambda: screen_board()),
    ("screen-charging.png", lambda: screen_board(battery=54, charging=True)),
    ("screen-lowbat.png", lambda: screen_board(battery=12)),
    ("screen-detail.png", screen_detail),
    ("screen-settings.png", screen_settings),
    ("screen-calibrate.png", lambda: screen_calibrate(1)),
    ("screen-offline.png", screen_board_offline),
]


def stale_renders():
    """Committed PNGs that no longer match what the sketch draws, and how many
    were compared. Everything is rendered in memory; nothing is written."""
    board = board_annotated(screen_board())
    expected = [
        (os.path.join(HERE, "board.png"), board),
    ]
    for name, render in SCREENS:
        expected.append((os.path.join(HERE, name),
                         render().resize((W * 2, H * 2), Image.NEAREST)))

    root = os.path.dirname(HERE)
    problems = []
    for path, image in expected:
        shown = os.path.relpath(path, root)
        if not os.path.exists(path):
            problems.append("  %s is missing" % shown)
            continue
        with Image.open(path) as committed:
            if committed.size != image.size:
                problems.append("  %s is %dx%d, the sketch draws %dx%d"
                                % ((shown,) + committed.size + image.size))
            elif committed.convert("RGB").tobytes() != image.convert("RGB").tobytes():
                problems.append("  %s no longer matches the sketch" % shown)
    return problems, len(expected)


def main():
    parser = argparse.ArgumentParser(
        description="Render the deck's screens from cyd_phone_deck.ino.")
    parser.add_argument("--check", action="store_true",
                        help="validate the palettes and write no images")
    args = parser.parse_args()

    print("%-28s %d themes of %d colours, every hex packs to its RGB565"
          % (os.path.basename(SKETCH), len(THEMES), len(PALETTE)))
    if args.check:
        try:
            stale, compared = stale_renders()
        except OSError as exc:
            print("%-28s not checked: %s" % ("renders", exc))
        else:
            if stale:
                raise SystemExit("the committed renders are stale:\n%s\n"
                                 "run without --check to redraw them"
                                 % "\n".join(stale))
            print("%-28s %d images, all match the sketch" % ("renders", compared))
        print("check only: no images written")
        return

    board = board_annotated(screen_board())
    path = os.path.join(HERE, "board.png")
    board.save(path)
    print("board.png                    %dx%d  %d B" % (board.width, board.height,
                                                       os.path.getsize(path)))

    for name, render in SCREENS:
        out = render().resize((W * 2, H * 2), Image.NEAREST)
        target = os.path.join(HERE, name)
        out.save(target)
        print("%-28s %dx%d  %d B" % (name, out.width, out.height,
                                     os.path.getsize(target)))


if __name__ == "__main__":
    main()
