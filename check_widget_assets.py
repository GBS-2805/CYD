#!/usr/bin/env python3
"""Fail when a widget's bundled copy in the app's assets drifts from the source.

android-companion/app/build.gradle copies each <widget>/widget.json and the
sketch that descriptor names into
android-companion/app/src/main/assets/widgets/<id>/ before every build, and
deletes anything under there that no descriptor claims. So that asset tree is
generated output and gitignored: no copy of it is ever committed, and a re-sync
leaves `git status` clean. WidgetCatalog reads the built copy at runtime, and the
app's toolchain.sh reads the sketch back out of the APK and compares md5s. It
goes stale the moment a sketch is edited without a rebuild, which is the gap
CONVENTIONS.md names and this file exists to close.

  ./check_widget_assets.py    exit 0 when every bundled copy matches, 1 otherwise
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_headroom as headroom  # noqa: E402  (one hash for every checker)

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "android-companion/app/src/main/assets/widgets"
NOT_A_WIDGET = {"android-companion"}


# One hash for every checker; see check_headroom.md5.
md5 = headroom.md5

# The app's own palette, so a deck cannot describe a page the phone does not.
PALETTE = ROOT / "android-companion/app/src/main/java/dev/phonedesk/companion/Palette.java"
# The twelve slots in the order both sketches declare them.
SLOTS = ["bg", "tile", "tileHi", "edge", "text", "dim",
         "accent", "accentDim", "pink", "purple", "yellow", "green"]


def argb(source, name):
    """`Palette.X = 0xFFF1F0EB` as `#f1f0eb`, or None when it is not there."""
    found = re.search(r"\b%s\s*=\s*0x([0-9A-Fa-f]{8})\b" % name, source)
    return "#" + found.group(1)[2:].lower() if found else None


DECKS = ROOT / "artifacts/decks"
RENDER_DECKS = ROOT / "artifacts/deck_screens.py"


def deck_page_problems():
    """Every page both decks can show is drawn, and the copies on disk are today's.

    The app's frames are held to the source by check_renders.py; the decks had
    no such gate, so a sketch could change and leave a picture of the old glass
    behind. This draws every page again into a temp dir and compares bytes.
    """
    problems = []
    if not RENDER_DECKS.is_file() or not DECKS.is_dir():
        return problems
    import subprocess
    import tempfile
    with tempfile.TemporaryDirectory() as temp:
        drawn = Path(temp)
        done = subprocess.run([sys.executable, str(RENDER_DECKS), "--out", str(drawn)],
                              capture_output=True, text=True)
        if done.returncode != 0:
            tail = (done.stderr or done.stdout).strip().splitlines()
            problems.append("%s failed, so no deck page could be checked: %s"
                            % (shown(RENDER_DECKS), tail[-1] if tail else "no output"))
            return problems
        pages = sorted(drawn.glob("*.png"))
        if not pages:
            problems.append("%s drew nothing" % shown(RENDER_DECKS))
        for page in pages:
            on_disk = DECKS / page.name
            if not on_disk.is_file():
                problems.append("%s is missing; draw it with artifacts/deck_screens.py"
                                % shown(on_disk))
            elif on_disk.read_bytes() != page.read_bytes():
                problems.append("%s is stale: md5 %s drawn now, %s on disk"
                                % (shown(on_disk), md5(page), md5(on_disk)))
    return problems


def palette_schemes(source):
    """The three site schemes in `Palette.java`, in the order `Palette.SCHEMES` lists them.

    Each is seventeen ints: the band, its ink, dim, line, hover, the accent and its
    ink, then the dark page's five and the light page's five. A deck's palette row
    carries the page and the body ink, so those are what this reads out of it.
    """
    found = {}
    for match in re.finditer(
            r"static final Scheme (\w+) = new Scheme\(\"(\w+)\", new int\[\] \{(.*?)\}\);",
            source, re.S):
        values = re.findall(r"0x([0-9A-Fa-f]{8})", match.group(3))
        if len(values) < 17:
            continue
        found[match.group(1)] = {
            "id": match.group(2),
            "dark": "#" + values[7][2:].lower(),
            "darkInk": "#" + values[8][2:].lower(),
            "light": "#" + values[12][2:].lower(),
            "lightInk": "#" + values[13][2:].lower(),
        }
    return [found[name] for name in ("GREEN_SCHEME", "MOSS_SCHEME", "NAVY_SCHEME")
            if name in found]


def palette_problems(folders):
    """Every deck row says the colour the app's palette says, or names the one that does not.

    A sketch is pasted on its own into ArduinoDroid, so its table cannot import the
    app's — it is a second copy. Rows 0 and 1 are the neutral pair and are held to
    `LIGHT_FIELD`/`LIGHT_TEXT` and `DARK_FIELD`/`DARK_TEXT`; rows 2 to 7 are the site's
    three palettes twice and are held to each scheme's own page and body ink, at
    `2 + palette * 2 + (light ? 0 : 1)`. A swatch that names a row the glass paints
    differently is the drift this catches.
    """
    problems = []
    if not PALETTE.is_file():
        return problems
    source = PALETTE.read_text(encoding="utf-8")
    neutral = {"paper": ("LIGHT_FIELD", "LIGHT_TEXT"), "ink": ("DARK_FIELD", "DARK_TEXT")}
    schemes = palette_schemes(source)
    for folder in folders:
        sketch = folder / (folder.name + ".ino")
        if not sketch.is_file():
            continue
        text = sketch.read_text(encoding="utf-8", errors="replace")
        start = text.find("THEMES[]")
        if start < 0:
            continue                      # a widget with no palette table
        block = text[start:text.find("};", start)]
        found = re.findall(r"0x[0-9A-Fa-f]{4}\s*/\*\s*(#[0-9a-fA-F]{6})\s*\*/", block)
        rows = [found[i:i + len(SLOTS)]
                for i in range(0, len(found) - len(SLOTS) + 1, len(SLOTS))]
        if len(rows) < len(neutral):
            continue                      # not a twelve-slot table; check_docs has it
        for name, (ground_const, ink_const) in neutral.items():
            row = rows[0 if name == "paper" else 1]
            for slot, constant, word in ((0, ground_const, "page"), (4, ink_const, "ink")):
                want = argb(source, constant)
                got = row[slot]
                if want and got.lower() != want.lower():
                    problems.append("%s draws the %s %s as %s, but Palette.%s is %s"
                                    % (shown(sketch), name, word, got, constant, want))
        for index, scheme in enumerate(schemes):
            for light in (True, False):
                row = 2 + index * 2 + (0 if light else 1)
                if row >= len(rows):
                    continue
                which = "light" if light else "dark"
                for slot, key, word in ((0, which, "page"),
                                        (4, which + "Ink", "ink")):
                    want = scheme[key]
                    got = rows[row][slot]
                    if want and got.lower() != want.lower():
                                                problems.append(
                            "%s draws the %s %s %s as %s, but "
                            "Palette.%s_SCHEME says %s"
                            % (shown(sketch), scheme["id"], which, word,
                               got, scheme["id"].upper(), want))
    return problems


# The deck grid. Both sketches declare it and artifacts/deck_screens.py copies it,
# so these two numbers are written down four times; this is what keeps them equal.
# A card runs MARGIN to SCREEN_W - MARGIN, and everything inside one starts CARD_PAD
# in from that edge.
GRID_MARGIN = 12
GRID_PAD = 10
# The band above the content, where the wordmark, the link pill and the back button
# sit. They are not cards, so the margin does not apply to them, and a deck that
# declares its own HEADER_H says where its band ends.
GRID_BAND = 34


def deck_grid(text):
    """A sketch's SCREEN_W, margin and inset, the last two defaulted to the grid.

    SCREEN_W has to be there, because it is what the right margin is measured from.
    A sketch that names MARGIN and CARD_PAD is read at its word -- and refused when
    the word is not the deck grid's -- while one that draws the same edges from
    literals is measured against the grid rather than told to declare it.
    """
    grid = {"MARGIN": GRID_MARGIN, "CARD_PAD": GRID_PAD}
    for name in ("SCREEN_W", "MARGIN", "CARD_PAD"):
        found = re.search(r"\b%s\s*=\s*(-?\d+)\s*;" % name, text)
        if found:
            grid[name] = int(found.group(1))
        elif name == "SCREEN_W":
            return name, None
    return None, grid


def rect_constants(text):
    """Every `static const Rect NAME = {...}`, and each row of a `Rect NAME[3]`."""
    for found in re.finditer(r"static\s+const\s+Rect\s+(\w+)\s*(\[[^\]]*\])?\s*=\s*\{", text):
        name = found.group(1)
        start = found.end() - 1
        depth = 0
        body = None
        for index in range(start, len(text)):
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
                if depth == 0:
                    body = text[start:index + 1]
                    break
        if body is None:
            continue
        rows = re.findall(r"\{([^{}]*)\}", body) if found.group(2) else [body[1:-1]]
        for row in rows:
            numbers = [int(number) for number in re.findall(r"-?\d+", row)]
            if len(numbers) == 4:
                yield name, numbers


def grid_problems_for(sketch):
    """Every rect in one deck sits on the page grid, or says which edge left it.

    The grid pass settled the deck pages on one margin and one card inset; a rule
    that only lives in the prose is broken again by the next page. This reads the
    layout constants out of the sketch rather than trusting a comment: the margin
    and inset have to be the grid's own numbers, no rect may cross either margin,
    no rect may invent an inset between the two grid lines, and a rect that starts
    on the margin and is wider than half the column has to reach the other side.
    Touch boxes (`_HIT`) and the band are exempt because they are slop and chrome,
    not cards, and a sketch holding no Rect at all is not a page.
    """
    text = sketch.read_text(encoding="utf-8", errors="replace")
    rects = list(rect_constants(text))
    if not rects:
        return []
    missing, grid = deck_grid(text)
    if missing:
        return ["%s is not on the page grid: it declares no %s"
                % (shown(sketch), missing)]

    problems = []
    if grid["MARGIN"] != GRID_MARGIN or grid["CARD_PAD"] != GRID_PAD:
        problems.append("%s sets MARGIN %d and CARD_PAD %d; the deck grid is %d and %d"
                        % (shown(sketch), grid["MARGIN"], grid["CARD_PAD"],
                           GRID_MARGIN, GRID_PAD))

    band = GRID_BAND
    band_line = re.search(r"\bHEADER_H\s*=\s*(-?\d+)\s*;", text)
    if band_line:
        band = int(band_line.group(1))
    left = grid["MARGIN"]
    right = grid["SCREEN_W"] - grid["MARGIN"]
    inset_left = left + grid["CARD_PAD"]
    inset_right = right - grid["CARD_PAD"]

    for name, (x, y, w, h) in rects:
        if name.endswith("_HIT") or y + h <= band:
            continue                     # touch slop and the band, not cards
        if x < left or x + w > right:
            problems.append("%s: %s spans %d..%d, past the page margin %d..%d"
                            % (shown(sketch), name, x, x + w, left, right))
        elif left < x < inset_left:
            problems.append("%s: %s starts at %d, between the margin %d and the card inset %d"
                            % (shown(sketch), name, x, left, inset_left))
        elif inset_right < x + w < right:
            problems.append("%s: %s ends at %d, between the card inset %d and the margin %d"
                            % (shown(sketch), name, x + w, inset_right, right))
        elif x == left and w * 2 > right - left and x + w != right:
            problems.append("%s: %s is a card from %d to %d, so it does not reach the "
                            "margin at %d" % (shown(sketch), name, left, x + w, right))
    return problems


def grid_problems(folders):
    """Every deck sketch on the grid, deck by deck."""
    problems = []
    for folder in folders:
        sketch = folder / (folder.name + ".ino")
        if sketch.is_file():
            problems.extend(grid_problems_for(sketch))
    return problems


# Where a `.ino` may live: in the widget folder that owns it, and nowhere else.
# The copy under app/src/main/assets is the one exception, because Gradle writes it
# from those folders and the comparison above holds it byte for byte. A third copy
# is a sketch nobody compiles and everybody might open — `dist/` carried one three
# layout generations old until 2026-11-04.
BUILD_DIRS = {"build", ".git", ".scratch", ".gradle", "node_modules"}


def stray_sketch_problems(folders):
    """Every .ino in the tree is some widget's own sketch, and there is one of each."""
    problems = []
    allowed = []
    for folder in folders:
        try:
            meta = json.loads((folder / "widget.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            meta = {}
        name = str(meta.get("sketch") or (folder.name + ".ino")).strip()
        allowed.append((folder / name, folder))
    for sketch in sorted(ROOT.rglob("*.ino")):
        if BUILD_DIRS & set(sketch.relative_to(ROOT).parts) or ASSETS in sketch.parents:
            continue
        if any(sketch == home for home, _ in allowed):
            continue
        twin = next((home for home, _ in allowed if home.name == sketch.name), None)
        if twin is not None:
            problems.append("%s is a second copy of %s; a sketch lives only in its widget folder"
                            % (shown(sketch), shown(twin)))
        else:
            problems.append("%s is a .ino outside any widget folder, so nothing compiles or ships it"
                            % shown(sketch))
    return problems


def shown(path):
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def widget_folders():
    for folder in sorted(ROOT.iterdir()):
        if not folder.is_dir() or folder.name in NOT_A_WIDGET:
            continue
        if (folder / "widget.json").is_file():
            yield folder


def main():
    if not ASSETS.is_dir():
        print("%s is missing; build the app once so Gradle copies the widgets in"
              % shown(ASSETS))
        return 1

    on_disk = {p.name for p in ASSETS.iterdir() if p.is_dir()}
    expected = set()
    rows = []
    problems = []

    for folder in widget_folders():
        descriptor = folder / "widget.json"
        try:
            meta = json.loads(descriptor.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            rows.append((folder.name, "-", "-", "descriptor unreadable"))
            problems.append("%s does not parse: %s" % (shown(descriptor), exc))
            continue

        widget_id = str(meta.get("id") or "").strip()
        if not widget_id:
            rows.append((folder.name, "-", "-", "descriptor has no id"))
            problems.append("%s has no id, so syncWidgets would fail the build"
                            % shown(descriptor))
            continue
        expected.add(widget_id)

        sketch_name = str(meta.get("sketch") or (folder.name + ".ino")).strip()
        sketch = folder / sketch_name
        if not sketch.is_file():
            rows.append((folder.name, sketch_name, "-", "sketch missing"))
            problems.append("%s names %s, which is not in %s"
                            % (shown(descriptor), sketch_name, shown(folder)))
            continue

        stale = []
        for name, source in (("widget.json", descriptor), (sketch_name, sketch)):
            bundled = ASSETS / widget_id / name
            if not bundled.is_file():
                stale.append("%s is missing; copy it from %s"
                             % (shown(bundled), shown(source)))
            elif bundled.read_bytes() != source.read_bytes():
                stale.append("%s is stale: md5 %s in the source, %s in the bundle"
                             % (shown(bundled), md5(source), md5(bundled)))
        rows.append((folder.name, sketch_name, md5(sketch)[:8],
                     "in sync" if not stale else "OUT OF SYNC"))
        problems.extend(stale)

    folders = list(widget_folders())
    palette = palette_problems(folders)
    grid = grid_problems(folders)
    pages = deck_page_problems()
    stray = stray_sketch_problems(folders)
    problems.extend(palette)
    problems.extend(grid)
    problems.extend(pages)
    problems.extend(stray)

    if not rows:
        print("no widget.json found under %s" % shown(ROOT))
        return 1

    for name, sketch_name, digest, state in rows:
        print("%-18s %-22s %-9s %s" % (name, sketch_name, digest, state))
    for line in problems:
        print("  - %s" % line)
    for name in sorted(on_disk - expected):
        print("  - %s exists but no descriptor claims id %r"
              % (shown(ASSETS / name), name))

    broken = (sum(1 for row in rows if row[3] != "in sync")
              + len(on_disk - expected) + len(palette) + len(grid) + len(pages)
              + len(stray))
    if broken:
        print("\n%d problem%s. The app build re-syncs from the widget folder, so "
              "run one, or copy the named file(s) over."
              % (broken, "" if broken == 1 else "s"))
        return 1

    print("\n%d widget%s bundled, every copy matches"
          % (len(rows), "" if len(rows) == 1 else "s"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
