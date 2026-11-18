#!/usr/bin/env python3
"""Build the one public page that carries the app, its APK and its pictures.

CONVENTIONS.md asks for a labelled public link to every touched screen before a UI
change ships, and the owner reads the chat on a phone. So the frames that git already
holds are copied, unchanged, into `artifacts/site/public/` with one page that walks the
app from top to bottom: the download, the ten captures, the deck's palette pages, the
mark, and the optional wide strip. One address, one scroll.

Nothing here draws the app. The pixels are `artifacts/renders/`'s and the deck pages are
`artifacts/decks/`'s; `check_renders.py` and the deck checkers keep those honest. This
script copies them, draws the strip from the same ten frames, and writes the page. The
handoff note beside it is the owner's own copy and is deliberately not published.

The bundle is cleared before it is filled, so a file that leaves the pipeline cannot
survive as an orphan URL. The old `public/decks/` did exactly that: a folder no current
step wrote, still served under the gallery because the cleanup only removed `*.png`.

    python3 artifacts/publish_renders.py          # rebuild the bundle
    # then deploy artifacts/site/manifest.json with the sites tooling

`public/` is build output and is ignored by git; the frames beside it are the source.
"""
import hashlib
import shutil
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
RENDERS = HERE / "renders"
PUBLIC = HERE / "site" / "public"

# Every frame the capture writes, in the order a person meets them in the app.
SCREENS = [
    ("paired", "Paired", "the link, then three decks drawn in their own three palettes"),
    ("preview", "Deck preview",
     "the bubble a plate opens: the deck's own front page in a 2.4\" bezel"),
    ("pull-down", "The pull-down", "PAIRED, BOARDS, SERIAL, in flow under the header"),
    ("side-panel", "The side panel",
     "SETTINGS, DECKS, BRIDGE, APPEARANCE, and the palette at its foot"),
    ("bridge-sheet", "Bridge", "the switch, its state, and what it needs first"),
    ("appearance-sheet", "Appearance", "three palettes and the one page switch"),
    ("projects", "Boards", "the shelf, alphabetical and folded, libraries note first"),
    ("projects-open", "One board open", "its notes, log, deck block and actions"),
    ("serial", "Serial", "the target, the USB flasher and the monitor"),
    ("settings", "Settings", "every permission in one list, each with its LED"),
]

