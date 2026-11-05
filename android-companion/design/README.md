# `design/` — the app as a file you can edit

The companion app's UI lives in Java, built view by view, so there was no artifact
you could open, poke at and hand back. This folder is that artifact.

It is **not** a build input. Nothing here is compiled, packaged into the APK or
read at runtime. It is a drawing that is metric-accurate enough to argue with.

**It draws the previous layout, and says so.** The page was drawn when the app
opened on a drawer with three rows and a PAPER/INK switch; the app now opens on
PAIRED, with the pull-down in the top bar's left corner and a side panel behind the
burger, on the site's three palettes. The current drawing of the app is the
captures in [`../../artifacts/renders/`](../../artifacts/renders/), which
`capture.sh` takes from the views themselves and `check_renders.py` holds to the
pixels. This page is kept as the measurement record of the layout it describes.

## Files

| File | What it is |
| --- | --- |
| `ui.html` | Seven of the app's nine frames, from the previous layout — all but the editor card and the file picker — at 393 × 852 dp, one static page. Open it in any browser. No build, no server, no dependencies. |
| `tokens.json` | The same numbers as data — palette, radii, type sizes, letter-spacing, padding — each tied to the `Ui.java` constant or the method that uses it. This is what a handoff gets diffed against. |
| `README.md` | This file. |

Both files were written by reading the Java, not by memory: the colours came from
`Ui.java`, the metrics from `MainActivity.buildTopBar()`, `DrawerHome`,
`ProjectsTab`, `DecksTab`, `BoardTab` and `SetupTab`. The `capture.sh` script in
the parent folder draws the app's nine screens to PNG from the same numbers; this
page draws seven of them, from the layout that was current when it was written, so
the two are descriptions of one design and they have to agree. The two missing from
`ui.html` are the editor card and the file picker: both came after this page was
drawn, and its frames are numbered 1 to 7.


## Opening and editing it

Open `ui.html` in a browser. On a phone-sized window it reads at roughly life size;
on a desktop, use the browser's zoom if you want the frames bigger. Everything is
plain HTML and CSS:

* **Colours and radii** are custom properties at the top of the `<style>` block,
  named after the Java constants (`--edge` is `Ui.EDGE`). Change them once there.
* **Sizes** are copied as px where the app says dp or sp, because the drawing is
  1 dp = 1 px. Change `16px` to `14px` and it means 14 dp.
* **Each block** carries an HTML comment naming the method that draws it, e.g.
  `<!-- DrawerHome.menuRow("PAIRED", "live link", …) -->`.
* **Type** is monospace throughout on purpose: the deck is a terminal, so the
  phone is meant to read as the same instrument. A proportional face here would be
  a different design, not a restyle.
* The **dashed line at 852px** is the bottom of the glass. Content under it is real
  and scrolls; it is drawn rather than clipped so you can still edit it.

Try things. Nothing here can break the app.

## Handing it back

1. Edit `ui.html` (and `tokens.json` if it is only numbers).
2. Say what changed in the chat, in words. Prose plus the file is enough; I read
   the diff too.
3. I change the Java, rebuild, and regenerate the renders — every screen that
   moved, **linked in the chat before anything ships**, per the rule in
   `CONVENTIONS.md`.
4. If a render disagrees with this file, the render is what the phone does and this
   file gets corrected. They are not allowed to drift apart.

A change is only done when the numbering agrees in three places: this page,
`tokens.json`, and the regenerated PNG.

## What the drawing cannot model

Be sceptical of anything here that is about *behaviour* rather than layout:

* the search field's caret, keyboard and focus — `ui.html` shows the empty field;
* `ScrollView` physics, overscroll and where a list lands after a rebuild;
* the `AlertDialog`s behind `LOG` and `STATUS`, and the `Spinner` inside the log
  form — those are platform chrome, not part of this page;
* ripples, long-press, and whether a 22dp chip is actually comfortable to hit;
* permission sheets and the notification-access system screen;
* the Spotify callback — `SpotifyCallbackActivity` answering
  `phonedesk://spotify-callback`, the browser chooser that leads to it and the one
  line of result it shows. The deck's own screen carries the sign-in, and this
  page draws that screen, not the return trip.

If a design depends on one of those, describe it and I will mock the dialog in the
page or note it as unverified until it runs on the phone.

## Settled in the last pass

Three rough edges this file used to list are now fixed in both the page and the
Java, which is what the pair is for:

* the drawer's expansion names the boards only, one row each, and a row opens the
  shelf: the same projects in both places made two indexes of one list, and the
  drawer's copy could only carry a name and a status. Two boards keep the 6dp gap
  (`Ui.space(row, 6)`) the project rows had, because the fill is only 6% above the
  ground and the 8dp edge alone did not separate them;
* the BOARDS crumb is now just the board (`2.4" CYD (RESISTIVE)`), not
  `BOARDS › board`: the bar already says `‹ OFF-SITE` and the title already says
  BOARDS;
* the libraries card puts two copy buttons to a row at 11sp instead of three
  full-width ones, and the hint is one sentence. The card is ~80dp shorter, so the
  shelf starts on the first screen rather than under the fold.

If you disagree with any of those, they are three edits in `ui.html` — say which
and why, and they go back the other way.
