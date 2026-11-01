# Conventions — ESP32-2432S024R widgets

House rules for every project in this repo. They exist so a new widget — or a new
chat — does not have to rediscover them. If a rule stops being true, change it
here in the same change as the code that broke it.

**Scope: the ESP32-2432S024R only** — the 2.4" CYD with a resistive panel, an
ESP-WROOM-32 and a CH340 USB bridge. A board with a different panel, touch
controller or bridge gets its own section here first.

## One folder per widget

* Folder and sketch share a name: `phone_deck_cyd24R/phone_deck_cyd24R.ino`.
  ArduinoDroid compiles one `.ino` whose name must match its folder, so renaming
  either breaks the phone workflow and the app's build. The name carries the
  board it runs on — see [Naming](#naming-topic_deck_board) below.
* One sketch, one tab, **no extra headers**. A pasted `.ino` cannot see a header
  beside it.
* Each folder has **one** `README.md`: what it is, how to flash it, the traps, and
  what is verified. No `HOWTO.md`, no `HANDOFF.md` — a task that needs steps gets
  them in that README.
* Add the row to the [root README](README.md) table in the same change.
* Widgets bundled into the APK also carry [`widget.json`](phone_deck_cyd24R/widget.json)
  (`schema: 1`, with `id`, `name`, `sketch`, `board`, `tagline`, `body`, `notes`,
  `libraries`, `never`, and a `ble` block when it speaks BLE). The app copies the
  sketch at build time, so `android-companion/app/src/main/assets/widgets/<id>/`
  must hold a byte-identical copy. Check it with
  [`check_widget_assets.py`](check_widget_assets.py) before committing: it fails
  when a descriptor names a missing sketch, when a bundled copy has drifted, or
  when `assets/widgets/` holds a widget no descriptor claims.

### Naming: `<topic>_deck_<board>`

One deck, many boards, so the board is part of the name — in the folder, the
sketch and the descriptor `id`, which always move together:

| Tag | For | Example |
| --- | --- | --- |
| `cyd24R`, `cyd28R`, `cyd28C` | an all-in-one CYD: inches × 10, then the touch panel — `R` resistive, `C` capacitive | `phone_deck_cyd24R` |
| `ili9341_28`, `st7735_16` | a bare panel on a devkit: controller, then inches × 10; the touch letter only when a touch panel is wired | `weather_deck_ili9341_28` |
| `tdisplay_s3` | a module whose module name *is* the board | `maps_deck_tdisplay_s3` |

* Lowercase ASCII, never a `"`, a `.` or a space: a quote breaks shell quoting and
  Gradle asset paths, a dot reads as a version, a space splits the asset folder.
  Inches are × 10 as digits, so `cyd24R` is the 2.4" resistive CYD.
* The spoken form — `2.4" CYD (resistive)` — lives in `widget.json`'s `name`,
  never in an identifier.
* One folder, one sketch, one `id`, one board. Two sizes of one deck are two
  widgets that share prose, not one widget that switches panel at compile time:
  the phone holds one project per sketch and the app keys its shelf by filename.
* `board` in the descriptor keeps the model number where there is one
  (`2432S024R`); the tag is the human summary of the same fact.
* Variants differ only in a `BOARD` block at the top of the sketch — pins,
  rotation, driver, backlight, touch — so a diff between two tags stays small and
  a fix is the same small patch in every copy.

## The board

| Signal | GPIO |
| --- | --- |
| TFT CS / DC / backlight | 15 / 2 / 27 |
| Touch CS | 33 |
| SPI SCK / MISO / MOSI | 14 / 12 / 13 |
| RGB status LED | 4 / 16 / 17 |

* The RGB LED is three discrete LEDs between the GPIOs and 3.3 V, so a channel
  lights when its pin is **LOW**. Drive all three HIGH at boot; a floating channel
  glows cyan.
* The backlight is on its own switch at the edge, not a button, and can be PWM'd
  on GPIO 27.
* Buttons available to a sketch: none. BOOT and RESET are for flashing only.
* **No PSRAM, and the module marking is the evidence**: ESP32-WROOM-32 stamped
  `N4XX` is 4 MB of flash and no PSRAM. On a board that does have it, `none found`
  still proves nothing, because `esp32:esp32:esp32` defaults to `PSRAM=disabled`.
  Read the marking, never a product listing.
* **Budget for 4 MB of flash and no PSRAM.** The default partition scheme gives
  the app 1,310,720 bytes and the deck takes ~1,162,800 of them (88% as reported
  by the compiler). DRAM is 327,680 bytes with ~300 kB of heap free at boot once
  the display and touch drivers are up; a 320x240 RGB565 framebuffer is 153,600,
  so there is room for one, not two. Treat "it fits" as a measurement from
  `Sketch uses ...`, never an assumption.
* **A control that ignores taps is usually a stale mapping, not a bad control.**
  The deck's `+` chip took no taps while `-` worked and a recalibration fixed it.
  Keep targets at least ~40 px on the short side, clear of the outer 8 px, with a
  few pixels of slop. The panel is resistive and single-touch: a second tap while
  one is latched is a bounce, not input.

## Build

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 my_widget
```

* Board package `esp32:esp32`. Both decks default to core **3.3.12** and compile
  clean on 2.0.17 too; keep a second install root for the old core when you want
  an A/B measurement, because one root holds one core and the compiler picks the
  newest installed platform.
* **The default path is proven end to end.** `ARDUINO_HOME=~/.arduino-cyd-3
  ./toolchain.sh` in a fresh root installed the core and libraries and built both
  sketches, exit 0: phone deck 1,165,222 B (88 %) / 45,060 B globals, Spotify
  1,122,281 B (85 %) / 53,572 B globals. Those are 48 B and 32 B above the same
  sketches built straight through `arduino-cli` from another path — the build-root
  sensitivity above, not a different toolchain.
* **The margin is checked, not assumed.** [`check_headroom.py`](check_headroom.py)
  compiles each deck with the deck FQBN and reads the compiler's own `Maximum is
  …` figures, so both ceilings — 1,310,720 B of flash and 327,680 B of DRAM —
  come from the build instead of a hard-coded number: switch to `huge_app` and
  the flash one follows. It fails a deck that overflows, that drops under 64 KiB
  of flash (`--min-free-kb`), or that leaves under **160 KiB of DRAM**
  (`--min-free-ram-kb`), because a full 320x240 RGB565 framebuffer is 153,600 of
  those bytes and a deck that cannot allocate one is stuck. Every run prints
  used, free and globals per deck, so erosion shows up even when nothing trips.
* **That check runs on commit.** [`.githooks/pre-commit`](.githooks/pre-commit)
  compiles the decks whose `<folder>/<folder>.ino` is staged and refuses the
  commit if one fails, so a sketch cannot grow past its partition unnoticed.
  Docs-only commits return immediately, the app's synced asset copies are
  ignored, and `git commit --no-verify` is the deliberate escape hatch. It needs
  `git config core.hooksPath .githooks` once per clone. A machine with no deck
  toolchain at all says the check did not run and lets the commit through, so a
  fresh clone is never blocked; but a root that exists with no runnable
  `arduino-cli` in it (`~/.arduino-cyd-3`, `~/.arduino-cyd` or `$ARDUINO_HOME`)
  is a half-finished install, and that refuses the commit and prints three ways
  out — finish the install, point `ARDUINO_CLI` at a compiler, or accept the gap
  with `HEADROOM_ALLOW_MISSING_TOOLCHAIN=1` for that one commit. A merge runs
  the same script through
  [`pre-merge-commit`](.githooks/pre-merge-commit), because git runs no
  `pre-commit` for the commit it makes at the end of a merge; a fast-forward
  creates no commit and so checks nothing. A refused merge is left staged rather
  than undone — git prints `Not committing merge` — so finishing it with
  `git commit` runs the gate again.
* **The gate's refusals are tested without a compiler.**
  [`selftest_headroom.py`](selftest_headroom.py) hands `check_headroom.py` a
  stand-in that prints canned `Sketch uses` lines, so an overflow, a tight floor,
  a DRAM floor, a build that died before printing a size and a folder that is not
  a deck are re-checked in about a second on a machine with no toolchain. The
  decks themselves only ever walk the passing path; the overflow path was proved
  once with a 1.4 MB throwaway sketch, and that is not a test anyone can rerun.
* **Before you change a sketch, run both.** From the repo root:

  ```bash
  python3 selftest_headroom.py                           # the refusals, ~1 s
  source ~/.arduino-cyd-3/env.sh && ./check_headroom.py  # the decks, ~17 s warm
  ```

  The first says the check still refuses what it should, in about a second and
  on a machine with no toolchain; the second is the measurement you will quote.
  Without the `source` line the second exits 2 saying it found no `arduino-cli`,
  because it will not guess a compiler — that is why the line is written down
  instead of remembered. The `source` line is enough on its own because `env.sh`
  also exports `ARDUINO_DIRECTORIES_DATA`, `_DOWNLOADS` and `_USER`: an
  `arduino-cli` given no `--config-file` falls back to `~/.arduino15` rather than
  the root it came from, so on a machine that keeps 2.0.17 beside 3.3.12 it would
  silently build the wrong core and report 73 % where the release reads 85 %.
  Both together took **18 s** here, on core 3.3.12 with the build cache warm; on
  2026-11-01 `check_headroom.py` over both decks read 1,165,062 B (145,658 B free)
  and 1,123,145 B (187,575 B free), exit 0.
* **A core upgrade costs whatever the sketch's stack costs, not a fixed overhead.**
  Measured on 2026-10-31, one machine, `--warnings all`, fresh build paths,
  same source, going 2.0.17 → 3.3.12: the Bluetooth-only phone deck grows
  1,162,8xx → 1,165,174 B (about **2.4 kB**), while the WiFi/TLS Spotify deck
  grows 967,493 → 1,122,249 B (**+154,756 B**, 73 % → **85 %** of the default
  app partition; the documented 2.0.17 build reads 967,413 on its own path,
  because the build root moves the last digits). It is not mbedTLS getting
  fatter: named `mbedtls_*` symbols
  only go 83,854 → 91,309 B and `wifi`/`lwip` symbols 83,470 → 87,007 B. It is
  how IDF 5 assembles the network stack — the 3.3.12 image links
  `network_provisioning`, `esp_driver_uart`/`gpio`/`i2c`, `esp_mm` and `espnow`
  that the 2.0.17 image never referenced, its `libnet80211`, `liblwip` and
  `libwpa_supplicant` are each larger, and more of the core's WiFi code is inline
  in headers, so the sketch's own object grows with it. So never scale one
  deck's core delta onto another: recompile and quote `Sketch uses …`, and check
  the partition before assuming a sketch still fits.
* **Why 3.x is the default, and what would reverse it.** Core 2.0.17 ships
  mbedTLS 2.28.7, a branch that reached end of life at the end of 2024, and the
  Spotify deck's whole job is TLS to a third party — an unpatched TLS stack
  holding OAuth tokens is the argument that matters here, not flash. 3.3.12
  ships mbedTLS **3.6.6**, an LTS with fixes promised until at least March 2027;
  Espressif's own 2.0.x support ended in July 2024; and no flash of either deck
  has been confirmed, so today is the cheapest this migration will ever be — both
  already link clean on both cores. The price is **155 kB** in the WiFi deck
  (343 kB → 188 kB of its app slot) and ~3 kB of RAM; the Bluetooth deck pays
  2.4 kB. Reverse only for a measured reason: if the WiFi deck runs out of flash,
  use `huge_app` (3 MB slot, 36 % used today) rather than an unsupported TLS
  stack. **Still unmeasured: heap during the TLS handshake on 3.x** — the first
  thing to watch when the Spotify deck reaches hardware, since 53,572 B of
  globals leaves 274 kB and mbedTLS 3.x is the larger consumer.
* Libraries, and only these unless the widget genuinely needs more: `Adafruit GFX
  Library`, `Adafruit ILI9341`, `XPT2046_Touchscreen`.
* **Never install ArduinoBLE.** Its `BLEDevice.h` shadows the core's header and
  the build dies in a wall of errors. BLE comes from the core (Bluedroid) —
  `BLEDevice`, `BLEServer`, `BLEClient`.
* Use the widget's [`toolchain.sh`](phone_deck_cyd24R/toolchain.sh) rather than
  assembling a toolchain per chat; it is idempotent and keeps everything under one
  `ARDUINO_HOME`.
* Report what actually ran. "Compiles" means a compile exited 0 in this repo.
* **Flashing from the phone is ArduinoDroid's job, not the app's.** On
  2026-10-31 the same phone, OTG adapter, cable and board flashed successfully
  from ArduinoDroid, which is the measurement that matters for the sketch: copy
  it into ArduinoDroid and upload from there. What failed the same day was the
  companion app's *own* uploader — its ROM handshake got no sync at 115200,
  57600, 230400, 460800 or 74880 baud in either DTR/RTS polarity while 552 B left
  the phone per round. Keep the two apart: a deck flashes from the phone today,
  and the app's USB flasher is the thing that cannot write a board until its
  packets arrive. A computer over the same cable is the fallback, and it is also
  the test that separates the app's transmit path from the bridge. That record
  does not name the image that flashed — no log of the session survives in either
  tree — so it proves the phone, the adapter and the board are up to a flash;
  it does not prove that either deck has run. Do not re-litigate either without a
  new measurement.

## Palette

Widgets that draw a UI keep their palette **in the sketch**, as a table:

```cpp
static constexpr Palette THEMES[] = {
  { 0x10A1 /* #14150f */, 0x2103 /* #20211a */, /* ... twelve entries */ },
};
static constexpr uint8_t THEME_INDEX = 1;   // which one is live
```

* Twelve fields, fixed order and fixed jobs: ground, panel, lifted panel,
  hairline, ink, dim ink, then accent, accent in shadow, action (the one button),
  marker, count, and "good". The field names are historical — `C_PINK` may be grey
  — so reason about the **job**, not the name.
* Status rules must keep the last three distinguishable in every theme, by
  luminance rather than hue: charging strongest against the ground, the warning
  state next, the healthy state quietest.
* **Ship monochrome.** Two themes: 0 PAPER (black on white) and 1 INK (white on
  black), six accent roles as a grey ramp, so a new widget needs no colour
  decisions to be readable. A widget that genuinely needs colour adds a theme to
  its own table and says why in its README.
* Keep the `0x5655 /* #rrggbb */` shape: the comment is how the palette is read
  and reviewed, and a reformat that drops it leaves a wall of numbers.
* The twelve `C_` names are plain variables loaded from the chosen theme, so a
  picker can reassign them at runtime without touching a draw call.

## NVS

* One Preferences namespace per concern: `settings` for user-visible settings,
  `touchcal` for the calibration fit, `identity` for the BLE owner.
* Version any stored structure and check the version on read — the fit carries
  `CAL_MAP_VERSION`; bump it when the shape changes.
* Validate on load (clamp ranges, bounds-check indices) so a corrupt byte cannot
  leave the widget with no escape.
* Diagnostic sketches never write to NVS.

## BLE identity

* Each board is its own widget: advertise `<Name> <TAG>` with the tag from the
  efuse MAC, and let the first phone to connect claim it.
* Keep every name the widget has ever advertised in the app's `nameMatch`, or
  boards flashed before a rename stop being found.
* Service and characteristic UUIDs are per deck, never reused across decks:
  every board tag of one deck speaks the same service, so one phone drives
  whichever board is flashed, and `nameMatch` keeps every name a tag has ever
  advertised.
* Nothing here pairs in the phone's Bluetooth settings: these are GATT
  peripherals, not keyboards. A design that makes Android treat the board as an
  input device gets the link dropped.

## Deck identity

* **A deck is a board, not an address.** The identity is the low 24 bits of the
  efuse MAC as six hex digits — `A1B2C3` — the same tag the Bluetooth deck puts in
  the name it advertises. A network deck puts it in its id instead:
  `spotify_deck_cyd24R-A1B2C3`. Two boards of one widget are two decks; a re-flash
  keeps the id, so the address is only ever a cache of where that id was last
  seen.
* **A network deck answers `GET /deck.json`** with one line of JSON: `id`,
  `widget`, `tag`, `name`, `role`, `board`, `ip`, `setup`, `signed`. Nothing
  secret — no token, no password, no client id — so the app can ask any address
  who lives there.
* **It is served in setup and in the player.** Setup already runs the page server
  on port 80; the player runs a listener that answers this one path and 404s
  everything else, because the sign-in pages belong to setup. Its read gives up
  after a second, so a stray connection cannot hold the poll loop.
* **The sign-in URL is the deck's.** `GET /auth?app=1&json=1` answers the
  authorize URL as text — `409` and `refused: <why>` when it cannot build one — so
  an app can open it without scraping a page. The PKCE verifier never leaves the
  board, which is exactly why the app asks for the URL instead of minting its own.
* **The app keys its records by id and rewrites the address.** The shelf's card
  for a widget with a `lan` block pairs by typed address, reads `/deck.json` to
  learn which board answered, and stores `{id, widget, tag, address, state, age}`.
  An id seen at a new address moves its record; a *different* id seen at a known
  address replaces the stale one, because two decks cannot honestly share an
  address at the same moment.
* A descriptor declares its network face in a `lan` block (`role`, `identity`,
  `signIn`, `page`), so the shelf draws that section from the widget instead of
  from a list in Java.
* **A Bluetooth deck publishes the same document**, read-only, in characteristic
  `6f2d0005-…` of its Phone Link service: `id`, `widget`, `tag`, `name`, `role`,
  `board` and `link`, and only a network deck adds `ip`, `setup` and `signed`. The
  app's `DeckIdentity` parses either, so one reader holds both decks, and the
  phone deck is keyed by `<widget>-<TAG>` too: `DeckStore` keeps the MAC inside
  the record as the address, never as the key. A board that has not published a
  document (an older flash, a read that failed) is filed under its MAC and
  upgraded the first time it answers, so the list migrates itself.
* **An identity document is longer than a default ATT MTU.** It is ~150 bytes
  against the 20 usable at MTU 23, so the app asks for 185 before discovering
  services and asks again after `onMtuChanged`; a truncated read names no widget
  and falls back to the address rather than inventing a half id.

## Working in this repo

* One widget per change. A change touching two widget folders is two changes.
* The Android app's Java belongs to the app's own chat and worktree. This tree
  gets sketch and asset copies only; check `git status` before assuming the files
  you see are yours.
* Nothing is pushed or deployed without being asked. The repo stays private.
* Numbers in docs are one board's measurements, not a contract. Say which.
* Never document a step as working because it should. The failed handshake is
  recorded with the baud rates and byte counts tried — including which handshake
  failed: the app's, not ArduinoDroid's — and that precision is what stops the
  next attempt from retreating to a cable it does not need.

## App UI

The app is a drawer (`DrawerHome`) with three rows: `PAIRED` (the row that
opens the link and the bridge), `BOARDS` (the one that opens the shelf) and
`SERIAL`. The first two were labelled `DECKS` and `PROJECTS` until 2026-11-01;
the names behind them did not move — `DecksTab`, `ProjectsTab`, `SECTION_DECKS`,
`SECTION_PROJECTS` and the `"decks"`/`"projects"` prefs keys still describe what
the screens are — so a grep for the old words finds code, not copy.
`SETTINGS` is deliberately not a fourth: it is configuration rather
than navigation, so it hangs off the gear at the outer end of the top bar, past
the link pill. The bottom tab bar is gone on purpose, because a drawer that *is*
the front page needs no second navigation.

* **BOARDS expands to the board alone.** One row per distinct board, labelled
  exactly as the shelf's crumb labels it — `2.4" CYD (resistive)` — and tapping
  that row opens the shelf. The projects are not repeated underneath it: their
  rows live on the shelf beside their bodies, logs and actions, and a copy in
  the drawer could only ever carry a name and a status. A second board is a
  second row, not a second screen.
* **The shelf is alphabetical and folded.** `String.compareToIgnoreCase` on the
  project name, so a new project lands where a reader would look for it. One
  accordion per project: collapsed shows kind, status and name; body, log and
  actions open on tap. Keep the fold state in `ProjectsTab`, not in the store.
  The screen states where it is — `BOARDS › <board>` — from
  `WidgetCatalog.boardLabel`, the same call the drawer's row is drawn from, so
  the crumb and the drawer cannot drift apart.
* **Libraries are one disclaimer at the top of the shelf**, read from the widget
  descriptors and never retyped in Java. Each library is a button that copies the
  exact name ArduinoDroid's library list searches for, and the ArduinoBLE warning
  stays under it.
* **PAIRED lists boards, not addresses.** One row per deck, titled with its name
  and detailed `tag · linked <age>`; the linked card above it says
  `tag · MAC · packet <age>`. The tag is the half of the id that cannot move, and
  the MAC appears once, as the address the live link runs over — so a board that
  re-pairs to a new address keeps its row.
* **A network deck is paired by address and tracked by tag.** A `lan` widget's
  card carries a `DECK` block: one row per paired board (`tag · address · state ·
  age`), with `SIGN IN` and `FORGET`, then `PAIR A DECK`, which asks for the
  address the deck printed. `SIGN IN` fetches the deck's own authorize URL and
  opens it, so the custom scheme still returns into this app; a deck that is
  signed in and out of setup says so instead of asking a page it does not serve.
* **Every UI change ships a render, embedded in the chat.** Draw the screens it
  touches with `android-companion/render_ui.py`, which reads its colours and
  metrics from `Ui.java`, and keep the PNGs in `artifacts/renders/`. Then *show*
  them: every changed screen goes into the reply as an inline image
  (`![home](artifacts/renders/home.png)`), not as a file link, so the render is one
  scroll away instead of a hunt through the tree. Link — or skip — only the
  screens the change did not touch. The PNGs are the page's own images —
  `tokens.json`'s `screens` maps each frame to one — so a change that redraws a
  screen regenerates that render and commits it with the code and the page. A
  render is metric-accurate, not a device screenshot — say which.
* **Music is mirrored, not fetched.** Nothing on this board holds a Spotify token
  and nothing asks the user to make a developer app: the companion app reads the
  phone's media session (already permitted by the notification access it needs for
  notifications) and writes one line — `state|position|duration|title|artist|album`
  — down the link at 1 Hz. The deck draws what it was told and cannot control
  playback, so its now-playing page has no transport buttons. This is also why the
  two decks differ: the WiFi deck signs itself in and can play on its own, and a
  Bluetooth deck shows the phone's own player instead. Cost of the mirror: one
  characteristic, ~120 bytes of RAM, and an optional dependency on a phone in the
  room.

## Adding a widget

1. `mkdir my_deck_cyd24R`, put `my_deck_cyd24R.ino` in it — one file, no
   headers, and the board tag on both.
2. Copy a sibling's shape: `README.md` (and `toolchain.sh` if it needs its own).
3. Compile it, then write down what you verified and what you did not, in the
   README's first paragraph of notes.
4. Add the row to the [root README](README.md) table.
5. If the app bundles it, add `widget.json` plus the synced asset copy and run
   `check_widget_assets.py`.
