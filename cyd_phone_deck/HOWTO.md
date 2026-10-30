# HOWTO — CYD phone deck firmware

Task-shaped instructions. Hardware, protocol and controls are in
[README.md](README.md); current state and open issues are in
[HANDOFF.md](HANDOFF.md).

## Flash it with arduino-cli

One command installs everything the sketch needs — arduino-cli, the pinned ESP32
core, the three libraries, and the pyserial the core's esptool imports — then
compiles it:

```bash
cd cyd_phone_deck
./toolchain.sh                          # into ~/.arduino-cyd, then compile
ARDUINO_HOME=/opt/cyd ./toolchain.sh    # or somewhere else
./toolchain.sh --setup-only             # install without compiling
```

It is idempotent, so it is also the recovery command after a wiped machine or a
new workspace. Everything goes under that one directory and no global Arduino
config is touched. For later shells, `source ~/.arduino-cyd/env.sh` puts
`arduino-cli` and the pyserial path on `PATH`.

By hand it is the same four steps:

```bash
arduino-cli core install esp32:esp32@2.0.17
arduino-cli lib install "Adafruit GFX Library" "Adafruit ILI9341" "XPT2046_Touchscreen"
arduino-cli compile --fqbn esp32:esp32:esp32 cyd_phone_deck
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 cyd_phone_deck
```

By hand, core 2.0.17's build shells out to its bundled `esptool.py`, which imports
`pyserial`. If the `python3` on `PATH` does not have it, the compile dies **after**
the link step with `ModuleNotFoundError: No module named 'serial'`. The script
installs it; otherwise `python3 -m pip install --target ~/.arduino-pylibs pyserial`
and `export PYTHONPATH=~/.arduino-pylibs`.

The CYD has no USB-serial chip on some revisions; if no port appears, use the
board's USB port with a data cable (not a charge-only one) and hold BOOT while
plugging in.

## Flash it from the phone (ArduinoDroid)

1. Copy the sketch from the app: **PROJECTS** → the Phone Deck card → **COPY
   DROID**, then paste into a new ArduinoDroid sketch. **SAVE** writes it to
   Downloads if you would rather open the file.
2. Keep it as one tab named `cyd_phone_deck.ino`. Do not add extra `.h` files.
3. Board: **ESP32 Dev Module**. Install only Adafruit GFX, Adafruit ILI9341 and
   XPT2046_Touchscreen. **ArduinoBLE must not be installed** — it shadows the
   core's BLE headers and every build fails.
4. Compile and upload over USB-OTG.

## Calibrate the touch panel

Calibration opens by itself on first boot after a flash, or whenever you hold the
title for 1.5 s, or if you touch the glass while the board resets.

1. Tap the four corner dots in the order shown (TL, TR, BR, BL).
2. Tap the three check dots. Each must land within 55 px of its target.
3. If a check misses, the recovery screen offers **SWAP AXES**, **FLIP X**,
   **FLIP Y**, **REDO CORNERS**, or **SAVE AS IS** — apply at most one correction
   and re-check.
4. Hold anywhere for 2 s to abandon without saving.

The fit is stored in NVS with a version byte; bumping the version in the sketch
invalidates stored fits and forces a fresh calibration after an update.

## Get data onto the deck

The deck cannot read a phone by itself — it is a GATT peripheral. On Android,
install the companion app from [`../android-companion`](../android-companion) and
link it. On a laptop, use the `bleak` snippet in [README.md](README.md) or nRF
Connect: connect to **Phone Deck** and write the three characteristics.

Writes are plain write-with-response; **no pairing is needed** and the deck never
appears in Android's Bluetooth settings list.

## Check the deck is alive

- Serial monitor at **115200** right after boot: `[ble] advertising as "Phone Deck"`.
- The deck screen should show the clock card, battery card and notification rows.
- With the companion linked, the header pill and the deck's own `LINKED` badge
  agree within a second.

## Troubleshooting

**Nothing in a Bluetooth scan**
- Serial log missing the advertising line means setup failed before
  `startBluetooth()`; check the boot messages above it.
- The deck stops advertising while a phone holds the link. Disconnect that phone.

**Phone will not reconnect after one success**
- Forget the stale **Phone Deck** entry in Bluetooth settings (old HID builds left
  a keyboard bond).
- Power-cycle the deck so it advertises again.

**Screen is white / blank**
- Check the backlight pin (GPIO 27) and that brightness is not at minimum:
  the SET screen has − and +.
- Wrong rotation or a dead panel shows as a black screen; the calibration screen
  would still draw if the sketch is running.

**Status LED is lit**
- It is active-low on GPIO 4/16/17; `disableOnboardLed()` drives all three HIGH.
  If it still glows, the board revision wires the LED differently — adjust the
  `PIN_LED_*` constants.
