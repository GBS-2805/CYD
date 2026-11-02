#!/usr/bin/env python3
"""Fail when a render's text runs off the canvas or lands on other text.

[`check_renders.py`](check_renders.py) keeps a committed PNG matching what the
renderer draws, and says nothing about whether what it draws is readable. A card
measured with the wrong line height, a caption placed 6dp too high, a long label in
a column too narrow — each of those is a picture that still "matches" and still
misleads, which is the one thing a render must not do: it is the page a change is
judged by.

So the renderer records every run of text it paints, and this walks those records.
Two answers the pixels cannot give:

  * nothing crosses the canvas edge — a run that is cut off was never reviewed;
  * no two runs overlap — text on text is a bug in the layout, not a rendering
    artifact, and it survives every screenshot.

  ./check_render_fit.py            every screen
  ./check_render_fit.py home       just the ones named

Exits 0 when every screen is clean, 1 when it finds something, 2 for a name the
renderer does not know. It draws in memory and writes nothing.
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
    sys.path.insert(0, str(APP))
    try:
        import render_ui
    except ImportError as exc:
        print("cannot draw from %s: %s" % (shown(APP / "render_ui.py"), exc))
        print("  the renderer needs Pillow: python3 -m pip install pillow")
        return None
    return render_ui


def overlap(a, b):
    """Boxes that share more than half a dp in both directions."""
    return (a[0] < b[0] + b[2] - 0.5 and b[0] < a[0] + a[2] - 0.5
            and a[1] < b[1] + b[3] - 0.5 and b[1] < a[1] + a[3] - 0.5)


def audit(render_ui, name):
    """Draw the screen and report everything wrong with what it painted.

    A run the renderer marked `clipped` sits in a scroller (`HorizontalScrollView`),
    where passing the edge is the app's own behaviour: the row is as wide as its
    chips and a thumb reaches the last one. It is counted, not failed.
    """
    render_ui.Screen.instances.clear()
    render_ui.SCREENS[name]()
    problems = []
    clipped = 0

    for canvas in render_ui.Screen.instances:
        width, height = render_ui.W_DP, canvas.height_dp
        for x, y, w, h, text, is_clipped in canvas.runs:
            label = text if len(text) <= 46 else text[:43] + "..."
            if is_clipped:
                clipped += 1
            elif x < -0.5 or x + w > width + 0.5:
                problems.append("%s: %r runs %s..%s dp across a %d dp canvas"
                                % (name, label, round(x, 1), round(x + w, 1), width))
            if y < -0.5 or y + h > height + 0.5:
                problems.append("%s: %r sits at %s..%s dp of a %d dp canvas"
                                % (name, label, round(y, 1), round(y + h, 1), height))

        runs = canvas.runs
        for i in range(len(runs)):
            for j in range(i + 1, len(runs)):
                if overlap(runs[i], runs[j]):
                    problems.append("%s: %r lands on %r at y=%s"
                                    % (name, runs[i][4][:34], runs[j][4][:34],
                                       round(runs[i][1], 1)))
    return problems, clipped


def audit_host(render_ui, name):
    """Audit a frame the app measured, from the operations it produced.

    These are the numbers the phone would use: the runs come out of `render_host`'s
dump, where `DrawerHome` decided every position, so a run that crosses an edge is a
    real fault in the app rather than a mistake in a transcription.
    """
    frame = render_ui.host_frames()[name]
    offset = render_ui.BAR_DP
    width = frame["width"]
    height = frame["height"] + offset
    runs = [(op["x"], op["y"] + offset, op["w"], op["h"], op["text"])
            for op in frame["ops"] if op["op"] == "text"]
    problems = []

    for x, y, w, h, text in runs:
        label = text if len(text) <= 46 else text[:43] + "..."
        if x < -0.5 or x + w > width + 0.5:
            problems.append("%s: %r runs %s..%s dp across a %d dp canvas"
                            % (name, label, round(x, 1), round(x + w, 1), width))
        if y < -0.5 or y + h > height + 0.5:
            problems.append("%s: %r sits at %s..%s dp of a %d dp canvas"
                            % (name, label, round(y, 1), round(y + h, 1), height))

    for i in range(len(runs)):
        for j in range(i + 1, len(runs)):
            if overlap(runs[i], runs[j]):
                problems.append("%s: %r lands on %r at y=%s"
                                % (name, runs[i][4][:34], runs[j][4][:34],
                                   round(runs[i][1], 1)))
    return problems, 0


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

    rows, problems = [], []
    for name in wanted:
        if name in render_ui.HOSTED:
            found, clipped = audit_host(render_ui, name)
        else:
            found, clipped = audit(render_ui, name)
        problems.extend(found)
        rows.append((name, len(found), clipped))

    width = max(len(name) for name, _, _ in rows)
    for name, count, clipped in rows:
        state = "clean" if not count else "%d problem%s" % (count, "" if count == 1 else "s")
        if clipped:
            state += "  (%d run%s in a scroller)" % (clipped, "" if clipped == 1 else "s")
        print("%-*s  %s" % (width, name, state))

    if problems:
        for problem in problems:
            print("  - %s" % problem)
        print("\n%d problem%s in %d screen%s. The render is what the owner judges "
              "the change by, so text that is cut off or stacked is a review that "
              "cannot happen." % (len(problems), "" if len(problems) == 1 else "s",
                                  len(rows), "" if len(rows) == 1 else "s"))
        return 1

    print("\n%d screen%s clean: every run inside its canvas, no run on another"
          % (len(rows), "" if len(rows) == 1 else "s"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
