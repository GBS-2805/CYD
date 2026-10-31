# HOWTO — companion app

Short, task-shaped instructions. Background and payloads are in
[README.md](README.md); current state and open issues are in [HANDOFF.md](HANDOFF.md).

## First setup

1. Install and open the app.
2. Accept **Nearby devices** (Android 12+). On older Android, accept the location
   prompt — it is required to scan for BLE devices.
3. Open **SETUP → NOTIFICATION ACCESS**, find **Off-site**, enable it.
4. The header pill should switch from `IDLE` to `SCANNING` and then `LINKED` when
   the deck is in range.

If it stays on `IDLE`, tap **START BRIDGE** in SETUP.

## Link a specific CYD

Use this when you have more than one deck powered on.

1. Open **DECKS** and let the scan run for a few seconds.
2. Find the board under **NEARBY** — it shows name, MAC and signal strength.
3. Tap **LINK**. The bridge pins itself to that MAC and drops any other link.
4. The row changes to `LINKED`, and the deck's own screen shows `LINKED` too.

The pin survives restarts: it is stored as the preferred deck. To move to another
board, tap **LINK** on that board's row.

Saved decks keep their last-seen time, so you can identify a board that is
currently switched off. Bring it near the phone and tap LINK again.

## Check the link health

The **DECKS** status card shows, live:

- **linked 4m 12s** — how long this link has held
- **-62 dBm** — RSSI, polled every 10 s
- **18 packets** — time/battery/notification writes the deck acknowledged
- **5 notices** — notifications forwarded since the link came up

A link that keeps resetting to `SCANNING` with a growing reconnect count is a
range or bonding problem, not a UI problem. See Troubleshooting.

## Put the deck sketch into ArduinoDroid

The bundled sketch is the one in this repo. Pick whichever route suits you.

**Clipboard route (fastest):**

1. **PROJECTS** → on the ESP32 Phone_Widget card, tap **COPY DROID**.
2. Open ArduinoDroid, create or open a sketch, select the whole editor and paste.
3. Compile and upload.

**File route (keeps a real file):**

1. **PROJECTS** → on the ESP32 Phone_Widget card, tap **SAVE**.
2. The file lands in `Downloads/cyd_phone_deck.ino` (Android 10+).
3. In ArduinoDroid, open the sketch through its file browser.

ArduinoDroid must see the sketch as a **single tab** named exactly
`cyd_phone_deck.ino`. The sketch is deliberately one file; splitting it fails
with `deck_types.h: No such file or directory`.

## Install Marauder, Bruce or Launcher on the CYD

Other firmwares are not flashed by this app. **PROJECTS** opens the
project's official installer in your browser instead.

1. Power the board and connect it to a computer over USB (data cable).
2. In **PROJECTS**, tap **INSTALLER** for the firmware you want.
   Marauder and Bruce both offer a browser flasher; it handles bootloader,
   partitions and offsets for the CYD.
3. In Chrome or Edge, select the CYD / ESP32-2432S024 target, connect, and flash.
4. If the board is not detected, hold **BOOT** while plugging in, then retry.

Offline route: download the release's merged `.bin` on the computer, then

```bash
esptool.py --chip esp32 --baud 921600 write_flash 0x0 <merged-cyd>.bin
```

Use the merged/factory image from the release, not an app-only `.bin` — a
partial image at `0x0` produces a board that boots to garbage. Imported `.bin`
files can be kept in the app and saved to Downloads when you need them on a PC.

**Launcher** is worth installing once if you switch firmwares often: after one
flash it boots other `.bin` files from SD or OTA, so you stop reflashing over USB.

**Libraries in ArduinoDroid.** Only the deck is an ArduinoDroid build. It needs
exactly three libraries:

| Sketch | Libraries | Never install |
| --- | --- | --- |
| Deck (`cyd_phone_deck.ino`) | Adafruit GFX Library, Adafruit ILI9341, XPT2046_Touchscreen | ArduinoBLE |
| Marauder / Bruce / Launcher | none — flash the prebuilt image | — |

### Install them without retyping the names

**PROJECTS → ESP32 Phone_Widget → LIBRARIES** has an **INSTALL** button for each library.
One tap copies that exact name and opens ArduinoDroid's library list.

1. Tap **INSTALL** next to **Adafruit GFX Library**.
2. ArduinoDroid opens on its library screen. Paste the name into the search
   field, install the library, then press back.
3. Return to this app and do the same for **Adafruit ILI9341**, then
   **XPT2046_Touchscreen**.
4. Compile the deck sketch.

**INSTALL ALL** copies all three names at once (one per line) and opens the same
screen, if you prefer installing from a single list. **COPY NAME** and **COPY
ALL** only touch the clipboard, and **OPEN ARDUINODROID** opens the editor itself
(or the Play Store listing when the app is not installed).

ArduinoDroid's library screen is found by looking through the installed app's own
activities, because its class name changes between releases; if a build exposes no
such screen the button opens the editor instead and the copied name is still on
the clipboard.

