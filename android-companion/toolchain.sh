#!/usr/bin/env bash
#
# Rebuild the Off-site debug APK from a machine that has nothing installed,
# then verify what came out.
#
#   ./toolchain.sh                  install into ~/.android-offsite, build, verify
#   ./toolchain.sh --setup-only     install without building
#   ./toolchain.sh --no-clean       reuse Gradle's build dir (faster, proves less)
#   OFFSITE_HOME=/opt/offsite ./toolchain.sh     install somewhere else
#
# Everything lives under one directory and nothing touches a global Android
# config, so this is safe to re-run and safe to delete. Idempotent: a JDK, SDK
# or Gradle already present is reused, which is what makes it the "put it back
# after the machine was wiped" command. It needs curl, unzip and Java's own
# dependencies only — no Android Studio, no system Gradle.
#
# Afterwards, in any shell:
#   source ~/.android-offsite/env.sh
#
# The versions are pinned rather than "latest" on purpose: the docs quote the
# APK's size, and a newer AGP or build-tools writes a different archive from
# the same sources. AGP 8.6.1 wants JDK 17 and Gradle 8.7 or newer, and it is the
# first AGP that supports compileSdk 35, which targetSdk 35 needs.
#
set -euo pipefail

ROOT="${OFFSITE_HOME:-$HOME/.android-offsite}"
JDK_VERSION="${OFFSITE_JDK:-17}"
GRADLE_VERSION="${OFFSITE_GRADLE:-8.7}"
CMDLINE_TOOLS="${OFFSITE_CMDLINE_TOOLS:-11076708}"   # cmdline-tools 12.0
COMPILE_SDK=35
BUILD_TOOLS=35.0.0

SETUP_ONLY=0
CLEAN=1
for arg in "$@"; do
  case "$arg" in
    --setup-only) SETUP_ONLY=1 ;;
    --no-clean) CLEAN=0 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # this script lives in android-companion
APP_DIR="$HERE"
APK_REL="app/build/outputs/apk/debug/app-debug.apk"
DIST="$HERE/dist/PhoneDeckBridge-debug.apk"

say() { printf '\n== %s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
md5of() { if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | awk '{print $1}'; else md5 -q "$1"; fi; }

for tool in curl unzip; do
  command -v "$tool" >/dev/null 2>&1 || fail "needs $tool on PATH; install it and re-run"
done

case "$(uname -s)" in
  Linux) OS_JAVA="linux"; OS_CMDLINE="linux" ;;
  Darwin) OS_JAVA="mac"; OS_CMDLINE="mac" ;;
  *) fail "unsupported OS $(uname -s); install JDK 17, the Android SDK and Gradle 8.7 by hand" ;;
esac
case "$(uname -m)" in
  x86_64|amd64) ARCH_JAVA="x64"; ARCH_CMDLINE="" ;;
  aarch64|arm64) ARCH_JAVA="aarch64"; ARCH_CMDLINE="" ;;
  *) fail "unsupported CPU $(uname -m)" ;;
esac

say "install root $ROOT"
mkdir -p "$ROOT" "$ROOT/downloads"

# ---------------------------------------------------------------------- jdk ---
# Temurin from Adoptium: a plain tarball, no installer, and the same build on
# both platforms. Its top-level directory name carries the exact version, so
# the script reads it back instead of guessing.

export JAVA_HOME="$ROOT/jdk"
if [ -x "$JAVA_HOME/bin/javac" ]; then
  say "JDK already at $JAVA_HOME"
else
  say "download Temurin JDK $JDK_VERSION ($OS_JAVA $ARCH_JAVA)"
  TMP="$(mktemp -d)"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/${OS_JAVA}/${ARCH_JAVA}/jdk/hotspot/normal/eclipse" \
    -o "$TMP/jdk.tar.gz" || fail "could not download a JDK; check network access"
  mkdir -p "$TMP/x"
  tar -xzf "$TMP/jdk.tar.gz" -C "$TMP/x"
  INNER="$(find "$TMP/x" -maxdepth 1 -mindepth 1 -type d | head -1)"
  [ -n "$INNER" ] || fail "the JDK archive had no top-level directory"
  mv "$INNER" "$JAVA_HOME"
  rm -rf "$TMP"
fi
export PATH="$JAVA_HOME/bin:$PATH"
JDK_ACTUAL="$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F'"' 'NR == 1 {print $2}')"
say "java $JDK_ACTUAL"

# ------------------------------------------------------------------- gradle ---

