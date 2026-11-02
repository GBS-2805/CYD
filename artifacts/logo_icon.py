#!/usr/bin/env python3
"""Turn the owner's logo.svg into the app's adaptive icon, and preview it.

The four `d` strings are copied out of `logo-source.svg` unchanged — Android's
pathData is SVG's own grammar, so the artwork ships verbatim inside a <group>
that only scales and centres it. This script writes the four resource files and
rasterises the result through the same transform for review.

The mark is the owner's **G** — G for ghost — and the artwork is black ink on a
white ground: one spiral, the C, with the ghost sitting in the gap between its
turns and the ghost's eyes and mouth back in ink inside that gap. Every white
pixel in the file is therefore the canvas showing through the spiral, not a
shape, so the top bar takes the ink alone — one PNG per density, its band filling
the gap — and the ghost reads as the band seen through the C. Drawing the ink
white on a dark field instead makes a white ring around a black blob, which is
not the artwork.

    python3 artifacts/logo_icon.py            write the res files and previews
    python3 artifacts/logo_icon.py --dry-run  only print the geometry it would use

The scale keeps the whole mark inside the 72dp safe zone of the 108dp adaptive
canvas, which is what the icon this replaces did: no launcher mask may crop the
letter's counter.
"""

import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SVG = os.path.join(HERE, "logo-source.svg")
RES = os.path.join(ROOT, "android-companion", "app", "src", "main", "res")

CANVAS = 108.0          # the adaptive-icon canvas, in dp
SAFE = 72.0             # what every launcher mask is guaranteed to show
MARK_DP = 36.0          # the top bar draws the mark this wide
DENSITIES = ((1.0, "mdpi"), (1.5, "hdpi"), (2.0, "xhdpi"),
             (3.0, "xxhdpi"), (4.0, "xxxhdpi"))
INK = "#000000"         # the logo's own ink
GROUND = "#FFFFFF"      # the logo's own ground, and the file's

TOKEN = re.compile(r"([MmLlCcAaZzHhVv])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)")


# ------------------------------------------------------------------ svg ----

def svg_paths():
    """The four `d` strings, in document order, plus the viewBox."""
    text = open(SVG).read()
    view = re.search(r'viewBox="([\d.\s-]+)"', text)
    if not view:
        raise SystemExit("no viewBox in " + SVG)
    box = [float(n) for n in view.group(1).split()]
    paths = re.findall(r'\sd="([^"]+)"', text)
    if not paths:
        raise SystemExit("no paths in " + SVG)
    return box, paths


def transform_for(box):
    """Centre the whole viewBox in the safe zone: one scale, two offsets."""
    width, height = box[2], box[3]
    scale = SAFE / max(width, height)
    return scale, (CANVAS - width * scale) / 2.0, (CANVAS - height * scale) / 2.0


# ------------------------------------------------------------ flattening ----

