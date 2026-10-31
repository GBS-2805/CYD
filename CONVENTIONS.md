# Conventions — ESP32-2432S024R widgets

House rules for every project in this repo. They exist so a new widget — or a new
chat — does not have to rediscover them. If a rule stops being true, change it
here in the same change as the code that broke it.

**Scope: the ESP32-2432S024R only** — the 2.4" CYD with a resistive panel, an
ESP-WROOM-32 and a CH340 USB bridge. A board with a different panel, touch
controller or bridge gets its own section here first.

## One folder per widget

* Folder and sketch share a name: `my_widget/my_widget.ino`. ArduinoDroid
  compiles one `.ino` whose name must match its folder, so renaming either breaks
  the phone workflow and the app's build.
* One sketch, one tab, **no extra headers**. A pasted `.ino` cannot see a header
  beside it.
* Each folder has **one** `README.md`: what it is, how to flash it, the traps, and
  what is verified. No `HOWTO.md`, no `HANDOFF.md` — a task that needs steps gets
  them in that README.
* Add the row to the [root README](README.md) table in the same change.
* Widgets bundled into the APK also carry [`widget.json`](cyd_phone_deck/widget.json)
  (`schema: 1`, with `id`, `name`, `sketch`, `board`, `tagline`, `body`, `notes`,
  `libraries`, `never`, and a `ble` block when it speaks BLE). The app copies the
  sketch at build time, so `android-companion/app/src/main/assets/widgets/<id>/`
  must hold a byte-identical copy. Check it with
  [`check_widget_assets.py`](check_widget_assets.py) before committing: it fails
  when a descriptor names a missing sketch, when a bundled copy has drifted, or
  when `assets/widgets/` holds a widget no descriptor claims.

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

* Board package `esp32:esp32`. The deck compiles clean on core **3.3.12** and
  2.0.17; `cyd_phone_deck/toolchain.sh` defaults to 2.0.17, so pass
  `ARDUINO_CORE=esp32:esp32@3.3.12` when you need 3.x. One core per install root —
  the compiler picks the newest installed platform.
* Libraries, and only these unless the widget genuinely needs more: `Adafruit GFX
  Library`, `Adafruit ILI9341`, `XPT2046_Touchscreen`.
* **Never install ArduinoBLE.** Its `BLEDevice.h` shadows the core's header and
  the build dies in a wall of errors. BLE comes from the core (Bluedroid) —
  `BLEDevice`, `BLEServer`, `BLEClient`.
* Use the widget's [`toolchain.sh`](cyd_phone_deck/toolchain.sh) rather than
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
* Service and characteristic UUIDs are per widget, never reused.
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

## Adding a widget

1. `mkdir my_widget`, put `my_widget.ino` in it — one file, no headers.
2. Copy a sibling's shape: `README.md` (and `toolchain.sh` if it needs its own).
3. Compile it, then write down what you verified and what you did not, in the
   README's first paragraph of notes.
4. Add the row to the [root README](README.md) table.
5. If the app bundles it, add `widget.json` plus the synced asset copy and run
   `check_widget_assets.py`.
