# Handing a deck change to the app

One chat edits the sketches and `android-companion/`'s Java, so a deck change that
needs a line of Java is one commit instead of a hand-off between branches. Who owns
what, and how another chat's work reaches this tree, is
[`## The two chats`](CONVENTIONS.md#the-two-chats) in `CONVENTIONS.md` — this page
is only the build step.

What still matters is that the APK carries a *copy* of each sketch.
`:app:syncWidgets` is what moves a sketch into `app/src/main/assets/widgets/`,
nothing else does, and a build that skipped it ships the previous file under the
new name. The rest of this page is that step.

## Preferred: cherry-pick the commit

Both chats are worktrees of one repository: `git worktree list` names this tree
and the app's side by side under one bare repo, so a commit made here is already
in the object store the app tree reads and `git cherry-pick <sha>` in that tree
works with nothing pushed. A push is a publish, not a hand-off — `CONVENTIONS.md`
says nothing goes out unless it is asked for — so the hand-off is a SHA, and it
has to be the right one:

```bash
git show <sha>:spotify_deck_cyd24R/spotify_deck_cyd24R.ino | md5sum
```

must print the md5 that deck's README quotes today, or the pick carries an older
sketch and the APK is built from it. `check_widget_assets.py` cannot catch that
one: it compares the bundled copies with the sketches in *its own* tree, and a
stale tree agrees with itself. Push-and-merge is the same hand-off for trees that
are separate clones rather than worktrees, where the commit really is unreachable
until it is published — a different route to the same commit, not a different
gate.

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

That is the whole re-gate on the app side: its host tests, `:app:syncWidgets`,
`check_widget_assets.py`, then `toolchain.sh`, whose own check reads the sketch
back out of the finished APK. A commit that touches no sketch needs none of it —
see *After a documentation-only deck change* below.

That asset tree is **generated output and gitignored**: `android-companion/.gitignore`
lists `app/src/main/assets/widgets/`, so nothing there is ever committed and a
re-sync leaves `git status` clean. Gradle derives
`app/src/main/assets/widgets/<id>/` from the `widget.json` and the sketch it
names, and deletes anything under there no descriptor claims; `WidgetCatalog`
reads the built copy at runtime and the app's `toolchain.sh` reads the sketch
back out of the built APK. So an edited sketch that never went through
`:app:syncWidgets` ships the *old* file in the *new* APK: the change is in the
repo, green in a build, and absent from the board. Nothing at commit time enforces
the sync step; the app's `toolchain.sh` does at build time, because it reads the
sketch back out of the finished APK and refuses a mismatch. `check_widget_assets.py`
is the cheaper alarm, naming both md5s in a second without Gradle,
and `OUT OF SYNC`, so run it after every sketch edit and before any release.

## Fallback: file-level, when the histories have diverged

A cherry-pick of a rename onto a branch that already made the same rename either
conflicts or duplicates it. Take the files directly instead:

```bash
git checkout <sha> -- spotify_deck_cyd24R   # or phone_deck_cyd24R
python3 check_widget_assets.py
```

## Keep one line

`main` holds its own copies of both decks. Pick a change into the app tree, or
work on top of `main` here, so the two copies do not drift. If they do, the
sketches are the truth and the app copy is the stale one. Pushing either side
back to `origin` is a publish and waits to be asked for, which is the other half
of why the hand-off is a commit SHA: the loop closes locally, and `origin` is
where the trees meet, not how they talk.

## The app now carries two decks

`syncWidgets` copies every `*/widget.json` beside `android-companion/`, so both
decks travel: `assets/widgets/phone_deck_cyd24R/` and
`assets/widgets/spotify_deck_cyd24R/`. `check_widget_assets.py` prints
`2 widgets bundled, every copy matches` (phone deck md5 `62c7e6c3…` on
2026-11-01, Spotify `3b17f04a…` on 2026-11-02 after its header was rewritten;
`f234b68b…` before that rewrite), and that is the check to run after either
sketch changes — a passing `assembleDebug` says nothing about which copies went
into the APK. The app's own docs described one bundled widget and a folder name from
before the rename; both halves are this tree's now, so keeping that current is
one more thing to do here.

## After a documentation-only deck change

The widget `README.md` files and `CONVENTIONS.md` are sketch-side docs.
`syncWidgets` copies only `widget.json` and the sketch that descriptor names, and
`check_widget_assets.py` compares exactly those two files, so nothing under
`android-companion/app/src/main/assets/widgets/` depends on a README. A
docs-only deck commit therefore needs no `:app:syncWidgets`, no APK rebuild and no
Java change to be correct; taking the commit anyway just keeps the two trees
level, and the READMEs are how the next deck edit finds its numbers.

The one way these docs can go stale in the app is if an app-side document quotes
a deck's flash use. Say which core: 967,413 B is 73 % of 1,310,720 on 2.0.17,
and 1,125,781 B is 85 % of 1,310,720 on 3.3.12. The deck READMEs hold the
measured figures and the verified/unverified split, so point at them instead of
copying numbers out.

## Both decks now default to core 3.3.12

The deck `toolchain.sh` files pin `esp32:esp32@3.3.12`; 2.0.17 stays available as
the A/B build in its own install root. No app file names a core, so there is
nothing to change in the app — but any deck size quoted in app docs now means the
3.3.12 build unless it says otherwise:

| deck | flash | share of its ceiling |
| --- | --- | --- |
| phone, default scheme | 1,168,314 B | 89 % of 1,310,720 |
| Spotify, `huge_app` | 1,878,661 B | 59 % of 3,145,728 |

`check_headroom.py` read both of them on 2026-11-02 with the link in place, from
`~/.arduino-cyd-3/env.sh` — the root those figures belong to, the one whose
`env.sh` pins its own data and library dirs, and the one to source before quoting
them ([build note](CONVENTIONS.md#build)). `~/.arduino-cyd/env.sh` sets only
`PATH` and builds through `~/.arduino15`, where the same two sketches read
1,168,282 B and 1,878,613 B — 32 B and 48 B under with the globals identical,
which is the build path rather than the sketch.