def flatten(d, scale, dx, dy):
    """`d` -> list of subpaths in canvas coordinates, absolute, y down."""
    tokens = []
    for command, number in TOKEN.findall(d):
        tokens.append(command if command else float(number))

    subpaths, current, start = [], None, None
    command, i = None, 0
    while i < len(tokens):
        if isinstance(tokens[i], str):
            command = tokens[i]
            i += 1
            if command in "Zz":
                if current and current != start:
                    subpaths[-1].append(start)
                current = start
                continue

        def take(n, at=i):
            return tokens[at:at + n]

        upper = command.upper()
        relative = command.islower()
        if upper == "M":
            x, y = take(2)
            if relative and start:
                x, y = start[0] + x, start[1] + y
            subpaths.append([])
            current = start = (x, y)
            subpaths[-1].append(current)
            i += 2
            command = "l" if relative else "L"   # implicit lineto after moveto
        elif upper == "L":
            x, y = take(2)
            if relative:
                x, y = start[0] + x, start[1] + y
            current = start = (x, y)
            subpaths[-1].append(current)
            i += 2
        elif upper == "H":
            (x,) = take(1)
            x = start[0] + x if relative else x
            current = start = (x, current[1])
            subpaths[-1].append(current)
            i += 1
        elif upper == "V":
            (y,) = take(1)
            y = start[1] + y if relative else y
            current = start = (current[0], y)
            subpaths[-1].append(current)
            i += 1
        elif upper == "C":
            x1, y1, x2, y2, x3, y3 = take(6)
            if relative:
                x1, y1 = start[0] + x1, start[1] + y1
                x2, y2 = start[0] + x2, start[1] + y2
                x3, y3 = start[0] + x3, start[1] + y3
            x0, y0 = current
            for step in range(1, 25):
                t = step / 24.0
                u = 1 - t
                subpaths[-1].append((
                    u**3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t**3 * x3,
                    u**3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t**3 * y3))
            current = start = (x3, y3)
            i += 6
        elif upper == "A":
            rx, ry, rot, large, sweep, x, y = take(7)
            if relative:
                x, y = start[0] + x, start[1] + y
            subpaths[-1].extend(arc(current, (x, y), rx, ry, int(large), int(sweep)))
            current = start = (x, y)
            i += 7
        else:
            raise SystemExit("unsupported path command %r" % command)

    return [[(x * scale + dx, y * scale + dy) for x, y in sub] for sub in subpaths]


def arc(p0, p1, rx, ry, large, sweep, steps=48):
    """SVG endpoint parametrisation to points, per the spec's own conversion."""
    x1, y1 = p0
    x2, y2 = p1
    if rx == 0 or ry == 0 or p0 == p1:
        return [p1]
    dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    x1p, y1p = dx2, dy2
    factor = x1p**2 / rx**2 + y1p**2 / ry**2
    if factor > 1:
        rx *= math.sqrt(factor)
        ry *= math.sqrt(factor)
    sign = -1 if large == sweep else 1
    num = rx**2 * ry**2 - rx**2 * y1p**2 - ry**2 * x1p**2
    den = rx**2 * y1p**2 + ry**2 * x1p**2
    coef = sign * math.sqrt(max(0.0, num / den))
    cxp = coef * rx * y1p / ry
    cyp = -coef * ry * x1p / rx
    cx, cy = cxp + (x1 + x2) / 2.0, cyp + (y1 + y2) / 2.0

    def angle(ux, uy, vx, vy):
        norm = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1.0, min(1.0, (ux * vx + uy * vy) / norm)))
        return -a if ux * vy - uy * vx < 0 else a

    theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                  (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi
    return [(cx + rx * math.cos(theta1 + delta * k / steps),
             cy + ry * math.sin(theta1 + delta * k / steps)) for k in range(1, steps + 1)]


# ---------------------------------------------------------------- output ----

def write_res(box, paths, scale, dx, dy):
    group = ('    <group\n'
             '        android:scaleX="%s"\n'
             '        android:scaleY="%s"\n'
             '        android:translateX="%s"\n'
             '        android:translateY="%s">\n') % (
                 num(scale), num(scale), num(dx), num(dy))

    body = "".join(
        '        <path\n'
        '            android:fillColor="%s"\n'
        '            android:pathData="%s" />\n' % (INK, d.strip()) for d in paths)

    foreground = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- The owner\'s logo, not a redraw: the four path strings below are copied\n'
        '     verbatim from the artwork (viewBox %s x %s) and only scaled and centred.\n'
        '     Android pathData is SVG\'s grammar, so they need no rewriting.\n\n'
        '     The mark is a standalone G — one ink contour plus marks — so the white it\n'
        '     reads against is the background layer (ic_launcher_background), and the box\n'
        '     is fitted to the %sdp safe zone of the 108dp canvas: a launcher mask may crop\n'
        '     the letter\'s outer edge, never its counter. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n\n'
        '%s\n%s    </group>\n</vector>\n'
    ) % (num(box[2]), num(box[3]), num(SAFE), group, body)

    # One path, evenOdd: the same four subpaths, so the interior marks punch through
    # one tinted layer, which is all a themed icon is ever given.
    monochrome = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Themed icons (Android 13+) keep one layer\'s alpha and tint it. The four\n'
        '     subpaths are the mark\'s own; evenOdd folds the letter and its marks into\n'
        '     one shape, the only form of this mark that survives being flattened\n'
        '     to a single tint. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n\n'
        '%s        <path\n'
        '            android:fillColor="#FFFFFF"\n'
        '            android:fillType="evenOdd"\n'
        '            android:pathData="%s" />\n'
        '    </group>\n</vector>\n'
    ) % (group, " ".join(d.strip() for d in paths))

    background = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <!-- The logo\'s ground. The G is one ink contour, so the plate behind it is\n'
        '         the white the letter is cut against. -->\n'
        '    <color name="ic_launcher_background">%s</color>\n'
        '</resources>\n'
    ) % GROUND

    for name, text in (
        ("drawable/ic_launcher_foreground.xml", foreground),
        ("drawable/ic_launcher_monochrome.xml", monochrome),
        ("values/ic_launcher_background.xml", background),
    ):
        path = os.path.join(RES, name)
        open(path, "w").write(text)
        print("wrote", os.path.relpath(path, ROOT))

    write_mark()


