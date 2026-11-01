#!/usr/bin/env bash
#
# Reinstall the toolchain that builds phone_deck_cyd24R.ino, then compile it.
#
#   ./toolchain.sh                  install into ~/.arduino-cyd, then compile
#   ./toolchain.sh --setup-only     install without compiling
#   ARDUINO_HOME=/opt/cyd ./toolchain.sh        install somewhere else
#
# A root that already holds 2.0.17 (the old default, ~/.arduino-cyd) needs
# a fresh one for the default: ARDUINO_HOME=~/.arduino-cyd-3 ./toolchain.sh
#
# Everything lives under one directory and nothing touches a global Arduino
# config, so this is safe to re-run and safe to delete. Idempotent: an
# arduino-cli, core and libraries already present are reused, which is what
# makes it the "put it back after the machine was wiped" command.
#
# Afterwards, in any shell:
#   source ~/.arduino-cyd/env.sh
#
# One core version per install root, because the compiler picks the newest
# installed platform. The default is core 3.x; keep 2.0.17 in its own root when
# you want the old core for an A/B measurement:
#   ARDUINO_HOME=~/.arduino-cyd-2 ARDUINO_CORE=esp32:esp32@2.0.17 ./toolchain.sh
#
set -euo pipefail

ROOT="${ARDUINO_HOME:-$HOME/.arduino-cyd}"
# "latest" by default: the 1.5.2 line was only ever published as a release
# candidate, so a pinned 1.5.2 URL 404s and silently falls back anyway.
CLI_VERSION="${ARDUINO_CLI_VERSION:-latest}"
CORE="${ARDUINO_CORE:-esp32:esp32@3.3.12}"
FQBN="${ARDUINO_FQBN:-esp32:esp32:esp32}"
LIBS=("Adafruit GFX Library" "Adafruit ILI9341" "XPT2046_Touchscreen")

SETUP_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --setup-only) SETUP_ONLY=1 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKETCH_DIR="$HERE"  # this script lives inside the sketch folder
CONFIG="$ROOT/arduino-cli.yaml"

say() { printf '\n== %s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- arduino-cli ---

say "install root $ROOT"
mkdir -p "$ROOT/bin" "$ROOT/staging" "$ROOT/pylibs"

if [ -x "$ROOT/bin/arduino-cli" ]; then
  say "arduino-cli already at $ROOT/bin/arduino-cli"
else
  case "$(uname -s)" in
    Linux) OS="Linux" ;;
    Darwin) OS="macOS" ;;
    *) fail "unsupported OS $(uname -s); install arduino-cli by hand and re-run" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) ARCH="64bit" ;;
    aarch64|arm64) ARCH="ARM64" ;;
    armv7l|armv7) ARCH="ARMv7" ;;
    *) fail "unsupported CPU $(uname -m)" ;;
  esac

  URL="https://downloads.arduino.cc/arduino-cli/arduino-cli_${CLI_VERSION}_${OS}_${ARCH}.tar.gz"
  say "download arduino-cli $CLI_VERSION ($OS $ARCH)"
  TMP="$(mktemp -d)"
  if ! curl -fsSL "$URL" -o "$TMP/acli.tar.gz"; then
    # A pinned version can disappear from the download host; fall back rather
    # than leaving the machine without a compiler.
    echo "  $URL unavailable, trying latest" >&2
    curl -fsSL "https://downloads.arduino.cc/arduino-cli/arduino-cli_latest_${OS}_${ARCH}.tar.gz" \
      -o "$TMP/acli.tar.gz" || fail "could not download arduino-cli"
  fi
  tar -xzf "$TMP/acli.tar.gz" -C "$ROOT/bin" arduino-cli
  rm -rf "$TMP"
fi

export PATH="$ROOT/bin:$PATH"
ACLI=(arduino-cli --config-file "$CONFIG")
# `arduino-cli version` prints "arduino-cli  Version: 1.5.2-rc.1 ..." — two
# spaces before "Version:", so the number is the third field.
CLI_VERSION_ACTUAL="$("$ROOT/bin/arduino-cli" version | awk '{print $3}')"
say "arduino-cli $CLI_VERSION_ACTUAL"

# ------------------------------------------------------------------ config ----

