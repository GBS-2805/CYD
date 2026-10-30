# Handoff — CYD phone deck firmware

State as of the last session. Read [README.md](README.md) for the hardware,
protocol and controls; this file is the context that is not in the code.

## What exists

One self-contained sketch: [`cyd_phone_deck.ino`](cyd_phone_deck.ino), no extra
headers. It must stay a single file — an earlier split into a `deck_types.h`
failed on ArduinoDroid with `fatal error: deck_types.h: No such file or
directory`, because only the `.ino` is compiled when you paste it in.

The sketch is a BLE peripheral with one service, **Phone Link**
(`6f2d0001-…`), and three write characteristics:

| Characteristic | Payload |
| --- | --- |
| Time `6f2d0002` | 7 bytes: year−2000, month, day, hour, minute, second, weekday |
| Battery `6f2d0003` | percent 0–100, optional charging flag |
| Notification `6f2d0004` | UTF-8 `app\|title\|body` |

The time write **must be 7 bytes** (`value.length() < 7` returns early) but the
seventh, weekday byte is ignored — `LinkTimeCallbacks` passes only `b[0]`–`b[5]`
to `epochFromParts`, and the deck derives the weekday from the date itself.

The deck is **not** pairable in Android's Bluetooth settings by design. It is a
GATT peripheral; nothing needs to be paired for Phone Link to work.

## Decisions that were expensive to reach

- **Android has no ANCS.** Apple's notification service is iOS-only. The iOS
  code path is still in the sketch behind `ENABLE_ANCS`, currently `false`.
- **The HID experiment was reverted.** Advertising a HID keyboard made iOS list
  the deck in Settings, but on Android it made the phone try to use the deck as
  a keyboard, fail, leave a stale bond, and then refuse to reconnect. Do not
  re-add HID for Android.
- **Advertising must not start in the same breath as the payload setup.**
  Bluedroid applies `setAdvertisementData` / `setScanResponseData`
  asynchronously; `startBluetooth()` waits 200 ms first. Without that delay the
  deck advertised empty and never appeared in any scan.
- **The RGB LED is active-low**: three discrete LEDs between GPIO 4/16/17 and
  3.3 V. Driving them HIGH keeps it dark. There is no WS2812 here —
  `neopixelWrite` was wrong and is gone.
- **Calibration** fits four corners with least squares, then demands three check
  taps inside 55 px before saving to NVS. The `CAL_MAP_VERSION` byte invalidates
  old fits after a mapping change.
- **Bonding is available, not required.** `startBluetooth()` sets
  `ESP_LE_AUTH_REQ_SC_BOND` with `ESP_IO_CAP_NONE` and accepts pairing
  (`onSecurityRequest()` returns true), while the three characteristics keep the
  core's default `READ|WRITE` permissions. An unpaired companion can therefore
  write, and a phone that asks to bond still completes — which is why nothing has
  to be paired, and also why the link carries no encryption until a phone bonds.

## Verified

- Compiles clean on **both** cores with `arduino-cli` 1.5.2-rc.1, re-measured on
  2026-10-30 after the comment fixes:
  **2.0.17** 1,162,797–1,162,813 B (**88 %** flash) and 41,436 B (12 % RAM);
  **3.3.12** 1,163,930 B (**88 %** flash) and 45,004 B (13 % RAM).
  The 2.x figure moves by ~16 bytes with the build root, so read it as 1,162.8 kB
  rather than a fixed count. Core 3.x costs ~1 kB of flash and 3.6 kB of RAM for
  this sketch; the macro pad needs 2.x, so 2.0.17 is the number that matters there.
- Both numbers come from [`toolchain.sh`](toolchain.sh), which installs a core,
  the three libraries and pyserial into one directory and compiles it — nothing in
  this file is a remembered figure any more. One core version per install root,
  because the compiler takes the newest installed platform.
- The advertising packet is 28/31 bytes: flags `0x06` (3), the 128-bit Phone
  Link UUID (18) and the shortened name "Phone" (7). In the core, `setFlags`
  emits `[2, 0x01, flag]` and `setCompleteServices` emits `[17, 0x07, uuid…]`,
  so 29 was never right.

## Not verified

- **No run on a phone since the HID removal and the new advertising packet.**
  Nothing has confirmed the deck links cleanly to the companion app.
- **Touch mapping on the actual panel.** The default mapping assumes rotated raw
  axes; the calibration screen is the only way to know.
- **Whether the status LED is actually dark now.** The last fix drives 4/16/17
  HIGH; it was not confirmed on hardware after flashing. If it still glows, the
  LED is either hardwired or on different pins.
- The iOS ANCS path, end to end.

## Obvious next work

1. Confirm the deck links to the companion app in
   [`../android-companion`](../android-companion) and that all three cards fill.
2. If the link drops and will not return, test the stale-bond theory first:
   forget "Phone Deck" in the phone's Bluetooth settings.
3. Six notification slots exist and three are shown; older ones are dropped. If
   that feels tight, `drawBoardBody()` and the notice buffers are the place to
   look.