def mark_cut(width_px, supersample=4):
    """The artwork's ink alone, `width_px` wide: everything that is not ink is gone.

    The file is one black spiral — the C — and the ghost sits in the gap between
    its turns, so that white is the canvas showing through rather than a body drawn
    inside the ink. White here is therefore a single region and all of it is
    background, which makes the alpha simply the ink's own mask. Keying the white,
    flooding it from the border and outlining the disc are three ways to invent a
    boundary this file does not have: each one keeps either a white notch beside
    the shoulder or a white plate behind the whole mark, and both were shipped here
    and rejected. Nothing is recoloured either — the eyes and the mouth are ink
    inside the gap, so a mark drawn in one ink is the file's own drawing.

    Cropped to the ink's box and scaled once, so the four `d` strings are the whole
    of the artwork's shape at every size.
    """
    box, paths = svg_paths()
    ink = mark_mask(box, paths, width_px * supersample, supersample=1)
    image = Image.new("RGBA", ink.size, (0, 0, 0, 255))
    image.putalpha(ink)
    image = image.crop(ink.getbbox())

    height = max(1, int(round(width_px * image.size[1] / image.size[0])))
    return image.resize((width_px, height), Image.LANCZOS)


def write_mark():
    """One PNG per density, so no device rescales the mark at run time."""
    for factor, bucket in DENSITIES:
        folder = os.path.join(RES, "drawable-" + bucket)
        os.makedirs(folder, exist_ok=True)
        image = mark_cut(int(round(MARK_DP * factor)))
        path = os.path.join(folder, "logo_mark.png")
        image.save(path)
        print("wrote", os.path.relpath(path, ROOT), "%dx%d" % image.size)


def num(value):
    text = ("%.4f" % value).rstrip("0").rstrip(".")
    return text or "0"


# ------------------------------------------------------------------- mark ----

def mark_mask(box, paths, width_px, supersample=4):
    """The mark as an alpha mask, filled even-odd: one letter, four subpaths.

    A scanline fill rather than a polygon per shape, because the mark is one
    contour whose subpaths overlap: only the combined even-odd winding count
    says which pixels are ink, and Pillow's `polygon` knows nothing about holes.
    Drawn at 4x and scaled down once, which is the whole of its antialiasing.
    """
    scale = width_px * supersample / box[2]
    subpaths = []
    for d in paths:
        subpaths.extend(flatten(d, scale, -box[0] * scale, -box[1] * scale))
    width = max(1, int(round(box[2] * scale)))
    height = max(1, int(round(box[3] * scale)))

    mask = Image.new("L", (width, height), 0)
    pen = ImageDraw.Draw(mask)
    for row in range(height):
        y = row + 0.5
        crossings = []
        for sub in subpaths:
            for i, (x0, y0) in enumerate(sub):
                x1, y1 = sub[(i + 1) % len(sub)]
                if (y0 <= y < y1) or (y1 <= y < y0):
                    crossings.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
        crossings.sort()
        for i in range(0, len(crossings) - 1, 2):
            pen.line([(crossings[i], row), (crossings[i + 1], row)], fill=255)

    return mask.resize((max(1, int(round(width_px))),
                        max(1, int(round(width_px * box[3] / box[2])))),
                       Image.LANCZOS)


