# Off-site (Android companion)

The firmware in [`../phone_deck_cyd24R`](../phone_deck_cyd24R) is a BLE peripheral that
waits for a phone to write its Phone Link characteristics. Android has no built-in
way to do that, so this app is the phone side — and it is also where the CYDs are
monitored and where firmware sketches are kept for ArduinoDroid.

Four tabs:

| Tab | What it is for |
| --- | --- |
| **DECKS** | Live link: what is connected, signal, uptime, packets, nearby decks |
| **PROJECTS** | Every project with status, build/touch log and links, plus a name search and the deck libraries |
| **BOARD** | Target board, OTG connection, bootloader probe, image flashing, serial monitor |
| **SETUP** | Permissions, battery, bridge switch, last crash |

Ask it anything about the deck's state and it answers from the link itself, not
from guesswork: `linked 4m 12s · -62 dBm · 18 packets · 5 notices`.

## Monitoring your CYDs

- The bridge scans for decks advertising the Phone Link service (or the name
  "Phone Deck") and lists them under **NEARBY** with signal strength.
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
- A **search field** under the title narrows the shelf as you type. It matches a
  project's own name and, for a bundled widget, the descriptor's name, id and
  tagline — not log notes or imported file names. It stacks with the status
  chips instead of replacing them, and a search that finds nothing says
  `No widget matches "<typed>".`, as opposed to `Nothing in that status yet.`
  when only the chip is empty.
- **LOG** records the build string you flashed, whether the touch panel worked,
  free-text notes and the time. A first log also promotes a project out of WANT TO
  TRY, because logging means it actually reached the board.
- Records live in `ProjectStore`: one JSON array in `SharedPreferences`, keyed by
  id rather than display name, so renaming a project keeps its history. It is
  seeded once from `FirmwareCatalog` plus the deck, and any log written through
  the older `FirmwareNotes` store is carried over.
- **TARGET BOARD** is a pull-down that drives the `FILES / <code>` header and the
  RECIPE text; `BoardCatalog` is where further boards get added.
- The **ESP32 Phone_Widget** card is this repo's firmware, marked `INSIDE APP` —
  the title is `name` in `phone_deck_cyd24R/widget.json`. It keeps **COPY DROID**
  (whole sketch to the clipboard), **SAVE** (to Downloads) and the **LIBRARIES**
  card, whose **INSTALL** buttons copy a library name and open ArduinoDroid's
  library screen.
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
without a computer. It has two modes, **FLASH** and **MONITOR**, and only one of
them owns the cable at a time; the choice is remembered across restarts.

- **PROBE USB** reads the chip magic and MAC, then restarts the board into its
  firmware — it never leaves the deck sitting in its bootloader.
- **One PROBE press tries four wirings**, because a silent board can hide behind
  any of them: normal and swapped control lines, each with the reset driven from
  here and with EN pulsed while you hold **BOOT**. It stops at the first that
  answers, says `found on wiring 2 of 4`, and sets **LINES** / **MANUAL BOOT** to
  match so FLASH and RESTART reuse it. Hold BOOT from the moment the log reaches
  wiring 3; the last two phases never drive IO0 low, and no software can press
  that button. Before any of those four, one phase sends sync packets without
  touching DTR or RTS at all: the ROM stays in its loader until something resets
  it, so a board already sitting in download mode answers that phase — the only
  case a wrong reset wiring cannot hide.
  A round whose banner reads `boot:0x3 (DOWNLOAD_BOOT…) waiting for
  download` has the chip in its loader and ignoring packets, so the closing line
  asks for a second host — a PC flashing over the same cable — instead of blaming
  the rate: the CH340 is on the board, so there is no data wire to check.
- **SYNC ONLY** sends one sync packet and nothing else — no reset, no baud change,
  no control line. A chip already sitting in its loader answers it, which is the
  one result that needs no EN pulse and no BOOT held; silence proves nothing on its
  own, because firmware does not answer packets either. It also never resets the
  board on the way out, so the state it tested is still there afterwards.
