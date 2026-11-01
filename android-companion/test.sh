#!/usr/bin/env bash
#
# Host tests for the ROM protocol: compile EspRom.java against a stub serial port
# and run it on the JVM. No phone, no board, no emulator, no Android SDK — so this
# is the one check that can run anywhere, in seconds, including while the board is
# unplugged.
#
#   ./test.sh                        use $JAVA_HOME, then ~/.android-offsite, then PATH
#   JAVA_HOME=/path/to/jdk ./test.sh
#
# toolchain.sh runs this before every Gradle build, so a green APK means these
# passed too.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${OFFSITE_HOME:-$HOME/.android-offsite}"
PKG="$HERE/app/src/main/java/dev/phonedesk/companion"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  BIN="$JAVA_HOME/bin"
elif [ -x "$ROOT/jdk/bin/javac" ]; then
  BIN="$ROOT/jdk/bin"
else
  BIN="$(dirname "$(command -v javac 2>/dev/null || echo /nonexistent/javac)")"
fi
[ -x "$BIN/javac" ] || {
  echo "error: no javac; run ./toolchain.sh --setup-only first, or set JAVA_HOME" >&2
  exit 1
}

OUT="$HERE/build/host"
rm -rf "$OUT"
mkdir -p "$OUT/src/dev/phonedesk/companion" "$OUT/classes"

# The real protocol source, verbatim, plus a stand-in for the USB driver it is
# handed. Only these two files are compiled: the rest of the app is Android.
cp "$PKG/EspRom.java" "$OUT/src/dev/phonedesk/companion/"
cp "$PKG/SpotifyLink.java" "$OUT/src/dev/phonedesk/companion/"
cp "$HERE/test/stub/UsbSerialCh340.java" "$OUT/src/dev/phonedesk/companion/"
cp "$HERE/test/HostTests.java" "$OUT/src/dev/phonedesk/companion/"

printf '\n== host tests (%s)\n' "$("$BIN/java" -version 2>&1 | awk -F'"' 'NR == 1 {print $2}')"
"$BIN/javac" -encoding UTF-8 -d "$OUT/classes" \
  "$OUT/src/dev/phonedesk/companion/EspRom.java" \
  "$OUT/src/dev/phonedesk/companion/SpotifyLink.java" \
  "$OUT/src/dev/phonedesk/companion/UsbSerialCh340.java" \
  "$OUT/src/dev/phonedesk/companion/HostTests.java"
"$BIN/java" -cp "$OUT/classes" dev.phonedesk.companion.HostTests
