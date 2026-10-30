# Handoff — CYD board check

State as of the last session. Read [README.md](README.md) for what each line on
the screen means; this file is the context that is not in the code.

## What exists

One self-contained sketch: [`cyd_board_check.ino`](cyd_board_check.ino), no extra
headers, ~300 lines. It reports, on screen and over serial:

| Reading | Source |
| --- | --- |
| chip model, revision, cores | `ESP.getChipModel()`, `ESP.getChipRevision()`, `ESP.getChipCores()` |
| flash, sketch, free app space | `ESP.getFlashChipSize()`, `ESP.getSketchSize()`, `ESP.getFreeSketchSpace()` |
| PSRAM | `psramFound()`, `ESP.getPsramSize()` |
| MAC | `esp_read_mac(…, ESP_MAC_WIFI_STA)` |
| reset cause | `esp_reset_reason()` |
| free heap | `ESP.getFreeHeap()` |
| RGB channels | GPIO 4 / 16 / 17, one at a time, 4 s each |
| touch | `XPT2046_Touchscreen::getPoint()` — raw x/y, pressure, mapped dot |

## Decisions

- **Touch is raw, with no calibration fit.** The deck already owns calibration
  (four corners, three checks, version byte in NVS). A second fit here would give
  two answers to the same question and would overwrite the deck's. This sketch
  prints the raw span instead, which is the input a calibration needs.
- **Nothing is written to NVS.** Flashing this over a board that already has a
  saved touch fit must not invalidate it, and a self-check that can break a good
  board is not a self-check.
- **The LED test is timed, not button-driven.** The CYD has one BOOT button;
  cycling every 4 seconds means the board can be tested with no hands on it.
- **No BLE, no WiFi.** Every radio path is a way for the test to fail for a reason
  that is not the board. Serial and SPI are the whole dependency set.
- **It reuses [`../cyd_phone_deck/toolchain.sh`](../cyd_phone_deck/toolchain.sh)**
  instead of carrying a second copy: same core, same three libraries. A copied
  script drifts; the deck's is already the recovery command after a wiped machine.
- **One file, no headers**, for the same reason as the deck: ArduinoDroid pastes a
  single `.ino`, and a split sketch fails there with a missing-header error.

## Verified

- Compiles clean with `arduino-cli` **1.5.2-rc.1** against ESP32 core **3.3.12**:
  **320,372 B (24 %)** flash and **24,420 B (7 %)** RAM, and `--warnings all`
  reports nothing. Re-measured 2026-10-30.
- Core **2.0.17** was not installed in this workspace when the sketch was written,
  so the 2.x number is unknown rather than equal. The deck's 2.x-vs-3.x figures are
  in [`../cyd_phone_deck/HANDOFF.md`](../cyd_phone_deck/HANDOFF.md).
- **The port and reset path**, measured on the real board in the companion-app chat
  on 2026-10-30 rather than by this sketch: the bridge is a **CH340** (`1a86:7523`,
  one interface), it opens over USB-OTG, and driving DTR/RTS resets the ESP32 — bytes
  arrive after the release. An upload from a computer therefore needs no BOOT press.

## Not verified

- **Nothing has run on hardware.** No board has been flashed with this sketch, so
  every reading above is a reading of the code, not of a panel.
- **The touch mapping constants are guesses** (`TOUCH_MIN_X` … `TOUCH_MAX_Y`,
  ±300–3800), as is the `z < 200` pressure floor. The RAW line exists precisely so
  the first real board can replace them.
- **The LED pins are inherited from the deck**, which is itself unconfirmed on
  hardware — the deck's handoff lists "whether the status LED is actually dark" as
  still open. If this sketch shows a channel lit under `off`, that is evidence for
  the deck too.
- **`psram` reads `none found` on a board that has PSRAM** unless the build enables
  it: the default `esp32:esp32:esp32` FQBN leaves PSRAM off in its menu. The line
  only means something under Tools → PSRAM → Enabled.
- **Flashing from a phone over OTG.** The ROM bootloader answered no sync at
  115200, 57600, 230400, 460800 or 74880 baud in either polarity while 552 B left
  the phone per round: the send path is proven, the handshake is the blocker. That
  matters most for this sketch, because it is what a bare board gets flashed with —
  a phone alone cannot do it yet; see [HOWTO.md](HOWTO.md).

## Obvious next work

1. Flash one board, then replace the example serial block in README.md with the
   real output and record the numbers here.
2. Tap all four corners and write down the raw span; set the constants or document
   the axis swap direction the panel shows.
3. If a second board ever disagrees with the first, the diff between their two
   reports is the whole diagnosis — that is what this sketch is for.
4. The sketch is **not** bundled in the companion app; `syncDeckSketch` ships only
   the deck. To get it onto a phone, import it through **FILES → IMPORT A FILE**.
