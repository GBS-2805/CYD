#!/usr/bin/env python3
"""Draw every page both decks can put on the glass, at 320x240, per theme.

`artifacts/deck_palette.py` proves each sketch's palette table. This draws the
screens themselves: the draw functions from `phone_deck_cyd24R.ino` and
`spotify_deck_cyd24R.ino`, ported call for call — the same rects, the same strings,
the same 6*size x 8*size character grid, the colours from that sketch's own
`THEMES` row.

Three things are stand-ins, and all three are visible if you know the glass:

  * the glyphs. Adafruit's 5x7 is a bitmap inside the library; this draws DejaVu
    Sans Mono on the same cell, so every string lands where the panel puts it and
    takes its width, but the letterforms are not Adafruit's.
  * the seam of a rounded corner and the 1px ring of `drawRoundRect`, which PIL
    draws by a slightly different rule.
  * the *content*: a frame needs a notification, a track and a battery, so the
    sample below stands in for what a phone would send. The layout does not.

It is a reconstruction, not a capture. Nothing here has run on a board.

    python3 artifacts/deck_screens.py     # writes artifacts/decks/<deck>-<page>-<theme>.png
"""
import os
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
OUT = HERE / "decks"
if "--out" in sys.argv:                       # check_widget_assets.py draws into a temp dir
    OUT = Path(sys.argv[sys.argv.index("--out") + 1])
DECKS = {"phone": ROOT / "phone_deck_cyd24R/phone_deck_cyd24R.ino",
         "spotify": ROOT / "spotify_deck_cyd24R/spotify_deck_cyd24R.ino"}

SLOTS = ["bg", "tile", "tileHi", "edge", "text", "dim",
         "accent", "accentDim", "pink", "purple", "yellow", "green"]

W, H = 320, 240
HEADER_H = 34
CORNER_CARD = 10
CORNER_BUTTON = 8

# ------------------------------------------------------------------ content ---
# What a phone would have sent. The frames are the layout; this is the filling.
DEVICE_TAG = "A1B2"
CLOCK = (9, 41, 7)          # hour, minute, second
BATTERY = 82
CHARGING = False
UNREAD = 2
NOTICES = [
    dict(app="MESSAGES", title="Running ten minutes late", at=3600, unread=True),
    dict(app="MAIL", title="Invoice from the workshop", at=7200, unread=False),
]
TRACK = dict(valid=True, playing=True, position=97, duration=244,
             title="Midnight City", artist="M83", album="Hurry Up, We're Dreaming")