- **FLASH** on an imported `.bin` erases and writes it at `0x0`, verifies it by
  MD5 and reboots into it. `0x0` is the merged-image offset; an app-only image
  belongs at `0x10000` and will not boot from `0x0`.
- **A flash owns the cable until it ends.** Rotation is handled in place rather
  than by rebuilding the screen, and ownership of the port is recorded outside
  the screen, so backing out mid-flash and coming back cannot start a second
  writer over the first: the fresh tab says `The port is busy (flash)`. The
  screen stays awake while it writes, and the percentage survives a rebuild of
  the tab, as does the sentence that says whether it worked.
- **RESTART BOARD** boots whatever is in flash without entering the bootloader.
  That is the way out of download mode (black screen, red LED) after a failed
  attempt, short of unplugging.
- **MANUAL BOOT** holds IO0 high and pulses EN from here, once per round, for a
  board whose IO0 leg is not driven by the adapter's DTR line: hold **BOOT** down
  for the manual phases, then let go. The toggle is what FLASH and RESTART use;
  PROBE tries this mode by itself. Every pulse writes both control lines, at
  levels that leave IO0 high, because a single line is not a reset on a board with
  the usual pair of reset transistors — a run that moved DTR alone read `0 B` at
  all five rates and looked exactly like a finger that never pressed BOOT. The
  proof of life runs here too, and the board is restarted afterwards like any
  other probe. A round whose banner says `boot:0x13 (SPI_FAST_FLASH_BOOT)`
  restarted into the firmware: EN works and IO0 was high, so BOOT is the thing to
  change.
- **LINES** swaps which control line carries which signal: **NORMAL** is DTR to
  IO0 and RTS to EN, **SWAPPED** is the reverse, for an adapter wired that way.
  The two look identical in the log — silence at every rate in both polarities —
  because a reversed pair releases reset with IO0 still high, so the ROM never
  starts. Inverting both lines does not help: each one just becomes its opposite.
- **MONITOR** reads what the board prints, and can answer it. **START MONITOR**
  opens the port at the **BAUD** rate in the pull-down (115200, 9600, 57600,
  230400, 460800, 921600); the rate can be changed while the stream runs, as
  long as the sketch is set to the same one. **RESTART + WATCH** pulses the
  board's reset with IO0 high, so the firmware already in flash reports from its
  first line. **SEND** writes the typed line plus the **EOL** ending — **CRLF**
  for a terminal, **LF** for a sketch that splits on the newline alone — and the
  transcript echoes it as `→ line`. COPY takes the transcript, CLEAR empties it.
- **The transcript is text, not a byte dump.** UTF-8 is decoded a read at a time,
  holding back a lead byte when a glyph is split so an accent does not arrive as
  a replacement box; carriage returns become newlines, tabs are kept, and other
  control bytes are dropped. It keeps the last 24,000 characters, trimmed at a
  line break, and repaints at most every 120 ms instead of once per read.
- Tapping **FLASH** stops the stream and frees the port within one read (200 ms),
  so the flasher never shares the cable with the reader. Unplugging the board ends
  the stream with `Board unplugged.`
- **PROBE LOG** below the buttons keeps the last 80 lines of every probe and
  flash. It opens with a proof of life: the chip prints boot text on every reset
  whatever is in flash. Before pulsing anything it drains 400 ms of traffic
  *without touching the lines* — bytes there are the board still booting from the
  plug-in, and crediting them to a reset would call a dead reset circuit healthy —
  then listens at 115200 and 74880 baud in both DTR/RTS polarities and only
  counts *readable text after a pulse* as proof; random bytes mean the board is
  talking at some other rate, not that it is reachable. The quoted text is the
  whole banner, up to 600 bytes, so the ROM's `rst:` and `boot:` lines and its
  build date are in the log instead of only the first line. Each rate then runs
  its reset rounds with alternating polarity and reports `N B in / M B out`, the
  hex that arrived and the reason the round failed; a round that reads nothing is
  marked `no ROM banner, so the chip never restarted`, because every reset prints
  one, and a bridge that refuses bytes stops the sweep instead of being retried at
  four more rates. COPY
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

