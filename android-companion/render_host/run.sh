#!/usr/bin/env bash
#
# Lay the screens out with the app's own Java and write down what it measured.
#
#   ./run.sh                 build and dump into ../build/render-host/dump.json
#   ./run.sh --keep          skip the clean, for a fast re-run while editing
#
# The app's source is compiled, not copied-and-edited: `Ui.java` and `DrawerHome.java`
# come straight out of `app/src/main/java`, and the only things standing in for the
# platform are `stubs.py`'s Android classes and four fixtures the phone would supply.
# Needs nothing but a JDK — no Android SDK, no Gradle, no device.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${OFFSITE_HOME:-$HOME/.android-offsite}"
APP="$HERE/../app/src/main/java/dev/phonedesk/companion"
OUT="$HERE/../build/render-host"
CLEAN=1
for arg in "$@"; do
  case "$arg" in
    --keep) CLEAN=0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  BIN="$JAVA_HOME/bin"
elif [ -x "$ROOT/jdk/bin/javac" ]; then
  BIN="$ROOT/jdk/bin"
else
  BIN="$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/javac)")"
fi
[ -x "$BIN/javac" ] || {
  echo "error: no javac; run ../toolchain.sh --setup-only first, or set JAVA_HOME" >&2
  exit 1
}

[ "$CLEAN" -eq 1 ] && rm -rf "$OUT"
mkdir -p "$OUT/src" "$OUT/classes"

# The stand-ins, regenerated every run so this file cannot drift from the build.
python3 "$HERE/stubs.py" "$OUT/src"

# The app's own display code, verbatim.
mkdir -p "$OUT/src/dev/phonedesk/companion"
cp "$APP/Ui.java" "$APP/DrawerHome.java" "$OUT/src/dev/phonedesk/companion/"

# The harness: the measured views and the dump.
cp -r "$HERE/src/." "$OUT/src/"

printf '== render host (%s)\n' "$("$BIN/java" -version 2>&1 | awk -F'"' 'NR == 1 {print $2}')"
mapfile -t SOURCES < <(find "$OUT/src" -name '*.java' | sort)
"$BIN/javac" -encoding UTF-8 -nowarn -d "$OUT/classes" "${SOURCES[@]}"
"$BIN/java" -Djava.awt.headless=true -cp "$OUT/classes" \
  dev.phonedesk.companion.Dump "$OUT/dump.json"
printf '  %s\n' "$OUT/dump.json"
