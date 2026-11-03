#!/usr/bin/env bash
#
# Build the release APK and sign it with a key that is not in this repository.
#
#   ./release.sh --new-key        make the key, then build and sign
#   ./release.sh                  build, align, sign, verify
#   ./release.sh --build-only     stop after Gradle, leaving the unsigned archive
#
# Everything the signature depends on lives under one directory outside the
# tree — OFFSITE_RELEASE_HOME, ~/.offsite-release by default: the keystore, the
# password file beside it, and the signed archive in dist/. Nothing here writes a
# key, a password or an archive into the repository, and the script refuses a
# keystore path that resolves inside it.
#
# That is the difference from the debug build. `toolchain.sh` signs with
# `keystore/debug.jks`, committed on purpose so two machines produce the same
# bytes; its password is in the tree, which makes it a reproducibility fixture and
# never an identity to publish under. A release key is the opposite: it must stay
# secret, so it cannot be committed, and the bytes it signs therefore cannot be
# rebuilt from a clone either. Keep a copy of the keystore and its password
# somewhere you would keep a password, because losing them means the next release
# installs as a different app.
#
# The toolchain is the one toolchain.sh installs; this script sources its env.sh
# when the caller has not already done so.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel 2>/dev/null || dirname "$HERE")"

KEY_HOME="${OFFSITE_RELEASE_HOME:-$HOME/.offsite-release}"
KEYSTORE="${OFFSITE_RELEASE_KEYSTORE:-$KEY_HOME/release.jks}"
PASS_FILE="${OFFSITE_RELEASE_PASS_FILE:-$KEY_HOME/pass}"
KEY_ALIAS="${OFFSITE_RELEASE_ALIAS:-offsite}"
OUT_DIR="${OFFSITE_RELEASE_OUT:-$KEY_HOME/dist}"
VALIDITY_DAYS="${OFFSITE_RELEASE_DAYS:-10000}"
KEY_BITS="${OFFSITE_RELEASE_BITS:-4096}"
BUILD_TOOLS="${OFFSITE_BUILD_TOOLS:-35.0.0}"

NEW_KEY=0
BUILD_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --new-key) NEW_KEY=1 ;;
    --build-only) BUILD_ONLY=1 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
size_of() { wc -c < "$1" | tr -d ' '; }
hash_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }
resolve() { python3 -c 'import os, sys; print(os.path.realpath(os.path.expanduser(sys.argv[1])))' "$1"; }

