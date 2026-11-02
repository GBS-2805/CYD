#!/usr/bin/env python3
"""Turn the owner's logo.svg into the app's adaptive icon, and preview it.

The four `d` strings are copied out of `logo-source.svg` unchanged — Android's
pathData is SVG's own grammar, so the artwork ships verbatim inside a <group>
that only scales and centres it. This script writes the three resource files and
rasterises the result through the same transform for review.

    python3 artifacts/logo_icon.py            write the res files and previews
    python3 artifacts/logo_icon.py --dry-run  only print the geometry it would use

The scale keeps the whole mark inside the 72dp safe zone of the 108dp adaptive
canvas, which is what the icon this replaces did: no launcher mask may crop the
ghost's mouth.
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
INK = "#000000"         # the logo's own ink
GROUND = "#FFFFFF"      # the logo draws the ghost as a hole: the ground is white

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
        '     The logo draws the ghost as a hole in one black contour, so the white it\n'
        '     reads against is the background layer (ic_launcher_background), and the box\n'
        '     is fitted to the %sdp safe zone of the 108dp canvas: a launcher mask may crop\n'
        '     the disc\'s outer edge, never the mouth. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n\n'
        '%s\n%s    </group>\n</vector>\n'
    ) % (num(box[2]), num(box[3]), num(SAFE), group, body)

    # One path, evenOdd: the same four subpaths, so eyes and mouth become holes in
    # the contour. Tinted, that is the logo's negative — the ghost reads as a gap.
    monochrome = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Themed icons (Android 13+) keep one layer\'s alpha and tint it. The four\n'
        '     subpaths are the logo\'s own; evenOdd turns the eyes and the mouth into\n'
        '     holes, which is the only form of this mark that survives being flattened\n'
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
        '    <!-- The logo\'s ground. Its single black contour carries the ghost as a\n'
        '         hole, so white here is what the ghost is drawn as. -->\n'
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


def num(value):
    text = ("%.4f" % value).rstrip("0").rstrip(".")
    return text or "0"


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
    icon is not one of render_ui.py's seven screens, so it gets its own sheet.
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
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
