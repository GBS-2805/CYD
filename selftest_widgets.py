#!/usr/bin/env python3
"""Exercise every refusal of check_widget_assets.py against a throwaway widget.

This check passes on every tree that has been built: Gradle re-syncs the bundled
copies before each build, so the drift it exists for is invisible until someone
edits a sketch and commits without rebuilding. Its other refusals — a descriptor
naming a sketch that is gone, an asset folder no descriptor claims, a descriptor
that does not parse — have never run here at all.

Each case builds a temp repo with one widget, its descriptor, its sketch and the
bundled copy, then breaks exactly one of those and asks what the check says.

  python3 selftest_widgets.py     exit 0 when every branch still behaves

Nothing is built and nothing outside the temp directory is touched.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_widget_assets.py"
HEADROOM = ROOT / "check_headroom.py"
WIDGET = "demo_deck_cyd24R"
SKETCH = "/* stand-in widget */\nvoid setup() {}\nvoid loop() {}\n"
ASSETS = "android-companion/app/src/main/assets/widgets"
PALETTE = ROOT / "android-companion/app/src/main/java/dev/phonedesk/companion/Palette.java"
PAPER = ["#f1f0eb", "#e8e7e2", "#dddcd7", "#b3b1ac", "#000000", "#5b5a56",
         "#000000", "#a9a8a3", "#1a1a18", "#7a7974", "#000000", "#4e4d49"]
INK = ["#000000", "#101010", "#1c1c1a", "#3a3a37", "#f1f0eb", "#a8a7a2",
       "#f1f0eb", "#6a6a66", "#ffffff", "#8c8b86", "#f1f0eb", "#b6b5b0"]


def tree(dest):
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_widget_assets.py")
    shutil.copy2(HEADROOM, dest / "check_headroom.py")
    if PALETTE.is_file():
        target = dest / "android-companion/app/src/main/java/dev/phonedesk/companion/Palette.java"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(PALETTE, target)
    folder = dest / WIDGET
    folder.mkdir()
    descriptor = folder / "widget.json"
    descriptor.write_text(json.dumps({"schema": 1, "id": WIDGET, "name": "Demo", "sketch": WIDGET + ".ino"}))
    sketch = folder / (WIDGET + ".ino")
    sketch.write_text(SKETCH)
    bundled = dest / ASSETS / WIDGET
    bundled.mkdir(parents=True)
    (bundled / "widget.json").write_bytes(descriptor.read_bytes())
    (bundled / "widget.json").chmod(0o644)
    (bundled / (WIDGET + ".ino")).write_bytes(sketch.read_bytes())
    (dest / ASSETS / "widget.json").write_text("{}")   # the parent dir is a list, not a widget


def descriptor(dest, meta):
    (dest / WIDGET / "widget.json").write_text(json.dumps(meta))


def themed(dest, paper=None, ink=None):
    """A sketch whose THEMES table says the app's two pages, or not."""
    paper = list(paper or PAPER)
    ink = list(ink or INK)
    rows = []
    for name, slots in (("0 PAPER", paper), ("1 INK", ink)):
        rows.append("  // %s\n  { %s }," % (name, ", ".join("0x0000 /* %s */" % h for h in slots)))
    text = ("struct Palette { uint16_t a, b; };\nstatic constexpr Palette THEMES[] = {\n"
            + "\n".join(rows) + "\n};\n")
    sketch = dest / WIDGET / (WIDGET + ".ino")
    sketch.write_text(text)
    (dest / ASSETS / WIDGET / (WIDGET + ".ino")).write_bytes(sketch.read_bytes())


def wrong_paper(dest):
    themed(dest, paper=["#ff00ff"] + PAPER[1:])


def wrong_ink(dest):
    themed(dest, ink=INK[:4] + ["#ff00ff"] + INK[5:])


RENDER = """import sys
from pathlib import Path
from PIL import Image
out = Path(sys.argv[sys.argv.index("--out") + 1])
out.mkdir(parents=True, exist_ok=True)
Image.new("RGB", (4, 4), (0, 0, 0)).save(out / "demo.png")
"""


def deck_renderer(dest):
    (dest / "artifacts").mkdir(parents=True, exist_ok=True)
    (dest / "artifacts/decks").mkdir(parents=True, exist_ok=True)
    (dest / "artifacts/deck_screens.py").write_text(RENDER)


def stale_page(dest):
    deck_renderer(dest)
    from PIL import Image
    Image.new("RGB", (4, 4), (255, 255, 255)).save(dest / "artifacts/decks/demo.png")


def missing_page(dest):
    deck_renderer(dest)
    from PIL import Image
    Image.new("RGB", (4, 4), (255, 255, 255)).save(dest / "artifacts/decks/other.png")


