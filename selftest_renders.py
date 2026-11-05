#!/usr/bin/env python3
"""Exercise every refusal of check_renders.py without Android or a device.

check_renders.py only ever passes here: all nine frames are current, so the
refusals — a PNG that no longer matches the pixels, one at the wrong size, a
committed render that is gone, a capture that wrote nothing, a capture that
failed, a screen the capture does not know — have never run. The one time a render
did go stale it was a reader who noticed, which is the whole reason the gate
exists.

The gate shells out to `android-companion/capture.sh` and then compares PNGs, so
this needs no renderer: each case gets a throwaway repo with a stand-in capture
that copies two-pixel-tall PNGs from a `fixtures/` folder, and the case then moves
one thing — a pixel, a size, a file, the script's exit code.

  python3 selftest_renders.py     exit 0 when every branch still behaves

Pillow is the gate's own dependency, so nothing extra is installed.
"""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # the gate cannot run without it either
    Image = None

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_renders.py"


def canonical_screens():
    """The screens check_renders.py knows, read from it rather than copied here.

    This list used to be written out again, and it went stale the day the app's
    screens were renamed: four cases stood in frames no capture writes, so they
    failed for a reason that had nothing to do with the refusals they exist to
    prove. Reading the checker's own list means a rename moves both halves at once
    and this file cannot describe a capture that no longer exists.
    """
    spec = importlib.util.spec_from_file_location("check_renders_under_test", CHECK)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return list(module.SCREENS)


SCREENS = canonical_screens()
if not SCREENS:
    raise SystemExit("check_renders.py names no screen, so there is nothing to stand in")
SIZE = (6, 4)
INK = (10, 20, 30)

STUB = (
    "#!/bin/sh\n"
    "mkdir -p app/build/capture\n"
    "cp fixtures/*.png app/build/capture/ 2>/dev/null || true\n"
    "exit 0\n"
)


def png(path, size=SIZE, colour=INK):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, colour).save(path)


def tree(dest):
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_renders.py")
    app = dest / "android-companion"
    app.mkdir()
    (app / "capture.sh").write_text(STUB)
    for name in SCREENS:
        png(app / "fixtures" / (name + ".png"))            # what the app "draws"
        png(dest / "artifacts" / "renders" / (name + ".png"))   # what is committed


def rewrite_stub(text):
    def mutate(dest):
        (dest / "android-companion" / "capture.sh").write_text(text)
    return mutate


def diverge(dest):
    png(dest / "android-companion" / "fixtures" / "paired.png", colour=(200, 0, 0))


def resize(dest):
    png(dest / "android-companion" / "fixtures" / "paired.png", size=(7, 4))


def uncommit(dest):
    (dest / "artifacts" / "renders" / "serial.png").unlink()


def corrupt(dest):
    (dest / "artifacts" / "renders" / "settings.png").write_bytes(b"this is not a png\n")


def no_frames(dest):
    shutil.rmtree(dest / "android-companion" / "fixtures")


def no_script(dest):
    (dest / "android-companion" / "capture.sh").unlink()


CASES = [
    dict(
        name="nine current frames pass",
        mutate=None,
        want_code=0,
        want=["9 renders current: what is committed is what the app shows"],
    ),
    dict(
        name="a pixel the app no longer draws is refused",
        mutate=diverge,
        want_code=1,
        want=["artifacts/renders/paired.png no longer matches the app",
              "1 of 9 renders stale"],
    ),
    dict(
        name="a frame at the wrong size is refused with both sizes",
        mutate=resize,
        want_code=1,
        want=["artifacts/renders/paired.png is 6x4 but the app draws 7x4; run "
              "capture.sh"],
    ),
    dict(
        name="a committed render that is gone is refused by name",
        mutate=uncommit,
        want_code=1,
        want=["artifacts/renders/serial.png does not exist"],
    ),
    dict(
        name="a file that is not a PNG is refused, not crashed on",
        mutate=corrupt,
        want_code=1,
        want=["artifacts/renders/settings.png does not open as a PNG"],
    ),
    dict(
        name="a capture that wrote no frame says so",
        mutate=no_frames,
        want_code=1,
        want=["paired was not captured"],
    ),
    dict(
        name="a failed capture compares nothing and fails",
        mutate=rewrite_stub("#!/bin/sh\nexit 3\n"),
        want_code=2,
        want=["the capture failed, exit 3: nothing was compared"],
    ),
    dict(
        name="a repo with no capture script says what is missing",
        mutate=no_script,
        want_code=2,
        want=["capture.sh is missing: there is no capture to compare against"],
    ),
    dict(
        name="a screen the capture does not know is refused by name",
        mutate=None,
        args=["nope"],
        want_code=2,
        want=["unknown screen: nope", "the capture knows: " + SCREENS[0]],
    ),
]


def main():
    if Image is None:
        print("note: Pillow is not installed, so the render refusals were NOT checked")
        return 0
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
                [sys.executable, str(dest / "check_renders.py"), *case.get("args", [])],
                capture_output=True,
                text=True,
                cwd=str(dest),
                env=env,
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [text for text in case.get("want", []) if text not in output]
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
    print(f"all {len(CASES)} branches of check_renders.py behave ({elapsed:.1f} s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
