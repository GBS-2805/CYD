#!/usr/bin/env python3
"""Fail when a committed render no longer shows what the app draws.

CONVENTIONS.md makes the linked render a gate before a UI change ships, and keeps
the PNGs in `artifacts/renders/` committed beside the code that draws them. A gate
nobody runs goes quiet instead of loud: edit `Ui.java` or a tab, skip the renderer,
and the picture from the last review still looks like the app while the app has
moved on. Nothing catches that, because a PNG is not compiled.

So this asks the renderer itself. It draws every screen again in memory and
compares the pixels with the PNG under version control, which means the answer
cannot drift from the picture: whatever `render_ui.py` would write now is what the
committed file has to be.

Modification times would not answer it. Git stores no mtime, so a clone or a fresh
checkout stamps every file with the same time and the silence would read as a pass.
Pixels survive that, and a renderer that gained a line of copy changes them.

  ./check_renders.py            exit 0 when every committed render is current
  ./check_renders.py home       check only the screens named (any render_ui.py knows)

It needs Pillow, like the renderer, and writes nothing: use
`python3 android-companion/render_ui.py [screen]` to bring a stale one up to date.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "android-companion"


def shown(path):
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def load_renderer():
    """The renderer is the only source of truth for what a screen looks like."""
    sys.path.insert(0, str(APP))
    try:
        import render_ui
    except ImportError as exc:
        print("cannot draw from %s: %s" % (shown(APP / "render_ui.py"), exc))
        print("  the renderer needs Pillow: python3 -m pip install pillow")
        return None
    return render_ui


def main(argv):
    render_ui = load_renderer()
    if render_ui is None:
        return 2

    wanted = argv[1:] or list(render_ui.SCREENS)
    unknown = [name for name in wanted if name not in render_ui.SCREENS]
    if unknown:
        print("unknown screen%s: %s" % ("" if len(unknown) == 1 else "s",
                                        ", ".join(unknown)))
        print("  render_ui.py knows: %s" % ", ".join(render_ui.SCREENS))
        return 2

    from PIL import Image

    out = Path(render_ui.OUT)
    rows = []
    problems = []
    for name in wanted:
        drawn = render_ui.SCREENS[name]().convert("RGB")
        committed = out / (name + ".png")
        if not committed.is_file():
            rows.append((name, "missing", "carries no render"))
            problems.append("%s does not exist; run render_ui.py %s and commit it"
                            % (shown(committed), name))
            continue
        try:
            stored = Image.open(committed).convert("RGB")
        except OSError as exc:
            rows.append((name, "unreadable", str(exc)))
            problems.append("%s does not open as a PNG: %s"
                            % (shown(committed), exc))
            continue

        if stored.size != drawn.size:
            rows.append((name, "stale", "%dx%d committed, %dx%d drawn"
                         % (stored.size + drawn.size)))
            problems.append("%s is %dx%d but the app draws %dx%d; run render_ui.py %s"
                            % ((shown(committed),) + stored.size + drawn.size + (name,)))
        elif stored.tobytes() != drawn.tobytes():
            rows.append((name, "stale", "pixels differ from what the app draws"))
            problems.append("%s no longer matches the app; run render_ui.py %s"
                            % (shown(committed), name))
        else:
            rows.append((name, "current", "%dx%d" % drawn.size))

    width = max(len(name) for name, _, _ in rows)
    for name, state, detail in rows:
        print("%-*s  %-9s %s" % (width, name, state, detail))

    if problems:
        for line in problems:
            print("  - %s" % line)
        print("\n%d of %d render%s stale. A screen nobody looked at is a change to "
              "be undone later, so regenerate before this ships."
              % (len(problems), len(rows), "" if len(rows) == 1 else "s"))
        return 1

    print("\n%d render%s current: what is committed is what the app draws"
          % (len(rows), "" if len(rows) == 1 else "s"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
