#!/usr/bin/env bash
#
# One gate, two speeds. A rule has one home, and this is where to run them from.
#
#   ./check.sh          what a commit must pass            (~3 s)
#   ./check.sh --full   what a release must pass           (~4 min: the decks on
#                       both cores, so it needs all three toolchains — source
#                       ~/.arduino-cyd/env.sh, ~/.arduino-cyd-2/env.sh or ~/.arduino15/env.sh for the old
#                       core, and ~/.android-offsite/env.sh)
#   ./check.sh --selftest  the checkers' own refusals        (~9 s, no toolchain)
#
# The gate passes on this tree every day, so its failure paths are the part
# nobody exercises: --selftest runs the six selftest_*.py files, which hand each
# checker a throwaway repo and a stand-in (compiler, capture script, git repo) and
# make it refuse.
#
# Fast:  docs (figures, links, prose, the recorded APK bytes), widget assets, the
#        index (no source file left outside it), the host tests, the branch list as
#        a note, and the seven render captures whenever a staged file could move a
#        pixel.
# Full:  the above, plus deck headroom on both cores, the seven render captures,
#        and the APK build with its signature, alignment and bundled-sketch checks.
#
# Each step takes the pattern of the line worth reading, so a passing step reports
# its measurement rather than whatever the script said last.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 1
mkdir -p .scratch

full=0
[ "${1:-}" = "--full" ] && full=1
failures=0

step () {
  local name="$1" filter="$2"; shift 2
  local out=".scratch/gate-$name.log"
  local start end rc last ms
  start=$(date +%s%N); : >"$out"
  "$@" >"$out" 2>&1; rc=$?
  end=$(date +%s%N); ms=$(( (end-start)/1000000 ))
  last=$(grep -E "$filter" "$out" | tail -1)
  [ -n "$last" ] || last=$(tail -1 "$out")
  if [ "$rc" -eq 0 ]; then
    printf 'ok    %-15s %6d ms  %s\n' "$name" "$ms" "$last"
  else
    printf 'FAIL  %-15s %6d ms  %s\n' "$name" "$ms" "$last"
    failures=$((failures + 1))
    echo "----- $name"; sed -n '1,40p' "$out"; echo "-----"
  fi
}

# A step whose prerequisite is not on this machine says which one, the way the
# old pre-commit hook did: a fresh clone has no built assets and a laptop may have
# no deck toolchain, and neither is a broken tree.
say () {
  printf 'note  %-15s %s\n' "$1" "$2"
}

# Information, never a failure: an unmerged branch in another chat's worktree is
# not a broken tree.
report () {
  local name="$1"; shift
  printf 'note  %-15s %s\n' "$name" "$("$@" 2>&1 | grep -v '^$' | head -1)"
}

# The gate finds the deck toolchain the way CONVENTIONS says to, so it does not
# depend on which shell launched it.
cli="${ARDUINO_CLI:-}"
if [ -z "$cli" ]; then
  for root in "${ARDUINO_HOME:-}" "$HOME/.arduino-cyd-3" "$HOME/.arduino-cyd"; do
    [ -n "$root" ] || continue
    if [ -f "$root/env.sh" ]; then
      . "$root/env.sh"
      # A root name must mean one build. An env.sh written before the toolchains
      # pinned their directories sets only PATH, so sourcing it quietly builds
      # against ~/.arduino15 and reports a size that belongs to no named root —
      # the confusion that put a 16-byte "spread" in five documents. Refuse it.
      if [ -z "${ARDUINO_DIRECTORIES_DATA:-}" ]; then
        printf 'error: %s does not pin the arduino-cli directories, so a build here\n       would use ~/.arduino15 instead of that root. Re-run the toolchain.sh in\n       that root, or source the env.sh of the root you mean.\n' "$root/env.sh" >&2
        exit 2
      fi
    fi
    if [ -z "$cli" ] && [ -x "$root/bin/arduino-cli" ]; then cli="$root/bin/arduino-cli"; break; fi
  done
fi
[ -n "$cli" ] || cli="$(command -v arduino-cli 2>/dev/null || true)"
cli_args=()
[ -n "$cli" ] && cli_args=(--arduino-cli "$cli")

