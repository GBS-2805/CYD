#!/usr/bin/env python3
"""Fail when a committed render no longer shows what the app shows.

CONVENTIONS.md makes the linked render a gate before a UI change ships, and keeps
the PNGs in `artifacts/renders/` committed beside the app they came from. A gate
nobody runs goes quiet instead of loud: edit `Ui.java` or a tab, forget
`capture.sh`, and the picture from the last review still looks like the app while
the app has moved on. Nothing catches that, because a PNG is not compiled.

So this asks the capture itself. It runs the app's screens through Android's
renderer again — the same test `capture.sh` runs — and compares the pixels with the
PNG under version control, so the answer cannot drift from the picture: whatever
the app draws now is what the committed file has to be.

Modification times would not answer it. Git stores no mtime, so a clone or a fresh
checkout stamps every file with the same time and the silence would read as a pass.
Pixels survive that, and a screen that gained a line of copy changes them.

  ./check_renders.py            exit 0 when every committed render is current
  ./check_renders.py home       check only the screens named

It needs the toolchain (`../toolchain.sh --setup-only`) because it really does run
the app, and writes nothing: `android-companion/capture.sh` installs a fresh one.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "android-companion"
FRESH = APP / "app" / "build" / "capture"
SCREENS = ["home", "home-open", "paired", "projects", "projects-open", "projects-edit",
           "serial", "settings"]


def shown(path):
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def capture():
    """Take the frames again, into the build directory, without touching the repo."""
    script = APP / "capture.sh"
    if not script.is_file():
        print("%s is missing: there is no capture to compare against" % shown(script))
        return False
    print("capturing from the app (one Gradle test run) ...", flush=True)
    done = subprocess.run(["bash", str(script), "--capture-only"], cwd=str(APP))
    if done.returncode != 0:
        print("the capture failed, exit %d: nothing was compared" % done.returncode)
        return False
    return True


def main(argv):
    wanted = argv[1:] or SCREENS
    unknown = [name for name in wanted if name not in SCREENS]
    if unknown:
        print("unknown screen%s: %s" % ("" if len(unknown) == 1 else "s",
                                        ", ".join(unknown)))
        print("  the capture knows: %s" % ", ".join(SCREENS))
        return 2

    from PIL import Image

    if not capture():
        return 2

    rows = []
    problems = []
    for name in wanted:
        fresh = FRESH / (name + ".png")
        committed = ROOT / "artifacts" / "renders" / (name + ".png")
        if not fresh.is_file():
            rows.append((name, "not taken", "the capture wrote no %s" % name))
            problems.append("%s was not captured; the test does not build that screen"
                            % name)
            continue
        if not committed.is_file():
            rows.append((name, "missing", "carries no render"))
            problems.append("%s does not exist; run android-companion/capture.sh and "
                            "commit it" % shown(committed))
            continue
        try:
            drawn = Image.open(fresh).convert("RGB")
            stored = Image.open(committed).convert("RGB")
        except OSError as exc:
            rows.append((name, "unreadable", str(exc)))
            problems.append("%s does not open as a PNG: %s" % (shown(committed), exc))
            continue

        if stored.size != drawn.size:
            rows.append((name, "stale", "%dx%d committed, %dx%d captured"
                         % (stored.size + drawn.size)))
            problems.append("%s is %dx%d but the app draws %dx%d; run capture.sh"
                            % ((shown(committed),) + stored.size + drawn.size))
        elif stored.tobytes() != drawn.tobytes():
            rows.append((name, "stale", "pixels differ from what the app draws"))
            problems.append("%s no longer matches the app; run capture.sh"
                            % shown(committed))
        else:
            rows.append((name, "current", "%dx%d" % drawn.size))

    width = max(len(name) for name, _, _ in rows)
    for name, state, detail in rows:
        print("%-*s  %-9s %s" % (width, name, state, detail))

    if problems:
        for line in problems:
            print("  - %s" % line)
        print("\n%d of %d render%s stale. A screen nobody looked at is a change to "
              "be undone later, so capture again before this ships."
              % (len(problems), len(rows), "" if len(rows) == 1 else "s"))
        return 1

    print("\n%d render%s current: what is committed is what the app shows"
          % (len(rows), "" if len(rows) == 1 else "s"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