# -------------------------------------------------------------------- panel ---
class Panel:
    """The subset of Adafruit_GFX these two sketches draw with, on a PIL image."""

    def __init__(self, slots):
        self.slots = slots
        self.c = {k: tuple(int(v[i:i + 2], 16) for i in (1, 3, 5))
                  for k, v in slots.items()}
        self.image = Image.new("RGB", (W, H), self.c["bg"])
        self.draw = ImageDraw.Draw(self.image)

    def fill_screen(self, slot):
        self.draw.rectangle([0, 0, W, H], fill=self.c[slot])

    def fill_rect(self, x, y, w, h, slot):
        self.draw.rectangle([x, y, x + w - 1, y + h - 1], fill=self.c[slot])

    def hline(self, x, y, w, slot):
        self.draw.line([x, y, x + w - 1, y], fill=self.c[slot])

    def vline(self, x, y, h, slot):
        self.draw.line([x, y, x, y + h - 1], fill=self.c[slot])

    def fill_round(self, r, radius, slot):
        x, y, w, h = r
        self.draw.rounded_rectangle([x, y, x + w - 1, y + h - 1], radius,
                                    fill=self.c[slot])

    def ring(self, r, radius, slot):
        x, y, w, h = r
        self.draw.rounded_rectangle([x, y, x + w - 1, y + h - 1], radius,
                                    outline=self.c[slot])

    def fill_circle(self, cx, cy, radius, slot):
        self.draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                          fill=self.c[slot])

    def circle(self, cx, cy, radius, slot):
        self.draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                          outline=self.c[slot])

    def line(self, x0, y0, x1, y1, slot):
        self.draw.line([x0, y0, x1, y1], fill=self.c[slot])

    # --- text: getTextBounds() of the built-in font, 6*size per character ------
    @staticmethod
    def bounds(text, size):
        return 6 * size * len(text) - size, 8 * size

    @staticmethod
    def font(size):
        import matplotlib
        root = os.path.join(os.path.dirname(matplotlib.__file__), "mpl-data", "fonts", "ttf")
        return ImageFont.truetype(os.path.join(root, "DejaVuSansMono.ttf"), max(6, 7 * size))

    def text(self, string, x, y, size, fg, bg):
        font = self.font(size)
        step = 6 * size
        for i, ch in enumerate(string):
            cx = x + i * step
            self.draw.rectangle([cx, y, cx + step - 1, y + 8 * size - 1],
                                fill=self.c[bg])
            if ch != " ":
                self.draw.text((cx, y + 7 * size), ch, font=font, fill=self.c[fg],
                               anchor="ls")

    def text_at(self, string, x, y, size, fg, bg):
        self.text(string, x, y, size, fg, bg)

    def text_centered(self, string, r, size, fg, bg):
        bw, bh = self.bounds(string, size)
        self.text(string, r[0] + (r[2] - bw) // 2, r[1] + (r[3] - bh) // 2, size, fg, bg)

    def text_right(self, string, right, y, size, fg, bg):
        bw, _ = self.bounds(string, size)
        self.text(string, right - bw, y, size, fg, bg)

    def wrapped(self, string, x, y, width, size, fg, bg, max_lines):
        line_height = 8 * size + 3
        line, used, lines = "", 0, 0
        for word in string.split():
            candidate = (line + " " + word) if used else word
            if self.bounds(candidate, size)[0] > width and used:
                self.text(line, x, y + lines * line_height, size, fg, bg)
                lines += 1
                if lines >= max_lines:
                    return
                line, used = word, len(word)
            else:
                line, used = candidate, len(candidate)
        if used and lines < max_lines:
            self.text(line, x, y + lines * line_height, size, fg, bg)


def fit_text(text, max_chars):
    return text if len(text) <= max_chars else text[:max_chars - 3] + "..."


def battery_slot(level, charging):
    if level < 0:
        return "dim"
    if charging:
        return "accent"
    return "pink" if level < 30 else "green"


def age_text(seconds):
    if seconds < 60:
        return "now"
    if seconds < 3600:
        return "%dm" % (seconds // 60)
    if seconds < 86400:
        return "%dh" % (seconds // 3600)
    return "%dd" % (seconds // 86400)


def clock_text(seconds):
    return "%d:%02d" % (seconds // 60, seconds % 60)


# ------------------------------------------------------------------ phone -----
PHONE_HEADER_BACK = (6, 3, 34, 28)
PHONE_BACK_BTN = (6, 3, 34, 28)
PHONE_GEAR = (284, 5, 32, 24)
PHONE_LINK_PILL = (204, 6, 76, 22)
PHONE_SET_LINK_PILL = (234, 6, 76, 22)
PHONE_STATUS_CARD = (8, 40, 304, 76)
PHONE_NOTIF_ROW = [(8, 134, 304, 32), (8, 168, 304, 32), (8, 202, 304, 32)]
PHONE_HOME_TAB = [(10, 84, 300, 30), (10, 118, 300, 30), (10, 152, 300, 30)]
PHONE_HOME_PILL_A = (10, 52, 96, 18)
PHONE_HOME_PILL_B = (114, 52, 96, 18)
PHONE_TABS = ["MESSAGES", "SETTINGS", "CALIBRATE"]
PHONE_DETAIL_DISMISS = (12, 202, 148, 30)
PHONE_DETAIL_BACK = (168, 202, 140, 30)
PHONE_NOW_BACK = (8, 202, 304, 30)
PHONE_SET_BRI_DOWN = (12, 64, 146, 28)
PHONE_SET_BRI_UP = (162, 64, 146, 28)
PHONE_SET_CLEAR = (232, 106, 72, 26)
PHONE_SET_CAL = (8, 146, 304, 32)
PHONE_SET_CLOSE = (8, 186, 304, 34)
PHONE_BRIGHTNESS = 70


def gear(p, cx, cy, ink):
    p.circle(cx, cy, 5, ink)
    for dx, dy in ((0, -1), (1, -1), (1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1)):
        p.line(cx + dx * 5, cy + dy * 5, cx + dx * 7, cy + dy * 7, ink)


def link_pill(p, pill, linked=True):
    p.fill_round(pill, 11, "tile")
    p.ring(pill, 11, "accent" if linked else "edge")
    if linked:
        p.fill_circle(pill[0] + 12, pill[1] + 11, 3, "accent")
    else:
        p.circle(pill[0] + 12, pill[1] + 11, 3, "dim")
    p.text_at("LINKED" if linked else "OFFLINE", pill[0] + 20, pill[1] + 8, 1,
              "accent" if linked else "dim", "tile")


def phone_button(p, r, label, size, accent):
    face = "pink" if accent else "tile"
    p.fill_round(r, CORNER_BUTTON, face)
    p.ring(r, CORNER_BUTTON, face if accent else "edge")
    p.text_centered(label, r, size, "bg" if accent else "text", face)


def phone_header(p):
    p.fill_rect(0, 0, W, HEADER_H, "bg")
    p.hline(0, HEADER_H - 1, W, "edge")
    p.ring(PHONE_HEADER_BACK, 7, "edge")
    p.text_centered("<", PHONE_HEADER_BACK, 2, "text", "bg")
    p.text_at("PHONE DECK", 48, 5, 2, "text", "bg")
    p.text_at("esp32 // #%s" % DEVICE_TAG, 48, 21, 1, "dim", "bg")
    link_pill(p, PHONE_LINK_PILL)
    p.fill_round(PHONE_GEAR, 6, "tile")
    p.ring(PHONE_GEAR, 6, "edge")
    gear(p, PHONE_GEAR[0] + PHONE_GEAR[2] // 2, PHONE_GEAR[1] + PHONE_GEAR[3] // 2, "text")


def phone_status_card(p):
    r = PHONE_STATUS_CARD
    p.fill_round(r, CORNER_CARD, "tile")
    p.ring(r, CORNER_CARD, "edge")
    p.text_at("$ sys.status --", r[0] + 12, r[1] + 7, 1, "dim", "tile")
    state = "READY // ONLINE"
    bw, _ = p.bounds(state, 1)
    text_right = r[0] + r[2] - 12
    p.text_at(state, text_right - bw, r[1] + 7, 1, "accent", "tile")
    p.fill_circle(text_right - bw - 9, r[1] + 11, 3, "accent")
    p.hline(r[0] + 12, r[1] + 20, r[2] - 24, "edge")

    hh, mm, ss = CLOCK
    p.text_at("%02d:%02d" % (hh, mm), r[0] + 12, r[1] + 24, 4, "text", "tile")
    p.text_at("%02d" % ss, r[0] + 134, r[1] + 36, 1, "accent", "tile")
    p.text_at("TUE 4 NOV  //  phone time", r[0] + 12, r[1] + 60, 1, "dim", "tile")

    batt_x = r[0] + 236
    p.text_at("BATTERY", batt_x, r[1] + 26, 1, "dim", "tile")
    p.text_at("%d%%" % BATTERY, batt_x, r[1] + 36, 2, battery_slot(BATTERY, CHARGING), "tile")
    bar = (batt_x, r[1] + 56, 58, 6)
    p.ring(bar, 3, "edge")
    fill = max(2, (bar[2] - 2) * BATTERY // 100)
    p.fill_round((bar[0] + 1, bar[1] + 1, fill, bar[3] - 2), 2, battery_slot(BATTERY, CHARGING))


def phone_section_header(p):
    p.fill_circle(12, 125, 3, "purple")
    p.text_at("01 // NOTIFICATIONS", 20, 121, 1, "text", "bg")
    p.text_right("%d NEW" % UNREAD if UNREAD else "DIRECT ACCESS", W - 8, 121, 1,
                 "yellow" if UNREAD else "dim", "bg")


def phone_notice_row(p, slot):
    r = PHONE_NOTIF_ROW[slot]
    has = slot < len(NOTICES)
    p.fill_round(r, CORNER_CARD, "tile" if has else "bg")
    if not has:
        if slot > 0:
            return
        p.ring(r, CORNER_CARD, "edge")
        p.text_centered("no notifications", r, 1, "dim", "bg")
        return
    n = NOTICES[slot]
    p.ring(r, CORNER_CARD, "accentDim" if n["unread"] else "edge")

    icon = (r[0] + 8, r[1] + 6, 20, 20)
    p.fill_round(icon, 6, "tileHi")
    p.ring(icon, 6, "accentDim" if n["unread"] else "edge")
    p.text_centered(n["app"][0], icon, 2, "yellow" if n["unread"] else "dim", "tileHi")

    p.text_at(n["app"], r[0] + 36, r[1] + 4, 1, "accent" if n["unread"] else "dim", "tile")
    p.text_right(age_text(n["at"]), r[0] + r[2] - 8, r[1] + 4, 1, "dim", "tile")

    ink = "text" if n["unread"] else "dim"
    if p.bounds(n["title"], 2)[0] <= r[2] - 46:
        p.text_at(n["title"], r[0] + 36, r[1] + 14, 2, ink, "tile")
    else:
        p.text_at(fit_text(n["title"], 20), r[0] + 36, r[1] + 16, 1, ink, "tile")


def page_phone_home(p):
    p.fill_screen("bg")
    p.text_at("SYNC %02d:%02d // PHONE TIME" % CLOCK[:2], 10, 6, 1, "dim", "bg")
    p.text_right("BATTERY %d%%" % BATTERY, PHONE_GEAR[0] - 6, 6, 1,
                 battery_slot(BATTERY, CHARGING), "bg")
    p.fill_round(PHONE_GEAR, 6, "tile")
    p.ring(PHONE_GEAR, 6, "edge")
    gear(p, PHONE_GEAR[0] + PHONE_GEAR[2] // 2, PHONE_GEAR[1] + PHONE_GEAR[3] // 2, "text")
    p.text_at("PHONE DECK", 10, 20, 3, "text", "bg")

    badge_right = W - 10
    badge_w = 6 * len("LINKED") - 1
    p.fill_circle(badge_right - badge_w - 12, 36, 4, "accent")
    p.text_right("LINKED", badge_right, 32, 1, "accent", "bg")

    p.fill_round(PHONE_HOME_PILL_A, 9, "bg")
    p.ring(PHONE_HOME_PILL_A, 9, "edge")
    p.fill_circle(PHONE_HOME_PILL_A[0] + 11, PHONE_HOME_PILL_A[1] + 9, 2,
                 "yellow" if UNREAD else "accent")
    p.text_at("%d NEW" % UNREAD if UNREAD else "INBOX CLEAR", PHONE_HOME_PILL_A[0] + 20,
              PHONE_HOME_PILL_A[1] + 5, 1, "text", "bg")

    p.fill_round(PHONE_HOME_PILL_B, 9, "text")
    p.text_centered("THEME %02d" % (0 if p.slots is PHONE_THEMES["PAPER"] else 1),
                    PHONE_HOME_PILL_B, 1, "bg", "text")

    p.hline(10, 78, W - 20, "edge")
    for i in range(3):
        p.text_at("%02d" % (i + 1), PHONE_HOME_TAB[i][0] + 4, PHONE_HOME_TAB[i][1] + 11,
                  1, "dim", "bg")
        p.text_at(PHONE_TABS[i], PHONE_HOME_TAB[i][0] + 26, PHONE_HOME_TAB[i][1] + 8,
                  2, "text", "bg")
    p.hline(10, 224, W - 20, "edge")


def page_phone_board(p):
    p.fill_screen("bg")
    phone_header(p)
    phone_status_card(p)
    phone_section_header(p)
    for i in range(3):
        phone_notice_row(p, i)


def page_phone_now(p):
    p.fill_screen("bg")
    phone_header(p)
    card = (8, 40, 304, 96)
    p.fill_round(card, CORNER_CARD, "tile")
    p.ring(card, CORNER_CARD, "edge")
    p.text_at("02 // NOW PLAYING", 20, 46, 1, "dim", "tile")
    p.wrapped(TRACK["title"], 20, 62, 280, 2, "text", "tile", 2)
    p.text_at(TRACK["artist"], 20, 104, 1, "accent" if TRACK["playing"] else "dim", "tile")
    p.text_at(TRACK["album"], 20, 118, 1, "dim", "tile")

    bar = (8, 146, 304, 10)
    p.ring(bar, 5, "edge")
    fill = max(0, (bar[2] - 4) * TRACK["position"] // TRACK["duration"])
    if fill:
        p.fill_round((bar[0] + 2, bar[1] + 2, fill, bar[3] - 4), 3, "accent")
    p.text_at(clock_text(TRACK["position"]), 10, 160, 1, "dim", "bg")
    p.text_right(clock_text(TRACK["duration"]), W - 10, 160, 1, "dim", "bg")
    p.text_at("PLAYING // MIRRORED FROM THE PHONE", 10, 178, 1, "accent", "bg")
    phone_button(p, PHONE_NOW_BACK, "BACK", 2, False)


def page_phone_detail(p):
    p.fill_screen("bg")
    p.hline(0, HEADER_H - 1, W, "edge")
    n = NOTICES[0]
    p.fill_round(PHONE_BACK_BTN, 6, "tile")
    p.ring(PHONE_BACK_BTN, 7, "edge")
    p.text_centered("<", PHONE_BACK_BTN, 2, "text", "tile")
    p.text_at(n["app"], 48, 8, 2, "text", "bg")
    p.text_right(age_text(n["at"]), W - 12, 13, 1, "dim", "bg")
    p.wrapped(n["title"], 12, 44, W - 24, 2, "text", "bg", 2)
    p.hline(12, 88, W - 24, "edge")
    body = ("Sam is stuck on the northbound line and asks whether we can push the "
            "standup to ten. No objection from the workshop side; the bench is "
            "still open until lunch.")
    p.wrapped(body, 12, 98, W - 24, 1, "dim", "bg", 9)
    phone_button(p, PHONE_DETAIL_DISMISS, "DISMISS", 2, True)
    phone_button(p, PHONE_DETAIL_BACK, "BACK", 2, False)


def page_phone_settings(p):
    p.fill_screen("bg")
    p.hline(0, HEADER_H - 1, W, "edge")
    p.fill_round(PHONE_BACK_BTN, 6, "tile")
    p.ring(PHONE_BACK_BTN, 7, "edge")
    p.text_centered("<", PHONE_BACK_BTN, 2, "text", "tile")
    p.text_at("SETTINGS", 48, 8, 2, "text", "bg")
    link_pill(p, PHONE_SET_LINK_PILL)

    p.fill_round((8, 38, 304, 58), CORNER_CARD, "tile")
    p.ring((8, 38, 304, 58), CORNER_CARD, "edge")
    p.text_at("BRIGHTNESS", 18, 44, 1, "dim", "tile")
    p.text_right("%d%%" % PHONE_BRIGHTNESS, 300, 46, 2, "accent", "tile")
    phone_button(p, PHONE_SET_BRI_DOWN, "-", 3, False)
    phone_button(p, PHONE_SET_BRI_UP, "+", 3, False)

    p.fill_round((8, 102, 304, 38), CORNER_CARD, "tile")
    p.ring((8, 102, 304, 38), CORNER_CARD, "edge")
    p.text_at("NOTIFICATIONS", 18, 108, 1, "dim", "tile")
    p.text_at("%d waiting" % len(NOTICES), 18, 120, 1, "text", "tile")
    phone_button(p, PHONE_SET_CLEAR, "CLEAR", 1, False)

    phone_button(p, PHONE_SET_CAL, "CALIBRATE TOUCH", 1, False)
    phone_button(p, PHONE_SET_CLOSE, "CLOSE", 2, True)
    p.text_at("Galaxy S23", 12, 226, 1, "purple", "bg")
    p.text_right("hold gear = forget", W - 12, 226, 1, "dim", "bg")


PHONE_PAGES = [
    ("home", page_phone_home),
    ("board", page_phone_board),
    ("now", page_phone_now),
    ("detail", page_phone_detail),
    ("settings", page_phone_settings),
    ("calibration", None),      # filled in below, declared after the crosshair
]
PHONE_THEMES = None


def phone_calibration(p):
    p.fill_screen("bg")
    x, y = 26, 26
    p.hline(x - 11, y, 23, "text")
    p.vline(x, y - 11, 23, "text")
    p.circle(x, y, 7, "accent")
    p.fill_circle(x, y, 1, "accent")
    p.text_at("CALIBRATE 1/3", 10, 10, 2, "accent", "bg")
    p.text_at("Tap the dot in this corner", 10, 36, 1, "text", "bg")
    p.text_at("order: TL TR BL, then done", 10, 48, 1, "dim", "bg")
    p.text_at("raw", 10, 212, 1, "dim", "bg")
    p.text_at("hold 2s anywhere = cancel", 10, 226, 1, "dim", "bg")
    for i in range(3):
        cx = 250 + i * 18
        if i < 1:
            p.fill_circle(cx, 22, 5, "accent")
        else:
            p.circle(cx, 22, 5, "edge")


PHONE_PAGES[5] = ("calibration", phone_calibration)

# ---------------------------------------------------------------- spotify -----
SPOTIFY_PREV = (8, 152, 96, 52)
SPOTIFY_PLAY = (112, 152, 96, 52)
SPOTIFY_NEXT = (216, 152, 96, 52)
SPOTIFY_SETUP = (200, 206, 112, 30)
SPOTIFY_SHORT = ["WiFi network name", "WiFi password", "Spotify client id",
                 "Client secret (may be blank)", "Spotify refresh token"]
SPOTIFY_TRACK = dict(title="Midnight City", artist="M83", device="phone",
                     position=97, duration=244, playing=True)
SPOTIFY_THEMES = None


def spotify_header(p, right="PHONE", right_slot="dim"):
    p.fill_rect(0, 0, W, 32, "bg")
    p.text_at("SPOTIFY DECK", 12, 10, 2, "text", "bg")
    p.text_right(right, W - 12, 13, 1, right_slot, "bg")
    p.hline(0, 31, W, "edge")


def spotify_chip(p, r, label, size, filled):
    face = "pink" if filled else "tile"
    p.fill_round(r, CORNER_BUTTON, face)
    p.ring(r, CORNER_BUTTON, "edge")
    p.text_centered(label, r, size, "bg" if filled else "text", face)


def spotify_crosshair(p, x, y):
    p.fill_circle(x, y, 6, "text")
    p.circle(x, y, 12, "edge")
    p.circle(x, y, 20, "edge")


def page_spotify_now(p):
    p.fill_screen("bg")
    spotify_header(p)

    card = (8, 40, 304, 104)
    p.fill_round(card, CORNER_CARD, "tile")
    p.ring(card, CORNER_CARD, "edge")
    p.wrapped(SPOTIFY_TRACK["title"], 18, 50, 284, 2, "text", "tile", 2)
    p.text_at(SPOTIFY_TRACK["artist"], 18, 94, 1, "dim", "tile")

    p.fill_rect(18, 112, 284, 6, "tile")
    p.fill_round((18, 112, 284, 6), 3, "tileHi")
    filled = 284 * SPOTIFY_TRACK["position"] // SPOTIFY_TRACK["duration"]
    p.fill_round((18, 112, filled, 6), 3, "text")
    p.text_at(clock_text(SPOTIFY_TRACK["position"]), 18, 121, 1, "dim", "tile")
    p.text_right(clock_text(SPOTIFY_TRACK["duration"]), 302, 121, 1, "dim", "tile")

    spotify_chip(p, SPOTIFY_PREV, "|<<", 2, False)
    spotify_chip(p, SPOTIFY_PLAY, "PAUSE" if SPOTIFY_TRACK["playing"] else "PLAY", 2, True)
    spotify_chip(p, SPOTIFY_NEXT, ">>|", 2, False)

    p.fill_rect(0, 204, W, 36, "bg")
    p.text_at(SPOTIFY_TRACK["device"], 12, 210, 1, "dim", "bg")
    p.text_at("hold to change it", 12, 224, 1, "dim", "bg")
    p.fill_round(SPOTIFY_SETUP, CORNER_BUTTON, "tile")
    p.ring(SPOTIFY_SETUP, CORNER_BUTTON, "edge")
    p.text_centered("SETUP", SPOTIFY_SETUP, 1, "dim", "tile")


def page_spotify_setup(p):
    p.fill_screen("bg")
    spotify_header(p, "SETUP", "dim")
    p.text_at("on the phone, no laptop", 12, 40, 1, "dim", "bg")
    p.text_at('1  join WiFi "Spotify Deck"', 12, 56, 1, "text", "bg")
    p.text_at("2  open http://192.168.4.1", 12, 70, 1, "text", "bg")
    p.text_at("3  fill in the page", 12, 84, 1, "text", "bg")

    p.fill_round((8, 102, 304, 96), CORNER_CARD, "tile")
    p.ring((8, 102, 304, 96), CORNER_CARD, "edge")
    p.text_at("OR SERIAL  1/5", 18, 110, 1, "dim", "tile")
    p.wrapped(SPOTIFY_SHORT[0], 18, 128, 284, 1, "text", "tile", 2)
    p.fill_rect(18, 184, 284, 10, "tile")
    p.text_at("workshop", 18, 184, 1, "text", "tile")

    p.text_at("192.168.4.1", 12, 214, 1, "dim", "bg")
    p.text_right("192.168.1.24", W - 12, 214, 1, "dim", "bg")
    p.fill_rect(0, 224, W, 16, "bg")
    p.text_at("no phone joined yet", 12, 228, 1, "dim", "bg")


def page_spotify_calibration(p):
    p.fill_screen("bg")
    p.text_at("CALIBRATE", 12, 10, 2, "text", "bg")
    p.text_right("TOUCH", W - 12, 13, 1, "dim", "bg")
    p.hline(0, 31, W, "edge")
    p.text_at("tap the dot", 12, 40, 1, "dim", "bg")
    p.text_at("hold 2 s to cancel", 12, 54, 1, "dim", "bg")
    spotify_crosshair(p, 26, 26)
    p.text_at("1 / 3", 12, 214, 1, "dim", "bg")


SPOTIFY_PAGES = [
    ("now", page_spotify_now),
    ("setup", page_spotify_setup),
    ("calibration", page_spotify_calibration),
]


# -------------------------------------------------------------------- main ---
def themes(sketch):
    text = sketch.read_text(encoding="utf-8")
    start = text.find("THEMES[]")
    if start < 0:
        raise SystemExit("%s has no THEMES[] table" % sketch.name)
    block = text[start:text.find("};", start)]
    found = re.findall(r"0x[0-9A-Fa-f]{4}\s*/\*\s*(#[0-9a-fA-F]{6})\s*\*/", block)
    if len(found) < 24:
        raise SystemExit("%s: %d hex comments, wanted 24" % (sketch.name, len(found)))
    return {"PAPER": dict(zip(SLOTS, found[:12])),
            "INK": dict(zip(SLOTS, found[12:24]))}


def render(deck, pages, table, out_dir):
    wrote = []
    for theme, slots in table.items():
        for name, draw in pages:
            panel = Panel(slots)
            draw(panel)
            path = out_dir / ("%s-%s-%s.png" % (deck, name, theme.lower()))
            panel.image.save(path)
            wrote.append(path)
    return wrote


def main():
    global PHONE_THEMES, SPOTIFY_THEMES
    OUT.mkdir(exist_ok=True)
    PHONE_THEMES = themes(DECKS["phone"])
    SPOTIFY_THEMES = themes(DECKS["spotify"])
    wrote = []
    for deck, pages, table in (("phone", PHONE_PAGES, PHONE_THEMES),
                               ("spotify", SPOTIFY_PAGES, SPOTIFY_THEMES)):
        wrote += render(deck, pages, table, OUT)
    print("%d page%s drawn: every page both decks can show, both themes"
          % (len(wrote), "" if len(wrote) == 1 else "s"))
    for path in wrote:
        try:
            print("  %s" % path.relative_to(ROOT).as_posix())
        except ValueError:
            pass                                # drawn into a checker's temp dir
    return 0


if __name__ == "__main__":
    sys.exit(main())