# The phone deck's front page in each palette. `deck_screens.py` draws these from the
# sketch's own draw calls; the site only carries the proof.
DECK_ORDER = [
    "phone-home-paper.png",
    "phone-home-ink.png",
    "phone-home-green-light.png",
    "phone-home-green-dark.png",
    "phone-home-moss-light.png",
    "phone-home-moss-dark.png",
    "phone-home-navy-light.png",
    "phone-home-navy-dark.png",
]
DECK_LABELS = {
    "phone-home-paper.png": "Paper",
    "phone-home-ink.png": "Ink",
    "phone-home-green-light.png": "Green · light",
    "phone-home-green-dark.png": "Green · dark",
    "phone-home-moss-light.png": "Moss · light",
    "phone-home-moss-dark.png": "Moss · dark",
    "phone-home-navy-light.png": "Navy · light",
    "phone-home-navy-dark.png": "Navy · dark",
}

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Off-site — the app, screen by screen</title>
<style>
  :root {{
    /* Moss, the app's own default: the page, its band, and the ink on each. */
    --field: #0e2a24; --band: #005043; --ink: #e7ddce;
    --on: #e7ddce; --dim: rgba(231, 221, 206, .72); --rule: rgba(231, 221, 206, .24);
    --card: rgba(231, 221, 206, .05);
  }}
  * {{ box-sizing: border-box; }}
  html {{ scroll-behavior: smooth; }}
  body {{
    margin: 0; background: var(--field); color: var(--on);
    font: 16px/1.6 Poppins, -apple-system, "Segoe UI", Roboto, Helvetica, Arial,
          sans-serif;
  }}
  a {{ color: inherit; }}
  .top {{
    position: sticky; top: 0; z-index: 3; background: var(--band);
    color: var(--ink); border-bottom: 1px solid rgba(0, 0, 0, .14);
  }}
  .bar {{
    max-width: 760px; margin: 0 auto; padding: 12px 18px;
    display: flex; align-items: center; justify-content: space-between; gap: 16px;
  }}
  .brand {{
    font-size: 15px; font-weight: 600; letter-spacing: .02em;
    text-decoration: none; white-space: nowrap;
  }}
  nav {{ display: flex; gap: 14px; font-size: 11px; letter-spacing: .09em;
         text-transform: uppercase; }}
  nav a {{ color: rgba(231, 221, 206, .70); text-decoration: none; }}
  nav a:hover {{ color: var(--ink); }}
  .hero {{ max-width: 760px; margin: 0 auto; padding: 30px 18px 24px; }}
  .eyebrow {{
    margin: 0 0 8px; font-size: 11px; letter-spacing: .14em;
    text-transform: uppercase; color: var(--dim);
  }}
  h1 {{ margin: 0 0 10px; font-size: 30px; line-height: 1.15; font-weight: 600; }}
  .lede {{ margin: 0 0 20px; max-width: 34em; color: var(--dim); }}
  .download {{
    display: inline-flex; align-items: baseline; gap: 10px;
    background: var(--ink); color: var(--band); text-decoration: none;
    font-size: 14px; font-weight: 600; padding: 12px 16px;
  }}
  .download span {{ font-weight: 400; opacity: .65; }}
  .meta {{ margin: 10px 0 0; font-size: 12px; color: var(--dim); }}
  main {{ max-width: 760px; margin: 0 auto; }}
  section {{ padding: 26px 18px 10px; scroll-margin-top: 70px; }}
  section + section {{ border-top: 1px solid var(--rule); }}
  h2 {{ margin: 0 0 4px; font-size: 19px; font-weight: 600; }}
  .section-note {{ margin: 0 0 16px; color: var(--dim); font-size: 13px; }}
  .app-list {{ max-width: 420px; margin: 0 auto; }}
  figure {{ margin: 0; background: var(--card); border: 1px solid var(--rule); }}
  figure img {{ display: block; width: 100%; height: auto; }}
  figcaption {{ padding: 9px 11px 11px; font-size: 12px; color: var(--dim);
                border-top: 1px solid var(--rule); }}
  figcaption b {{ display: block; color: var(--on); font-weight: 500; }}
  figcaption span {{ display: block; }}
  .app-list figure {{ margin-bottom: 14px; }}
  .app-list figure:last-child {{ margin-bottom: 0; }}
  .deck-grid {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
                gap: 10px; }}
  .mark {{ max-width: 560px; margin: 0 auto; }}
  .mark img {{ background: var(--ink); padding: 10px; }}
  .strip-link {{
    display: inline-block; border: 1px solid var(--rule); padding: 10px 12px;
    font-size: 13px; text-decoration: none;
  }}
  .strip-link:hover {{ background: var(--card); }}
  footer {{
    max-width: 760px; margin: 0 auto; padding: 18px 18px 48px;
    border-top: 1px solid var(--rule); color: var(--dim); font-size: 12px;
  }}
  footer p + p {{ margin: 8px 0 0; }}
  footer a {{ color: var(--on); text-decoration: underline; text-underline-offset: 3px; }}
  footer code {{ font-size: .95em; }}
  @media (min-width: 620px) {{
    h1 {{ font-size: 36px; }}
    .hero {{ padding-top: 38px; }}
    section {{ padding-top: 34px; }}
    .deck-grid {{ grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }}
  }}
</style>
</head>
<body>
<header class="top">
  <div class="bar">
    <a class="brand" href="#top">Off-site</a>
    <nav>
      <a href="#app">App</a>
      <a href="#deck">Deck</a>
      <a href="#mark">Mark</a>
    </nav>
  </div>
