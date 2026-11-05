#!/usr/bin/env python3
"""Fetch Poppins 400/500/600, subset them, and install them as app resources.

Run once. The app packages the TTFs from `app/src/main/res/font/`, which is what
`Ui.face()` loads; nothing at runtime downloads a font.
"""
import glob
import os
import subprocess
import sys

from fontTools.ttLib import TTFont
from fontTools import subset

OUT = "/workspace/chats/e9252a57e411c2e2ed13a226/android-companion/app/src/main/res/font"
FACES = {
    "poppins_400": ("Poppins-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Regular.ttf"),
    "poppins_500": ("Poppins-Medium.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Medium.ttf"),
    "poppins_600": ("Poppins-SemiBold.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-SemiBold.ttf"),
}

# Everything the app can print: ASCII, the punctuation it uses in prose, the
# arrows and marks it puts in rows, and the currency and accent letters a deck
# name or a project name can carry.
KEEP = (
    "".join(chr(c) for c in range(0x20, 0x7F))
    + "\u00a0\u00b0\u00b7\u00d7\u00e9\u00c9\u00fc\u00dc\u00f6\u00d6\u00e4\u00c4\u00df"
    + "\u00a3\u20ac\u2013\u2014\u2018\u2019\u201c\u201d\u2022\u2026\u2190\u2192\u2191\u2193"
    + "\u2212\u2248\u00b1\u2713\u2717"
)


def fetch(name, url):
    path = os.path.join("/tmp/poppins", name)
    os.makedirs("/tmp/poppins", exist_ok=True)
    if os.path.exists(path) and os.path.getsize(path) > 60000:
        return path
    subprocess.run(["curl", "-sSfL", "-o", path, url], check=True)
    return path


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for face, (file, url) in FACES.items():
        source = fetch(file, url)
        font = TTFont(source)
        options = subset.Options()
        options.layout_features = ["kern", "liga"]
        options.name_IDs = ["*"]
        options.notdef_outline = True
        options.recalc_bounds = True
        sub = subset.Subsetter(options=options)
        sub.populate(text=KEEP)
        sub.subset(font)
        font.flavor = None
        target = os.path.join(OUT, face + ".ttf")
        font.save(target)
        size = os.path.getsize(target)
        total += size
        print("%-14s %7d B  (%s)" % (face + ".ttf", size, os.path.basename(source)))
    print("total %d B over %d faces" % (total, len(FACES)))


if __name__ == "__main__":
    sys.exit(main())
