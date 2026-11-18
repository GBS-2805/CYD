#!/usr/bin/env python3
"""Fetch Inter Tight 400/500/600 and Geist Mono 400/500, subset them, and install them as app resources.

Run once. The app packages the TTFs from `app/src/main/res/font/`, which is what
`Ui.face()` loads; nothing at runtime downloads a font.
"""
import glob
import os
import subprocess
import sys

from fontTools.ttLib import TTFont
from fontTools import subset

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app/src/main/res/font")
FACES = {
    "inter_tight_400": ("InterTight-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/intertight/InterTight%5Bwght%5D.ttf"),
    "inter_tight_500": ("InterTight-Medium.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/intertight/InterTight%5Bwght%5D.ttf"),
    "inter_tight_600": ("InterTight-SemiBold.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/intertight/InterTight%5Bwght%5D.ttf"),
    "geist_mono_400": ("GeistMono-Regular.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/geistmono/GeistMono%5Bwght%5D.ttf"),
    "geist_mono_500": ("GeistMono-Medium.ttf", "https://raw.githubusercontent.com/google/fonts/main/ofl/geistmono/GeistMono%5Bwght%5D.ttf"),
}
# A variable font is instanced at the weight each face needs before subsetting.
WEIGHTS = {"inter_tight_400": 400, "inter_tight_500": 500, "inter_tight_600": 600,
           "geist_mono_400": 400, "geist_mono_500": 500}

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
    path = os.path.join("/tmp/offsite-fonts", name)
    os.makedirs("/tmp/offsite-fonts", exist_ok=True)
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
        if "fvar" in font:
            from fontTools.varLib import instancer
            font = instancer.instantiateVariableFont(font, {"wght": WEIGHTS[face]})
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