**Which apps get through is the user's choice.** SETUP lists every launchable app
with a checkbox, and the first **SAVE** turns filtering on: after that only the
ticked apps reach the deck, and **ALL APPS** returns to the unfiltered default.
Nothing is filtered until someone chooses, so a fresh install behaves like the
unfiltered build — but the point of the list is that a one-time code or a private
message stays on the phone instead of lighting up a board other people can read.

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
`curl` and `unzip`. It runs the host tests below before Gradle, then checks the
signature, the 4-byte alignment, the manifest, the bundled sketch and the launcher
icon, refuses an APK that asks for `INTERNET`, is `debuggable`, targets anything
but 35 or ships without an icon, and prints the size and SHA-256 of what it copied
to `dist/`.

Android Studio still works: open the `android-companion` folder (Ladybug or
newer), let it sync, and run the `app` configuration.

`minSdk` is 26, `targetSdk` is 35 — what a new store listing requires. Targeting
35 puts the app edge to edge on Android 15, so `MainActivity` pads the header and
the tab bar by the system bar height there, and leaves older releases alone: below
15 the window still fits the bars itself and that padding would double it. The
sketch is only bundled when the build goes
through Gradle; a raw `javac` build produces an APK-less class check and the
PROJECTS deck card reports that the bundled sketch is missing.

A prebuilt debug APK is committed at
[`dist/PhoneDeckBridge-debug.apk`](dist/PhoneDeckBridge-debug.apk) (140 kB,
package `dev.phonedesk.companion`, debug-signed). Install it with
`adb install -r dist/PhoneDeckBridge-debug.apk`, or copy it to the phone and open
it with "install unknown apps" allowed.

Step-by-step usage is in [HOWTO.md](HOWTO.md).

## Tests

`./test.sh` compiles `EspRom.java` against a stub `UsbSerialCh340` — no USB, no
Android class — and runs the protocol checks on a plain JVM in a few seconds: SLIP
framing round-trips, the sync packet carries esptool's `0xDD` checksum, the
handshake reaches download mode and reads the MAC, a reply with the wrong opcode
is skipped, a refused write is blamed on the bridge rather than the board, a
silent board fails, and `flash` refuses a session that never synced. `build/host`
is its scratch directory.

It cannot test the board. Nothing on a JVM can: the reset pulse, the adapter's
line coding and the ROM's willingness to answer are hardware facts.

## Distribution

The APK is meant to be installable by other people, so the build checks what a
listing checks and fails rather than warns: `targetSdk` must be 35, the APK must
carry a launcher icon, it must not request `android.permission.INTERNET`, and it
must not be `debuggable`. The last one is not paperwork — notification access plus
a debuggable process means any machine with adb can attach to the code that reads
message text, so `build.gradle` sets `debuggable false` on the debug type for
exactly that reason.

The APK is still signed with the debug certificate, which only affects updates: a
build made on a different machine carries a different key, so it will not install
over the previous one. Uninstall first, or build on the machine that made it.

## Setup on the phone

1. Open the app and accept the Bluetooth prompt (Android 12+ asks for *Nearby
   devices*).
2. **SETUP → NOTIFICATION ACCESS** and enable **Off-site**. Without it,
   time and battery still flow but notifications never arrive. **CHOOSE APPS**
   under **NOTIFICATIONS** then decides which apps are allowed to reach the deck;
   until something is chosen, all of them are.
3. Exempt the app from battery optimisation from the same tab if the deck stops
   updating overnight.
4. The bridge starts by itself once both permissions are granted.

The deck does not appear in Settings → Bluetooth and does not need pairing: it is
a GATT peripheral, not a keyboard or headset.

## Limits worth knowing

- **SAVE to Downloads needs Android 10+.** On older versions use COPY.
- Imported sketches live inside the app; uninstalling the app removes them.
- Bundled sketches are read-only, by design — they track the repo.
- Notification text comes from the title/text extras; apps with fully custom
  notification layouts may post neither and are skipped.
- Notification access also exposes message content to this app. Nothing is stored:
  the text goes to the deck over BLE and nowhere else.
