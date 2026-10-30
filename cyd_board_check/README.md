# CYD Board Check

A new **ESP32-2432S024R** arrives as a set of unknowns: the panel may be a
different controller, the touch axes may be rotated or mirrored, the RGB LED may
be wired the other way round, and the flash may not be the size the listing
claimed. This sketch answers all of that on one screen and in one serial report
in about ten seconds, with nothing else in the chain — no phone, no Bluetooth, no
companion app. If something goes wrong here it is the board, because there is
nothing else it could be.

It is what you flash **before** the deck firmware in
[`../cyd_phone_deck`](../cyd_phone_deck), and what you re-flash when a board that
used to work stops: if this screen is sane, the board is sane and the fault is
above it.

One file, [`cyd_board_check.ino`](cyd_board_check.ino), no extra headers.

## What the screen shows

| Line | What it proves | When it looks wrong |
| --- | --- | --- |
| header + backlight | the panel draws at all; backlight on GPIO 27 | black screen with a lit backlight: the panel or the SPI wiring, not the backlight |
| chip | ESP32 variant, silicon revision, core count | not `ESP32-D0WD…` on a genuine CYD |
| flash | the size the chip reports | 2 MB on a board sold as 4 MB — the deck will still fit, but not much else will |
| psram | whether the module has PSRAM | `none found` is normal on most S024R boards — but it also means "not enabled" unless the build turns PSRAM on (Tools → PSRAM → Enabled) |
| mac | the factory WiFi MAC, unique per board | two boards with the same MAC means a cloned or mis-set eFuse |
| reset | why this boot happened | `panic` or `watchdog` on a board that should have just powered on |
| heap | free DRAM after the display and touch drivers | under ~200 kB means something else is holding memory |
| led | one RGB channel at a time, 4 s each | one channel missing, or lit when the screen says `off` |
| touch | raw x/y and pressure of the current tap | no reaction at all, or `z` near zero on every touch |
| raw | smallest and largest raw value seen since power-on | a span that never approaches both ends means the panel is only half alive, or the axes are swapped |

## Hardware

Same board and the same fixed pins as the deck:

| Signal | GPIO |
| --- | --- |
| TFT CS / DC / BL | 15 / 2 / 27 |
| Touch CS | 33 |
| SPI SCK / MISO / MOSI | 14 / 12 / 13 |
| RGB status LED | 4 / 16 / 17 |

The RGB LED is three discrete LEDs between the GPIOs and 3.3 V, so a channel
lights when its pin is **LOW**. The sketch drives all three HIGH first, then
lights them one at a time, which also tells you whether the board is wired that
way — a channel that stays lit while the screen says `off` is wired differently.

## Touch is reported raw, on purpose

Tap the glass: the sketch prints the raw x/y, the pressure, and a mapped position,
and draws a dot where it thinks you touched. The raw numbers are the measurement;
the constants `TOUCH_MIN_X`, `TOUCH_MAX_X`, `TOUCH_MIN_Y`, `TOUCH_MAX_Y` in the
sketch are **starting points, not calibration**. Tap all four corners and read the
`raw` line: the range it shows is the box this panel actually reports. If the axes
are swapped, the dot moves diagonally instead of following your finger - that is
the answer you were looking for, and the deck's own calibration is where the fix
belongs. Nothing here is saved to flash.

## Serial report

At **115200** baud, right after boot. One 4 MB board's output, so treat the shape
as the contract and the numbers as that board's:

```
== CYD board check ==
chip       ESP32-D0WD-V3 rev 3, 2 core(s) at 240 MHz
flash      4096 kB, sketch 320 kB, 967 kB free
psram      none found
mac        A0:B7:65:12:34:56
reset      power-on
heap       297 kB free
touch      raw values, uncalibrated - tap all four corners
```

Every tap then prints one line, `tap 3   raw 1841,2203  pressure 812`, so the raw
values are recoverable from a log even if the screen is the thing that is broken.

## Build

Same core and the same three libraries as the deck — **Adafruit GFX Library**,
**Adafruit ILI9341**, **XPT2046_Touchscreen** — and no BLE library at all. The
deck's [`../cyd_phone_deck/toolchain.sh`](../cyd_phone_deck/toolchain.sh) installs
exactly that set, so there is no second copy of it here. Step by step:
[HOWTO.md](HOWTO.md).

## Limits worth knowing

* Nothing is calibrated and nothing is written to flash: this sketch never touches
  NVS, so it cannot corrupt a board you already trust.
* The LED test is timed rather than button-driven — the CYD has one BOOT button and
  watching four seconds per channel beats holding it.
* No SD card, no speaker, no WiFi or BLE radio test. It proves the parts the deck
  needs and stops there.
* The touch mapping constants are guesses until a real panel has reported its own
  corners; treat the dot as a hint about axis order, not as an accurate pointer.
