#!/usr/bin/env python3
"""Refuse a ``dist/`` APK that is not the one this tree builds.

The archive in ``android-companion/dist/`` is what the site serves, what the
owner installs and what ``HANDOFF.md`` quotes, and the fast gate had no opinion
about it. ``check_docs.py`` asks that HANDOFF print the file's own size, which is
circular, and the step that proves the bytes is ``--full``'s rebuild — so a
stale archive survived any commit that did not build, and it is exactly the
archive someone downloads.

Three questions need no SDK and no build: the file is a zip, it carries the
manifest and a dex, and every sketch a widget descriptor names is inside it
byte for byte. The fourth is a decision rather than a measurement: the size
ceiling. A full partition is refused by ``check_headroom.py``; the app's own
weight should be refused the same way, so growing it is an edit with a reason
instead of a number nobody reads.

  python3 check_apk.py       exit 0 when dist/ is this tree's own build
"""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APK = ROOT / "android-companion" / "dist" / "PhoneDeckBridge-debug.apk"

# 320 kB. The build measured 239,217 B when this was set, so there is room for a
# screen or a feature and not for a dependency that doubles the app. Raise it in
# the same change that grows the app, and say why in that message.
CEILING = 327_680


def shown(path):
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def described():
    """Every widget the tree holds: its id, its sketch's name, and the sketch."""
    for descriptor in sorted(ROOT.glob("*/widget.json")):
        try:
            data = json.loads(descriptor.read_text(encoding="utf-8"))
        except ValueError:
            continue
        if not isinstance(data, dict):
            continue
        folder = descriptor.parent
        identity = str(data.get("id") or folder.name).strip()
        sketch = str(data.get("sketch") or (folder.name + ".ino")).strip()
        source = folder / sketch
        if source.is_file():
            yield identity, sketch, source


def main():
    problems = []

    if not APK.is_file():
        problems.append("%s is missing, so there is nothing to ship"
                        % shown(APK))
        return report(problems, "")

    size = APK.stat().st_size
    if size > CEILING:
        problems.append(
            "%s is %s bytes, over the %s-byte ceiling; raise the ceiling in this "
            "checker in the same change that grows the app"
            % (shown(APK), "{:,}".format(size), "{:,}".format(CEILING)))

    if not zipfile.is_zipfile(APK):
        problems.append("%s is not a zip archive, so a truncated build would ship"
                        % shown(APK))
        return report(problems, "")

    sketches = 0
    with zipfile.ZipFile(APK) as archive:
        names = set(archive.namelist())
        for required in ("classes.dex", "AndroidManifest.xml"):
            if required not in names:
                problems.append("%s carries no %s, so the build did not finish"
                                % (shown(APK), required))
        for identity, sketch, source in described():
            entry = "assets/widgets/%s/%s" % (identity, sketch)
            if entry not in names:
                problems.append(
                    "%s carries no %s, so syncWidgets did not run before the "
                    "archive was made" % (shown(APK), entry))
                continue
            bundled = hashlib.md5(archive.read(entry)).hexdigest()
            built = hashlib.md5(source.read_bytes()).hexdigest()
            if bundled != built:
                problems.append(
                    "%s carries a stale %s: bundled %s, this tree %s"
                    % (shown(APK), sketch, bundled[:8], built[:8]))
            else:
                sketches += 1

    digest = hashlib.sha256(APK.read_bytes()).hexdigest()
    return report(problems, "%s B of a %s B ceiling, sha256 %s, %d sketch cop%s match"
                  % ("{:,}".format(size), "{:,}".format(CEILING), digest[:16],
                     sketches, "y" if sketches == 1 else "ies"))


def report(problems, measurement):
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    print(measurement)
    return 0


if __name__ == "__main__":
    sys.exit(main())