if [ "${1:-}" = "--selftest" ]; then
  step "docs selftest"   'branches of check_docs'           python3 selftest_docs.py
  step "assets selftest" 'branches of check_widget_assets'  python3 selftest_widgets.py
  step "margin selftest" 'branches of check_headroom'       python3 selftest_headroom.py
  step "branch selftest" 'branches of check_branches'       python3 selftest_branches.py
  step "render selftest" 'branches of check_renders'        python3 selftest_renders.py
  step "gate selftest"   'branches of check.sh'            python3 selftest_gate.py
  echo
  if [ "$failures" -gt 0 ]; then
    echo "$failures step(s) failed"
    exit 1
  fi
  echo "every check passed"
  exit 0
fi

# A file the tree needs but the index never got. Every commit here stages tracked
# files (`git add -u`), which cannot pick up a brand-new one: LinkCommand.java was
# referenced by two tracked classes and still spent a day outside the tree, so a
# clone would not have compiled the app this worktree builds.
tracked_step () {
  local loose
  loose=$(git ls-files --others --exclude-standard -- '*.java' '*.ino' '*.py' '*.sh' '*.md' '*.json')
  if [ -n "$loose" ]; then
    echo "$loose" | sed 's/^/  - /'
    echo "these source files are not in the index, so a clone would not have them"
    return 1
  fi
  echo "every source file is in the index"
}

if [ -n "$cli" ]; then
  step "docs"       'docs, '              python3 check_docs.py "${cli_args[@]}"
else
  say "docs" "no arduino-cli, so the figures and links were NOT checked"
fi
if [ -d android-companion/app/src/main/assets/widgets ]; then
  step "widgets"    'widgets bundled'     python3 check_widget_assets.py
else
  say "widgets" "no built assets yet, so there is no bundled copy to compare"
fi
step "tracked"    'index'             tracked_step
# test.sh finds its own JDK — $JAVA_HOME, then android-companion/jdk, then the
# off-site root, then PATH — so the gate looks in the same four places before it
# says the suite did not run. Asking only PATH skipped the suite on a machine that
# has JDK 17 in its toolchain.
have_javac=0
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] && have_javac=1
[ -x android-companion/jdk/bin/javac ] && have_javac=1
[ -x "$HOME/.android-offsite/jdk/bin/javac" ] && have_javac=1
command -v javac >/dev/null 2>&1 && have_javac=1
if [ "$have_javac" -eq 1 ]; then
  step "host tests" 'host checks'         bash android-companion/test.sh
else
  say "host tests" "no javac, so the host suite did NOT run"
fi
report "branches" python3 check_branches.py

# A commit that can change a pixel has to leave a picture of it behind, and a rule
# nobody runs is a paragraph. The fast gate draws the frames whenever the staged
# files could move one — the app's Java, its resources, or the capture itself —
# and says plainly when it did not. That is why the renders stopped arriving: they
# were a --full step, and the pre-commit hook runs the fast gate.
ui_staged () {
  git diff --cached --name-only --diff-filter=ACMR \
    -- 'android-companion/app/src/main/java' 'android-companion/app/src/main/res' \
       'android-companion/capture.sh' | grep -q .
}
if [ "$full" -eq 1 ]; then
  : # drawn below, staged or not
elif ui_staged; then
  if command -v gradle >/dev/null 2>&1 || [ -f "$HOME/.android-offsite/env.sh" ]; then
    step "renders" 'renders current' python3 check_renders.py
  else
    say "renders" "a UI file is staged and there is no capture toolchain, so frames were NOT drawn"
  fi
else
  say "renders" "no UI file staged, so the frames were not redrawn"
fi

if [ "$full" -eq 1 ]; then
  step "headroom" 'deck\(s\) fit'       python3 check_headroom.py "${cli_args[@]}"
  # The old core is a separate question, not a second opinion: a deck that fits on
  # 3.3.12 can still overflow on 2.0.17, and that root carries its own data dir and
  # its own Python path, so it is built in a shell that sourced its env.sh.
  abroot=""
  for candidate in "$HOME/.arduino-cyd-2/env.sh" "$HOME/.arduino15/env.sh"; do
    if [ -f "$candidate" ]; then abroot="$candidate"; break; fi
  done
  if [ -n "$abroot" ]; then
    step "headroom 2.0.17" 'deck\(s\) fit' bash -c '. "$0" && exec python3 check_headroom.py' "$abroot"
  else
    say "headroom 2.0.17" "no A/B root in ~/.arduino-cyd-2 or ~/.arduino15, so the old core was NOT built"
  fi
  step "renders"  'renders current'     python3 check_renders.py
  step "apk"      '[0-9] bytes'         bash android-companion/toolchain.sh
fi

echo
if [ "$failures" -gt 0 ]; then
  echo "$failures step(s) failed"
  exit 1
fi
echo "every check passed"