GRADLE_HOME="$ROOT/gradle-$GRADLE_VERSION"
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
  say "Gradle $GRADLE_VERSION already at $GRADLE_HOME"
else
  say "download Gradle $GRADLE_VERSION"
  TMP="$(mktemp -d)"
  curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    -o "$TMP/gradle.zip" || fail "could not download Gradle; check network access"
  unzip -q "$TMP/gradle.zip" -d "$ROOT"
  rm -rf "$TMP"
  [ -x "$GRADLE_HOME/bin/gradle" ] || fail "Gradle unpacked somewhere unexpected under $ROOT"
fi

# -------------------------------------------------------------- android sdk ---
# cmdline-tools only: no emulator, no IDE. `sdkmanager` is the supported way to
# get an exact platform and build-tools, and it writes into our own directory.

export ANDROID_SDK_ROOT="$ROOT/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ -x "$SDKMANAGER" ] && [ -d "$ANDROID_SDK_ROOT/platforms/android-$COMPILE_SDK" ] \
   && [ -d "$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS" ]; then
  say "Android SDK already has platform $COMPILE_SDK and build-tools $BUILD_TOOLS"
else
  if [ ! -x "$SDKMANAGER" ]; then
    say "download Android commandline-tools ($CMDLINE_TOOLS)"
    TMP="$(mktemp -d)"
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-${OS_CMDLINE}-${CMDLINE_TOOLS}_latest.zip" \
      -o "$TMP/cmdline.zip" || fail "could not download commandline-tools"
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    unzip -q "$TMP/cmdline.zip" -d "$TMP/x"
    # sdkmanager insists on being the "latest" directory; the zip ships as
    # "cmdline-tools" and refuses to run from anywhere else.
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    mv "$TMP/x/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -rf "$TMP"
  fi

  say "accept SDK licences"
  yes 2>/dev/null | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

  say "install platform $COMPILE_SDK, build-tools $BUILD_TOOLS, platform-tools"
  "$SDKMANAGER" --install "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" \
    "platform-tools" || fail "sdkmanager could not install the SDK packages"
fi

# Gradle finds the SDK through this file; it is gitignored, so writing it here
# cannot leak an absolute path into the repo.
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$APP_DIR/local.properties"

cat > "$ROOT/env.sh" <<EOF
# Generated by android-companion/toolchain.sh — source this in a new shell.
export JAVA_HOME="$JAVA_HOME"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$GRADLE_HOME/bin:\$PATH"
EOF

say "toolchain ready"
printf '  java    %s\n  gradle  %s\n  sdk     %s\n' "$JDK_ACTUAL" "$GRADLE_VERSION" "$ANDROID_SDK_ROOT"

if [ "$SETUP_ONLY" -eq 1 ]; then
  say "setup only; run again without --setup-only to build and verify"
  exit 0
fi

# -------------------------------------------------------------------- build ---
# `clean` by default: a warm build dir reports BUILD SUCCESSFUL and runs no
# compile at all, which is not evidence that the sources still compile.

# Host tests first: they are the only check that does not need the board, and a
# failure here means the protocol's framing or checksum broke.
say "host tests"
"$HERE/test.sh"

say "build $(basename "$APP_DIR")"
cd "$APP_DIR"
TASKS=(assembleDebug)
[ "$CLEAN" -eq 1 ] && TASKS=(clean assembleDebug)
BUILD_LOG="$("$GRADLE_HOME/bin/gradle" --no-daemon --console=plain "${TASKS[@]}" 2>&1)" || {
  printf '%s\n' "$BUILD_LOG" | tail -40
  fail "gradle build failed"
}
printf '%s\n' "$BUILD_LOG" | grep -E '^BUILD |actionable tasks' || true
printf '%s\n' "$BUILD_LOG" | grep -i 'deprecat' || true

# Prove the compiler actually ran instead of trusting the exit status.
if ! printf '%s\n' "$BUILD_LOG" | grep -q 'Task :app:compileDebugJavaWithJavac'; then
  fail "no compileDebugJavaWithJavac task ran; the build proved nothing"
fi
if printf '%s\n' "$BUILD_LOG" | grep -q 'Task :app:compileDebugJavaWithJavac UP-TO-DATE'; then
  fail "Java compile was UP-TO-DATE; re-run without --no-clean"
fi

# ------------------------------------------------------------------- verify ---

APK="$APP_DIR/$APK_REL"
[ -f "$APK" ] || fail "no APK at $APK"
BT="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS"

