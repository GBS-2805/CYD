#!/usr/bin/env bash
#
# Take the app's screenshots by running the app.
#
#   ./capture.sh                 capture, install into artifacts/renders/, rebuild the scroll
#   ./capture.sh --capture-only  leave the PNGs in app/build/capture and touch nothing
#
# The frames are not drawn here and not transcribed anywhere: `CaptureTest` builds
# `TopBar` and each screen, lays them out and draws them with Android's own renderer
# on the JVM (Robolectric, native graphics), so the picture carries the platform's
# fonts, the real vector drawables and the real text layout. A state the app reaches
# by tapping is reached by tapping.
#
# Needs the pinned toolchain: run ../toolchain.sh --setup-only once, or source
# ~/.android-offsite/env.sh.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${OFFSITE_HOME:-$HOME/.android-offsite}"
INSTALL=1
for arg in "$@"; do
  case "$arg" in
    --capture-only) INSTALL=0 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

if [ -f "$ROOT/env.sh" ]; then
  # shellcheck disable=SC1091
  source "$ROOT/env.sh"
fi
command -v gradle >/dev/null 2>&1 || {
  echo "error: no gradle; run ../toolchain.sh --setup-only first" >&2
  exit 1
}

cd "$HERE"
rm -rf app/build/capture
# The test writes PNGs as a side effect, so Gradle cannot see that `build/capture` was
# deleted and would skip the capture entirely. That is now handled where it belongs, in
# app/build.gradle (`outputs.upToDateWhen { false }` on the test tasks), instead of with
# --rerun-tasks, which also re-ran every compile. The daemon is kept, and its JDK named
# explicitly because this environment's default javaHome is a path that does not exist.
# Measured on 2026-11-02: 21 s per capture before, 10 s warm after, the same frames
# byte for byte.
JVM=()
[ -n "${JAVA_HOME:-}" ] && JVM=("-Dorg.gradle.java.home=$JAVA_HOME")
gradle :app:testDebugUnitTest --tests '*CaptureTest*' --console=plain "${JVM[@]}"

shopt -s nullglob
frames=(app/build/capture/*.png)
[ "${#frames[@]}" -gt 0 ] || { echo "error: the test wrote no frames" >&2; exit 1; }
for file in "${frames[@]}"; do printf '  %-24s %s\n' "$(basename "$file")" \
  "$(python3 -c "import sys;from PIL import Image;print('%dx%d' % Image.open(sys.argv[1]).size)" "$file")"; done

if [ "$INSTALL" -eq 1 ]; then
  cp "${frames[@]}" "$HERE/../artifacts/renders/"
  python3 "$HERE/../artifacts/render_scroll.py"
  # The rendered bundle the site serves is rebuilt here, not remembered: a capture
  # that installed frames and left the published copy behind is how the owner ends
  # up reviewing last week's screen. Putting it online is still a separate step
  # that has to be asked for — this only keeps the files current.
  python3 "$HERE/../artifacts/publish_renders.py"
fi
