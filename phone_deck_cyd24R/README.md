# Phone Deck

A phone dashboard for the **ESP32-2432S024R** (2.4" resistive-touch CYD): clock,
battery and notifications, set like a woodblock print — flat panels, heavy rules,
one ink, nothing shaded. It ships **eight rows** of twelve slots each: the original
monochrome pair, then the owner's site in its three palettes twice —

* **0 PAPER** — black ink on an off-white page.
* **1 INK** — the same page inverted: off-white ink on black.
* **2 GREEN light** / **3 GREEN dark** — the lime band's cream page, or its night page.
* **4 MOSS light** / **5 MOSS dark** — the cream page, or the green page with the lime.
* **6 NAVY light** / **7 NAVY dark** — powder blue-grey, or slate on near-black blue.

The index is `2 + palette * 2 + (light ? 0 : 1)`, computed by this sketch and the
companion app alike, so a swatch tapped in the app is the row the glass draws. INK is
what a board shows before any phone writes, and the app sets the rest down the link
(`6f2d0008-…`, one byte) without a re-flash; the board keeps the choice in NVS. The
last six slots are the one highlight at different strengths rather than hues, so link
state, the one action and the battery warning separate by luminance. The full table is
`THEMES[]` at the top of the sketch, one `0x5655 /* #rrggbb */` entry per slot, and
`THEME_NAMES[]` beside it is the name the footer prints.

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
battery and gear right), a `PHONE DECK` wordmark with the link state beside it,
three numbered tabs, and a footer line carrying the unread count on the left and
the live palette on the right. The gear opens settings from here and from
the messages board; holding it for a second forgets the bound phone. The palette
name is a label, not a control: the switch lives in the companion app (see
*Phone Link service*), so a deck changes palette without a re-flash.

* **01 MESSAGES** opens the notification board. Its `<` chip, and every settings
  path, come back here.
* **02 SPOTIFY** opens the now-playing page: the title, artist and progress the
  phone mirrors, and no transport buttons, because the deck cannot ask Spotify
  anything on its own. It is the Spotify deck's now page as far as this board can
  honestly carry it, and it says `nothing playing` when the phone is not.
* **03 SETTINGS** is brightness (two buttons, one half of the card each) and
  calibration, and it says whether a touch fit is stored: `TOUCH FIT SAVED` after
  a run, `TOUCH FIT ASSUMED` before one. That is read from the stored record each
  time the page is drawn, not kept in a flag. The notifications card it used to
  carry is gone: a count with no
  action behind it held a row a control could use, and clearing the inbox is
  waiting on a home of its own. The unread count still sits in the front page's
  footer.

Calibration is reached from settings, or by holding the title for 1.5 s, so it
no longer needs a row of its own here.

The THEME page is gone. It was a contact sheet that could only say "set
`THEME_INDEX`, then reflash", and the app now writes the palette down the link
instead, so the board needs no page of its own for it and the fourth row was
dropped rather than repurposed.

The front page carries no graphic: the three tabs measure 296x34 at y 72, 110
and 148 under a rule at 64, and a closing rule runs at 192 with the footer line
beneath it — the air where the THEME row sat is the only trace of it. The clock and
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
* Do **not** install **ArduinoBLE** — its `BLEDevice.h` shadows the core header. Nor
  **NimBLE-Arduino**: this sketch is written against the core's Bluedroid stack, so
  another BLE library is a rewrite of the link, not an install.
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

**What proves a change to this deck.** The root's [`check.sh`](../check.sh) is
the gate, and the deck-scoped forms are the ones a sketch edit needs:

```bash
./check.sh --decks-only phone_deck_cyd24R           # headroom, the old core, two builds
./check.sh --decks-only --cached phone_deck_cyd24R  # the same, reusing what this tree proved
./check.sh --full phone_deck_cyd24R                 # those three, plus the frames and the APK
```

