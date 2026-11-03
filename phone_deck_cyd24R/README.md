# Phone Deck

A phone dashboard for the **ESP32-2432S024R** (2.4" resistive-touch CYD): clock,
battery and notifications, set like a woodblock print — flat panels, heavy rules,
one ink, nothing shaded. It ships **two monochrome themes** of twelve slots each:

* **0 PAPER** — black on white: ground #ffffff, panels #f4f4f4, hairlines
  #c8c8c8, ink #000000, dim ink #6f6f6f, the one action #2b2b2b.
* **1 INK** — white on black: ground #000000, panels #0e0e0e, hairlines #333333,
  ink #ffffff, dim ink #8c8c8c, the one action #d4d4d4.

`THEME_INDEX = 1` selects INK. The six accent slots are a grey ramp rather than
hues, so link state, the one action and the battery warning separate by
luminance instead of colour, which is what keeps both themes readable on a panel
this size. The full table is `THEMES[]` at the top of the sketch.

The folder and the sketch share one name, `phone_deck_cyd24R`, like every widget
here: ArduinoDroid compiles one `.ino` whose name must match its folder, and
the companion app resolves both from `widget.json`, so they move together.
Boards flashed back when this was `cyd_phone_deck/` still advertise
`ESP32 Phone_Widget <TAG>`, and the app matches that fragment too.

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
temperature/humidity header. The module is an **ESP-WROOM-32** stamped `N4XX` -
4 MB of flash, no PSRAM - so nothing here can use PSRAM even though the ESP32 Dev
Module board menu offers the option (there is nothing to map).

## Front page

The deck boots into a front page: a status line across the top (clock left,
battery and gear right), a `PHONE DECK` wordmark with the link state beside it, an
outlined pill with the unread count, a solid pill naming the palette this build is
baked with, and four numbered tabs. The gear opens settings from here and from
the messages board; holding it for a second forgets the bound phone.

* **01 MESSAGES** opens the notification board. Its `<` chip, and every settings
  path, come back here.
* **02 SETTINGS** is brightness (two buttons, one half of the card each), clear
  and calibration.
* **03 THEME** is a read-only contact sheet: both palettes on their own
  grounds with the live one outlined. The palette is compile-time, so the page
  points at `THEME_INDEX` instead of pretending to have a switch.
* **04 CALIBRATE** starts the three-tap calibration; saving or cancelling returns
  to the front page.

The front page carries no graphic: the four tabs measure 300x30 at y 84, 118,
152 and 186 under a hairline at 78, and a closing rule runs at 224. The clock and
battery began in a strip along the bottom at 222; they now sit at y 6, above the
wordmark, so a glance does not have to travel past the tabs to find them. An
earlier version drew a lens-shaped heat band under the tabs; it was removed
because a tinted strip there competed with the wordmark and read as decoration.

## Picture

`renders/board.png` is the one image in this folder: the board with the deck on
it, marked up with the facts above. It is kept by hand — there is no renderer to
run and nothing to check — so read it as an illustration and read the sketch for
what the screen actually draws.

## Build

* ESP32 Arduino core **2.x or 3.x** (compiled against 2.0.17 and 3.3.12). The
  sketch uses the core's own Bluedroid library (`BLEDevice`, `BLEServer`).
* Libraries: **Adafruit GFX Library**, **Adafruit ILI9341**,
  **XPT2046_Touchscreen**.
* Do **not** install **ArduinoBLE** — its `BLEDevice.h` shadows the core header.
* One file only: `phone_deck_cyd24R.ino`, no extra headers.

```bash
arduino-cli core install esp32:esp32
arduino-cli lib install "Adafruit GFX Library" "Adafruit ILI9341" "XPT2046_Touchscreen"
arduino-cli compile --fqbn esp32:esp32:esp32 phone_deck_cyd24R
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 phone_deck_cyd24R
```

On a machine with none of that installed, [`toolchain.sh`](toolchain.sh) does all
of it — arduino-cli, the core, the three libraries, the pyserial the core's
esptool imports — and compiles the sketch, in one idempotent command:

```bash
cd phone_deck_cyd24R
./toolchain.sh                        # into ~/.arduino-cyd, then compile
ARDUINO_HOME=/opt/cyd ./toolchain.sh  # or somewhere else
./toolchain.sh --setup-only           # install without compiling
source ~/.arduino-cyd/env.sh          # puts arduino-cli and pyserial on PATH
```

It is also the recovery command after a wiped machine, which is why the numbers
below are a compile in this repo rather than a remembered figure. Core 2.0.17's
build shells out to its bundled `esptool.py`, which imports `pyserial`: if the
`python3` on `PATH` lacks it the compile dies **after** the link step with
`ModuleNotFoundError: No module named 'serial'`. The script installs it, or
`python3 -m pip install --target ~/.arduino-pylibs pyserial` and
`export PYTHONPATH=~/.arduino-pylibs`.

The USB bridge is a **CH340** (`1a86:7523`, one interface), so a data cable is
enough for a port to appear and `lsusb` separates a dead cable from a dead board.
Its DTR/RTS lines reset the ESP32, so `arduino-cli upload` needs no BOOT press.
Holding **BOOT** while plugging in is untested on this board: the fallback for an
upload that will not sync, not a step.

## Edit it on the phone (ArduinoDroid)

1. **BOARDS** → the **Phone Deck** card → **COPY DROID**, then paste into a new
   ArduinoDroid sketch. **SAVE** writes it to Downloads instead.
2. Keep it one tab named `phone_deck_cyd24R.ino`, and do not add `.h` files: an
   earlier split into `deck_types.h` failed on the phone with
   `fatal error: deck_types.h: No such file or directory`, because only the
   `.ino` is compiled.
3. Board **ESP32 Dev Module**; install only Adafruit GFX, Adafruit ILI9341 and
   XPT2046_Touchscreen. **ArduinoBLE must not be installed.**
4. **Upload from ArduinoDroid**, with an OTG adapter and a USB **data** cable.
   That is measured, not assumed: on 2026-10-31 the same phone, adapter, cable and
   board flashed successfully from ArduinoDroid, so the CH340, the cable and the
   board's reset circuit are all known good. What failed that day was the
   companion app's *own* uploader, whose ROM handshake drew no sync at **115200,
   57600, 230400, 460800 or 74880** baud in either DTR/RTS polarity while 552 B
   left the phone per attempt. That is the app's bug, not the board's, and it is
   no reason to walk the deck to a computer. A computer over the same cable is the
   fallback when the phone's handshake will not take.

## widget.json: how the app finds this widget

`widget.json` in this folder is the widget's side of the contract with the
companion app. The app does not hard-code this sketch; it reads this file, so a
widget is a folder that describes itself.

| Field | Used for |
| --- | --- |
| `id` | the project id (`widget:phone_deck_cyd24R`) and the asset folder name |
| `name`, `tagline`, `body`, `notes` | the project card in the app |
| `sketch` | the file copied into the APK; must match the folder plus `.ino` |
| `libraries`, `never` | the LIBRARIES card, including what to refuse to install |
| `ble.service`, `ble.nameMatch` | what the app looks for while scanning |

The app's Gradle task walks every `*/widget.json` beside `android-companion/`
and copies the descriptor and the sketch it names into
`app/src/main/assets/widgets/<id>/` at build time. Editing the sketch here stays
enough for a rebuild to carry it; there is no second copy in the app's source.
Run [`../check_widget_assets.py`](../check_widget_assets.py) before committing to
confirm the two copies still match byte for byte.

To get a new widget onto the phone without publishing anything: put this folder
in your own private transfer location (Drive, mail, USB), download
`phone_deck_cyd24R.ino` on the phone, and use the app's **IMPORT** button. The
sketch lands on the flash shelf and opens in ArduinoDroid, exactly like the
bundled copy.

## First run and testing the link

1. Flash and let the deck boot. The first boot opens touch calibration: tap the
   three dots — top-left, top-right, bottom-left — and the fit saves itself.
   Each tap is the median of four panel reads and is dismissed if the samples
   disagree by more than 300 raw units, so one noisy conversion cannot misplace a
   corner; three taps fix the affine map exactly. Hold anywhere for 2 s to
   abandon without saving, and bump `CAL_MAP_VERSION` in the sketch to invalidate
   stored fits after a mapping change.
2. **Forget any old "ESP32 Phone_Widget" entry** in the phone's Bluetooth
   settings if you paired the earlier HID build; that stale bond points at a
   keyboard service the deck no longer has.
3. Install the companion app from [`android-companion`](../android-companion),
   grant notification access, and let it link. The deck should jump to
   `LINKED`, show the clock, battery and notification rows.
4. If it stays empty, install **nRF Connect for Mobile**, scan for
   **Phone Deck**, connect, and write the Phone Link characteristics
   (UUIDs below). If that
   works, the deck and its display are fine and the problem is on the phone
   side.

A serial monitor at **115200** right after boot prints `[id] Phone Deck A1B2C3
owner unbound` and `[ble] advertising as "Phone Deck A1B2C3"`; the tag is that
board's MAC, so it differs per board.

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

Service `6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10`: four write characteristics and one
read.

The Spotify deck speaks the same service with the same six UUIDs and adds a
seventh for its transport chips, so a scan run from the app now sees **both**
decks when both are powered on; the shelf tells them apart by name and tag, not
by service.

| Characteristic | UUID | Payload |
| --- | --- | --- |
| Time | `6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10` | 7 bytes: year−2000, month, day, hour, minute, second, weekday (ignored — see below) |
| Battery | `6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10` | 1–2 bytes: level 0–100, optional charging flag |
| Notification | `6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10` | UTF-8 `app\|title\|body`, any part may be empty |
| Identity | `6f2d0005-9a3c-4b57-9d21-6a1e0c4f8b10` | **read** |
| Now playing | `6f2d0006-9a3c-4b57-9d21-6a1e0c4f8b10` | UTF-8 `state\|position\|duration\|title\|artist\|album`, whole seconds, state `play`/`pause`/`stop` |

The now-playing line is a **mirror, not a login**. This deck never talks to
Spotify: the companion app reads the phone's own media session — the same
notification access it already asks for — and writes one line a second, so there
is no client id, no browser page and no token anywhere in this path. `stop` clears
the card; a `play` line starts the deck's progress bar moving on its own between
writes. The track shows on the status card's last line (`>> 1:12 3:45  Title`) and
the whole page opens with a tap on it. That page has no transport buttons on
purpose: the deck has no way to ask Spotify anything, so a pause here would be a
promise it cannot keep. The text fields are capped at 47, 31 and 31 UTF-8 bytes,
and the app replaces any `|` in a title with `/` before sending, because this
characteristic splits on it.

The identity characteristic is one line of JSON and it is the one thing a reader
asks for: the same keys a network deck answers at `GET /deck.json`, so the app
keys this board by widget and tag — `phone_deck_cyd24R-A1B2C3` — instead of by MAC.
A re-flashed board keeps its row in the app, and a second board of the same widget
is a second row.

```json
{"id":"phone_deck_cyd24R-A1B2C3","widget":"phone_deck_cyd24R",
 "tag":"A1B2C3","name":"Phone Deck","role":"phone",
 "board":"2432S024R","link":"ble"}
```

The three a board with no network has none of — `ip`, `setup`, `signed` — are
simply absent. The document is about 150 bytes, longer than the 20 a default ATT
MTU carries, so a reader asks for a larger MTU first (the app asks for 185) and a
read that comes back cut short names no widget, which leaves the app on the
address fallback rather than inventing an id.

Writes are normal GATT write-with-response; no subscription is needed and no
pairing is required. Time is local wall-clock time, so the deck needs no timezone
handling. The seventh, weekday byte is required only to reach the 7-byte minimum
(`value.length() < 7` returns early): `LinkTimeCallbacks` passes `b[0]`–`b[5]` to
`epochFromParts` and the sketch derives the weekday from the date. Notification
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
* Scan response: the complete name `Phone Deck <TAG>`, where `TAG` is the low 24
  bits of the chip's MAC in uppercase hex, e.g. `Phone Deck A1B2C3`. No two
  boards advertise the same name. A board flashed before the rename still says
  `ESP32 Phone_Widget <TAG>`, which the app matches as well.
* `startBluetooth()` waits 200 ms after configuring the payloads before starting
  advertising, because Bluedroid applies them asynchronously. Without that delay
  the deck advertised empty and never appeared in any scan.

## One board, one phone

Every board is its own widget and answers to one phone, so a shelf of decks is a
list of distinct devices rather than a row of identical entries.

* `DEVICE_TAG` is the low 24 bits of the ESP32's efuse MAC, read once at boot
  with `ESP.getEfuseMac()` and printed as six uppercase hex digits.
* The BLE name is `Phone Deck <TAG>`; the header wordmark is `Phone Deck` and
  the subtitle shows `esp32 // #<TAG>`, so the screen and the scan list match.
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

## Traps already hit

* **One file, no extra headers.** Everything the sketch needs lives above the
  first function that uses it; a header beside the `.ino` does not exist for the
  phone.
* **An address is not an identity.** An Android central uses a resolvable private
  address that rotates, so the binding is a record, not a gate: gating
  `serverConnected` on the stored address left a phone attached while the deck
  read OFFLINE. `rememberPeer()` stores and logs who holds the link, and
  `peerAllowed` follows the connection.
* **Do not re-add HID for Android.** Advertising a HID keyboard made iOS list the
  deck, but on Android the phone tried to use it as a keyboard, failed, left a
  stale bond and then refused to reconnect.
* **Touch is sampled, not read once.** `readRawTouch()` takes four conversions,
  drops any that convert zero pressure, medians each axis and rejects the tap if
  the spread exceeds 300 raw units. The pressure floor is deliberately not a
  gate: the scale differs per panel and a blind threshold could disable touch.
* **"Brightness only goes down" was calibration drift, not the button.** Taps
  mapped tens of pixels off, landed beside the chip and were dropped. The chips
  were widened to half the card anyway, and `applyBrightness()` logs the percent,
  the duty and `ledcRead()` because a silent `ledcAttach()` failure looks exactly
  like a setting that does nothing.
* **Bonding is available, not required.** `startBluetooth()` sets
  `ESP_LE_AUTH_REQ_SC_BOND` with `ESP_IO_CAP_NONE`, while the characteristics keep
  the core's default `READ|WRITE` permissions, so an unpaired companion can write
  and a phone that asks to bond still completes. Nothing has to be paired, and
  the link carries no encryption until a phone does.
* **The RGB LED is active-low**: three discrete LEDs between GPIO 4/16/17 and
  3.3 V. There is no WS2812 on this board — `neopixelWrite` was wrong and is gone.

## Verified, and not

A compile in this repo, on 2026-10-30 and before the mirror, with `arduino-cli`
1.5.2-rc.1:
**core 2.0.17** 1,162,797–1,162,813 B, **88 %** of 1,310,720, and
41,436 B, **12 %** of 327,680. **core 3.3.12** 1,163,930 B, 88 % of 1,310,720,
and 45,004 B, 13 % of 327,680. The 2.x figure moves by ~16 bytes with the build
root, so read it as 1,135.6 kB rather than a fixed count.
Core 3.x costs 1–2.3 kB of flash and 3.7 kB of RAM for this sketch, depending on
the build path.

### Headroom

Measured here on 2026-11-01 with [`../check_headroom.py`](../check_headroom.py),
core **3.3.12**, default partition scheme:

| | used | free | ceiling |
| --- | --- | --- | --- |
| flash | 1,168,314 | **142,406** | 1,310,720 (89 % used) |
| DRAM | 45,244 globals | **282,436** | 327,680 |

The root behind those figures is `~/.arduino-cyd/env.sh`: `check_docs.py
--refresh` re-read 1,168,314 B and 45,244 B from it on 2026-11-02. The second
3.3.12 root here, `/tmp/arduino-cyd-3`, prints 1,168,170 B for the same sketch —
144 B apart with the globals identical — so quote the root with the number (see
[the build note](../CONVENTIONS.md#build)).

The check refuses a deck that overflows, that drops under **64 KiB** of free flash
or that leaves under **160 KiB** of DRAM — the last floor is just over the
153,600 B a full 320x240 RGB565 framebuffer needs. So this deck has one
framebuffer's worth of slack and 126 kB besides, and a single addition larger
than about **139 KiB** overflows the slot: a 1,400,000-byte const table measured
1,668,748 B (127 % of 1,310,720), which is the overflow the refusal names rather than a bare
`compile failed`. Re-measure with `python3 ../check_headroom.py phone_deck_cyd24R`
from the repo root, after sourcing a toolchain.

### At a glance

**Verified here** — none of this needs hardware:

* Core **2.0.17** links the sketch at 1,162,797–1,162,813 bytes (88 % of 1,310,720) with
  41,436 bytes of RAM (12 %), measured on 2026-10-30; the spread is the build
  root, so read it as 1,135.6 kB. It is the A/B build now, not the default —
  `toolchain.sh` pins 3.3.12 — and it has **not** been re-measured since the
  mirror, so its current size is that pre-mirror figure plus an unknown amount,
  not a number to quote.
* Core **3.3.12** links it at 1,168,314 bytes (89 % of 1,310,720) with 45,244 bytes of RAM
  (13 %), `EXIT=0` under `--warnings all`, re-measured on 2026-11-01 after the
  mirror. An earlier 3.3.12 build on a shorter path reported 1,163,930 / 45,004,
  so 3.x is path-sensitive too — 1,137–1,141 kB, not a fixed count.
* The bundled copy matches byte for byte after `:app:syncWidgets`;
  `check_widget_assets.py` prints `2 widgets bundled, every copy matches`
  (this sketch md5 `62c7e6c3…`, the Spotify one `3b17f04a…`).
* Core 3.x costs this sketch ~2.3 kB of flash and ~3.7 kB of RAM over 2.0.17 —
  nothing like the Spotify deck's 155 kB gap, which is that sketch's network
  stack rather than the core (see the build note in
  [CONVENTIONS](../CONVENTIONS.md#build)).

**Unverified — these need a board, and a phone:**

* **No run of this sketch on a phone has been confirmed since the HID removal
  and the new advertising packet**, so the 28-byte packet and the scan-response
  name have not been seen on the air in this build. A board did flash from
  ArduinoDroid on 2026-10-31, but that record does not name the image.
* The binding has never been exercised end to end: phone A binds, phone B is
  refused, the 1.2 s gear hold clears it, and a re-flash does not.
* The touch mapping has not met a real panel, so every tap target here is
  arithmetic until then.
* The status LED has not been confirmed dark after flashing.

The first flash of a new board is the test of those, not a formality.

## When it misbehaves

| Symptom | Cause | What to do |
| --- | --- | --- |
| nothing in a Bluetooth scan | setup failed before `startBluetooth()`, or a phone holds the link | check the boot log for the advertising line; disconnect that phone |
| a second phone changes nothing | that is the binding: `[ble] refused: this deck is bound to another phone` | hold the gear 1.2 s (`[id] binding cleared`), then connect the phone you want |
| will not reconnect after one success | a stale HID bond from an old build | forget `ESP32 Phone_Widget` in Bluetooth settings, power-cycle the deck |
| white or blank screen | backlight at minimum, or the pin is not driven | SET has − and +; the backlight is GPIO 27 |
| status LED still glows | a channel wired differently on this revision | adjust `PIN_LED_*` |

## Limits worth knowing

* Notifications live in RAM only; a reboot clears the list.
* Six notifications are kept, three are shown at once.
* No pairing means no encryption: anyone in radio range could write the link.
* The clock drifts by roughly a second a day and re-syncs on the next time write.
* The iOS ANCS path is untested against a live iPhone.

## Measured

`check_headroom.py` built this sketch on 2026-11-02 with core 3.3.12 and read
`Sketch uses 1,168,314 bytes` and `45,244 bytes` of globals; this folder's sketch
md5 is `62c7e6c3…`.
