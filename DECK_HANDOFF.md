# Handing a deck change to the app

This tree owns the sketches; the app tree (`android-companion/`) owns the Java.
A deck change reaches the APK by moving the commit, never by editing the app.

## Preferred: cherry-pick the commit

Both chats are worktrees of one repository on the same machine, so the commit is
already reachable — no push required.

```bash
git cherry-pick <sha>                 # the deck commit from this branch
source ~/.android-offsite/env.sh      # sets JAVA_HOME
ANDROID_HOME=~/.android-offsite/sdk ~/.android-offsite/gradle-8.7/bin/gradle \
  --no-daemon --console=plain :app:syncWidgets
python3 check_widget_assets.py
```

`:app:syncWidgets` rewrites `app/src/main/assets/widgets/<id>/` from each
`widget.json`, so the APK carries the new sketch and the old asset folder goes
away. It reads the id and sketch name from the descriptor, which is why a rename
has to move the folder, the `.ino` and the `id` in one commit.

Then build the APK as usual. `check_widget_assets.py` printing
`N widgets bundled, every copy matches` is the check that the copies agree — a
bare "BUILD SUCCESSFUL" only means Gradle ran.

That asset tree is **generated output that is also committed**. Gradle derives
`app/src/main/assets/widgets/<id>/` from the `widget.json` and the sketch it
names, and deletes anything under there no descriptor claims; `WidgetCatalog`
reads the committed copy at runtime and the app's `toolchain.sh` reads the
sketch back out of the built APK. So an edited sketch that never went through
`:app:syncWidgets` ships the *old* file in the *new* APK: the change is in the
repo, green in a build, and absent from the board. Nothing enforces the sync
step — `check_widget_assets.py` is what makes the drift loud, naming both md5s
and `OUT OF SYNC`, so run it after every sketch edit and before any release.

## Fallback: file-level, when the histories have diverged

A cherry-pick of a rename onto a branch that already made the same rename either
conflicts or duplicates it. Take the files directly instead:

```bash
git checkout <sha> -- spotify_deck_cyd24R   # or phone_deck_cyd24R
python3 check_widget_assets.py
```

## Keep one line

`main` holds its own copies of both decks. Pick a change into the app tree and
push it back, or work on top of `main` here, so the two copies do not drift. If
they do, the sketches are the truth and the app copy is the stale one.

## The app now carries two decks

`syncWidgets` copies every `*/widget.json` beside `android-companion/`, so both
decks travel: `assets/widgets/phone_deck_cyd24R/` and
`assets/widgets/spotify_deck_cyd24R/`. `check_widget_assets.py` prints
`2 widgets bundled, every copy matches` (phone deck md5 `62c7e6c3…`, Spotify
`f234b68b…`, both on 2026-11-01), and that is the check to run after either
sketch changes — a passing `assembleDebug` says nothing about which copies went
into the APK. The app's own docs described one bundled widget and a folder name
from before the rename; that side is the app chat's to keep current, and what
this tree has to get right is that a second descriptor now exists.

## After a documentation-only deck change

The widget `README.md` files and `CONVENTIONS.md` are sketch-side docs.
`syncWidgets` copies only `widget.json` and the sketch that descriptor names, and
`check_widget_assets.py` compares exactly those two files, so nothing under
`android-companion/app/src/main/assets/widgets/` depends on a README. A
docs-only deck commit therefore needs no `:app:syncWidgets`, no APK rebuild and no
Java change to be correct; taking the commit anyway just keeps the two trees
level, and the READMEs are how the next deck edit finds its numbers.

The one way these docs can go stale in the app is if an app-side document quotes
a deck's flash use. Say which core: the same Spotify sketch is 967,413 B on
2.0.17 and 1,125,781 B on 3.3.12 — 73 % against 85 % of the 1,310,720-byte app
slot. The deck READMEs hold the measured figures and the verified/unverified
split, so point at them instead of copying numbers out.

## Both decks now default to core 3.3.12

The deck `toolchain.sh` files pin `esp32:esp32@3.3.12`; 2.0.17 stays available as
the A/B build in its own install root. No app file names a core, so there is
nothing to change in the app — but any deck size quoted in app docs now means the
3.3.12 build unless it says otherwise: 1,168,314 B for the phone deck on the
default scheme (89 %) and 1,878,581-1,878,629 B for the Spotify deck on `huge_app` (59 %),
as `check_headroom.py` read both of them on 2026-11-01 with the link in place.
