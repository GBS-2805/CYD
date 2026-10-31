# Conventions — ESP32-2432S024R widgets

House rules for every project in this repo. They exist so a new widget — or a new
chat — does not have to rediscover them. If a rule stops being true, change it
here in the same change as the code that broke it.

**Scope: the ESP32-2432S024R only** — the 2.4" CYD with a resistive panel, an
ESP-WROOM-32 and a CH340 USB bridge. A board with a different panel, touch
controller or bridge gets its own section here first, because much of what follows
is about *this* hardware: fixed pins, LED polarity, a PSRAM question that turns on
the module and a build option, and a bootloader that does not answer over OTG.

## One folder per widget

* The folder and the sketch share a name: `my_widget/my_widget.ino`. ArduinoDroid
  compiles one `.ino` whose name must match its folder, so renaming either breaks
  the phone workflow and the companion app's build.
* One sketch, one tab, **no extra headers**. Everything the widget needs lives in
  that file, above the first function that uses it.
* Each folder carries `README.md`, `HOWTO.md` and `HANDOFF.md` — see
  [Docs](#docs-record-what-is-true) below.
* Add the widget to the table in the [root README](README.md) in the same change.
* Widgets bundled into the APK also carry
  [`widget.json`](cyd_phone_deck/widget.json) (`schema: 1`, with `id`,
  `name`, `sketch`, `board`, `tagline`, `body`, `notes`, `libraries`, `never`,
  and a `ble` block when it speaks BLE). The app copies the sketch at build time,
  so `android-companion/app/src/main/assets/widgets/<id>/` must hold a copy with
  an identical md5 to the source sketch. Sync it whenever the sketch changes,
  and check it with [`check_widget_assets.py`](check_widget_assets.py) before
  committing: it fails when a descriptor names a sketch that is not there, when a
  bundled copy has drifted, or when `assets/widgets/` holds a widget no
  descriptor claims.

## The board

| Signal | GPIO |
| --- | --- |
| TFT CS / DC / backlight | 15 / 2 / 27 |
| Touch CS | 33 |
| SPI SCK / MISO / MOSI | 14 / 12 / 13 |
| RGB status LED | 4 / 16 / 17 |

* The RGB LED is three discrete LEDs between the GPIOs and 3.3 V, so a channel
  lights when its pin is **LOW**. Drive all three HIGH at boot to keep the LED
  dark; a floating channel glows cyan.
* The backlight is on its own switch at the edge, not a button, and can be PWM'd
  on GPIO 27.
* Buttons available to a sketch: none. BOOT and RESET are for flashing only.
* **This unit has no PSRAM, and the module marking is the evidence.** It is an
  ESP32-WROOM-32 stamped `ESP-32 WIFI+BT N4XX`: N4 is 4 MB of flash and no PSRAM
  (a WROVER board would say `WROVER` and carry 4 or 8 MB). So `none found` from
  [`cyd_board_check`](cyd_board_check/README.md) is the true reading here, not a
  build artifact. Even on a board that does have it, `none found` proves nothing on
  its own, because the `esp32:esp32:esp32` board menu defaults to `PSRAM=disabled`;
  enabling it adds `-DBOARD_HAS_PSRAM`, which is what makes `psramFound()` true.
  Read the module marking before allocating against PSRAM; never infer it from a
  product listing.
* **Budget for what is actually there: 4 MB of flash, no PSRAM.** The default
  partition scheme gives the app 1,310,720 bytes, and the deck sketch already takes
  1,162,526 of them - the compiler reports 88%. DRAM is 327,680 bytes, with about
  300 kB of heap free at boot once the display and touch drivers are up. A 320x240
  RGB565 framebuffer is 153,600 bytes, so there is room for one, not for a design
  that keeps two, and nothing can be pushed off to PSRAM. Treat "it fits" as a
  measurement from `Sketch uses ...`, never an assumption, and check the headroom
  before adding a second radio stack, a font table or a big static buffer. These
  are one build's numbers, from an ESP32 Arduino core 3.3.12 build of the deck.
* Nothing on the board is self-describing — flash a diagnostic before trusting a
  new widget on unknown hardware.

* **A control that ignores taps is usually a stale mapping, not a bad control.**
  The deck's `+` brightness chip took no taps while `-` worked; a recalibration
  fixed it, because the stored mapping had drifted and the tap was landing tens
  of pixels beside the chip. Calibrate before believing a hit-test bug - the
  mapping lives in NVS and survives a reflash that keeps NVS. Keep targets big and
  off the extremes anyway: at least ~40 px on the short side, clear of the outer
  8 px, with a few pixels of slop in the hit test (the back chip takes 6 px). The
  panel is resistive and single-touch, so a second tap while one is latched is a
  bounce, not input.

## Build

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 my_widget
```

* Board package: `esp32:esp32`. The deck sketch is written for **core 3.x** and
  compiles clean on 3.3.12; `cyd_phone_deck/toolchain.sh` defaults to 2.0.17, so
  pass `ARDUINO_CORE=esp32:esp32@3.3.12` when you need 3.x. One core version per
  install root — the compiler picks the newest installed platform.
* Libraries, and only these, unless the widget genuinely needs more:
  `Adafruit GFX Library`, `Adafruit ILI9341`, `XPT2046_Touchscreen`.
* **Never install ArduinoBLE.** Its `BLEDevice.h` shadows the core's header and
  the build dies in a wall of errors. BLE comes from the core (Bluedroid) —
  `BLEDevice`, `BLEServer`, `BLEClient`. Sketches that use it ship `#error`
  guards for both mistakes; keep that habit.
* Use the widget's [`toolchain.sh`](cyd_phone_deck/toolchain.sh) (idempotent,
  everything under one `ARDUINO_HOME`) rather than assembling a toolchain per chat.