# ------------------------------------------------------------ never in the tree ---
# A release key inside the repository is a release key in every clone and in
# every screenshot of `git status`. This is the one check worth refusing to
# continue past, because a key that has been committed has to be replaced rather
# than moved: the file is in the history either way.
REPO_REAL="$(resolve "$REPO")"
for what in keystore password; do
  case "$what" in
    keystore) path="$KEYSTORE" ;;
    password) path="$PASS_FILE" ;;
  esac
  real="$(resolve "$path")"
  case "$real" in
    "$REPO_REAL"/*) fail "$what would live at $real, inside $REPO_REAL; a release key in the tree is a release key in every clone. Point OFFSITE_RELEASE_HOME somewhere outside it" ;;
  esac
done

# ------------------------------------------------------------------- toolchain ---
if ! command -v java >/dev/null 2>&1 || [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  ENV_FILE="${OFFSITE_HOME:-$HOME/.android-offsite}/env.sh"
  [ -f "$ENV_FILE" ] && . "$ENV_FILE"
fi
[ -n "${ANDROID_SDK_ROOT:-}" ] || fail "no Android SDK in this shell; run android-companion/toolchain.sh once, then source its env.sh"
BT="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS"
[ -x "$BT/apksigner" ] && [ -x "$BT/zipalign" ] && [ -x "$BT/aapt2" ] \
  || fail "build-tools $BUILD_TOOLS is not in $ANDROID_SDK_ROOT; toolchain.sh installs it"
GRADLE="$(command -v gradle || true)"
[ -n "$GRADLE" ] || fail "no gradle on PATH; source ${OFFSITE_HOME:-$HOME/.android-offsite}/env.sh"

# ------------------------------------------------------------------- password ---
# Read from the file, never from the command line and never echoed. keytool and
# apksigner both take it through the environment (`:env` and `env:`), so the value
# does not appear in `ps` either.
read_pass() {
  if [ -n "${OFFSITE_RELEASE_PASS:-}" ]; then
    return 0
  fi
  [ -f "$PASS_FILE" ] || return 1
  OFFSITE_RELEASE_PASS="$(tr -d '\r\n' < "$PASS_FILE")"
  [ -n "$OFFSITE_RELEASE_PASS" ] || fail "$PASS_FILE is empty; put the keystore password in it or set OFFSITE_RELEASE_PASS"
  export OFFSITE_RELEASE_PASS
}

# ---------------------------------------------------------------------- the key ---
if [ "$NEW_KEY" -eq 1 ]; then
  [ -f "$KEYSTORE" ] && fail "$KEYSTORE already exists; a signing identity is replaced deliberately, not overwritten — move the old one aside first"
  say "make a release key at $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")" "$(dirname "$PASS_FILE")"
  chmod 700 "$(dirname "$KEYSTORE")"
  if [ ! -f "$PASS_FILE" ]; then
    # Generated here rather than asked for, so the key is usable without the
    # password crossing a shell history or this transcript. It is written once,
    # read-only to its owner, and printed nowhere.
    python3 -c 'import secrets; print(secrets.token_urlsafe(24))' > "$PASS_FILE"
    chmod 600 "$PASS_FILE"
    echo "  password written to $PASS_FILE (mode 600) and nowhere else"
  fi
  read_pass || fail "no password for the new key; $PASS_FILE is missing"
  keytool -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize "$KEY_BITS" -validity "$VALIDITY_DAYS" \
    -dname "CN=Off-site Release, O=Off-site, C=US" \
    -storepass:env OFFSITE_RELEASE_PASS -keypass:env OFFSITE_RELEASE_PASS \
    >/dev/null || fail "keytool could not create the key"
  chmod 600 "$KEYSTORE"
  echo "  $(size_of "$KEYSTORE") bytes, RSA $KEY_BITS, valid $VALIDITY_DAYS days"
fi

read_pass || fail "no release key yet: run $0 --new-key, or set OFFSITE_RELEASE_KEYSTORE and OFFSITE_RELEASE_PASS for the key you have"
[ -f "$KEYSTORE" ] || fail "no keystore at $KEYSTORE (OFFSITE_RELEASE_KEYSTORE)"

# -------------------------------------------------------------------- build ---
# `clean` first, for the same reason toolchain.sh cleans: a warm build dir prints
# BUILD SUCCESSFUL without compiling anything, which proves nothing about these
# sources.
say "build the release APK"
cd "$HERE"
TASKS=(clean assembleRelease)
LOG="$("$GRADLE" --no-daemon --console=plain "${TASKS[@]}" 2>&1)" || {
  printf '%s\n' "$LOG" | tail -40
  fail "gradle could not build the release"
}
printf '%s\n' "$LOG" | grep -E '^BUILD |actionable tasks' || true
printf '%s\n' "$LOG" | grep -q 'Task :app:compileReleaseJavaWithJavac' \
  || fail "no compileReleaseJavaWithJavac task ran, so the build proved nothing"
printf '%s\n' "$LOG" | grep -q 'Task :app:lintVitalRelease' \
  || fail "lintVitalRelease did not run; the release checks were skipped"

UNSIGNED="$HERE/app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$UNSIGNED" ] || fail "no unsigned archive at $UNSIGNED"
say "unsigned $UNSIGNED"
printf '  %s bytes\n' "$(size_of "$UNSIGNED")"

if [ "$BUILD_ONLY" -eq 1 ]; then
  say "build only; nothing was signed"
  exit 0
fi

# --------------------------------------------------------------------- sign ---
# zipalign rewrites the archive, which also drops whatever the build stamped into
# the signing block, and apksigner adds back only the signature. Same two steps
# toolchain.sh runs on the debug archive, so a release and a debug build differ in
# their key rather than in how they were packed.
say "align and sign with $KEYSTORE"
SIGNED="$UNSIGNED.signed"
"$BT/zipalign" -f 4 "$UNSIGNED" "$SIGNED" || fail "zipalign could not rewrite the archive"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:OFFSITE_RELEASE_PASS --key-pass env:OFFSITE_RELEASE_PASS \
  "$SIGNED" || fail "apksigner could not sign the archive"
"$BT/apksigner" verify "$SIGNED" || fail "apksigner rejected the archive it just signed"

# The signature is the whole point, so what signed it is read back rather than
# assumed: a keystore path typo would otherwise ship a debug-signed "release".
CERTS="$("$BT/apksigner" verify --print-certs "$SIGNED")"
printf '%s\n' "$CERTS" | grep -q 'CN=Off-site Release' \
  || { printf '%s\n' "$CERTS"; fail "the archive is not signed by the release key at $KEYSTORE"; }
printf '%s\n' "$CERTS" | grep -qi 'CN=Android Debug' \
  && fail "the archive carries the debug identity as well; the release key is not the only signature"

say "verify manifest"
BADGING="$("$BT/aapt2" dump badging "$SIGNED" 2>/dev/null)"
printf '%s\n' "$BADGING" | grep -E '^package:|^sdkVersion|^targetSdkVersion'
printf '%s\n' "$BADGING" | grep -q "name='dev.phonedesk.companion'" || fail "package is not dev.phonedesk.companion"
printf '%s\n' "$BADGING" | grep -q 'application-debuggable' && fail "the release archive is debuggable"
printf '%s\n' "$BADGING" | grep -q "targetSdkVersion:'35'" || fail "targetSdk is not 35"

# Each widget's sketch is bundled by Gradle, so the release archive carries the
# same sources the debug one does. Presence is checked here; the bytes are already
# compared against the repo by toolchain.sh on the debug build.
say "verify bundled widgets"
TMP="$(mktemp -d)"
for descriptor in "$HERE"/../*/widget.json; do
  [ -f "$descriptor" ] || continue
  id="$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$descriptor" | head -1)"
  name="$(sed -n 's/.*"sketch"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$descriptor" | head -1)"
  [ -n "$name" ] || name="$(basename "$(dirname "$descriptor")").ino"
  unzip -p "$SIGNED" "assets/widgets/$id/$name" > "$TMP/$name" 2>/dev/null || true
  [ -s "$TMP/$name" ] || fail "the release archive carries no assets/widgets/$id/$name"
  printf '  %s/%s\n' "$id" "$name"
done
rm -rf "$TMP"

# --------------------------------------------------------------------- ship ---
# Outside the tree by default: these bytes cannot be rebuilt from a clone,
# because the key they need is deliberately not in it, so they are a file to hand
# over rather than a file to commit.
VERSION="$("$BT/aapt2" dump badging "$SIGNED" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
mkdir -p "$OUT_DIR"
DIST="$OUT_DIR/PhoneDeckBridge-${VERSION:-1.0}-release.apk"
cp "$SIGNED" "$DIST"
BYTES="$(size_of "$DIST")"
say "signed release at $DIST"
printf '  %s bytes (%s kB)\n  signed with %s\n  sha256 %s\n' \
  "$BYTES" "$(( (BYTES + 512) / 1024 ))" "$KEYSTORE" "$(hash_of "$DIST")"
say "keep $KEYSTORE and $PASS_FILE; without them this archive cannot be updated in place"
