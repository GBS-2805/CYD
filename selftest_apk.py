#!/usr/bin/env python3
"""Prove ``check_apk.py`` still refuses an archive that is not the tree's build.

A checker for a committed binary is the kind that rots silently: every refusal in
``check_apk.py`` is a branch the real tree never takes, because the archive here
is always the build. Each case below copies the checker into a throwaway repo, lays
out a small widget beside a small APK, and makes exactly one thing wrong — a stale
sketch, a truncated file, a manifest with no dex in it, an archive over the
ceiling, no archive at all. Only the last two need no zip at all.

  python3 selftest_apk.py     exit 0 when the checker still refuses every one
"""
import importlib.util
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_apk.py"

SKETCH = b"void setup() { }\nvoid loop() { }\n"
OLDER = b"void setup() { }  // the sketch before the last edit\n"


def ceiling():
    """The checker's own number, so a raised ceiling does not strand this fixture."""
    spec = importlib.util.spec_from_file_location("check_apk", CHECK)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.CEILING


def tree(base, archive=True, bundled=SKETCH, dex=True, zipped=True, size=None):
    """A repo with one widget, and an APK carrying it — or one thing wrong."""
    root = Path(base) / "repo"
    dist = root / "android-companion" / "dist"
    dist.mkdir(parents=True)
    widget = root / "demo_deck"
    widget.mkdir()
    (widget / "demo_deck.ino").write_bytes(SKETCH)
    (widget / "widget.json").write_text(
        '{\n  "id": "demo_deck",\n  "sketch": "demo_deck.ino"\n}\n', encoding="utf-8")
    apk = dist / "PhoneDeckBridge-debug.apk"
    if archive:
        if not zipped:
            apk.write_bytes(b"PK\x03\x04" + b"\x00" * 64)
        else:
            with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as archive_file:
                if dex:
                    archive_file.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 32)
                archive_file.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00" + b"\x00" * 16)
                archive_file.writestr("assets/widgets/demo_deck/demo_deck.ino", bundled)
            if size is not None:
                # Padding after the archive keeps it a valid zip and still counts
                # against the file's size, which is what the ceiling measures.
                with open(apk, "ab") as handle:
                    handle.write(b"\x00" * max(0, size - apk.stat().st_size))
    return root


def build(root):
    shutil.copyfile(CHECK, root / "check_apk.py")
    return root


def run(root):
    return subprocess.run(
        [sys.executable, str(root / "check_apk.py")],
        capture_output=True,
        text=True,
        cwd=str(root),
    )


def case_built(base):
    return build(tree(base))


def case_stale(base):
    return build(tree(base, bundled=OLDER))


def case_truncated(base):
    return build(tree(base, zipped=False))


def case_no_dex(base):
    return build(tree(base, dex=False))


def case_over_ceiling(base):
    return build(tree(base, size=ceiling() + 4096))


def case_missing(base):
    return build(tree(base, archive=False))


CASES = [
    dict(
        name="the archive this tree builds is accepted",
        build=case_built,
        code=0,
        want=["sketch cop"],
        forbids=["stale", "over the", "not a zip", "carries no", "is missing"],
    ),
    dict(
        name="a sketch the tree has changed since refuses",
        build=case_stale,
        code=1,
        want=["carries a stale"],
        forbids=[],
    ),
    dict(
        name="an archive that is not a zip refuses",
        build=case_truncated,
        code=1,
        want=["is not a zip archive"],
        forbids=[],
    ),
    dict(
        name="a build with no dex refuses",
        build=case_no_dex,
        code=1,
        want=["carries no classes.dex"],
        forbids=[],
    ),
    dict(
        name="an archive over the ceiling refuses",
        build=case_over_ceiling,
        code=1,
        want=["over the"],
        forbids=[],
    ),
    dict(
        name="a missing archive refuses",
        build=case_missing,
        code=1,
        want=["is missing"],
        forbids=[],
    ),
]


def main():
    failures = 0
    for case in CASES:
        with tempfile.TemporaryDirectory() as tmp:
            root = case["build"](tmp)
            result = run(root)
            said = (result.stdout + result.stderr)
            trouble = []
            if result.returncode != case["code"]:
                trouble.append("exit %d, wanted %d" % (result.returncode, case["code"]))
            for phrase in case["want"]:
                if phrase not in said:
                    trouble.append("did not say %r" % phrase)
            for phrase in case["forbids"]:
                if phrase in said:
                    trouble.append("said %r, which it should not" % phrase)
            if trouble:
                failures += 1
                print("FAIL  %s: %s" % (case["name"], "; ".join(trouble)))
                for line in said.strip().splitlines():
                    print("      %s" % line)
            else:
                print("ok    %s" % case["name"])
    if failures:
        print("%d of %d cases failed" % (failures, len(CASES)))
        return 1
    print("%d branches of check_apk refuse what they exist for" % len(CASES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