_marks = {}


def mark_image(width_px, ink=(255, 255, 255)):
    """The mark alone, `width_px` wide, in one ink, transparent everywhere else.

    The renderer and the scroll composer both call this, so the G is rasterised
    from the artwork every time rather than pasted from a picture of itself.
    """
    key = (int(width_px), tuple(ink))
    if key not in _marks:
        box, paths = svg_paths()
        mask = mark_mask(box, paths, key[0])
        image = Image.new("RGBA", mask.size, key[1] + (0,))
        image.putalpha(mask)
        _marks[key] = image
    return _marks[key]


# --------------------------------------------------------------- preview ----

def preview(box, paths, scale, dx, dy, side=648):
    image = Image.new("RGB", (side, side), GROUND)
    draw = ImageDraw.Draw(image)
    unit = side / CANVAS
    for d in paths:
        for sub in flatten(d, scale, dx, dy):
            if len(sub) > 2:
                draw.polygon([(x * unit, y * unit) for x, y in sub], fill=INK)
    image.save(os.path.join(HERE, "launcher-preview.png"))

    masked = Image.new("RGB", (side, side), (18, 18, 18))
    circle = Image.new("L", (side, side), 0)
    ImageDraw.Draw(circle).ellipse([0, 0, side - 1, side - 1], fill=255)
    masked.paste(image, (0, 0), circle)
    masked.resize((192, 192), Image.LANCZOS).save(
        os.path.join(HERE, "launcher-preview-masked.png"))

    squircle = Image.new("RGB", (side, side), (18, 18, 18))
    mask = Image.new("L", (side, side), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, side - 1, side - 1],
                                           radius=side * 0.28, fill=255)
    squircle.paste(image, (0, 0), mask)
    squircle.resize((192, 192), Image.LANCZOS).save(
        os.path.join(HERE, "launcher-preview-squircle.png"))
    print("wrote previews: square, circle-masked, squircle-masked")


def sheet():
    """One picture to link: the mark at launcher sizes, in both common masks.

    CONVENTIONS.md makes a linked render the gate before anything ships, and the
    icon is not one of the app's seven screens, so it gets its own sheet.
    """
    src = Image.open(os.path.join(HERE, "launcher-preview.png"))
    width, height = 1000, 560
    canvas = Image.new("RGB", (width, height), (244, 244, 243))
    draw = ImageDraw.Draw(canvas)
    try:
        caption = ImageFont.load_default(15)
    except Exception:
        caption = ImageFont.load_default()
    grey = (70, 70, 70)

    hero = 220
    canvas.paste(src.resize((hero, hero), Image.LANCZOS), (28, 28))
    draw.text((28, 28 + hero + 10), "the 108dp canvas, unmasked", fill=grey, font=caption)
    draw.text((300, 20), "what a launcher shows, in two masks", fill=grey, font=caption)

    sizes = (48, 72, 96, 144)
    for row, shape in enumerate(("circle", "squircle")):
        y = 64 + row * 258
        for column, size in enumerate(sizes):
            x = 300 + column * (size + 40)
            tile = Image.new("RGB", (size, size), (232, 232, 231))
            tile.paste(src.resize((size, size), Image.LANCZOS), (0, 0), mask(size, shape))
            canvas.paste(tile, (x, y))
            draw.text((x, y + size + 8), "%d px" % size, fill=grey, font=caption)
        draw.text((300, y - 16), shape + " mask", fill=grey, font=caption)

    path = os.path.join(HERE, "launcher-preview-sheet.png")
    canvas.save(path)
    print("wrote", os.path.relpath(path, ROOT))