The catalogue says the same on each card, and **COPY RECIPE** includes it. Those
three are PlatformIO projects: Bruce's source alone pins about 40 libraries (NimBLE-Arduino,
ArduinoJson, FastLED, RadioLib…), so compiling them in ArduinoDroid is not a
supported path — use their installer or a prebuilt `.bin`.

**Match the panel, not just the name.** The board here is the ESP32-2432S024R
(2.4" resistive). The far more common CYD is the ESP32-2432S028/R (2.8") with
different touch pins — a 2.8" image can boot with a blank or inverted screen and
dead touch.

| Firmware | What to look for |
| --- | --- |
| Marauder | Official installer's CYD 2432S024 entry; if touch is off, Fr4nkFletcher's CYD fork, which explicitly supports the 2432S024R |
| Bruce | The CYD-2432S024R image, not 2432S028; accept the first-boot touch calibration |
| Launcher | A `Launcher-<device>.bin` whose name contains 2432S024R |

## Log which build is on the board

After flashing a third-party firmware, record it so you never have to guess later.

1. **PROJECTS**, find the card you flashed, tap **LOG**.
2. Fill in the build/version string (for example the release name shown by the
   installer), pick the touch status, and add anything worth remembering.
3. **Save**. The card now shows `TOUCH: WORKS · BUILD: … · logged 2h ago` plus
   your notes.

Reopen **LOG** to update it after a recalibration, or **Clear** to remove the
record. Everything stays on the phone in app storage; it is not sent anywhere.

**COPY RECIPE** on the same card puts a runnable recipe on the clipboard: the
firmware name, the board, why it has no ArduinoDroid source, the installer and
source links, the board note, any saved log, and the command that writes the
image —

```bash
python -m esptool --chip esp32 --baud 921600 write_flash 0x0 <merged-2432S024R>.bin
```

**Why these cards have no "copy droid" button.** Marauder, Bruce and Launcher are
PlatformIO C++ projects: Bruce alone pins about forty libraries and the sources
run to tens of thousands of lines. There is no ArduinoDroid-pasteable form of
them, and an app that offered one would be pasting a sketch that cannot compile.
The deck is the only firmware here that is ArduinoDroid source, which is why its
button reads **COPY DROID** and the others read **COPY RECIPE**.

**No computer at hand?** Flash **Launcher** over USB once, then install other
firmwares from the phone: put the `.bin` on the CYD's SD card, or upload it through
Launcher's web UI while the board is on Wi-Fi. That is the route that switches
firmware without a cable, because the deck links over BLE and the ESP32's
bootloader only speaks UART.

Useful things to note: which installer/version produced a working image, whether
first-boot touch calibration was needed, screen orientation quirks, and whether a
2.8" build was flashed by mistake.

## Keep your own sketches on the phone

1. **BOARD → IMPORT A FILE**, pick any `.ino`.
2. The app keeps its own copy under **IMPORTED**, showing the size.
3. **COPY** or **SAVE** to move it into ArduinoDroid; **DELETE** removes the copy
   inside the app only.

Importing is how you carry sketches between phones without a cable.

## Build the APK on a computer

You only need this when changing the app; the APK committed in `dist/` installs
as it is.

```bash
cd android-companion
./toolchain.sh                 # install, build, verify, copy to dist/
```

The first run downloads a JDK, Gradle and the Android SDK into
`~/.android-offsite` (about 1 GB) and needs `curl` and `unzip`. Later runs reuse
them, so the same command finishes in well under a minute. Set
`OFFSITE_HOME=/some/path` to install somewhere else.

The script builds with `clean` on purpose and then checks the result: signature,
4-byte alignment, package name, min/target SDK and the MD5 of every widget's
sketch inside the APK against the file its descriptor names
(`../<folder>/<sketch>.ino`). It prints the APK's size and SHA-256 last.

| Message | What it means |
| --- | --- |
| `needs unzip on PATH` | install `unzip`; the Gradle and SDK archives need it |
| `could not download …` | no network, or a proxy that blocks adoptium/gradle/google |
| `error: gradle build failed` | the last 40 lines of the Gradle log follow the message |
| `Java compile was UP-TO-DATE` | something restored a build directory; drop `--no-clean` |
| `<id>/<sketch> is stale` | `syncWidgets` did not run — check that the folder's `widget.json` names an existing sketch |
| `the APK carries no assets/widgets/…` | the descriptor exists but the build skipped the task; build through Gradle |
| `apksigner rejected the APK` | the `Android Debug` keystore in `~/.android` is missing or unreadable |

The size is not byte-stable: two builds of the same sources differ by a dozen
bytes because the dex files carry build-time data. Treat the printed number as
"about 100 kB", not as a checksum.

## Flash the deck from the phone over USB

1. Connect the CYD with an OTG adapter and a USB **data** cable, with the right
   board chosen under **TARGET BOARD** (only the 2432S024R is listed today).
