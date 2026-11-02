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
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "android-companion/app/src/main/assets/widgets"
NOT_A_WIDGET = {"android-companion"}


def md5(path):
    return hashlib.md5(path.read_bytes()).hexdigest()


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

    broken = sum(1 for row in rows if row[3] != "in sync") + len(on_disk - expected)
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
