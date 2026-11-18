#!/usr/bin/env python3
"""Cut the deck's front pages into the app's assets.

`artifacts/deck_screens.py` draws every page each sketch can put on the glass. The
companion app's preview bubble shows the front page of the deck being flashed, so
this copies the front pages that exist into `app/src/main/assets/decks/` under the
names `DeckPreview` reads:

    phone_deck_cyd24R-<green|moss|navy>-<light|dark>.png   the eight palette rows
    phone_deck_cyd24R-<paper|ink>.png                      the two monochrome rows
    spotify_deck_cyd24R-<ink|paper>.png                    that deck's two rows

The names are not free-form: the suffix is what `DeckLook` asks the board to show,
so a file under the wrong name would be the wrong picture for the palette.

Each page is stored as a palette PNG of the colours it actually uses, not as the
24-bit draw. A deck's page is flat: eight or nine inks and no gradient, so the
indexed copy is the same pixels and about a third of the bytes, and the app is
carrying ten of them. `--check` compares pixels rather than file bytes for that
reason — the picture is the contract, the encoding is not.

    ./sync_deck_assets.py            copy from ../artifacts/decks
    ./sync_deck_assets.py --check    fail if the assets and the draws disagree
"""
import argparse
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
DRAWN = HERE.parent / "artifacts" / "decks"
ASSETS = HERE / "app" / "src" / "main" / "assets" / "decks"

# drawn name -> asset name. The drawn names are page-first (`phone-board-moss-dark`);
# the assets are deck-first, because the app picks one by deck and then by palette.
PAIRS = {
    "phone-board-paper.png": "phone_deck_cyd24R-paper.png",
    "phone-board-ink.png": "phone_deck_cyd24R-ink.png",
    "phone-board-green-light.png": "phone_deck_cyd24R-green-light.png",
    "phone-board-green-dark.png": "phone_deck_cyd24R-green-dark.png",
    "phone-board-moss-light.png": "phone_deck_cyd24R-moss-light.png",
    "phone-board-moss-dark.png": "phone_deck_cyd24R-moss-dark.png",
    "phone-board-navy-light.png": "phone_deck_cyd24R-navy-light.png",
    "phone-board-navy-dark.png": "phone_deck_cyd24R-navy-dark.png",
    # The Spotify deck ships only its two monochrome pages; its front page is the
    # now-playing screen, which is what its `-ink`/`-paper` draw is.
    "spotify-now-ink.png": "spotify_deck_cyd24R-ink.png",
    "spotify-now-paper.png": "spotify_deck_cyd24R-paper.png",
}


def flat(path):
    """The draw as an 8-bit palette PNG of exactly its own colours, or None.

    A deck's page is flat colour, so this is lossless: every colour in the source
    is in the palette, and the conversion is checked rather than assumed. A draw
    with more inks than a palette holds comes back as None and is copied as it is,
    because a preview that is wrong is worse than one that is large.
    """
    image = Image.open(path).convert("RGB")
    found = [colour for _, colour in image.getcolors(256) or []]
    if not found:
        return None
    palette = Image.new("P", (1, 1))
    flat_list = []
    for colour in found:
        flat_list += list(colour)
    flat_list += [0] * (768 - len(flat_list))
    palette.putpalette(flat_list)
    indexed = image.quantize(palette=palette, dither=Image.Dither.NONE)
    if indexed.convert("RGB").tobytes() != image.tobytes():
        return None
    out = path.with_suffix(".indexed.png")
    indexed.save(out, optimize=True)
    return out


def same_pixels(asset, source):
    try:
        a = Image.open(asset).convert("RGB")
        b = Image.open(source).convert("RGB")
    except OSError:
        return False
    # `tobytes()` rather than `getdata()`: the same comparison without a per-pixel
    # Python object, which is what made this slow enough to notice on ten frames.
    return a.size == b.size and a.tobytes() == b.tobytes()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="do not write; fail if an asset is missing or stale")
    args = parser.parse_args()

    missing = [name for name in PAIRS if not (DRAWN / name).exists()]
    if missing:
        print("missing draws (run ../artifacts/deck_screens.py first):", file=sys.stderr)
        for name in missing:
            print("  " + name, file=sys.stderr)
        return 1

    ASSETS.mkdir(parents=True, exist_ok=True)
    stale = []
    for drawn, asset in PAIRS.items():
        source = DRAWN / drawn
        target = ASSETS / asset
        # A 24-bit copy of the same pixels is still a copy to make: the palette
        # encoding is what the app carries, so it is part of what this syncs.
        if (target.exists() and same_pixels(target, source)
                and Image.open(target).mode == "P"):
            continue
        stale.append(asset)
        if args.check:
            continue
        flat_source = flat(source)
        if flat_source is None:
            flat_source.write_bytes(source.read_bytes())
        flat_source.replace(target)

    if args.check:
        if stale:
            print("deck assets are stale: " + ", ".join(stale), file=sys.stderr)
            print("run ./sync_deck_assets.py", file=sys.stderr)
            return 1
        print("deck assets: " + str(len(PAIRS)) + " files match the draws")
        return 0

    print(("copied " + str(len(stale)) + " of " + str(len(PAIRS)) + " deck assets")
          if stale else "deck assets already match the draws")
    return 0


if __name__ == "__main__":
    sys.exit(main())
