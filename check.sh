#!/usr/bin/env bash
#
# One gate, two speeds. A rule has one home, and this is where to run them from.
#
#   ./check.sh          what a commit must pass            (~3 s)
#   ./check.sh --full   what a release must pass           (~7 min): every deck on
#                       both cores, the old ones compared with old_core.json, each
#                       deck built twice, the nine frames redrawn, the APK built
#                       and rebuilt. It needs all three toolchains: source
#                       ~/.arduino-cyd-3/env.sh, ~/.arduino-cyd-2/env.sh (or
#                       ~/.arduino15/env.sh) for the old core, and
#                       ~/.android-offsite/env.sh.
#   ./check.sh --full <folder> ...
#                       the same for the decks you name — their headroom, their
#                       old-core build and their two-build repro — with the other
#                       decks left alone. The APK and the frames are app-wide, so
#                       they still run.
#   ./check.sh --decks-only <folder> ...
#                       those decks and nothing app-wide: their headroom, their
#                       old-core build and their two-build repro, with the frames,
#                       the APK and its rebuild skipped. It proves the decks you
#                       named still fit and still build the same bytes twice, and
#                       nothing about the app — so it is never the release gate.
#   ./check.sh --full --cached      the same steps without re-proving what this
#                       tree already proved: each expensive step hashes its inputs
#                       first, and one whose bytes match .gate_evidence.json is not
#                       compiled again. Evidence about a deck or a file this tree
#                       no longer holds is dropped before the run. For working on
#                       the tree, never for a release.
#   ./check.sh --selftest  the checkers' own refusals       (~10 s, no toolchain)
#
# The gate passes on this tree every day, so its failure paths are the part
# nobody exercises: --selftest runs the twelve selftest_*.py files, which hand each
# checker a throwaway repo and a stand-in (compiler, capture script, git repo) and
# make it refuse.
#
# Fast:  docs (figures, links, prose, the recorded APK bytes and its ceiling),
#        widget assets, the
#        index (no source file left outside it), the 2.0.17 record's shape (no
#        compiler needed), the screen counts, the host tests, and the nine render
#        captures whenever a staged file could move a pixel.
# Full:  the above, plus deck headroom on 3.3.12, the 2.0.17 decks compared with
#        old_core.json, the nine render captures, the APK build with its signature,
#        alignment and bundled-sketch checks, two more builds of that APK that must
#        match it byte for byte, and every deck built twice with its clock pinned,
#        whose images must match too.
#
# Each step takes the pattern of the line worth reading, so a passing step reports
# its measurement rather than whatever the script said last.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT" || exit 1
mkdir -p .scratch

full=0
selftest=0
cached=0
decks_only=0
reused=0
decks=()
failures=0

# Flags first, then deck names. A name that is not a deck is refused here rather
# than down in a checker, where an unknown folder yields no sketches and a step
# that measured nothing reads as a pass.
for arg in "$@"; do
  case "$arg" in
    --full)     full=1 ;;
    --selftest) selftest=1 ;;
    --cached)   cached=1 ;;
    --decks-only) decks_only=1 ;;
    -h|--help)
      awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
      exit 0 ;;
    -*)
      printf 'error: unknown flag %s (try --help)\n' "$arg" >&2
      exit 2 ;;
    *)
      if [ ! -f "$arg/$arg.ino" ]; then
        printf 'error: %s is not a deck: this tree has no %s/%s.ino\n' \
               "$arg" "$arg" "$arg" >&2
        exit 2
      fi
      decks+=("$arg") ;;
  esac
done

# Reuse is a mode of the work, not of the release: asking for it also asks for
# the full set, and then each expensive step may answer from what this tree
# already proved. The store is only ever written by a step that really ran.
if [ "$cached" -eq 1 ]; then full=1; fi

