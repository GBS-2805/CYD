# Refresh the bundle, the release check and `dist/` after the widget rename

Handoff from the firmware chat, not applied here. Four defects, all caused by the
same two changes landing separately: the sketch was renamed to **Phone Deck** and
`syncDeckSketch` was replaced by `syncWidgets`, which writes
`assets/widgets/<id>/` instead of `assets/deck/`.

**Before any of it:** in this worktree `../cyd_phone_deck/` still holds the sketch
at md5 `10c464ad…`. The firmware chat's newer sketch is `a97320f7…` plus a
rewritten `widget.json`, and it is uncommitted there. `syncWidgets` copies from
*this* worktree's sibling folder, so a rebuild here reproduces the old firmware
and step 1's new check would fail against a sketch that is genuinely newer. Land
that commit first, then work down this list.

## Why

The app is described in its own docs as shipping "the firmware in this repo", and
its release script claims to prove it, but neither is true on this tree:

- `toolchain.sh` verifies `assets/deck/cyd_phone_deck.ino`, which nothing produces
  any more, so the release check fails on a path that no build writes.
- `WidgetCatalog.fallback()` still describes the GMK Dracula palette, two palettes
  behind the sketch, and it is what a build that skips Gradle shows.
- `dist/` still publishes the pre-monochrome sketch and an APK built from it:
  `dist/cyd_phone_deck.ino` and the APK's `assets/deck/cyd_phone_deck.ino` are
  byte-identical at md5 `d53664ece3afecbbb4d7f855c99bbd55`, 70,191 bytes. That
  sketch has **no `THEMES[]` table at all**, still sets
  `C_BG = 0x10A3 // #12141f slate black`, and advertises `DEVICE_NAME =
  "Phone Deck"` with no MAC tag. `dist/index.html` links that file for download.
- Three docs still call the card **ESP32 Phone_Widget** and one still documents
  `syncDeckSketch` and `assets/deck/`.

None of it is visible as a build error: a Gradle build succeeds, the app runs, and
the card, the downloadable sketch and the shipped APK all describe a board that no
longer exists.

## 1. `toolchain.sh`: verify the asset the app actually reads

Evidence: `app/build.gradle` registers only `syncWidgets`
(`tasks.register("syncWidgets")`, output dir `src/main/assets/widgets`, wired into
`preBuild`); `app/src/main/assets/` contains exactly
`widgets/phone_deck/{cyd_phone_deck.ino,widget.json}`; and the runtime reads the
same path — `WidgetCatalog.java` sets
`widget.assetPath = "widgets/" + dir + "/" + widget.sketch` and `FlashLibrary`
opens `widget.assetPath`. The shipped APK still carries `assets/deck/…` only,
which is what the old task used to write.

### Replace this block

```bash
say "verify bundled sketch"
TMP="$(mktemp -d)"
unzip -p "$APK" assets/deck/cyd_phone_deck.ino > "$TMP/sketch.ino" \
  || fail "the APK has no assets/deck/cyd_phone_deck.ino"
BUNDLED="$(md5of "$TMP/sketch.ino")"
REPO_MD5="$(md5of "$SKETCH")"
rm -rf "$TMP"
printf '  bundled %s\n  repo    %s\n' "$BUNDLED" "$REPO_MD5"
[ "$BUNDLED" = "$REPO_MD5" ] || fail "the bundled sketch is stale; syncDeckSketch did not run"
```

### With this block

```bash
say "verify bundled sketch"
# syncWidgets writes assets/widgets/<id>/<sketch>, and WidgetCatalog.assetPath
# reads that same path at runtime, so this is the copy a user actually gets.
BUNDLED_REL="assets/widgets/phone_deck/cyd_phone_deck.ino"
TMP="$(mktemp -d)"
unzip -p "$APK" "$BUNDLED_REL" > "$TMP/sketch.ino" \
  || fail "the APK has no $BUNDLED_REL"
BUNDLED="$(md5of "$TMP/sketch.ino")"
REPO_MD5="$(md5of "$SKETCH")"
rm -rf "$TMP"
printf '  bundled %s\n  repo    %s\n' "$BUNDLED" "$REPO_MD5"
[ "$BUNDLED" = "$REPO_MD5" ] || fail "the bundled sketch is stale; syncWidgets did not run"
```

Keep `SKETCH="$HERE/../cyd_phone_deck/cyd_phone_deck.ino"` as it is: the repo
sketch stays the reference the bundle is compared against.

## 2. `WidgetCatalog.fallback()`: two palettes behind

Evidence: `widget.json` (both the folder copy and the bundled one) now reads
`"body": "A desk dashboard for your phone: clock, battery and notifications, set
like a woodblock print in two monochrome themes — black ink on paper, or white ink
on black."` The fallback at `WidgetCatalog.java` still says *"drawn in the GMK
Dracula keycap palette"*, which is the palette two generations back. Its own
javadoc is the reason this matters: a raw build that skipped Gradle gets these
values, so "edit both or the two builds show different cards".

### Replace this block