if [ ! -f "$CONFIG" ]; then
  say "write $CONFIG"
  "${ACLI[@]}" config init --dest-dir "$ROOT" --overwrite >/dev/null
  "${ACLI[@]}" config set directories.data "$ROOT/data" >/dev/null
  "${ACLI[@]}" config set directories.downloads "$ROOT/staging" >/dev/null
  "${ACLI[@]}" config set directories.user "$ROOT/user" >/dev/null
fi

# ------------------------------------------------------------------ core ------

CORE_NAME="${CORE%@*}"
CORE_WANT=""
case "$CORE" in *@*) CORE_WANT="${CORE##*@}" ;; esac
CORE_HAVE="$("${ACLI[@]}" core list 2>/dev/null | awk -v n="$CORE_NAME" 'NR > 1 && $1 == n { print $2 }')"

if [ -n "$CORE_HAVE" ] && { [ -z "$CORE_WANT" ] || [ "$CORE_HAVE" = "$CORE_WANT" ]; }; then
  say "core $CORE already installed"
else
  if [ -n "$CORE_HAVE" ] && [ -n "$CORE_WANT" ] && [ "$CORE_HAVE" != "$CORE_WANT" ]; then
    # The compiler takes the newest installed platform, so installing a second
    # version here would silently build the wrong one and report a number that
    # does not belong to the version asked for.
    fail "$CORE_NAME $CORE_HAVE is installed in $ROOT, not $CORE_WANT. Use a
       separate install root per core version, e.g.
       ARDUINO_HOME=$HOME/.arduino-cyd-3 ARDUINO_CORE=$CORE $0"
  fi
  # Measured on 3.3.12 in a fresh root: 927 MiB of downloads that unpack to
  # 6.1 GB, because the platform now carries two toolchains (esp-x32 and
  # esp-rv32, 5.9 GB together) against 2.4 GB on 2.0.17. staging/ also keeps a
  # 1.7 GB copy of the archives and is safe to delete, so budget ~8 GB.
  say "install core $CORE (downloads ~1 GB, unpacks to ~6 GB of toolchains)"
  "${ACLI[@]}" core update-index
  "${ACLI[@]}" core install "$CORE"
fi

# ------------------------------------------------------------------ libs ------

have_lib() { "${ACLI[@]}" lib list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "$1"; }

for lib in "${LIBS[@]}"; do
  if have_lib "$lib"; then
    say "library already installed: $lib"
  else
    say "install library $lib"
    "${ACLI[@]}" lib install "$lib"
  fi
done

# ---------------------------------------------------------------- pyserial -----
# Core 2.0.17's build shells out to its bundled esptool.py, which imports
# pyserial. Without it the compile dies after the link step with
# "ModuleNotFoundError: No module named 'serial'".

say "pyserial for the python3 the core calls"
if command -v python3 >/dev/null 2>&1; then
  python3 -m pip install --quiet --disable-pip-version-check \
    --target "$ROOT/pylibs" pyserial 2>/dev/null \
    || python3 -m pip install --quiet --break-system-packages \
         --target "$ROOT/pylibs" pyserial 2>/dev/null \
    || echo "  could not install pyserial with pip; the compile may fail if it is missing"
else
  echo "  no python3 found; install one if the compile fails on 'import serial'"
fi

cat > "$ROOT/env.sh" <<EOF
# Generated by phone_deck_cyd24R/toolchain.sh — source this in a new shell.
export PATH="$ROOT/bin:\$PATH"
export PYTHONPATH="$ROOT/pylibs\${PYTHONPATH:+:\$PYTHONPATH}"
EOF

# ---------------------------------------------------------------- compile -----

if [ "$SETUP_ONLY" -eq 1 ]; then
  say "setup only; run again without --setup-only to compile"
  exit 0
fi

[ -d "$SKETCH_DIR" ] || fail "no sketch at $SKETCH_DIR"

say "compile $(basename "$SKETCH_DIR") for $FQBN"
export PYTHONPATH="$ROOT/pylibs${PYTHONPATH:+:$PYTHONPATH}"
OUTPUT="$("${ACLI[@]}" compile --fqbn "$FQBN" "$SKETCH_DIR" 2>&1)" || {
  printf '%s\n' "$OUTPUT" | tail -40
  fail "compile failed"
}
printf '%s\n' "$OUTPUT" | grep -E 'Sketch uses|Global variables' \
  || printf '%s\n' "$OUTPUT" | tail -10

say "done — source $ROOT/env.sh to use the toolchain in this shell"