</header>
<div class="hero" id="top">
  <p class="eyebrow">Android companion</p>
  <h1>The app, screen by screen.</h1>
  <p class="lede">The phone side of the desk deck: link a CYD, keep its projects,
    flash its board, and see every page it draws.</p>
  {download}
</div>
<main>
  <section id="app">
    <h2>The app</h2>
    <p class="section-note">Nine views, captured by Android's renderer on a JVM —
      not device screenshots.</p>
    <div class="app-list">
{figures}
    </div>
  </section>
  <section id="deck">
    <h2>The deck's glass</h2>
    <p class="section-note">The phone deck's home page in each palette, drawn from
      the sketch's own draw calls. Reconstructions, not captures.</p>
    <div class="deck-grid">
{deck_figures}
    </div>
  </section>
  <section id="mark">
    <h2>The mark</h2>
    <p class="section-note">The owner's mark on the band, at five densities. The
      ghost is the band showing through the C.</p>
{mark}
  </section>
  <section id="strip">
    <h2>The wide strip</h2>
    <p class="section-note">All ten screens in one image. It is a desktop extra —
      on a phone, use the screens above.</p>
    <a class="strip-link" href="chat-sheet.jpg">Open the strip · {strip_dim}</a>
  </section>
</main>
<footer>
  <p>Frames are <code>artifacts/renders/</code>; deck pages are
  <code>artifacts/decks/</code>. Both are redrawn and checked by the gate. The APK
  is <code>android-companion/dist/PhoneDeckBridge-debug.apk</code>. This page is
  generated by <code>artifacts/publish_renders.py</code>.</p>
