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
WIDGET = "demo_deck_cyd24R"
SKETCH = "/* stand-in widget */\nvoid setup() {}\nvoid loop() {}\n"
ASSETS = "android-companion/app/src/main/assets/widgets"


def tree(dest):
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_widget_assets.py")
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