```java
        widget.body = "A desk dashboard for your phone: clock, battery and notifications, "
                + "drawn in the GMK Dracula keycap palette.";
```

### With this block

```java
        widget.body = "A desk dashboard for your phone: clock, battery and notifications, "
                + "set like a woodblock print in two monochrome themes — black ink on paper, "
                + "or white ink on black.";
```

The literal em dash is UTF-8 in a source file that already carries `·` elsewhere,
so it needs no escape — but if the build ever sets `-encoding` to something other
than UTF-8, `\u2014` is the safe form. Nothing else in that fallback is stale: the
tags, libraries, `never` list, `nameMatch` (`phone_widget` and `phone deck`, so
both the old and new advertised names are found) and the service UUID all match
the descriptor.

## 3. `dist/`: republish, and stop hand-copying

Evidence: `dist/cyd_phone_deck.ino` is md5 `d53664ec…`, and
`unzip -p dist/PhoneDeckBridge-debug.apk assets/deck/cyd_phone_deck.ino | md5sum`
returns the same digest — the APK and the downloadable sketch are the same old
file, and both are pre-monochrome. `dist/index.html` links
`href="cyd_phone_deck.ino"` and `href="PhoneDeckBridge-debug.apk"`, so the
download page serves them directly.

Rebuild through `./toolchain.sh`, then refresh `dist/cyd_phone_deck.ino` from the
source sketch rather than by hand. Adding this next to the `cp "$APK" "$DIST"`
step makes the drift impossible instead of merely fixed:

```bash
cp "$SKETCH" "$HERE/dist/cyd_phone_deck.ino"
```

Then check both digests:

```bash
md5sum ../cyd_phone_deck/cyd_phone_deck.ino dist/cyd_phone_deck.ino
unzip -p dist/PhoneDeckBridge-debug.apk assets/widgets/phone_deck/cyd_phone_deck.ino | md5sum
```

All three must print `a97320f705fb4927597bc928c483288a` (or whatever the sketch is
once the newer commit lands). The byte count should rise: the monochrome sketch is
about 1,165,174 B *compiled* but the source is larger than 70,191 B now that
`THEMES[]` and the header helpers exist.

## 4. Docs that name the old card and the old task

- `README.md` — "The **ESP32 Phone_Widget** card is this repo's firmware, marked
  `INSIDE APP`" → the descriptor says `Phone Deck`.
- `HOWTO.md` — three steps say **PROJECTS → ESP32 Phone_Widget** (COPY DROID,
  SAVE, DECK LIBRARIES); make them **Phone Deck**.
- `HOWTO.md` troubleshooting row `the bundled sketch is stale` attributes it to
  `syncDeckSketch`; that task no longer exists.
- `HANDOFF.md` — "Firmware files" repeats the card name, and the bullet
  "Gradle task `syncDeckSketch` copies … into `app/src/main/assets/deck/`" now
  describes neither the task nor the path. Its "do not commit a duplicate into
  assets" advice still holds and is worth keeping; only the names and the path
  change.

Leave the earlier verification log in `HANDOFF.md` alone. It records that
`assets/deck/cyd_phone_deck.ino` matched the repo sketch on the day it ran, which
was true then; rewriting it would make the history claim something that never
happened.

## 5. Optional: let the firmware chat's checker gate the release

`../check_widget_assets.py` (repo root, stdlib only, exit 1 on drift) compares the
committed `assets/widgets/<id>/` copies against the widget folder byte for byte,
checks that each `widget.json` names a sketch that exists, and complains about an
asset folder no descriptor claims. Put it ahead of Gradle in `toolchain.sh`:

```bash
python3 "$HERE/../check_widget_assets.py" || fail "the committed widget bundle does not match the widget folder"
```

It is not a substitute for the APK check in step 1 — one guards what is committed,
the other guards what was packaged. Both matter: `syncWidgets` overwrites the
committed copies at build time, so a stale commit is invisible to anyone who only
ever builds.

## How to verify the whole thing

```bash
# from android-companion/, on a branch that contains the newer widget commit
../check_widget_assets.py          # expect: 1 widget bundled, every copy matches
./toolchain.sh                     # expect: bundled == repo, both a97320f7…
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep widget
                                   # expect: assets/widgets/phone_deck/… and no assets/deck/
unzip -p dist/PhoneDeckBridge-debug.apk assets/widgets/phone_deck/cyd_phone_deck.ino | grep -c 'THEMES\[\]'
                                   # expect: 1, not 0
```

On a device, the PROJECTS card should read the one-ink, two-theme sentence, and
COPY DROID should paste a sketch that opens with `THEMES[]` and `THEME_INDEX`.

Two things this note cannot check for you: `toolchain.sh` needs the offsite
Gradle/Android SDK environment, so the back half of the verification is unrun from
here; and the intermediate
`app/build/intermediates/assets/debug/mergeDebugAssets/widgets/phone_deck/cyd_phone_deck.ino`
sits at `3c51989e…` from an older build. A clean build replaces it — it is residue,
not a source of truth, but it will mislead anyone comparing md5s by hand.
