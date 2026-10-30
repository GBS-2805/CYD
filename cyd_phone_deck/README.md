# ESP32 Phone_Widget

A phone dashboard for the **ESP32-2432S024R** (2.4" resistive-touch CYD): clock,
battery and notifications, drawn in the **GMK Dracula** keycap palette — #282a36
base, #44475a cards, #f8f8f2 alphas, and the set's pastel accent caps:
#9decf5 cyan, #ffa8d5 rose, #c6b6ff lavender, #f4efa8 butter, #aaead4 mint.

The folder and the sketch file keep the name `cyd_phone_deck`: ArduinoDroid
compiles one `.ino` whose name must match its folder, and the companion app's
Gradle task copies that exact path into the APK. Everything a person reads — the
BLE name, the header wordmark, these docs — says **ESP32 Phone_Widget**.

The deck is a Bluetooth LE peripheral exposing a small GATT service called
**Phone Link**. Something on the phone writes the time, battery level and
notification text to it. The deck draws what it receives.

## Read this first: Android has no ANCS

Apple's Notification Center Service (ANCS) is **iOS only**. Android exposes no
equivalent, and a BLE peripheral cannot read a phone's battery, clock or
notifications on its own. So on Android the deck gets nothing until a companion
writes to Phone Link.

That is why the deck does not appear in Android's Bluetooth settings as a
pairable device: it is not a keyboard or headset, it is a GATT peripheral that an
app connects to. Nothing needs to be paired for Phone Link to work.

`ENABLE_ANCS` in the sketch is `false`. The iOS path is still there but disabled,
and it is ANCS only: the HID advertising that once sat beside it made Android try
to use the deck as a keyboard, fail, and drop the link.

| Data | Android (default) | iPhone (`ENABLE_ANCS = true`) |
| --- | --- | --- |
| Notifications | Companion writes Phone Link | ANCS |
| Time | Companion writes Phone Link | Notification dates / Current Time Service |
| Battery | Companion writes Phone Link | Not exposed by iOS |

## Hardware

ESP32-2432S024R. Pins are fixed in the sketch:

| Signal | GPIO |
| --- | --- |
| TFT CS / DC / BL | 15 / 2 / 27 |
| Touch CS | 33 |
| SPI SCK / MISO / MOSI | 14 / 12 / 13 |
| RGB status LED (disabled) | 4 / 16 / 17 |

The RGB LED is three discrete LEDs between the GPIOs and 3.3 V, so each channel
lights when its pin is LOW. `disableOnboardLed()` drives red, green and blue
HIGH at boot, which keeps the LED dark; a floating channel glows cyan.

The board carries three buttons: **BOOT** and **RESET** on the back, and a
**battery switch** next to the battery connector. The backlight has its own
switch at the side edge, not a button. The sketch reads none of them — BOOT and
RESET matter only for flashing.

Also on the back and unused by the firmware: a **TF** card slot, a speaker
header, the battery JST, a 4P-1.25 connector, extended IO, and a
temperature/humidity header. The module is an **ESP-WROOM-32**, so no PSRAM.

## Renders

There is exactly one render: `renders/board.png`, the board with the deck on it
and numbered markers for the facts above. Regenerate it with
`python3 renders/make_renders.py` (Pillow; it downloads Adafruit's `glcdfont.c`
to `/tmp` at render time, so nothing third-party is vendored here). It draws
from the sketch's own rects, colours and 5×7 typeface, so it cannot drift from
the firmware without the script being edited too.

The image itself is `renders/board.png`, committed here: read that file when you
need to see the board. `site/` holds an optional static bundle (`index.html`,
`renders/board.png` and one asset-serving Worker, deployed from
`site/manifest.json`), and it is **unpublished**: nothing from this repo is
reachable without your account. Deploy it deliberately if a link is ever wanted
again, and remember that `make_renders.py` writes the fresh PNG into the bundle
too, so a deployed copy cannot lag the render.

After any change to what the deck draws, regenerate it and look at
`renders/board.png` rather than describing the screen.

## Build

Flashing, calibration and link testing step by step: [HOWTO.md](HOWTO.md).

* ESP32 Arduino core **2.x or 3.x** (compiled against 2.0.17 and 3.3.12). The
  sketch uses the core's own Bluedroid library (`BLEDevice`, `BLEServer`).
* Libraries: **Adafruit GFX Library**, **Adafruit ILI9341**,
  **XPT2046_Touchscreen**.
* Do **not** install **ArduinoBLE** — its `BLEDevice.h` shadows the core header.
* One file only: `cyd_phone_deck.ino`, no extra headers.

```bash
arduino-cli core install esp32:esp32
arduino-cli lib install "Adafruit GFX Library" "Adafruit ILI9341" "XPT2046_Touchscreen"
arduino-cli compile --fqbn esp32:esp32:esp32 cyd_phone_deck
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 cyd_phone_deck
```

On a machine with none of that installed, [`toolchain.sh`](toolchain.sh) does all
of it — toolchain, core, libraries, the pyserial the core's esptool needs — and
compiles the sketch, in one idempotent command. See [HOWTO.md](HOWTO.md).

## widget.json: how the app finds this widget

`widget.json` in this folder is the widget's side of the contract with the
companion app. The app does not hard-code this sketch; it reads this file, so a
widget is a folder that describes itself.

| Field | Used for |
| --- | --- |
| `id` | the project id (`widget:phone_deck`) and the asset folder name |
| `name`, `tagline`, `body`, `notes` | the project card in the app |
| `sketch` | the file copied into the APK; must match the folder plus `.ino` |
| `libraries`, `never` | the LIBRARIES card, including what to refuse to install |
| `ble.service`, `ble.nameMatch` | what the app looks for while scanning |

The app's Gradle task walks every `*/widget.json` beside `android-companion/`
and copies the descriptor and the sketch it names into
`app/src/main/assets/widgets/<id>/` at build time. Editing the sketch here stays
enough for a rebuild to carry it; there is no second copy in the app's source.

To get a new widget onto the phone without publishing anything: put this folder
in your own private transfer location (Drive, mail, USB), download
`cyd_phone_deck.ino` on the phone, and use the app's **IMPORT** button. The
sketch lands on the flash shelf and opens in ArduinoDroid, exactly like the
bundled copy.

## First run and testing the link

1. Flash and let the deck boot. The first boot opens touch calibration: tap the
   four corner dots, then the three check dots.
2. **Forget any old "ESP32 Phone_Widget" entry** in the phone's Bluetooth
   settings if you paired the earlier HID build; that stale bond points at a
   keyboard service the deck no longer has.
3. Install the companion app from [`android-companion`](../android-companion),
   grant notification access, and let it link. The deck should jump to
   `LINKED`, show the clock, battery and notification rows.
4. If it stays empty, install **nRF Connect for Mobile**, scan for
   **ESP32 Phone_Widget**, connect, and write the Phone Link characteristics
   (UUIDs below). If that
   works, the deck and its display are fine and the problem is on the phone
   side.

### Companion options

* **[Off-site](../android-companion)** — the companion app in this repo (formerly
  Phone Deck Bridge):
  a `NotificationListenerService` plus a BLE GATT client that writes time,
  battery and notifications automatically. This is the intended setup; build
  and install it from the `android-companion` folder.
* **Automate (LlamaLab)** — has a notification trigger plus Bluetooth LE write
  blocks, so a flow can push app/title/text, battery and time without building
  anything. Useful if you would rather not install an app.
* Anything else that can write GATT characteristics works too; the protocol is
  below.

## Phone Link service

Service `6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10`, three write characteristics:

| Characteristic | UUID | Payload |
| --- | --- | --- |
| Time | `6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10` | 7 bytes: year−2000, month, day, hour, minute, second, weekday (ignored — see below) |
| Battery | `6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10` | 1–2 bytes: level 0–100, optional charging flag |
| Notification | `6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10` | UTF-8 `app\|title\|body`, any part may be empty |

Writes are normal GATT write-with-response; no subscription is needed and no
pairing is required. Time is local wall-clock time, so the deck needs no timezone
handling. The weekday byte is required only to reach the 7-byte minimum: the
sketch reads the first six bytes and derives the weekday from the date. Notification
writes are appended; the oldest of six slots is dropped.

Example with `bleak` from a laptop:

```python
import asyncio, datetime as dt
from bleak import BleakClient

DECK = "<deck MAC>"

async def main():
    async with BleakClient(DECK) as client:
        now = dt.datetime.now()
        await client.write_gatt_char(
            "6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10",
            bytes([now.year - 2000, now.month, now.day, now.hour, now.minute,
                   now.second, 0]))  # 7th byte required, value ignored
        await client.write_gatt_char("6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10",
                                     bytes([87, 0]))
        await client.write_gatt_char("6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10",
                                     b"Discord|Ada|Deploy finished")

asyncio.run(main())
```

## Controls

| Action | Result |
| --- | --- |
| Tap a notification | Full title and message |
| Hold a notification (0.7 s) | Remove it from the deck |
| Tap `DISMISS` in the detail view | Remove it from the deck |
| Tap the gear (top right) | Brightness, clear notifications, calibrate touch |
| Hold the title for 1.5 s | Touch calibration |
| Touch the screen at boot | Touch calibration |

## Advertising

* Packet (28/31 bytes): flags `0x06` (3), the Phone Link 128-bit service UUID
  (18), and the shortened name "Phone" (7).
* Scan response: the complete name `ESP32 Phone_Widget <TAG>`, where `TAG` is the
  low 24 bits of the chip's MAC in uppercase hex, e.g. `ESP32 Phone_Widget
  A1B2C3`. No two boards advertise the same name.
* `startBluetooth()` waits 200 ms after configuring the payloads before starting
  advertising, because Bluedroid applies them asynchronously.

## One board, one phone

Every board is its own widget and answers to one phone, so a shelf of decks is a
list of distinct devices rather than a row of identical entries.

* `DEVICE_TAG` is the low 24 bits of the ESP32's efuse MAC, read once at boot
  with `ESP.getEfuseMac()` and printed as six uppercase hex digits.
* The BLE name is `ESP32 Phone_Widget <TAG>`; the header subtitle shows
  `esp32 // #<TAG>` so the screen and the scan list match.
* The first phone to connect is written to NVS (`identity` / `owner`) and
  becomes the only phone the deck listens to. Another phone can still connect,
  but its writes are dropped in the Phone Link callbacks and the link is not
  counted, so the deck keeps showing `OFFLINE`.
* Hold the gear for 1.2 s on the board screen to forget the bound phone. The
  serial log prints `[id] binding cleared`, and the next phone to connect claims
  the board.
* The binding is in NVS, so re-flashing does not clear it — only the gear hold
  (or an erase-flash) does. Because the tag comes from the MAC, re-flashing also
  keeps the same name; two different boards differ in both.

## Limits worth knowing

* Notifications live in RAM only; a reboot clears the list.
* Six notifications are kept, three are shown at once.
* No pairing means no encryption: anyone in radio range could write the link.
* The clock drifts by roughly a second a day and re-syncs on the next time write.
* The iOS ANCS path is untested against a live iPhone.