# Naming decks is what makes the gate narrower, so --decks-only with no name has
# nothing to narrow: it would skip the app and prove no deck, and a run that
# measured nothing must not read as a pass.
if [ "$decks_only" -eq 1 ]; then
  full=1
  if [ "${#decks[@]}" -eq 0 ]; then
    printf 'error: --decks-only names no deck, so it would skip the app and prove nothing. Name the decks to prove.\n' >&2
    exit 2
  fi
fi

step () {
  local name="$1" filter="$2"; shift 2
  local out=".scratch/gate-$name.log"
  local start end rc last ms
  start=$(date +%s%N); : >"$out"
  "$@" >"$out" 2>&1; rc=$?
  end=$(date +%s%N); ms=$(( (end-start)/1000000 ))
  last=$(grep -E "$filter" "$out" | tail -1)
  [ -n "$last" ] || last=$(tail -1 "$out")
  # The step's own sentence, for the evidence cache and for anything else that
  # wants the measurement rather than a second grep of the log.
  STEP_LINE="$last"
  if [ "$rc" -eq 0 ]; then
    printf 'ok    %-15s %6d ms  %s\n' "$name" "$ms" "$last"
  else
    printf 'FAIL  %-15s %6d ms  %s\n' "$name" "$ms" "$last"
    failures=$((failures + 1))
    echo "----- $name"; sed -n '1,40p' "$out"; echo "-----"
  fi
}

