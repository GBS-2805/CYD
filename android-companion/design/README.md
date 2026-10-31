# `design/` — the app as a file you can edit

The companion app's UI lives in Java, built view by view, so there was no artifact
you could open, poke at and hand back. This folder is that artifact.

It is **not** a build input. Nothing here is compiled, packaged into the APK or
read at runtime. It is a drawing that is metric-accurate enough to argue with.

## Files

| File | What it is |
| --- | --- |
| `ui.html` | The whole app: six screens at 393 × 852 dp, one static page. Open it in any browser. No build, no server, no dependencies. |
| `tokens.json` | The same numbers as data — palette, radii, type sizes, letter-spacing, padding — each tied to the `Ui.java` constant or the method that uses it. This is what a handoff gets diffed against. |
| `README.md` | This file. |

Both files were written by reading the Java, not by memory: the colours came from
`Ui.java`, the metrics from `MainActivity.buildTopBar()`, `DrawerHome`,
`ProjectsTab`, `BoardTab` and `SetupTab`. The `render_ui.py` script in the parent
folder draws the same six screens to PNG from the same numbers, so there are three
descriptions of one design and they have to agree.

## Opening and editing it

Open `ui.html` in a browser. On a phone-sized window it reads at roughly life size;
on a desktop, use the browser's zoom if you want the frames bigger. Everything is
plain HTML and CSS:

* **Colours and radii** are custom properties at the top of the `<style>` block,
  named after the Java constants (`--edge` is `Ui.EDGE`). Change them once there.
* **Sizes** are copied as px where the app says dp or sp, because the drawing is
  1 dp = 1 px. Change `16px` to `14px` and it means 14 dp.
* **Each block** carries an HTML comment naming the method that draws it, e.g.
  `<!-- DrawerHome.menuRow("DECKS", "live link", …) -->`.
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
3. I change the Java, rebuild, and regenerate the renders — which are then
   **embedded inline in the chat**, every screen that moved, per the rule in
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
* permission sheets and the notification-access system screen.

If a design depends on one of those, describe it and I will mock the dialog in the
page or note it as unverified until it runs on the phone.

## Known rough edges worth a look

These are real in the app today, not drawing mistakes:

* the project rows inside the expanded drawer are flush — `DrawerHome.projectRow`
  gives them a fill and an 8dp radius but no gap, so five of them read as one
  block;
* the PROJECTS crumb repeats the section name (`PROJECTS › 2.4" CYD (RESISTIVE)`)
  because the bar crumb already says `‹ OFF-SITE`; it could drop the first word;
* the libraries card is taller than the first screen, so the shelf itself starts
  below the fold on a 393 × 852 phone.

Fix any of these in `ui.html` first; they are cheap to try there and each is a
handful of lines in the Java.