say "verify signature"
"$BT/apksigner" verify "$APK" || fail "apksigner rejected the APK"
"$BT/apksigner" verify -v "$APK" | grep -i 'scheme' || true

say "verify zip alignment"
"$BT/zipalign" -c 4 "$APK" >/dev/null || fail "APK is not 4-byte aligned"

say "verify manifest"
BADGING="$("$BT/aapt2" dump badging "$APK" 2>/dev/null)"
printf '%s\n' "$BADGING" | grep -E '^package:|^sdkVersion|^targetSdkVersion' || fail "aapt2 read no manifest"
printf '%s\n' "$BADGING" | grep -q "name='dev.phonedesk.companion'" \
  || fail "package is not dev.phonedesk.companion"

# Properties this build is supposed to keep, checked instead of assumed. INTERNET
# is now expected: the Spotify hand-off is one request to the deck's own address,
# and cleartext has to be allowed for it because the board serves plain HTTP with
# no certificate. The debugger still must not attach to a process holding
# notification access.
printf '%s\n' "$BADGING" | grep -q 'android.permission.INTERNET' \
  || fail "the APK cannot reach the deck; the sign-in hand-off needs INTERNET"
"$BT/aapt2" dump xmltree "$APK" --file AndroidManifest.xml \
  | grep -q 'usesCleartextTraffic.*true' \
  || fail "cleartext is not allowed, so http://<deck>/code would fail at runtime"
printf '%s\n' "$BADGING" | grep -q 'application-debuggable' \
  && fail "the APK is debuggable; notification access must not be attachable"

# A store listing checks these two first, so they are asserted rather than
# remembered: target 35 or the upload is refused, and an icon or the launcher
# falls back to the Android robot.
printf '%s\n' "$BADGING" | grep -q "targetSdkVersion:'35'" \
  || fail "targetSdk is not 35; a new store listing needs 35"
printf '%s\n' "$BADGING" | grep -q 'application-icon' \
  || fail "the APK ships no launcher icon"

say "verify bundled widgets"
TMP="$(mktemp -d)"
# Read from the descriptors rather than from a list in this script: whatever the
# build bundled gets checked, so a widget added to the repo cannot ship unverified.
SEEN=0
for descriptor in "$HERE"/../*/widget.json; do
  [ -f "$descriptor" ] || continue
  SEEN=1
  ID="$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$descriptor" | head -1)"
  NAME="$(sed -n 's/.*"sketch"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$descriptor" | head -1)"
  [ -n "$ID" ] || fail "$descriptor has no id"
  [ -n "$NAME" ] || NAME="$(basename "$(dirname "$descriptor")").ino"
  source="$(dirname "$descriptor")/$NAME"
  [ -f "$source" ] || fail "$descriptor names $NAME but $source does not exist"
  # -s rather than unzip's exit status: some builds exit 0 and write nothing for
  # a missing entry, which would read as a stale file instead of a missing one.
  unzip -p "$APK" "assets/widgets/$ID/$NAME" > "$TMP/$NAME" 2>/dev/null || true
  [ -s "$TMP/$NAME" ] \
    || fail "the APK carries no assets/widgets/$ID/$NAME; syncWidgets did not run"
  BUNDLED="$(md5of "$TMP/$NAME")"
  REPO_MD5="$(md5of "$source")"
  printf '  %s\n    bundled %s\n    repo    %s\n' "$ID/$NAME" "$BUNDLED" "$REPO_MD5"
  [ "$BUNDLED" = "$REPO_MD5" ] || fail "$ID/$NAME is stale; syncWidgets did not run"
done
[ "$SEEN" -eq 1 ] || fail "no */widget.json beside $HERE; nothing to verify"
rm -rf "$TMP"

# ---------------------------------------------------------------- ship it -----

mkdir -p "$(dirname "$DIST")"
cp "$APK" "$DIST"
SIZE="$(wc -c < "$DIST" | tr -d ' ')"
say "ship $DIST"
printf '  %s bytes (%s kB)\n  sha256 %s\n' "$SIZE" "$((SIZE / 1024))" \
  "$(if command -v sha256sum >/dev/null 2>&1; then sha256sum "$DIST" | awk '{print $1}'; else shasum -a 256 "$DIST" | awk '{print $1}'; fi)"

# The archive is not byte-reproducible: dex files carry build-time data, so the
# hash moves even when the sources do not. `HANDOFF.md` holds these exact bytes,
# and `README.md` beside it plus `dist/index.html` quote the size to the nearest
# kB; update all three when this number changes.
say "done — source $ROOT/env.sh to use the toolchain in this shell"
