# HOWTO — CYD board check

Task-shaped instructions. What each line on the screen means is in
[README.md](README.md); current state and open questions are in
[HANDOFF.md](HANDOFF.md).

## Flash it

The deck's toolchain already installs the core and the same three libraries this
sketch uses, so run it once and then build this folder with the `arduino-cli` it
leaves behind:

```bash
cd cyd_phone_deck
./toolchain.sh --setup-only          # idempotent; skips anything already there
source ~/.arduino-cyd/env.sh         # puts arduino-cli and pyserial on PATH

cd ../cyd_board_check
arduino-cli compile --fqbn esp32:esp32:esp32 cyd_board_check
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 cyd_board_check
```

`-p` is whatever port the board appeared on: `ls /dev/ttyUSB*` on Linux,
`ls /dev/cu.*` on macOS, and on Windows the `COM` number in Device Manager. No
port at all usually means a charge-only cable; hold **BOOT** while plugging in if
the board is already in a bad state.

If the compile dies with `ModuleNotFoundError: No module named 'serial'`, the
`PYTHONPATH` from `env.sh` is missing — core 2.x shells out to its bundled
`esptool.py`, which imports pyserial.

## Run it

1. Unplug the USB cable after the upload and plug it back in. That is the cheapest
   power-on reset, and it makes the `reset` line read `power-on` rather than
   `software`.
2. Watch the screen for ~20 seconds: the header paints, the rows fill in, and the
   LED cycles red → green → blue → off once every 4 seconds.
3. Tap the four corners of the glass, then the middle. A dot should appear near
   each tap and the `touch` row should count up.
4. Open the serial monitor at **115200** and confirm the boot report appeared.

## Read the result

| What you see | What it means | What to do |
| --- | --- | --- |
| everything fills in, dot follows the finger | board is good | flash [`../cyd_phone_deck`](../cyd_phone_deck) |
| dot moves diagonally, raw x and y swap as you move | touch axes are rotated | expected on some revisions; the deck's calibration handles it, note which way it swapped |
| dot runs off one edge before your finger reaches it | the mapping box is too wide for this panel | record the `raw` span and adjust `TOUCH_MIN_*` / `TOUCH_MAX_*` |
| `touch` never counts, serial shows nothing on tap | touch not responding | reseat the display ribbon, then suspect the panel — the deck cannot use this board either |
| one LED channel never lights | that channel's GPIO or LED is dead | cosmetic on the deck, but note it; the deck drives all three HIGH to stay dark |
| an LED channel stays lit with `led off` | LED wired differently on this revision | the deck's `PIN_LED_*` constants are the place to change it |
| `panic` or `watchdog` in `reset` on a plain power-on | previous firmware crashed and reset the board | expected after flashing something broken; flash this sketch and it clears |
| black screen, backlight off | backlight pin or power | check GPIO 27 is driven high; if the LED test also fails, the board is not coming up |
| no serial output at all | the sketch did not start | re-flash holding **BOOT**, and confirm the upload ended with `Hash of data verified` |

## Pass the board on

A board that passes goes straight to the deck firmware — same cable, same
`arduino-cli`, one folder up:

```bash
cd ../cyd_phone_deck
./toolchain.sh
```

Then delete or keep this sketch; it writes nothing to flash, so re-flashing over
it leaves no trace.

## Recovery

- **Stuck in the bootloader** (no boot log, no screen): hold **BOOT**, tap **RST**,
  release **BOOT**, then upload again.
- **Screen white or blank but the serial report is fine**: the panel side is the
  problem, not the sketch. The board still works as a headless ESP32.
- **Port disappears mid-upload**: unplug, replug, and retry. A hub between the
  board and the computer is the usual cause.
