#!/usr/bin/env python3
"""Fail when the owner-only handoff enters the public site bundle.

The handoff is kept at `artifacts/site/handoff.html` for the owner and deliberately
left out of the public bundle built by `artifacts/publish_renders.py`. Removing the
copy once is not a rule: a later publisher edit could put it back, and a stale
`public/` directory could keep serving it after the source stopped being copied.

So this checks both places. It scans the bundle in the working tree when one exists,
then copies the publisher and its inputs into a throwaway tree with a canary
`handoff.html` and runs the real publisher there. A copy by name or a renamed copy
with the same bytes is refused in either bundle.

The working bundle is build output and may not exist in a fresh clone; the
throwaway build is still made, so the check never passes merely because nobody had
built the site yet.

  ./check_publish.py       exit 0 when neither bundle carries the handoff
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PUBLISHER = ROOT / "artifacts" / "publish_renders.py"
SITE = ROOT / "artifacts" / "site"
HANDOFF = SITE / "handoff.html"
PUBLIC = SITE / "public"


def shown(path):
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def files_in(bundle):
    return sorted(path for path in bundle.rglob("*") if path.is_file())


def leaks(bundle, handoff):
    """Files in a bundle that carry the handoff by name or by exact bytes."""
    body = handoff.read_bytes() if handoff.is_file() else None
    found = []
    for path in files_in(bundle):
        if "handoff" in path.name.lower():
            found.append(path)
            continue
        if body is not None and path.stat().st_size == len(body):
            if path.read_bytes() == body:
                found.append(path)
    return found


def build_in_temp():
    """Run the real publisher in a throwaway tree and inspect what it writes.

    The source handoff is present beside the publisher, as it is in the working
    tree, so a future copy step has something to copy and this catches it.
    """
    with tempfile.TemporaryDirectory(prefix="offsite-publish-") as tmp:
        root = Path(tmp)
        artifacts = root / "artifacts"
        site = artifacts / "site"
        site.mkdir(parents=True)

        shutil.copyfile(PUBLISHER, artifacts / "publish_renders.py")
        for name in ("renders", "decks"):
            source = ROOT / "artifacts" / name
            if source.is_dir():
                shutil.copytree(source, artifacts / name)

        logo = ROOT / "artifacts" / "logo-compare.png"
        if logo.is_file():
            shutil.copyfile(logo, artifacts / logo.name)

        apk = ROOT / "android-companion" / "dist" / "PhoneDeckBridge-debug.apk"
        if apk.is_file():
            dist = root / "android-companion" / "dist"
            dist.mkdir(parents=True)
            shutil.copyfile(apk, dist / apk.name)

        if HANDOFF.is_file():
            shutil.copyfile(HANDOFF, site / HANDOFF.name)
        else:
            (site / "handoff.html").write_text(
                "<!doctype html><title>owner handoff</title>\n", encoding="utf-8")

        done = subprocess.run(
            [sys.executable, str(artifacts / "publish_renders.py")],
            cwd=str(root),
            capture_output=True,
            text=True,
        )
        if done.returncode != 0:
            lines = (done.stderr or done.stdout).strip().splitlines()
            return None, "failed", lines[-1] if lines else ""

        public = site / "public"
        if not public.is_dir():
            return None, "missing", ""
        found = [path.relative_to(public).as_posix()
                 for path in leaks(public, site / HANDOFF.name)]
        return found, "built", len(files_in(public))


def main():
    problems = []

    if not PUBLISHER.is_file():
        problems.append("there is no publisher at %s, so the public bundle cannot be "
                        "checked" % shown(PUBLISHER))
    else:
        if PUBLIC.is_dir():
            current = leaks(PUBLIC, HANDOFF)
            print("working bundle: %d files%s"
                  % (len(files_in(PUBLIC)), ", handoff found" if current else ""))
            for path in current:
                problems.append("%s is in the public bundle; the handoff is owner-only "
                                "and must not be published" % shown(path))
        else:
            print("working bundle: not built; checking a throwaway build")

        fresh, status, detail = build_in_temp()
        if status == "failed":
            problems.append("the publisher failed, so a fresh bundle could not be "
                            "checked")
            if detail:
                print("  publisher: %s" % detail)
        elif status == "missing":
            problems.append("the publisher wrote no public bundle, so a fresh build "
                            "was not checked")
        else:
            print("throwaway build: %d files%s"
                  % (detail, ", handoff found" if fresh else ""))
            for path in fresh:
                problems.append("a fresh build put %s in the public bundle; the "
                                "handoff is owner-only" % path)

    if problems:
        print()
        for text in problems:
            print("  - %s" % text)
        print("\n%d problem(s): the handoff is owner-only and must stay out of the "
              "public bundle" % len(problems))
        return 1

    print("\npublic bundle carries no handoff")
    return 0


if __name__ == "__main__":
    sys.exit(main())
