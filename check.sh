#!/usr/bin/env bash
#
# One gate, two speeds. A rule has one home, and this is where to run them from.
#
#   ./check.sh          what a commit must pass            (~3 s)
#   ./check.sh --full   what a release must pass           (~80 s, needs both
#                       toolchains: source ~/.arduino-cyd/env.sh and
#                       ~/.android-offsite/env.sh)
#   ./check.sh --selftest  the checkers' own refusals        (~4 s, no toolchain)
#
# The gate passes on this tree every day, so its failure paths are the part
# nobody exercises: --selftest runs the five selftest_*.py files, which hand each
# checker a throwaway repo and a stand-in (compiler, capture script, git repo) and
# make it refuse.
#
# Fast:  docs (figures, links, prose, the recorded APK bytes), widget assets, the
#        index (no source file left outside it), the host tests, and the branch list
#        as a note.
# Full:  the above, plus deck headroom, the seven render captures, and the APK
#        build with its signature, alignment and bundled-sketch checks.
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
    if [ -f "$root/env.sh" ]; then . "$root/env.sh"; fi
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

step "docs"       'docs, '              python3 check_docs.py "${cli_args[@]}"
step "widgets"    'widgets bundled'     python3 check_widget_assets.py
step "tracked"    'index'             tracked_step
step "host tests" 'host checks'         bash android-companion/test.sh
report "branches" python3 check_branches.py

if [ "$full" -eq 1 ]; then
  step "headroom" 'deck\(s\) fit'       python3 check_headroom.py "${cli_args[@]}"
  step "renders"  'renders current'     python3 check_renders.py
  step "apk"      '[0-9] bytes'         bash android-companion/toolchain.sh
fi

echo
if [ "$failures" -gt 0 ]; then
  echo "$failures step(s) failed"
  exit 1
fi
echo "every check passed"