# A step whose inputs are hashed before it runs. With --cached a run whose bytes
# this tree already proved says so and returns without compiling; a changed byte,
# a missing entry or a store that cannot be read all fall through to the real
# step, and a step that fails is never recorded. Naming a deck changes the key,
# so one deck's evidence can never answer for two.
step_evidenced () {
  local name="$1" filter="$2" stamp="$3"; shift 3
  local watch=()
  while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do watch+=("$1"); shift; done
  [ "$#" -gt 0 ] && shift
  if [ "$cached" -eq 0 ]; then
    step "$name" "$filter" "$@"
    return
  fi
  local start=$SECONDS ms key said when summary rc
  local scribble=".scratch/gate-$name.key"
  start=$(date +%s%N)
  key=$(python3 check_evidence.py key --stamp "$stamp" --watch "${watch[@]}" 2>"$scribble")
  if [ -z "$key" ]; then
    say "$name" "nothing to key on, so it is proved again: $(tail -1 "$scribble")"
    step "$name" "$filter" "$@"
    return
  fi
  if said=$(python3 check_evidence.py hit --name "$name" --of "$key" 2>/dev/null); then
    when=${said%%$'\t'*}
    summary=${said#*$'\t'}
    ms=$(( ($(date +%s%N)-start)/1000000 ))
    printf 'cached %-14s %6d ms  %s\n' "$name" "$ms" "$summary"
    echo "       that measurement was taken at $when on these same bytes; it was not run now"
    reused=$((reused + 1))
    return 0
  fi
  step "$name" "$filter" "$@"
  rc=$?
  if [ "$rc" -eq 0 ] && [ -n "${STEP_LINE:-}" ]; then
    python3 check_evidence.py record --name "$name" --of "$key" \
      --watch ${watch[@]+"${watch[@]}"} --summary "$STEP_LINE" >/dev/null 2>&1
  fi
  return "$rc"
}

# A step whose prerequisite is not on this machine says which one, the way the
# old pre-commit hook did: a fresh clone has no built assets and a laptop may have
# no deck toolchain, and neither is a broken tree.
say () {
  printf 'note  %-15s %s\n' "$1" "$2"
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

if [ "$selftest" -eq 1 ]; then
  step "docs selftest"   'branches of check_docs'           python3 selftest_docs.py
  step "assets selftest" 'branches of check_widget_assets'  python3 selftest_widgets.py
  step "margin selftest" 'branches of check_headroom'       python3 selftest_headroom.py
  step "core selftest"   'branches of check_old_core'       python3 selftest_old_core.py
  step "render selftest" 'branches of check_renders'        python3 selftest_renders.py
  step "screens selftest" 'branches of check_screens'       python3 selftest_screens.py
  step "repro selftest" 'branches of check_repro'          python3 selftest_repro.py
  step "sketch selftest" 'branches of check_sketch_repro'   python3 selftest_sketch_repro.py
  step "gate selftest"   'branches of check.sh'            python3 selftest_gate.py
  step "apk selftest"    'branches of check_apk'           python3 selftest_apk.py
  step "cache selftest"  'branches of check_evidence'      python3 selftest_evidence.py
  step "map selftest"    'branches of check_map'           python3 selftest_map.py
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
# The old core's record is the one 2.0.17 number the fast gate can check, because
# it needs no compiler: a deck it names, one row per deck, an md5 for a sketch a
# clone would have. A build on that core is the slower question, in --full.
step "old record" 'consistently'      python3 check_old_core.py --record
# The archive in dist/ is what the site serves and what HANDOFF quotes, and the
# step that builds it is --full's. This is the cheap question a commit can answer
# without building: is it still this tree's own build, and inside its ceiling?
step "apk bytes"  'ceiling'           python3 check_apk.py
# The deck's three faces are bitmap data pasted into a sketch, and Adafruit_GFX
# reads a glyph as one bitstream with no row padding. Padding rows to bytes is
# what turned every letter on glass into blocks on the first flash, and the
# frames missed it because the renderer padded too. This holds both round trips.
step "fonts"      'glyphs packed'      python3 check_fonts.py
# A number of screens is a claim like a size, and the one that drifted: seven files
# said seven for a week after the app drew nine. This reads the tracked prose against
# the frames git holds, so the count has one home instead of eight.
step "screens"    'screen count'      python3 check_screens.py
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
# Not a failure: the branches are the check. This is the index from a failing
# case back to the refusal it pins, so a red --selftest names a rule.
step   "map"      'has a selftest'     python3 check_map.py

# A commit that can change a pixel has to leave a picture of it behind, and a rule
# nobody runs is a paragraph. The fast gate draws the frames whenever the staged
# files could move one — the app's Java, its resources, or the capture itself —
# and says plainly when it did not. That is why the renders stopped arriving: they
# were a --full step, and the pre-commit hook runs the fast gate.
ui_staged () {
  git diff --cached --name-only --diff-filter=ACMR \
    -- 'android-companion/app/src/main/java' 'android-companion/app/src/main/res' \
       'android-companion/app/src/main/assets' '*/widget.json' \
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
  # Evidence about a deck or a file this tree no longer holds can never be reused,
  # and it should not sit in the store looking current. Quiet when there is
  # nothing to drop; a store that cannot be read is refused rather than rewritten.
  if [ -f .gate_evidence.json ]; then
    pruned=$(python3 check_evidence.py prune 2>&1) || true
    [ -n "$pruned" ] && say "cache" "$(printf '%s' "$pruned" | tr '\n' ' ' | sed 's/ *$//')"
  fi
  # What a key covers: the decks this run proves (the ones named, or every deck
  # the tree holds), so a run scoped to one deck can never answer for two.
  watched=()
  if [ "${#decks[@]}" -gt 0 ]; then
    watched=("${decks[@]}")
  else
    for descriptor in */widget.json; do watched+=("$(dirname "$descriptor")"); done
  fi
  # A toolchain is an input too: a core installed into another root is another
  # build, so its stamp is in every key the decks are proved under.
  stamp_deck="cli=${cli:-none} data=${ARDUINO_DIRECTORIES_DATA:-none}"
  stamp_android="jdk=${JAVA_HOME:-none} sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-none}}"
  step_evidenced "headroom" 'deck\(s\) fit' "$stamp_deck" \
    "${watched[@]}" check_headroom.py check_evidence.py -- \
    python3 check_headroom.py "${cli_args[@]}" ${decks[@]+"${decks[@]}"}
  # The old core is a separate question, not a second opinion: a deck that fits on
  # 3.3.12 can still overflow on 2.0.17, and that root carries its own data dir and
  # its own Python path, so it is built in a shell that sourced its env.sh. The
  # build is then compared with old_core.json: a figure the docs quote is only as
  # good as the last run behind it, and nothing else rebuilds 2.0.17.
  abroot=""
  for candidate in "$HOME/.arduino-cyd-2/env.sh" "$HOME/.arduino15/env.sh"; do
    if [ -f "$candidate" ]; then abroot="$candidate"; break; fi
  done
  if [ -n "$abroot" ]; then
    # $0 is the script name bash -c was given, so the env.sh is $1 and the deck
    # names follow it: sourcing then shifting leaves exactly those names.
    step_evidenced "old core" 'old_core.json' "ab=$abroot" \
      "${watched[@]}" check_old_core.py old_core.json check_evidence.py -- \
      bash -c '. "$1"; shift; exec python3 check_old_core.py "$@"' \
      - "$abroot" ${decks[@]+"${decks[@]}"}
  else
    say "old core" "no A/B root in ~/.arduino-cyd-2 or ~/.arduino15, so the old core was NOT built"
  fi
  # The app is one build, and --decks-only is the mode that leaves it alone: the
  # frames, the APK and its second build are the three steps that prove the app
  # rather than a deck, and skipping them is what makes this run narrower.
  if [ "$decks_only" -eq 0 ]; then
    step_evidenced "renders" 'renders current' "$stamp_android" \
      android-companion/app/src/main/java android-companion/app/src/main/res \
      android-companion/capture.sh check_renders.py check_evidence.py \
      "${watched[@]}" -- python3 check_renders.py
    step_evidenced "apk" '[0-9] bytes' "$stamp_android" \
      android-companion/app/src/main/java android-companion/app/src/main/res \
      android-companion/app/src/main/AndroidManifest.xml \
      android-companion/build.gradle android-companion/settings.gradle \
      android-companion/gradle.properties android-companion/toolchain.sh \
      check_apk.py check_evidence.py "${watched[@]}" -- \
      bash android-companion/toolchain.sh
    # One build can never say whether the archive reproduces: AGP restamped a block
    # on every run until 2026-11-03 and nothing noticed for weeks. Two builds, twice
    # the cost, and the only question is whether the bytes match.
    step_evidenced "repro" 'same bytes' "$stamp_android" \
      android-companion/app/src/main/java android-companion/app/src/main/res \
      android-companion/app/src/main/AndroidManifest.xml \
      android-companion/build.gradle android-companion/settings.gradle \
      android-companion/gradle.properties android-companion/toolchain.sh \
      check_repro.py check_apk.py check_evidence.py "${watched[@]}" -- \
      python3 check_repro.py
  fi
  # The sketches are flashed, so their bytes matter the same way the APK's do:
  # each deck is compiled twice and the images must match.
  # The decks compile together, so a new deck costs a compile, not a run.
  step_evidenced "sketch repro" 'same bytes' "$stamp_deck" \
    "${watched[@]}" check_sketch_repro.py check_evidence.py -- \
    python3 check_sketch_repro.py "${cli_args[@]}" ${decks[@]+"${decks[@]}"}
fi

echo
if [ "$failures" -gt 0 ]; then
  echo "$failures step(s) failed"
  exit 1
fi
if [ "$reused" -gt 0 ]; then
  echo "every check passed, $reused of them reused from .gate_evidence.json:"
  echo "those steps were not run this time, so this is not the release gate"
elif [ "$decks_only" -eq 1 ]; then
  echo "every check passed for ${decks[*]}: headroom, the old core and two builds that match"
else
  echo "every check passed"
fi
# A run that never built the app has not proved it, and its last line says which
# two things were left out rather than leaving a reader to remember. When reuse
# has already said this is not the release gate, it says what else was skipped
# instead of repeating itself.
if [ "$decks_only" -eq 1 ]; then
  if [ "$reused" -eq 0 ]; then
    echo "the frames and the APK were not built, so this is not the release gate"
  else
    echo "the frames and the APK were not built either"
  fi
fi
exit 0