def mask(size, shape):
    alpha = Image.new("L", (size, size), 0)
    pen = ImageDraw.Draw(alpha)
    if shape == "circle":
        pen.ellipse([0, 0, size - 1, size - 1], fill=255)
    else:
        pen.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.28, fill=255)
    return alpha


def palette(name, fallback):
    """One colour from Palette.java, so this sheet cannot invent the app's own grey."""
    text = open(os.path.join(ROOT, "android-companion", "app", "src", "main", "java",
                             "dev", "phonedesk", "companion", "Palette.java")).read()
    found = re.search(r"\b%s\s*=\s*0x([0-9A-Fa-f]{8})" % name, text)
    if not found:
        return fallback
    value = int(found.group(1), 16)
    return ((value >> 16) & 255, (value >> 8) & 255, value & 255)


def compare():
    """One picture to link: the file, and the bar's mark at three densities.

    The eyes are 7.5% of the artwork's width, so at the bar's 36dp they are 2.7px at
    mdpi and 7.4px on a 2.75x phone. Showing all three is the honest way to say "the
    eyes are there": a single mdpi frame is a downscale, and a downscale of a thin
    dark shape on white is not evidence that the shape is missing.
    """
    box, paths = svg_paths()
    field = palette("FIELD", (63, 78, 150))
    band = palette("BAND", (217, 217, 217))
    canvas = Image.new("RGB", (1000, 470), field)
    draw = ImageDraw.Draw(canvas)
    # Pillow's default face is a bitmap with no em dash, no multiplication sign and
    # no middot: the first sheet printed empty boxes where those were. ASCII only.
    caption = ImageFont.load_default(15)
    dim = (226, 229, 242)

    hero = 220
    plate = Image.new("RGB", (hero + 40, int(hero * box[3] / box[2]) + 40), (255, 255, 255))
    cut = mark_cut(hero)
    plate.paste(cut, (20, 20), cut)
    canvas.paste(plate, (26, 30))
    draw.text((26, 30 + plate.size[1] + 10),
              "the file: the artwork on the white canvas it ships on", fill=dim, font=caption)

    # The three are on the bar's band, because that is where they now live: the
    # band is the grey standing in for the white ground the file was drawn on.
    draw.rounded_rectangle([306, 20, 990, 152], radius=12, fill=band)

    drawn = [(36, "36dp at mdpi: 35px"), (72, "2x: 72px"),
             (99, "xxhdpi: what the phone shows")]
    for column, (px, label) in enumerate(drawn):
        x = 320 + column * 226
        image = mark_cut(px)
        canvas.paste(image, (x, 30), image)
        draw.text((x, 190), label, fill=dim, font=caption)

    for line, text in enumerate([
            "the bar's mark: the same file's ink alone, so the ghost is the band",
            "showing through the C and its eyes and mouth are the file's own ink.",
            "The eyes are 7.5% of the artwork's width: 2.7px at mdpi, 7.4px on a",
            "2.75x phone - mdpi is the harshest view of them."]):
        draw.text((320, 330 + line * 24), text, fill=dim, font=caption)

    path = os.path.join(HERE, "logo-compare.png")
    canvas.save(path)
    print("wrote", os.path.relpath(path, ROOT))


def main(argv):
    box, paths = svg_paths()
    scale, dx, dy = transform_for(box)
    print("viewBox %s, %d paths, scale %s, offset (%s, %s)"
          % (" x ".join(num(v) for v in box[2:]), len(paths), num(scale), num(dx), num(dy)))
    if "--dry-run" in argv:
        return 0
    write_res(box, paths, scale, dx, dy)
    preview(box, paths, scale, dx, dy)
    sheet()
    compare()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
