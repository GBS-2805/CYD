#!/usr/bin/env python3
"""Exercise check_repro.py without Android, Gradle or a keystore.

The check passes here because the build really is reproducible today, so its
refusals — two archives of different bytes, of different sizes, a build that
exits non-zero, a build that ships nothing — would never run. Each case hands the
checker a stand-in build command that writes a file twice: the same bytes, one
byte of drift, a byte more, an exit code, or nothing at all.

  python3 selftest_repro.py      exit 0 when every branch still behaves
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_repro.py"

# A stand-in build: writes N bytes into the artifact the checker is watching.
WRITES = (
    "#!/bin/sh\n"
    "mkdir -p dist\n"
    "printf '%s' \"$PAYLOAD\" > dist/app.apk\n"
)

# A stand-in build that counts its runs, so the second one can drift by a byte.
DRIFTS = (
    "#!/bin/sh\n"
    "mkdir -p dist\n"
    "n=$(cat count 2>/dev/null || echo 0)\n"
    "n=$((n + 1))\n"
    "echo \"$n\" > count\n"
    "printf 'build %s\\n' \"$n\" > dist/app.apk\n"
)


def tree(dest, script, payload="same bytes\n"):
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "check_repro.py").write_text(
        (ROOT / "check_repro.py").read_text(encoding="utf-8"), encoding="utf-8")
    (dest / "build.sh").write_text(script, encoding="utf-8")
    env = dict(os.environ, PAYLOAD=payload)
    return env


CASES = [
    dict(name="a build that ships the same bytes twice passes", script=WRITES,
         want_code=0, want=["two builds, same bytes"], forbids=["not reproducible"]),
    dict(name="a build whose bytes move is refused", script=DRIFTS,
         want_code=1,
         want=["two builds of one tree gave", "not reproducible",
               "build(s) produced different bytes"]),
    dict(name="a build that fails is an error, not a pass", script="exit 3\n",
         want_code=2, want=["the build command failed, so there is nothing to compare"]),
    dict(name="a build that ships nothing is an error, not a pass",
         script="#!/bin/sh\nexit 0\n", want_code=2,
         want=["wrote no", "nothing to compare"]),
]


def main():
    base_env = {key: value for key, value in os.environ.items()}
    failed = 0

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            env = tree(dest, case["script"])

            done = subprocess.run(
                [sys.executable, str(dest / "check_repro.py"),
                 "--artifact", "dist/app.apk",
                 "--build-cmd", "sh build.sh"],
                capture_output=True, text=True, cwd=str(dest), env=env,
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [t for t in case["want"] if t not in output]
            if missing:
                problems.append("no " + ", ".join(repr(t) for t in missing))
            unexpected = [t for t in case.get("forbids", []) if t in output]
            if unexpected:
                problems.append("unasked-for " + ", ".join(map(repr, unexpected)))

            if problems:
                failed += 1
                print(f"FAIL {index}. {case['name']}: {'; '.join(problems)}")
                for line in output.strip().splitlines():
                    print(f"     | {line}")
            else:
                print(f"ok   {index}. {case['name']}")

    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check_repro.py behave")
    return 0


if __name__ == "__main__":
    sys.exit(main())
