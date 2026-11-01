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
  Espressif's own 2.0.x support ended in July 2024; and neither deck has ever
  been flashed, so today is the cheapest this migration will ever be — both
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
* **Flashing needs a computer with a cable.** Upload over USB-OTG from the phone
  does not work on this board: the ROM bootloader answered no sync at 115200,
  57600, 230400, 460800 or 74880 baud in either DTR/RTS polarity while 552 B left
  the phone per round. Do not re-litigate that without a new measurement.

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

## Working in this repo

* One widget per change. A change touching two widget folders is two changes.
* The Android app's Java belongs to the app's own chat and worktree. This tree
  gets sketch and asset copies only; check `git status` before assuming the files
  you see are yours.
* Nothing is pushed or deployed without being asked. The repo stays private.
* Numbers in docs are one board's measurements, not a contract. Say which.
* Never document a step as working because it should. The OTG upload is recorded
  as broken with the baud rates and byte counts tried; that record is what stops
  the next attempt from repeating it.

## App UI

The app is a drawer (`DrawerHome`) with three rows: `DECKS`, `PROJECTS`,
`SERIAL`. `SETTINGS` is deliberately not a fourth: it is configuration rather
than navigation, so it hangs off the gear at the outer end of the top bar, past
the link pill. The bottom tab bar is gone on purpose, because a drawer that *is*
the front page needs no second navigation.

* **PROJECTS is alphabetical and folded.** `String.compareToIgnoreCase` on the
  project name, so a new project lands where a reader would look for it. One
  accordion per project: collapsed shows kind, status and name; body, log and
  actions open on tap. Keep the fold state in `ProjectsTab`, not in the store.
  The section states where it is — `PROJECTS › <board>` — from
  `WidgetCatalog.boardLabel`, the same call the drawer groups by, so the crumb
  and the board headings cannot drift apart.
* **Libraries are one disclaimer at the top of PROJECTS**, read from the widget
  descriptors and never retyped in Java. Each library is a button that copies the
  exact name ArduinoDroid's library list searches for, and the ArduinoBLE warning
  stays under it.
* **Every UI change ships a render, embedded in the chat.** Draw the screens it
  touches with `android-companion/render_ui.py`, which reads its colours and
  metrics from `Ui.java`, and keep the PNGs in `artifacts/renders/`. Then *show*
  them: every changed screen goes into the reply as an inline image
  (`![home](artifacts/renders/home.png)`), not as a file link, so the render is one
  scroll away instead of a hunt through the tree. Link — or skip — only the
  screens the change did not touch. Renders are review artifacts: regenerate them
  on the next change, never commit them. A render is metric-accurate, not a device
  screenshot — say which.

## Adding a widget

1. `mkdir my_deck_cyd24R`, put `my_deck_cyd24R.ino` in it — one file, no
   headers, and the board tag on both.
2. Copy a sibling's shape: `README.md` (and `toolchain.sh` if it needs its own).
3. Compile it, then write down what you verified and what you did not, in the
   README's first paragraph of notes.
4. Add the row to the [root README](README.md) table.
5. If the app bundles it, add `widget.json` plus the synced asset copy and run
   `check_widget_assets.py`.
