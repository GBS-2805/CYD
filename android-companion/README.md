# Off-site (Android companion)

The firmware in [`../cyd_phone_deck`](../cyd_phone_deck) is a BLE peripheral that
waits for a phone to write its Phone Link characteristics. Android has no built-in
way to do that, so this app is the phone side — and it is also where the CYDs are
monitored and where firmware sketches are kept for ArduinoDroid.

Four tabs:

| Tab | What it is for |
| --- | --- |
| **DECKS** | Live link: what is connected, signal, uptime, packets, nearby decks |
| **PROJECTS** | Every project with status, build/touch log and links, plus the deck libraries |
| **BOARD** | Target board, OTG connection, bootloader probe, image flashing |
| **SETUP** | Permissions, battery, bridge switch, last crash |

Ask it anything about the deck's state and it answers from the link itself, not
from guesswork: `linked 4m 12s · -62 dBm · 18 packets · 5 notices`.

## Monitoring your CYDs

- The bridge scans for decks advertising the Phone Link service (or a name
  containing `phone_widget`, which is what the firmware calls itself now; older
  boards that still say "phone deck" are found too) and lists them under
  **NEARBY** with signal strength.
- **One deck is linked at a time.** That is a firmware and Android limit, not an
  app choice: each CYD accepts one phone, and the app keeps a single GATT link.
- Tapping **LINK** on a row pins the bridge to that board. Once pinned, another
  CYD in range never steals the connection; the choice survives restarts.
- Every deck that links is remembered under **SAVED DECKS** with its last-seen
  time, so a board that is switched off still shows up.
- The header pill reads `LINKED`, `SCANNING` or `IDLE`, and the monitor refreshes
  once a second. RSSI is polled from the live link every 10 s.

## The project shelf

**PROJECTS** is the notebook: every project is a card with a status, a log and the
links it needs.

- Four states, filterable with chips at the top: **WANT TO TRY**, **FLASHED**,
  **WORKING**, **PARKED**, plus **ALL** with the total. A card's **STATUS**
  button moves it between them and the counts follow immediately.
- **LOG** records the build string you flashed, whether the touch panel worked,
  free-text notes and the time. A first log also promotes a project out of WANT TO
  TRY, because logging means it actually reached the board.
- Records live in `ProjectStore`: one JSON array in `SharedPreferences`, keyed by
  id rather than display name, so renaming a project keeps its history. It is
  seeded once from `FirmwareCatalog` plus the deck, and any log written through
  the older `FirmwareNotes` store is carried over.
- **TARGET BOARD** is a pull-down that drives the `FILES / <code>` header and the
  RECIPE text; `BoardCatalog` is where further boards get added.
- The **ESP32 Phone_Widget** card is this repo's firmware, marked `INSIDE APP`. It keeps
  **COPY DROID** (whole sketch to the clipboard), **SAVE** (to Downloads) and the
  **DECK LIBRARIES** card, whose **INSTALL** buttons copy a library name and open
  ArduinoDroid's library screen.
- **INSTALLER** and **SOURCE** open a project's pages. **RECIPE** copies a runnable
  recipe: target board, why there is no ArduinoDroid source, both links, the saved
  log, and the esptool command that writes the image.
- **FILES** holds imports. **IMPORT A FILE** takes any `.ino`/`.bin` through the
  system picker and keeps the copy inside the app, with COPY / SAVE / FLASH /
  DELETE. The bundled deck is read-only.
- The catalogue is still fixed at the four seeded projects: adding, editing and
  deleting projects, and attaching a file to a card, is the next step.

## Direct USB

**BOARD** talks to the board's CH340 bridge over an OTG cable,
without a computer.

- **PROBE USB** reads the chip magic and MAC, then restarts the board into its
  firmware — it never leaves the deck sitting in its bootloader.
- **FLASH** on an imported `.bin` erases and writes it at `0x0`, verifies it by
  MD5 and reboots into it. `0x0` is the merged-image offset; an app-only image
  belongs at `0x10000` and will not boot from `0x0`.
- **RESTART BOARD** boots whatever is in flash without entering the bootloader.
  That is the way out of download mode (black screen, red LED) after a failed
  attempt, short of unplugging.