* Report what actually ran. "Compiles" means a compile exited 0 in this repo;
  it never means "should compile".
* Flashing needs a computer with a cable. Upload over USB-OTG from the phone does
  not work on this board — the panel is editable and compilable on the phone, but
  the ROM bootloader never answered the sync at any baud tried. Do not re-litigate
  that without a new measurement.

## Palette

Widgets that draw a UI keep their palette **in the sketch**, as a table:

```cpp
static constexpr Palette THEMES[] = {
  { 0x10A1 /* #14150f */, 0x2103 /* #20211a */, /* ... twelve entries */ },
};
static constexpr uint8_t THEME_INDEX = 1;   // which one is live
```

* Twelve fields, fixed order and fixed jobs: ground, panel, lifted panel,
  hairline, ink, dim ink, then accent (link / status / charging), accent in
  shadow, action (the one button, low battery), marker, count, and "good"
  (healthy battery). The field names are historical — `C_PINK` may be indigo in
  one theme — so reason about the **job**, not the name.
* `batteryColor()`-style rules must keep those last three distinguishable in every
  theme. Check contrast per theme rather than by eye on one. In mono that means
  luminance order, not hue: charging strongest against the ground, the warning
  state next, the healthy state quietest.
* **Ship monochrome for now.** Two themes: 0 PAPER (black on white) and 1 INK
  (white on black). The six accent roles are a grey ramp, not hues, so a new
  widget needs no colour decisions to be readable. A widget that genuinely needs
  colour adds a theme to its own table and says why in its `HANDOFF.md`.
* The `0x5655 /* #rrggbb */` shape is load-bearing: the renderer parses it, so a
  reformat that drops the comment breaks rendering, not the firmware. The
  renderer checks that each hex packs back to its 565 value.
* The twelve `C_` names are plain variables loaded from the chosen theme, so a
  picker can reassign them at runtime without touching a draw call.

## Renders

* Generated, never hand-edited: `python3 renders/make_renders.py`, drawn from the
  sketch's own rects, colours and Adafruit 5×7 typeface.