2. **BOARD → PROBE USB**, accept Android's permission prompt.
   A working link reports `ESP32 bootloader found · MAC …`, fills **PROBE LOG**
   with each handshake step, then restarts the board into its firmware.
3. Import the image you want under **IMPORT A FILE** (a **merged** `.bin`).4. Tap **FLASH** on that entry, read the confirmation, and accept. The card shows
erase, block-write and MD5 progress; the board reboots into the new image at
the end.

**PROBE LOG** under the USB buttons holds the last 80 lines of the last probes
and flashes: the port opening, every rate tried, the bytes that arrived, the chip
magic, the MAC, block progress and both MD5s. COPY LOG puts all of it on the
clipboard, CLEAR empties it. It keeps earlier runs on purpose, so the attempt
before a failure is still there to compare against.

**Black screen and a red LED** means the board is in its ROM download mode, not
broken. Tap **RESTART BOARD** to boot the firmware that is still in flash, or
unplug the cable. The flash is only erased once a write has actually started.

**No CH340 on the USB bus**: the phone cannot see the bridge — check the adapter,
try another cable, and confirm the phone supports USB host mode.

**no answer from the bootloader**: read **PROBE LOG** from the top, in this order.

1. Four `proof of life` lines — `115200 normal`, `115200 inverted`, `74880
   normal`, `74880 inverted`. If one of them quotes text in quotes, the cable, the
   adapter and the reset wiring are all fine and the chip runs when it is
   released; skip to 3.
2. All four say `nothing` → not a byte reached the phone after a reset: cable, the
   adapter's receive line, or a board held in reset.
3. All four say `no text — <hex>` → the board is transmitting at some rate the
   probe is not listening on. The data path works; the rate is the open question.
4. `round 1: 0 B in / 0 B out` means the bridge refused the bytes and the sweep
   stops there. `0 B in / 552 B out` means 12 sync packets left the phone and the
   ROM stayed silent. Rounds alternate the DTR/RTS polarity and the log marks the
   inverted ones, so compare rounds before blaming the board: a board whose EN/IO0
   pair is inverted answers only on the inverted rounds.

**MD5 mismatch after writing**: something interfered with the link (a hub, a
charge-only cable, an unplugged board). Re-flash; the board boots whatever it
holds, so a failed verify means the image on it is suspect, not that the board is
lost. The two digests are in **PROBE LOG** as `MD5 board …, file …`, so you can
tell a partial write from a wrong file without flashing again.

## Watch what the board prints over USB

1. Connect the CYD with the OTG adapter and a USB **data** cable.
2. **BOARD → MONITOR**.
3. Set **BAUD** to the rate the sketch prints at — 115200 is the deck's log. To
   see the ROM's own boot messages instead, use 74880.
4. **START MONITOR** and accept Android's permission prompt. **OUTPUT** fills as
   bytes arrive and keeps the newest 24,000 characters.
5. **RESTART + WATCH** resets the board while the stream is open, so a sketch
   that prints once at boot is still caught from its first line.
6. **SEND** answers a sketch that is waiting on `Serial`: it writes the typed line
   plus the ending on the **EOL** button. **CRLF** is a terminal's Enter; **LF** is
   for a sketch that splits on the newline alone.
7. Tap **FLASH** to stop the stream before flashing: the monitor holds the port
   open, and it closes within a fraction of a second of leaving the mode.

**Output stays empty** — most sketches print nothing until something happens, so
silence is not a fault by itself. Confirm the rate matches the sketch, then tap
**RESTART + WATCH**: a board that says anything at all says it at boot. Nothing
at 115200 or 74880 points at the cable, the adapter, or the sketch's baud rather
than at the app. Nothing here has run on a phone yet, so treat the counters as
unmeasured rather than as expected values.

## Troubleshooting

**Deck never appears under NEARBY**
- Confirm the deck is flashed and powered; its screen draws the dashboard.
- The deck advertises for as long as it is on. If a phone is already linked to it,
  it stops advertising — disconnect that phone first.
- Watch the serial log at 115200 for `[ble] advertising as "Phone Deck"`.

**LINK fails or the deck says "Deck service missing"**
- The deck is running an older sketch without the Phone Link service; reflash the
  bundled sketch.
- If Bluetooth settings still lists a **Phone Deck** keyboard entry from an older
  build, forget it. That stale bond interferes with GATT connects.

**Notifications never arrive though LINKED shows**
- Notification access is off or was revoked by an app update; re-grant it in SETUP.
- The posting app uses a fully custom notification layout with no title/text
  extras; those are skipped by design.

**Deck stops updating overnight**
- SETUP → BATTERY SETTINGS, exempt the app. Some vendors kill foreground services
  regardless; re-opening the app restarts the bridge.

**SAVE does nothing**
- It needs Android 10+. Use COPY, or upgrade; the app does not request storage
  permissions.