- **PROBE LOG** below the buttons keeps the last 80 lines of every probe and
  flash. It opens with a proof of life: the chip prints boot text on every reset
  whatever is in flash, so the log listens at 115200 and 74880 baud in both
  DTR/RTS polarities and only counts *readable text* as proof — random bytes mean
  the board is talking at some other rate, not that it is reachable. Each rate
  then runs its reset rounds with alternating polarity and reports `N B in / M B
  out`, the hex that arrived and the reason the round failed; a bridge that
  refuses bytes stops the sweep instead of being retried at four more rates. COPY
  LOG puts it on the clipboard, CLEAR empties it.

`UsbSerialCh340` implements the CH340 register sequence in-tree and `EspRom` the
ROM protocol: SLIP framing, sync, register reads, `FLASH_BEGIN`/`FLASH_DATA`/
`FLASH_END` and the MD5 check.

## What it sends to the deck

| Characteristic | UUID suffix | Payload |
| --- | --- | --- |
| Time | `6f2d0002-…` | 7 bytes: year−2000, month 1–12, day, hour, minute, second, weekday (the deck ignores the weekday but requires the byte) |
| Battery | `6f2d0003-…` | 2 bytes: percent 0–100, then `1` while charging or full |
| Notification | `6f2d0004-…` | UTF-8 `App\|Title\|Text`, truncated to 120 bytes |

Service UUID is `6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10`; full UUIDs are in
[`DeckService.java`](app/src/main/java/dev/phonedesk/companion/DeckService.java).

Time and battery are pushed on connect and then every 30 seconds. Notifications
are pushed as they arrive; duplicates within 5 seconds are dropped, and group
summaries and quiet ongoing items are skipped.

## Build

No AndroidX and no third-party libraries — only the platform SDK, so the build
resolves nothing beyond the Android Gradle Plugin.

One script gets a machine that has nothing installed to a verified APK. It puts
its own JDK 17, Gradle 8.7 and Android SDK under `~/.android-offsite`, so nothing
global changes and deleting that directory undoes all of it:

```bash
cd android-companion
./toolchain.sh                      # install, clean build, verify, copy to dist/
source ~/.android-offsite/env.sh    # adds java, gradle and adb to PATH
adb install -r dist/PhoneDeckBridge-debug.apk
```

`./toolchain.sh --setup-only` stops once the toolchain is ready; `--no-clean`
reuses Gradle's build directory. The first run downloads about 1 GB and needs
`curl` and `unzip`. It finishes by checking the signature, the 4-byte alignment,
the manifest and the bundled sketch, and prints the size and SHA-256 of what it
copied to `dist/`.

Android Studio still works: open the `android-companion` folder (Ladybug or
newer), let it sync, and run the `app` configuration.

`minSdk` is 26, `targetSdk` is 34. The sketch is only bundled when the build goes
through Gradle; a raw `javac` build produces an APK-less class check and the
PROJECTS deck card reports that the bundled sketch is missing.

A prebuilt debug APK is committed at
[`dist/PhoneDeckBridge-debug.apk`](dist/PhoneDeckBridge-debug.apk) (97.2 kB,
package `dev.phonedesk.companion`, debug-signed). Install it with
`adb install -r dist/PhoneDeckBridge-debug.apk`, or copy it to the phone and open
it with "install unknown apps" allowed.

Step-by-step usage is in [HOWTO.md](HOWTO.md).

## Setup on the phone

1. Open the app and accept the Bluetooth prompt (Android 12+ asks for *Nearby
   devices*).
2. **SETUP → NOTIFICATION ACCESS** and enable **Off-site**. Without it,
   time and battery still flow but notifications never arrive.
3. Exempt the app from battery optimisation from the same tab if the deck stops
   updating overnight.
4. The bridge starts by itself once both permissions are granted.

The deck does not appear in Settings → Bluetooth and does not need pairing: it is
a GATT peripheral, not a keyboard or headset.

## Limits worth knowing

- **SAVE to Downloads needs Android 10+.** On older versions use COPY.
- Imported sketches live inside the app; uninstalling the app removes them.
- The bundled sketch is read-only, by design — it tracks the repo.
- Notification text comes from the title/text extras; apps with fully custom
  notification layouts may post neither and are skipped.
- Notification access also exposes message content to this app. Nothing is stored:
  the text goes to the deck over BLE and nowhere else.