def broken_renderer(dest):
    (dest / "artifacts/decks").mkdir(parents=True, exist_ok=True)
    (dest / "artifacts/deck_screens.py").write_text(
        "import sys\nsys.stderr.write('boom\\n')\nsys.exit(1)\n")


def empty_renderer(dest):
    (dest / "artifacts/decks").mkdir(parents=True, exist_ok=True)
    (dest / "artifacts/deck_screens.py").write_text("import sys\n")


def drift(dest):
    (dest / ASSETS / WIDGET / (WIDGET + ".ino")).write_text(SKETCH + "// edited later\n")


def unbundled(dest):
    (dest / ASSETS / WIDGET / (WIDGET + ".ino")).unlink()


def rename_sketch(dest):
    descriptor(dest, {"schema": 1, "id": WIDGET, "name": "Demo", "sketch": "elsewhere.ino"})


def no_id(dest):
    descriptor(dest, {"schema": 1, "name": "Demo", "sketch": WIDGET + ".ino"})


def unreadable(dest):
    (dest / WIDGET / "widget.json").write_text("{\n")


def no_assets(dest):
    shutil.rmtree(dest / "android-companion")


def ghost(dest):
    (dest / ASSETS / "ghost").mkdir()


def no_widgets(dest):
    shutil.rmtree(dest / WIDGET)


CASES = [
    dict(
        name="a widget whose copy matches passes",
        mutate=None,
        want_code=0,
        want=["1 widget bundled, every copy matches"],
    ),
    dict(
        name="a bundled copy that drifted is refused, with both md5s",
        mutate=drift,
        want_code=1,
        want=["is stale: md5"],
    ),
    dict(
        name="a bundled file that is missing is refused by name",
        mutate=unbundled,
        want_code=1,
        want=["is missing; copy it from"],
    ),
    dict(
        name="a descriptor naming a sketch that is gone is refused",
        mutate=rename_sketch,
        want_code=1,
        want=["names elsewhere.ino, which is not in"],
    ),
    dict(
        name="a descriptor with no id is refused, because the build would fail",
        mutate=no_id,
        want_code=1,
        want=["has no id"],
    ),
    dict(
        name="a descriptor that does not parse is refused",
        mutate=unreadable,
        want_code=1,
        want=["does not parse"],
    ),
    dict(
        name="a tree with no built assets says to build once",
        mutate=no_assets,
        want_code=1,
        want=["build the app once"],
    ),
    dict(
        name="an asset folder no descriptor claims is refused",
        mutate=ghost,
        want_code=1,
        want=["exists but no descriptor claims id 'ghost'"],
    ),
    dict(
        name="a tree with no widget at all is an error, not a pass",
        mutate=no_widgets,
        want_code=1,
        want=["no widget.json found"],
    ),
    dict(
        name="a deck whose paper page disagrees with the app is refused",
        mutate=wrong_paper,
        want_code=1,
        want=["draws the paper page as #ff00ff", "but Palette.LIGHT_FIELD is"],
    ),
    dict(
        name="a deck whose ink disagrees with the app is refused",
        mutate=wrong_ink,
        want_code=1,
        want=["draws the ink ink as #ff00ff", "but Palette.DARK_TEXT is"],
    ),
    dict(
        name="a deck page that is not today's drawing is refused",
        mutate=stale_page,
        want_code=1,
        want=["artifacts/decks/demo.png is stale: md5", "drawn now", "on disk"],
    ),
    dict(
        name="a deck page the renderer draws but the tree lacks is refused",
        mutate=missing_page,
        want_code=1,
        want=["artifacts/decks/demo.png is missing",
              "draw it with artifacts/deck_screens.py"],
    ),
    dict(
        name="a renderer that fails is refused, not skipped",
        mutate=broken_renderer,
        want_code=1,
        want=["failed, so no deck page could be checked", "boom"],
    ),
    dict(
        name="a renderer that draws nothing is refused, not skipped",
        mutate=empty_renderer,
        want_code=1,
        want=["artifacts/deck_screens.py drew nothing"],
    ),
]


def main():
    env = {k: v for k, v in os.environ.items() if not k.startswith("HEADROOM_")}
    failed = 0
    started = time.time()

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            tree(dest)
            if case["mutate"]:
                case["mutate"](dest)

            done = subprocess.run(
                [sys.executable, str(dest / "check_widget_assets.py")],
                capture_output=True,
                text=True,
                cwd=str(dest),
                env=env,
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [text for text in case["want"] if text not in output]
            if missing:
                problems.append("no " + ", ".join(repr(text) for text in missing))

            if problems:
                failed += 1
                print(f"FAIL {index}. {case['name']}: {'; '.join(problems)}")
                for line in output.strip().splitlines():
                    print(f"     | {line}")
            else:
                print(f"ok   {index}. {case['name']}")

    elapsed = time.time() - started
    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check_widget_assets.py behave ({elapsed:.1f} s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