* `renders/board.png` is the image to look at after a UI change. Read it rather
  than describing the screen from memory.
* `renders/make_renders.py --check` validates the palette and reports whether the
  committed PNGs still match the sketch. Run it before committing.
* After a UI change, the renders go to the Drive `RENDERS` folder, replacing the
  old files, with **capitalised** names (`BOARD`, `BOARD.ANNOTATED`,
  `SCREEN-LOWBAT`, …). The mechanics - temp host, trash, upload from URL, then
  check the folder - are in
  [`cyd_phone_deck/HOWTO.md`](cyd_phone_deck/HOWTO.md#publish-the-renders-to-drive).
* [`cyd_phone_deck/renders/make_renders.py`](cyd_phone_deck/renders/make_renders.py)
  is the only renderer in the tree today. Copy it for a new UI widget rather than
  importing across folders, and treat the second copy as the signal to extract the
  shared parts; do not build a framework for widgets that may never have screens.

## NVS

* One Preferences namespace per concern, not one per widget: `settings` for
  user-visible settings (`bri`, `theme`), `touchcal` for the calibration fit,
  `identity` for the BLE owner.
* Version any stored structure and check the version on read — the calibration fit
  carries `CAL_MAP_VERSION`; bump it when the shape changes, so a stale fit is
  discarded instead of silently misreading.
* Validate on load (clamp ranges, bounds-check indices) so a corrupt byte cannot
  put the widget in a state with no escape.
* Diagnostic sketches never write to NVS. A board check must not be able to
  corrupt a board you already trust.

## BLE identity

* Each board is its own widget: advertise `<Name> <TAG>` with the tag read from
  the efuse MAC, and let the first phone to connect claim it.
* Keep every name the widget has ever advertised in the app's `nameMatch`, or
  boards flashed before a rename stop being found.
* Service and characteristic UUIDs are per widget and never reused across
  widgets.
* Nothing here pairs in the phone's Bluetooth settings: these are GATT
  peripherals, not keyboards. A design that makes Android try to treat the board
  as an input device will get the link dropped.

## Docs: record what is true

* `README.md` — what it is, how it works, the hardware table, and limits.
* `HOWTO.md` — task-shaped steps: flash it, edit it on the phone, calibrate it,
  what to do when it misbehaves.
* `HANDOFF.md` — current state, decisions and their reasons, what is verified and
  what is not, and the traps already hit.
* Numbers in docs are one board's measurements, not a contract. Say which.
* Never document a step as working because it should: the deck's OTG upload is
  recorded as broken with the baud rates and byte counts that were tried, and
  that record is what stops the next attempt from repeating it.
* [`cyd_board_check`](cyd_board_check/README.md) is the reference diagnostic for
  this board — flash it before blaming a new sketch.

## Working in this repo

* One widget per change. A change that touches two widget folders is two changes,
  or a shared-extraction change that says so.
* The Android app's Java belongs to the app's own chat and worktree. This tree
  gets sketch and asset copies only; check `git status` before assuming the files
  you see are yours.
* Shared surfaces — the root README table, `android-companion` assets — are edited in a change that names them as shared.
* Nothing here is deployed or pushed without being asked. The repo stays private.

## Adding a widget

1. `mkdir my_widget` and put `my_widget.ino` in it — one file, no headers.
2. Copy the shape of a sibling: `README.md`, `HOWTO.md`, `HANDOFF.md`, and
   `toolchain.sh` if it needs its own toolchain.
3. If it draws a UI, add a `renders/make_renders.py` (copy the deck's until a
   shared version exists) with the `THEMES[]` table in the sketch.
4. Compile it: `arduino-cli compile --fqbn esp32:esp32:esp32 my_widget`.
5. Render it, look at `renders/board.png`, and run `--check`.
6. Add the row to the [root README](README.md) table.
7. If the app bundles it, add `widget.json` and the synced asset copy, and check
   the md5s match.
8. Write down what you verified and what you did not, in `HANDOFF.md`.
