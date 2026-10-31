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