Measured here on 2026-11-05 with core 3.3.12 from `/tmp/arduino-cyd-3`:
**168 s**, and **3.7 s** with `--cached`; the three steps inside it took 44 s of
headroom, 26 s on 2.0.17 and 95 s for the two builds. `--decks-only` skips the
frames and the APK build, so it proves this deck still fits and still builds the
same bytes twice, and nothing about the app — the release gate is `./check.sh`
with no names on it. What each form proves, and the one-entry-per-step caveat
behind a cached figure, are in
[`CONVENTIONS.md`](../CONVENTIONS.md#working-in-this-repo).

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
   XPT2046_Touchscreen. **ArduinoBLE and NimBLE-Arduino must not be installed.**
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

1. Flash and let the deck boot. It lands on the front page: a fresh flash draws
   with the assumed mapping (raw axes rotated 90 degrees) instead of stopping for
   calibration. When taps land wrong, open calibration from settings or by holding
   the title for 1.5 s: tap the three dots — top-left, top-right, bottom-left —
   and the fit saves itself.
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

Service `6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10`: four write characteristics, one
read-write and one read.

The Spotify deck speaks the same service with the same core UUIDs — time,
battery, notice, now playing, theme — and adds one for its transport chips; the
label is this deck's own. A scan run from the app therefore sees **both** decks
when both are powered on; the shelf tells them apart by name and tag, not by
service.

| Characteristic | UUID | Payload |
| --- | --- | --- |
| Time | `6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10` | 7 bytes: year−2000, month, day, hour, minute, second, weekday (ignored — see below) |
| Battery | `6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10` | 1–2 bytes: level 0–100, optional charging flag |
| Notification | `6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10` | UTF-8 `app\|title\|body`, any part may be empty |
| Identity | `6f2d0005-9a3c-4b57-9d21-6a1e0c4f8b10` | **read** |
| Now playing | `6f2d0006-9a3c-4b57-9d21-6a1e0c4f8b10` | UTF-8 `state\|position\|duration\|title\|artist\|album`, whole seconds, state `play`/`pause`/`stop` |
| Theme | `6f2d0008-9a3c-4b57-9d21-6a1e0c4f8b10` | **read/write**, 1 byte: `0` PAPER, `1` INK, then `2 + palette * 2 + (light ? 0 : 1)` for green, moss and navy — applied, stored in NVS and repainted |
| Label | `6f2d0009-9a3c-4b57-9d21-6a1e0c4f8b10` | **read/write**, up to 16 printable ASCII bytes: the owner's name for this board |

The palette is a setting now: one byte naming a row of `THEMES[]`, applied, stored
in the `settings` namespace beside `bri` and repainted on whichever page is showing.
A write is `2 + palette * 2 + (light ? 0 : 1)` — the same arithmetic the app's
`DeckTheme` does — so the six palette rows are the phone's three swatches and its
page circle. A read answers where the board actually is, so the app's page circle
follows the board's own answer rather than what it last sent. `THEME_COUNT` and the clamp on the stored
byte are what keep a corrupt value from leaving the deck with no palette and no
way back to one; a byte past the table is ignored, not guessed at. That is also
why the deck has no theme page: the switch is the phone's, one pair of chips per
deck, and a deck flashed before this existed simply has no `6f2d0008-…`
characteristic — the app says so instead of writing into nothing.

The now-playing line is a **mirror, not a login**. This deck never talks to
Spotify: the companion app reads the phone's own media session — the same
notification access it already asks for — and writes one line a second, so there
is no client id, no browser page and no token anywhere in this path. `stop` clears
the card; a `play` line starts the deck's progress bar moving on its own between
writes. The track shows on the status card's last line (`>> 1:12 3:45  Title`) and
the whole page opens with a tap on it. That page has no transport buttons on
purpose: the deck has no way to ask Spotify anything, so a pause here would be a
promise it cannot keep. It leaves by its own `BACK` button or the header's `<`
chip, and a tap on the card does nothing: until 2026-11-04 any tap anywhere on
that page went to the messages board, so the card could only be left by accident
and the one thing that looked like a control did not behave like one. The text fields are capped at 47, 31 and 31 UTF-8 bytes,
and the app replaces any `|` in a title with `/` before sending, because this
characteristic splits on it.

The identity characteristic is one line of JSON and it is the one thing a reader
asks for: the same keys a network deck answers at `GET /deck.json`, so the app
keys this board by widget and tag — `phone_deck_cyd24R-A1B2C3` — instead of by MAC.
A re-flashed board keeps its row in the app, and a second board of the same widget
is a second row.

```json
{"id":"phone_deck_cyd24R-A1B2C3","widget":"phone_deck_cyd24R",
 "tag":"A1B2C3","name":"Phone Deck","label":"","role":"phone",
 "v":"1.0.1","link":"ble"}
```

`id` names the hardware and does not move: it is the key the app files a row under,
so changing it would split one board into two rows and lose the history of both. What
an owner can change is `label` — up to 16 printable ASCII bytes, stored in the
`identity` namespace, blank until set — written to the label characteristic below and
quoted here, so the app shows `Kitchen` rather than `Phone Deck`, and a second board
of this widget stays its own row. Renaming is one write:

```python
await client.write_gatt_char("6f2d0009-9a3c-4b57-9d21-6a1e0c4f8b10", b"Kitchen")
```

A blank write clears it. A board that has not published a document at all is filed
under its address, and the app shows the address's last four hex digits so two
identical un-named boards never read as two identical rows.

The three a board with no network has none of — `ip`, `setup`, `signed` — are
simply absent. The document is written from a 192-byte buffer, longer than the 20
a default ATT MTU carries, so a reader asks for a larger MTU first: the app asks
for 247, which carries the whole buffer, and a deck that caps its own MTU lower
(the Spotify deck asks 185, leaving 182 for payload) still answers because Android
takes the smaller of the two. The sketch prints `identity is <n> bytes, over one
185-MTU read` if the document ever grows past what the smallest reader can take,
because a read that comes back cut short names no widget and leaves the app on the
address fallback rather than inventing an id.

On connect the deck asks for a 7.5–15 ms interval with no slave latency and a 4 s
supervision timeout (`updateConnParams(…, 6, 12, 0, 400)`), the same request the
Spotify deck makes. Without it the link sits at whatever interval the phone chose:
a tap waits for the next one, and a phone that walks away takes longer to read as
gone. The phone may grant more; this is a request, not a setting.

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
| Tap the gear (top right) | Brightness and calibrate touch |
| Tap `BACK` on the now-playing page | Back to the front page |
| Hold the title for 1.5 s | Touch calibration |

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
* **A bond the phone deleted is deleted here too.** The deck used to keep its half
  of a bond the phone had forgotten, so every retry failed with an authentication
  error and the only way back was the 1.2 s gear hold. `onDisconnect` now reads the
  HCI reason (`0x05` authentication failure, `0x06` PIN or key missing, `0x3E` link
  never established), and `onAuthenticationComplete` reads the pairing result;
  either one calls `esp_ble_remove_bond_device()` for the phone's address, clears
  the owner record and advertises again — at most once every five seconds, so a
  failing attempt cannot stop a pairing from finishing. **Unverified on hardware.**
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
* **Letters came out as blocks.** The three bitmap faces were pasted in
  row-padded, and `drawChar()` reads a glyph as one unbroken bitstream, so
  every character on glass was a shifted block while the frames stayed
  perfect. Fixed 2026-11-04 by repacking both sketches and the renderer; a
  deck flashed before that date needs this build to show text. The whole
  story is in [the typeface section](../CONVENTIONS.md#typefaces-one-bitstream-per-glyph).

## Verified, and not

### Proving the theme toggle

The palette is written by the phone, so this is the one test that needs both ends.
It has not been run on a board yet; write each result here, with its date, when it
is.

1. Flash this sketch from ArduinoDroid — `PartitionScheme=huge_app` (the 3 MB app
   slot, and what `toolchain.sh` pins), core 3.3.12, Adafruit
   GFX, Adafruit ILI9341, XPT2046_Touchscreen — and record the `Sketch uses …`
   line and the md5 ArduinoDroid reports.
2. In the app: PAIRED, scan, LINK the board. The card shows the tag, the MAC and
   the palette it answered with (`MOSS DARK`, say). That name is the byte the board
   reads back, so a dash there means the characteristic did not answer.
3. Tap the moss swatch on that row: its ring moves, and the glass repaints in moss
   within a second. Tap the page circle: the same palette on its other page.
4. Flip SETTINGS → APPEARANCE on the phone. The phone and the linked deck should
   end on the same page, because the app writes its choice down the link.
5. Power-cycle the board with the backlight switch off and on: it must come back on
   the page it was left on, without a rescan. That is NVS, not the phone.
6. Unlink and re-link: the chips light from the board's answer, not from what the
   phone last sent. An unlink that re-lights the other chip was the app's guess.

A compile in this repo, on 2026-10-30 and before the mirror, with `arduino-cli`
1.5.2-rc.1:
**core 2.0.17** 1,162,797–1,162,813 B, **88 %** of 1,310,720, and
41,436 B, **12 %** of 327,680: the file then in the tree (`d53664ec`),
which the docs used to rebuild at 1,162,877 B here. That file is in no
commit this repository holds, so the figure is a record rather than a
measurement anyone can repeat; today's 2.0.17 figures live in
[`old_core.json`](../old_core.json), beside the md5 of the sketch that printed
them, and `./check.sh --full` rebuilds them instead of trusting the file.
**core 3.3.12** 1,163,930 B, **88 %** of 1,310,720, and
45,004 B, **13 %** of 327,680. The 2.x figure moves by ~16 bytes with the
build root: read it as 1,135.6 kB, not a fixed count. 3.x costs 1–2.3 kB of
flash and 3.7 kB of RAM more, depending on the path.

### Headroom

Measured on 2026-11-18 with [`../check_docs.py`](../check_docs.py) `--refresh`,
Arduino CLI 1.6.0, core **3.3.12** under `/home/ubuntu/.arduino-cyd-3`, and
`PartitionScheme=huge_app`:

| | used | free | ceiling |
| --- | --- | --- | --- |
| flash | 1,180,382 | **1,965,346** | 3,145,728 (37 % used) |
| DRAM | 45,340 globals | **282,340** | 327,680 |

Earlier runs on a different toolchain install reported 1,179,690 B with the
same globals. [The build note](../CONVENTIONS.md#build) explains why to record the
root and CLI alongside a measurement.

The check refuses a deck that overflows, that drops under **64 KiB** of free flash
or that leaves under **160 KiB** of DRAM — the last floor is just over the
153,600 B a full 320x240 RGB565 framebuffer needs. So this deck has almost two megabytes of flash to grow into, and one
framebuffer's worth of DRAM slack. The table gives the measured flash margin. On the default scheme it took far less: a
1,400,000-byte const table measured 1,668,748 B and overflowed the
1,310,720-byte slot it was built for, which is the refusal that named the need
for `huge_app` rather than a bare `compile failed`. Re-measure with `python3 ../check_headroom.py phone_deck_cyd24R`
from the repo root, after sourcing a toolchain.

### At a glance

**Verified here** — none of this needs hardware:

* The palette is one byte and survives a power cycle: `applyTheme()` is the only
  writer of the twelve `C_` names, `loadSettings()` clamps the stored byte to
  `0..THEME_COUNT-1`, and the two builds below link with the new characteristic
  in place. **Not** verified on a board: no write from a phone has reached a real
  deck, so the repaint on `EVT_THEME` is compiled, not seen.

* Core **2.0.17** is the A/B build, not the default — `toolchain.sh` pins 3.3.12.
  The 1,162,797–1,162,813 B / 41,436 B above is the 2026-10-30 file (`d53664ec`),
    which no commit here holds: its 1,162,877 B at this repo's path cannot be
    rebuilt, so it is quoted as history. Today's build on that core is recorded in
    [`old_core.json`](../old_core.json) — size, globals and md5 together, measured
    2026-11-04 through `~/.arduino-cyd-2` — because a 2.0.17 figure quoted here
    would be checked against the 3.3.12 build and refused.
* Core **3.3.12** links it at 1,180,382 bytes (37 % of 3,145,728) with 45,340 bytes of RAM
  (13 % of 327,680), `EXIT=0` under `--warnings all`, re-measured on 2026-11-18 with the link tuning. An earlier 3.3.12 build on a shorter path reported 1,163,930 / 45,004,
  so 3.x is path-sensitive too — 1,137–1,141 kB, not a fixed count.
* The bundled copy matches byte for byte after `:app:syncWidgets`;
  `check_widget_assets.py` prints `2 widgets bundled, every copy matches`
  (this sketch md5 `8f430c6a…`, the Spotify one `f7079149…`).
* Core 3.x costs this sketch ~1.1 kB of flash and ~3.6 kB of RAM over 2.0.17 —
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
| will not reconnect after one success | a phone that is not scanning, or a bond one end kept after the other deleted it | the deck now drops its own half on an authentication failure; if it still refuses, forget the deck in Bluetooth settings and power-cycle the board |
| white or blank screen | backlight at minimum, or the pin is not driven | SET has − and +; the backlight is GPIO 27 |
| status LED still glows | a channel wired differently on this revision | adjust `PIN_LED_*` |

## Limits worth knowing

* Notifications live in RAM only; a reboot clears the list.
* Six notifications are kept, three are shown at once.
* No pairing means no encryption: anyone in radio range could write the link.
* The clock drifts by roughly a second a day and re-syncs on the next time write.
* The iOS ANCS path is untested against a live iPhone.

## Measured

The current build measurement is in [Headroom](#headroom); this folder's
sketch md5 is `8f430c6a…`.