</footer>
</body>
</html>
"""


# The strip one chat message shows. Drawn from the same frames the page uses, at
# publish time: a strip copied out of `renders/` is how the published one came to show
# a week-old capture's seven frames while the app drew nine.
STRIP = "chat-sheet.jpg"
THUMB = 196                          # a 393 px capture at half width
GUTTER = 10
PAD = 12
FIELD = (0x0E, 0x2A, 0x24)           # moss: Palette.MOSS_SCHEME's dark page
BAND = (0x3C, 0x57, 0x4F)            # the hairline that page draws its cells in


def strip():
    """One row of the screens, drawn from the frames just copied into `public/`.

    The page is a scroll because frames side by side are unreadable at phone
    width; this is those same frames as one picture, so it is drawn rather than
    carried in `renders/`, where a copy dates while the app moves. The frames keep
    their own proportions and hang from the same top edge, so a short screen is a
    short card.
    """
    shots = []
    for name, _, _ in SCREENS:
        frame = Image.open(PUBLIC / (name + ".png")).convert("RGB")
        shots.append(frame.resize((THUMB, round(frame.height * THUMB / frame.width)),
                                  Image.LANCZOS))
    cell = max(shot.height for shot in shots)
    width = PAD * 2 + THUMB * len(shots) + GUTTER * (len(shots) - 1)
    canvas = Image.new("RGB", (width, cell + PAD * 2), FIELD)
    draw = ImageDraw.Draw(canvas)
    x = PAD
    for shot in shots:
        canvas.paste(shot, (x, PAD))
        draw.rectangle([x - 1, PAD - 1, x + THUMB, PAD + shot.height], outline=BAND)
        x += THUMB + GUTTER
    canvas.save(PUBLIC / STRIP, quality=88, optimize=True)
    return canvas.size


def reset_public():
    """Remove every old bundle entry, including subdirectories.

    The old gallery only deleted `*.png`, so a folder an earlier publisher wrote
    (`public/decks/`) stayed in the bundle and kept answering under the live site.
    A bundle is a product of this script; nothing in it should outlive a run.
    """
    if PUBLIC.exists():
        for child in PUBLIC.iterdir():
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()
    PUBLIC.mkdir(parents=True, exist_ok=True)


def main():
    reset_public()

    figures = []
    for name, title, blurb in SCREENS:
        source = RENDERS / (name + ".png")
        if not source.is_file():
            raise SystemExit("missing %s: run android-companion/capture.sh first" % source)
        shutil.copyfile(source, PUBLIC / source.name)
        figures.append(
            '  <figure>\n    <img src="%s.png" alt="%s">\n'
            '    <figcaption><b>%s</b><span>%s</span></figcaption>\n  </figure>'
            % (name, title, title, blurb))

    size = strip()

    # The logo sheet is drawn by `logo_icon.py` beside the drawable it shows, so it
    # cannot describe a mark the app stopped drawing. Copied here, not remembered.
    logo = HERE / "logo-compare.png"
    if logo.is_file():
        shutil.copyfile(logo, PUBLIC / logo.name)
        mark = (
            '    <figure class="mark">\n'
            '      <img src="logo-compare.png" '
            'alt="The mark at five densities">\n'
            '      <figcaption><b>Five densities</b>'
            '<span>mdpi through xxxhdpi</span></figcaption>\n'
            '    </figure>'
        )
    else:
        mark = '    <p class="meta">The mark sheet is not in this tree.</p>'

    # The deck's own glass: the phone deck's front page in every palette row, drawn
    # by artifacts/deck_screens.py from the sketch's own draw calls. Copied so a
    # palette can be looked at on a phone, not only inside the app's frames.
    deck_figures = []
    deck_pages = sorted(
        (HERE / "decks").glob("phone-home-*.png"),
        key=lambda page: (DECK_ORDER.index(page.name)
                          if page.name in DECK_ORDER else len(DECK_ORDER)))
    for page in deck_pages:
        shutil.copyfile(page, PUBLIC / ("deck-" + page.name))
        label = DECK_LABELS.get(page.name,
                                page.stem.replace("phone-home-", "").replace("-", " ").title())
        deck_figures.append(
            '    <figure>\n      <img src="deck-%s" alt="%s">\n'
            '      <figcaption><b>%s</b><span>phone deck</span></figcaption>\n'
            '    </figure>' % (page.name, label, label))
    if not deck_figures:
        deck_figures = ['    <p class="meta">No deck sheets are in this tree.</p>']

    # The download has to be the APK this tree built. It used to be copied by hand
    # after every build, which is a step that gets skipped exactly when the build
    # changed something: the bundle would then serve the previous app under a
    # gallery of the new one.
    apk = HERE.parent / "android-companion" / "dist" / "PhoneDeckBridge-debug.apk"
    if apk.is_file():
        shutil.copyfile(apk, PUBLIC / apk.name)
        size_b = apk.stat().st_size
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()[:8]
        download = (
            '<a class="download" href="%s">Download the app <span>%s kB</span></a>\n'
            '  <p class="meta">%s bytes · sha256 %s… · debug build</p>'
            % (apk.name, round(size_b / 1024), format(size_b, ","), digest))
    else:
        download = ('<p class="meta">The APK is not built in this tree.</p>')
        print("note: %s is not built, so the bundle carries no APK"
              % apk.relative_to(HERE.parent))

    (PUBLIC / "index.html").write_text(PAGE.format(
        download=download,
        figures="\n".join(figures),
        deck_figures="\n".join(deck_figures),
        mark=mark,
        strip_dim="%d × %d" % (size[0], size[1]),
    ))

    files = sorted(p.name for p in PUBLIC.iterdir())
    print("bundle: %d files in %s" % (len(files), PUBLIC.relative_to(HERE.parent)))
    for name in files:
        print("  %-20s %6.0f kB" % (name, (PUBLIC / name).stat().st_size / 1024))
    print("strip: %s %d x %d from the same %d frames" % (STRIP, size[0], size[1],
                                                         len(SCREENS)))


if __name__ == "__main__":
    main()
