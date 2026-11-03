# Handoff — Off-site (Android companion)

State as of the last session. [README.md](README.md) has the product view,
[HOWTO.md](HOWTO.md) the task steps; this file is the context behind the code.

## What exists

A single-module Android app in Java, **no AndroidX and no third-party
libraries**, so Gradle resolves only the Android Gradle Plugin. Package
`dev.phonedesk.companion`, `minSdk` 26, `targetSdk` 35.

The drawer (`DrawerHome`) is the front page and `MainActivity` hosts it:

| File | Role |
| --- | --- |
| `MainActivity.java` | Header pill, drawer host, 1 s refresh loop, permissions, SAF result |
| `DrawerHome.java` | The front page: `PAIRED`, `BOARDS`, `SERIAL`, and the gear that opens `SETUP` |
| `TopBar.java` | The crumb, the centred mark, the link pill and the gear: one row, built by `TopBar` so the render host can measure it |
| `DecksTab.java` | Live monitor: link telemetry, nearby scan results, saved decks |
| `ProjectsTab.java` | Notebook: status chips, project cards, deck libraries, recipes |
| `BoardTab.java` | Target picker, USB probe/restart, imported images, flashing, serial monitor |
| `SetupTab.java` | Notification access, Bluetooth, battery settings, start/stop |
| `DeckService.java` | Foreground service: scan, GATT link, write queue, snapshot API |
| `DeckStore.java` | SharedPreferences: known decks and the preferred MAC |
| `DeckIdentity.java` | Parses the deck's one-line identity JSON, from BLE or `/deck.json` |
| `DeckRegistry.java` | One record per board: id, widget, tag, address, state, age |
| `LanDeck.java` | A network deck: `/deck.json`, its authorize URL, the code hand-off |
| `NowPlaying.java` | Reads the phone's media session for the mirror |
| `NowPacket.java` | Builds and clamps the now-playing line |
| `NoticeFilter.java` | Per-app notification allowlist, one boolean per package |
| `FlashLibrary.java` | Asset + app-storage files (binary-safe), import, MediaStore save |
| `FirmwareCatalog.java` | Links to Marauder / Bruce / Launcher installers; no bundled binaries |
| `UsbSerialCh340.java` | CH340 driver: claim, init writes, baud divisor, flow control, DTR/RTS |
| `BoardCatalog.java` | Target-board list behind the BOARD tab pull-down |
| `EspRom.java` | ROM protocol: SLIP, sync, register reads, flash write, MD5 verify, hard reset |
| `FirmwareNotes.java` | SharedPreferences log per firmware: build label, touch status, notes |
| `DeckNotificationListener.java` | Forwards posted notifications to the service |
| `Ui.java` | Palette, rounded cards, buttons, duration/size formatting |
| `Actions.java` | Shared toast, clipboard, link and save helpers |
| `CrashLog.java` | Uncaught-exception handler and the LAST CRASH reader |
| `SpotifyCallbackActivity.java` | Catches `phonedesk://spotify-callback` and hands the code to the deck through the browser |
| `SpotifyLink.java` | Pure parser for that link — code, and the deck address in `state`; host-tested |

The UI is built programmatically — no `res/layout`, no `R` class. That is what
lets `javac` type-check the whole app here; keep it that way unless there is a
reason to add a resource pipeline.

## Monitoring model

- The service records every scan result that looks like a deck (Phone Link UUID or
  a name containing "phone deck"), keeps 12 with a 30 s TTL, and exposes them
  through `DeckService.snapshot()`.
- **One link at a time.** A second GATT link to another CYD was deliberately not
  built: the firmware accepts one phone, and juggling several links with Android's
  single-operation GATT API is not worth the complexity.
- Tapping LINK calls `connectTo(mac)`, which writes the MAC to `DeckStore` as the
  preferred deck. When a preference exists the scanner ignores other decks, so a
  neighbouring board cannot steal the connection.
- `snapshot()` also carries `writes` (acknowledged GATT writes), `notices`
  (notifications forwarded), `connectedAtMs`, `lastWriteMs` and `rssi`
  (`readRemoteRssi()` every 10 s).
- `DecksTab` rebuilds only when a structural signature changes and updates the
  live line every second, so scroll position and row identity stay stable.

## Firmware files

- **Two surfaces, one store.** Both bundled sketches appear as cards in BOARDS —
  **Phone Deck** and **Spotify Deck**, each titled by `name` in its own
  `widget.json`, not by a string in the app; imported `.ino`/`.bin` files appear
  under **IMAGES** on BOARD, which is also the only place with COPY / SAVE /
  FLASH.
  `FlashLibrary` backs both.
- **A text sketch is never flashable.** The APK carries ArduinoDroid source, not a
  compiled `.bin`, so the write path runs on imported images only.
- Each project folder carries a `widget.json` descriptor: `id`, `name`, `sketch`,
  `board`, `libraries`, `never`, and a `ble` block with the service UUID and the
  name fragments a deck advertises. Gradle task `syncWidgets` copies the
  descriptor and the sketch it names into `app/src/main/assets/widgets/<id>/`
  before `preBuild`, so the repo keeps one copy of each sketch; do not commit a
  duplicate into assets. The destination is wiped first, because a copy alone
  would leave a removed widget in the APK forever. The descriptor **and** the
  sketch it names are both declared inputs; declaring only the descriptor was a
  real bug, because the output lives in `src/main/assets`, which `clean` does not
  delete, so editing a sketch alone left the task UP-TO-DATE and a full rebuild
  shipped the previous copy silently. `toolchain.sh` reads the
  descriptors beside this folder, pulls `assets/widgets/<id>/<sketch>` back out of
  the APK and compares it with the repo file, so a bundled sketch cannot skip the
  check.
- `WidgetCatalog` parses those descriptors once (one entry per
  `assets/widgets/<id>/`), and `FlashLibrary` reads each sketch by its asset path
  and caches the bytes (the tab refreshes every second and a sketch is ~70 kB). A
  build that skipped Gradle has no descriptors and falls back to a built-in deck
  entry, so the card never disappears.
- Imported sketches are copied into `filesDir/flash` from a SAF URI, so the app
  owns them; deleting the app deletes them.
- SAVE writes to `MediaStore.Downloads`, which needs Android 10+; below that the
  UI tells the user to COPY instead rather than requesting storage permission.
- COPY puts the entire sketch on the clipboard for ArduinoDroid's editor. That is
  the path that works regardless of Android version.

## Project notebook

- `ProjectStore` holds one record per project: id, name, tagline, body, libraries,
  source and installer URLs, notes, build, fileName, kind, status, touch and
  updatedMs. One JSON array in `SharedPreferences`; `org.json` is a platform
  class, so the no-dependency build is unchanged.
- **Keyed by id, not by name.** `FirmwareNotes` keyed its log by display name, so
  a rename orphaned the history. Seeding migrates any existing `FirmwareNotes`
  record into the matching project and then stops using that store.
- **Status and result are separate.** Status is the user's relationship to the
  project (WANT TO TRY / FLASHED / WORKING / PARKED); touch and build describe
  what happened when it ran.
- **`ProjectsTab` rebuilds on a signature** covering the file list, the store
  version, the selected board, the active filter and the search query. The store
  version is what makes a LOG or STATUS write repaint the card; before it, the
  shelf kept showing text from two screens ago until a file was imported.
- **Search is a name filter, not a full-text one.** `matches()` trims and
  lower-cases each keystroke once, then tests the project's own name and, when the
  project has a descriptor, the widget's name, id and tagline — so a card is
  findable by what its widget calls itself, not only the title it shows. Notes,
  build strings and imported file names are deliberately not searched: a log-line
  hit would surface a card with no visible reason for being there. The field is
  detached and re-added inside `build()` so its text and cursor survive the
  rebuild its own filtering triggers, and it combines with the status chips rather
  than resetting them.
- The bundled deck is a project of kind INSIDE: its card carries COPY DROID and
  SAVE, and the LIBRARIES card hangs off it.
- **Add, edit, delete and attach are built.** `ADD A PROJECT` and the `EDIT` chip
  open the same form card — name, tagline, status, notes — and `DELETE` is offered
  only for records this app made (`user:` ids), because a widget's card belongs to
  its descriptor and would be seeded back on the next read. `ATTACH A FILE` opens
  the picker as a card in its place, listing what FILES holds and offering
  `IMPORT A FILE` for what it does not: a `.ino` becomes
  `COPY SOURCE` (the text ArduinoDroid compiles) and a `.bin` becomes `FLASH`,
  which opens SERIAL with that image marked `PICKED FROM THE SHELF`, and a folded
  card carries a `FILE` badge when it names one. The writing
  is still the app's own uploader, which has never synced, so a card can reach the
  flasher but cannot make it work — that gap is unchanged. The catalogue is the
  seeded cards, plus whatever this app added, plus whatever is imported into
  FILES.

## Third-party firmware: catalogue, do not bundle

`FirmwareCatalog` is a hardcoded list of name, tagline, blurb, repo URL, installer
URL and a flashing note. Decisions behind it:

- **No images in the APK.** Marauder, Bruce and Launcher are GPL releases that
  change constantly; bundling them would mean shipping their source and licences
  and going stale within weeks.
- **No third-party in-app flashing.** The ESP32 is programmed over UART, not BLE.
  The BOARD tab's flasher takes imported merged `.bin` files and never opens the
  installers' images; Marauder, Bruce and Launcher are flashed by their own
  installers or a PC-side esptool run. The decks are the exception only in that
  they reach the board as ArduinoDroid source over the same cable.
- **Board support was checked against the 2432S024R specifically**, not the
  generic "CYD" label, because the 2.8" 2432S028 uses different panel and touch
  pins. Evidence: Fr4nkFletcher's Marauder fork states "Add support for Guition
  ESP32-2432S024R 2.4\" Resistive Touch"; Bruce issue #660 tracks the same board
  and its CYD-2432S024R builds gained a first-boot touch calibration; Launcher's
  catalogue lists 2432S024R (port tracked in its issue #99). GhostESP was dropped
  from the catalogue: its CYD builds target the 2.8" 2432S028 and its own board
  wiki lists the 2.4" 2432S024 as unsupported, so it was a warning with no action.
- **Copy button is honest about what it can copy.** `ProjectsTab` labels the bundled
  sketch's button **COPY DROID** (it is real ArduinoDroid source) and the
  third-party cards' button **COPY RECIPE**, which copies the board, the reason
  there is no sketch form, links, the saved log and a runnable
  `python -m esptool … write_flash 0x0 <merged-2432S024R>.bin` line. Rewriting
  Marauder/Bruce/Launcher as an ArduinoDroid sketch is not possible: they are
  PlatformIO C++ projects (Bruce pins ~40 libraries), and embedding their code
  would also bring GPL source-distribution obligations into the APK.
- **Library requirements are per option, not generic.** Only the deck sketch is
  an ArduinoDroid build (Adafruit GFX, Adafruit ILI9341, XPT2046_Touchscreen; never
  ArduinoBLE). The other three are prebuilt PlatformIO images — Bruce alone pins
  roughly 40 libraries — so their cards say "None" and point at the installer
  rather than pretending they can be compiled in ArduinoDroid.
- **One-tap library setup, driven by the descriptor.** The LIBRARIES card under an
  INSIDE project reads `widget.libraries` and warns about anything in
  `widget.never` ("Never install ArduinoBLE: …"). Each row has **INSTALL**
  (`installLibrary()`), which copies the name and then opens ArduinoDroid's library
  screen, plus **COPY NAME**. **INSTALL ALL** does the same with every name on the
  clipboard; **COPY ALL** only copies; **OPEN ARDUINODROID** opens the editor.
- **Library screen is resolved, not hard-coded.** `openArduinoDroidLibraryManager()`
  scans the installed package's activities for one whose class name contains
  "librar" and launches it explicitly, falling back to `openArduinoDroid()`. That
  avoids guessing a class name that changes between releases; a build with no such
  activity simply opens the editor with the name already copied.
- **The manifest `<queries>` block is required.** Without it, Android 11+ hides
  ArduinoDroid from `getPackageInfo()` / `getLaunchIntentForPackage()`, so both the
  library lookup and the open-app button silently fall through to the store. Both
  package names (`...arduinodroid2` and the legacy `...arduinodroid`) are listed.
- If any of those projects drop or rename a build, the card's note is the thing to
  update — the app only links, it never bundles an image.

## Direct USB: probe and flash

- `UsbSerialCh340` is a minimal CH340/CH341A driver: claim interfaces, the
  reference init writes, the baud divisor at registers `0x1312`/`0x0F2C`, and
  DTR/RTS through vendor request `0xA4`. The sequence is usb-serial-for-android's
  (Apache-2.0, Copyright 2014 Andreas Butti), kept in-tree to stay dependency-free.
- `EspRom`: SLIP framing, the `0x08` sync command, `0x0A` register reads. The
  register value is taken from the reply header's 4-byte value field, not the
  trailing bytes — that is what esptool's `check_command` does, and reading the
  tail instead silently yields status bytes.
  `CHIP_DETECT_MAGIC` is `0x40001000`, expecting `0x00f01d83` on ESP32; the MAC is
  best effort from eFuse `0x3FF5A004`/`0x3FF5A008` and never fails a detection.
- Reset is esptool's classic sequence: DTR/RTS hold EN low, drop IO0, release.
- **Flow control is disabled explicitly** (`0x2727 = 0`) during CH340 init. Left
  enabled, the bridge withholds traffic because CTS is never asserted — which
  presents as a board that resets into download mode but never answers a sync.
- **The log separates the three ways a probe can fail.** A real run on
  2026-10-30 showed they look identical in a per-rate byte count: a board that
  never spoke, a bridge that never took the bytes, and a reset that never
  happened. Each rate now logs `N B in / M B out` plus the failure reason, and a
  refused write aborts the sweep instead of being retried four more times.
- **Baud fallback.** `EspRom.connect()` sweeps `BAUDS = {115200, 57600, 230400,
  460800, 74880}` until the bootloader answers, resetting between rounds. 115200
  gets the full three rounds and 12 syncs; the alternates get two rounds and six
  syncs each, so a totally dead link costs ~20 s instead of a minute. The rate
  that answered is remembered for the rest of the session (`baudRate()`), because
  the ROM measures the sync packet and then stays on that rate — a handshake at
  57600 keeps working at 57600. `UsbSerialCh340.changeBaudRate()` reprograms the
  divisor and flushes. This only helps when the bridge's crystal is far enough off
  that the default divisor garbles the line; a wrong cable still reads zero bytes
  at every rate, and the ROM sends nothing until it accepts a sync, so silence is
  not proof of a dead link.
- **Proof of life before the protocol.** `detectPolarity()` listens at 115200
  and 74880 baud, in both DTR/RTS polarities, for boot text after a plain reset.
  Only *readable ASCII* counts (`textPreview()`): on the first hardware run a
  byte-count check declared victory on 234 bytes that turned out to be noise, and
  the wrong polarity it locked in held the chip in reset for the whole sweep.
  Noise is now reported as noise, and the failure line says what each case leaves.
  Boot text was unreachable by construction before: `hardReset()` ended the probe
  and the port closed before anything could read it.
- **A banner must be earned.** `listenQuiet()` reads 400 ms with the lines
  untouched before the first pulse. The 31 October logs quoted a full banner with
  no way to tell a fresh reset from the bytes of the board's own plug-in boot,
  which is exactly the ambiguity that sends the next test at the wrong subsystem;
  those bytes are now drained and reported separately. The same logs settled a
  second rule: every reset prints a ROM banner (download mode's ends with
  `waiting for download`), so a sync round that reads **0 B** means the chip never
  restarted. Each such round now says so, and the failure line blames the reset
  line instead of the packet.
- **One line change per control transfer.** esptool calls `_setDTR` and `_setRTS`
  separately, 100 ms then 50 ms apart; this app wrote both bits in a single
  transfer. On 31 October the only pulse the board ever answered (396 B,
  `rst:0x1 (POWERON_RESET)`, `boot:0x13`) moved RTS alone, while all eleven rounds
  — which moved DTR and RTS together — read zero bytes at every rate. The app now
  drives the lines esptool's way and logs what each pulse itself produced, before
  any sync packet is sent. The same phone, cable and board flash successfully from
  ArduinoDroid, so the adapter and the board's reset circuit are known good and
  the difference is in this app, not in the hardware.
- **Manual mode resets the chip; standing back was the wrong idea.** The first
  version left DTR/RTS untouched and told the user to tap the board's reset, so
  the app's own handshake contained no reset at all and every round ran against
  whatever state the board happened to be in. It now pulses EN once per round and
  never drives IO0 low, so the BOOT button alone chooses the strap, each round
  reports what its own pulse produced, and the button and its note read MANUAL
  BOOT. That pulse writes **both** lines, `lines(false, true)` then
  `lines(false, false)`: on 31 October a DTR-only pulse read `0 B` at all five
  rates while the both-lines pulse of the phase before it printed a banner on
  every round, which is what a pair of reset transistors does when only one of its
  two inputs moves.
- **The protocol has host tests, and they are not optional.** `test.sh` compiles
  `EspRom.java` against a stub `UsbSerialCh340` and asserts framing, the `0xDD`
  sync checksum, the handshake, opcode skipping, the refused-write and silent-board
  branches, and the flash guard, all on a JVM. The `0xDD` bug was found by reading
  a log, which is not a method; `toolchain.sh` now runs these before Gradle, so no
  APK ships without them.
- **Control polarity alternates between sync rounds**, because an inverted EN/IO0
  pair holds the chip in reset for the whole handshake: no bytes at any rate,
  indistinguishable from a dead link. Round 1 uses the polarity the boot text
  arrived on, round 2 the other, round 3 the first again, and the log names the
  inverted rounds, so the log alone identifies the wiring.
- **Manual reset** (`EspRom.setManualReset`) is the way out when IO0 is not wired
  to the adapter: `connect()` runs the proof of life as usual, then one round per
  rate with `resetOnly()` instead of `enterBootloader()`, so EN is pulsed and IO0
  is only ever driven high. The proof of life used to be skipped here to keep DTR
  still, which also meant this phase inherited whatever polarity the phase before
  it left behind — on 31 October that happened to be the workable one, so the
  inheritance was not the fault, but the blind spot was real. `probeUsb()`
  hard-resets on this path too, so a failed attempt does not leave the ROM in
  download mode.
- **A download-mode banner is its own verdict.** The 13:49 run on 31 October
  printed `boot:0x3 (DOWNLOAD_BOOT(UART0/UART1/SDIO_REI_REO_V2)) waiting for
  download` and still got nothing back from 552 B of sync packets at 115200, then
  nothing at any other rate. `downloadBoot` is now recorded the way `flashBootMode`
  is, and the closing message consults it before every other branch: the loader is
  running, so EN, the strap, the cable and the receive path all work and the packets
  are not arriving. The message does not send anyone after a wire: the deck's CH340
  is on the board (HOWTO step 1 is an OTG adapter and a data cable into the CYD's
  own port), so both data directions are the board's own traces. It asks for a
  second host — a PC flashing over the same cable — because that is the only test
  left that separates this app from the bridge. It also no longer sends the next
  bench session to press BOOT again, which the previous wording would have.
- **A quiet sync phase runs before the first reset.** `connect()` sends
  `QUIET_SYNC_ATTEMPTS` (3) sync packets with no `lines()`, `io0()` or `en()` call
  at all, because the ROM stays in its loader until something resets it: a chip
  left there by an interrupted run, by another tool, or by an earlier probe of this
  app answers packets alone. Every other phase resets first, so a board whose EN or
  IO0 leg is not wired to the bridge could never show an awake loader that way. A
  success is logged as `synced at … baud with no reset`, and it means the packets
  arrived — which is the fact the 13:49 log could not establish.
- **SYNC ONLY on the BOARD tab is the same packet, alone.** `EspRom.pingLoader()`
  sends one sync through the quiet phase and returns whether anything came back; the
  tab opens the port, traces the attempt and closes it without resetting, so the
  loader state it tests survives the test. `RESTART BOARD` is how that chip gets
  back to its firmware. The failure wording refuses to call silence a fault: on its
  own it is not one, because firmware does not answer sync. The status line also
  says FLASH still needs a PROBE, since `flash()` requires a session that synced
  and read the chip ID.
- **The stub prints a banner when EN is released on either line.** It used to
  inject only from `setDtrRts()`, so the one-line `enterBootloader()` pulse read as
  silence in the tests while the chip answered it on hardware. The download-mode
  check surfaced that: its round line had no banner to quote.
- **Swapped control lines** (`EspRom.setSwappedLines`, the **LINES** button) put EN
  on DTR and IO0 on RTS. Inverting both lines, which the polarity rounds do,
  cannot fix a crosswired pair: each line only becomes its opposite, so reset is
  still released with IO0 high. That case reads exactly like a dead cable in the
  log, so it is a user-visible toggle instead of a guess hidden in the handshake.
- **The sync packet carries esptool's checksum.** `sync()` sent zero; esptool
  4.5.1 sends `0xEF` XOR every payload byte, which is `0xDD` here because the 32
  `0x55`s cancel in pairs. The ROM accepts the sync either way, so this closed a
  difference from the reference implementation rather than a proven fault.
- **The banner is kept whole.** `PREVIEW_BYTES` (32) still caps the hex preview,
  but `listenAfterReset()` raises `previewCap` to `BANNER_BYTES` (600) and
  `bannerText()` logs the window as one line. The 30 October run quoted only
  `ets Jul 29 2019 12:21:46` because the boot-mode line sat past the 32nd byte;
  the ROM's date and `boot:0x13` versus `boot:0x3` are what identify the chip and
  whether IO0 took effect.
- **Theme**: the compress sheet the owner pasted — `#3f4e96` as the field every
  screen sits on, `#d9d9d9` as the band the top bar and every primary action use,
  black as the ink on that band, and the band grey's own alpha steps where the INK
  theme kept a ramp: `GOOD` .78, `DIM` .70, `VIOLET` .66, `EDGE` .26, with `PINK`
  the single value above the band at `#e8e8e8`, so a status still separates by
  luminance rather than hue. A card is a **hairline cell on the field** now
  (`Ui.CARD` is the field), which is how the sheet draws its grid; the surfaces
  that need a fill of their own — a secondary action, a chip off, the search
  field, the drawer's board row — use `Ui.CARD_HI`, a 16 % black wash. The live
  pill is the one filled shape on the band: black fill, band-grey text.
  Monospace did not move: the deck is a terminal, so the font stayed.
- **The ramp has a floor, and it is measured.** `Palette` holds the values (so the
  host suite can read them without Android) and `Contrast` does WCAG 2.1 arithmetic
  on them; `HostTests.contrast()` walks every ink the app draws and holds body ink
  to 4.5:1, hints and status words to 3:1, and a hairline to 1.5:1 as a line rather
  than a word. Its first run found `VIOLET` at 2.48:1 — the sheet's own .48 — so the
  ink moved to .66 (3.32:1) and `DIM` to .70 (3.52:1), which keeps the order
  FLASHED 5.43 > WORKING 3.97 > PARKED 3.56 > WANT TO TRY 3.32. The check also
  refuses a deliberately too-quiet ink, so a future palette cannot pass by being
  unreadable.
- **Probe always restores the board.** `probeUsb()` calls `hardReset()` in both
  paths, success and failure, because leaving the ESP32 in download mode is
  exactly the black-screen-with-red-LED state a user reads as a brick. The same
  reset backs the **RESTART BOARD** button.
- **Write path** is `EspRom.flash(offset, image, progress)`: `FLASH_BEGIN`
  (erase up front, 4 kB blocks), `FLASH_DATA` per 4096-byte block with a 0xEF
  seeded XOR checksum and three attempts, then `SPI_FLASH_MD5` compared against
  a locally computed digest before `FLASH_END` reboots. Anything that fails
  before `FLASH_BEGIN` is accepted leaves the old firmware untouched.
- **Offset is 0x0 on purpose**: imports are treated as merged images, which is
  what every vendor ships for a browser installer. An app-only image at 0x0
  boots to garbage, so the confirmation dialog says so before erasing.
- **Still not wired to the bundle**: the APK carries the deck's `.ino` source,
  not a compiled `.bin`, so flashing runs on imported images only.
- **The monitor and the flasher share one port.** A `FLASH` / `MONITOR` mode row
  at the top of the tab persists its choice (`monitor_mode`), and the flash paths
  call `monitorBusy()` and refuse while the reader holds the CH340 open instead
  of racing it. Choosing FLASH calls `stopMonitor()`, which clears
  `monitorRunning` so the reader closes the port within one 200 ms read.
- **The reader owns the connection.** `runMonitor()` runs on a `usb-monitor`
  thread; a baud change from the spinner and **RESTART + WATCH** set flags the
  loop acts on between reads, so the UI thread never calls into a connection that
  is mid-transfer. A rate change reprograms the divisor through
  `UsbSerialCh340.changeBaudRate()` and logs `== <rate> baud ==`; a rate the
  bridge refuses is written into the transcript instead of ending the session.
  `stillAttached()` checks the device list every 15 quiet reads, so unplugging
  says `Board unplugged.` rather than looking like silence.
- **The transcript is decoded, capped and coalesced.** `decodeInto()` holds back
  a UTF-8 lead byte when a glyph is split across reads; `clean()` keeps printable
  text, tabs and newlines (a lone `\r` becomes one) and drops other control
  bytes, because a ROM dump is not a terminal. `appendMonitor()` keeps the last
  `MONITOR_CHARS_MAX` (24,000) characters, trimmed at a line break, and
  `scheduleMonitorUpdate()` repaints at most every 120 ms — a chatty sketch would
  otherwise rebuild the view dozens of times a second.
- **Unverified**: the monitor has never opened a real CH340. Baud switching while
  streaming, the decode holdback and the unplug check are untested on a phone.
- The manifest declares `android.hardware.usb.host` as *not required*, so installs
  on phones without OTG still work; the card then reports that USB host is absent.
- **Partially verified on hardware.** Two probes ran on a real phone (Android
  17, CH340 `1a86:7523`). The first saw `0 B` at all five rates and could not say
  whether the board was silent or the bytes never left. The second, with counters,
  showed **552 B out per round** (46 B × 12 syncs — the send path is proven) with
  **0 B in**, plus **234 B of non-ASCII at 74880** after a plain reset. Bytes at
  the wrong rate that are not text mean the chip runs when released but says
  nothing at 115200 during the sync rounds, which is the signature of a polarity
  holding it in reset — what the alternating rounds now test. A clean `ESP32
  answered its bootloader` has still not been observed.
- **Unverified**: the write path has never run against real flash. Framing and
  block sizes are taken from esptool, but no erase or MD5 check has completed on
  a board.

- **One PROBE press covers all four wirings**, as its third step now — see *Probe
  order* at the end of this file for what runs before them. Trying a crosswired adapter
  used to mean flipping **LINES**, pressing PROBE, waiting out a silent sweep and
  flipping it back; `EspRom.connectAll()` runs `connect()` four times — normal and
  swapped lines, each with the reset driven from the app and with EN pulsed while
  BOOT is held — and stops at the first that syncs. Each phase resets the per-round
  counters and `bannerSeen` so one wiring's rounds cannot be counted as another's,
  and a `WriteFailure` is rethrown rather than retried, because a bridge that
  refuses bytes fails every wiring. The tab then persists the combination that
  answered, which also stops a toggle left `SWAPPED` from sabotaging the next
  probe, and the closing `hardReset()` runs on the manual paths too so the board
  always comes back out of download mode.
- The manual phases log `hold BOOT down now` and pause 2.5 s first: no software
  can press that button, and phases 3–4 only answer while it is held. The sweep is
  **untested on hardware**; what is tested is that it stops at wiring 1 when the
  stub answers and that a silent stub reports all 4 wirings tried, with the host
  suite covering both.

- **A flash boot is its own verdict.** The 31 October run ended with every round of
  wiring 3 printing a banner, and the banner reading `boot:0x13
  (SPI_FAST_FLASH_BOOT)`: the chip restarted into its firmware, so EN, the cable
  and the receive path all worked and IO0 was high as it left reset. The old
  closing message guessed "the packet or the rate", which sends the next attempt
  in the wrong direction. `bootMode()` now lifts the ROM's `boot:0x..` field out of
  the banner, `flashNote()` puts it on the round line, and the verdict in the
  failure message is that the strap never took — hold BOOT, or find where the
  adapter's IO0 leg stops reaching the board.

## Crash reporting

- `CrashLog` installs a default uncaught-exception handler from
  `MainActivity.onCreate` and writes the trace to `filesDir/last-crash.txt`,
  then hands the exception to the platform handler so the process still dies
  normally. It never swallows a crash.
- **SETUP** shows a `LAST CRASH` card (with COPY TRACE and CLEAR) whenever that
  file exists, because there is no adb on the phone this is developed against.
- `ProjectsTab.refresh()` also wraps `build()` and renders a `SHELF FAILED TO
  DRAW` card with the trace instead of taking the app down, so a rendering bug in
  the shelf stays readable rather than fatal.

## Naming and tabs

- The app is **Off-site** (`android:label`, header title). The package stays
  `dev.phonedesk.companion` and the APK stays `dist/PhoneDeckBridge-debug.apk`, so
  an upgrade replaces the installed copy instead of sitting beside it.
- Three drawer sections: **PAIRED** (live link), **BOARDS** (the notebook: status
  chips, cards, deck libraries) and **SERIAL** (target picker, USB probe/restart,
  images, flashing and the serial monitor), with **SETUP** behind the gear in the
  top bar (permissions, bridge switch, last crash).
- `BoardTab` owns every call that touches the ESP32: `UsbSerialCh340`, `EspRom`,
  the USB permission receiver and the flash progress. The project shelf never
  opens the port, which is what lets each tab keep a single job.
- `Actions` holds the shared toast/clipboard/link/save helpers so the two shelves
  do not each carry their own copy.
- Catalogue and card copy was cut back to one or two lines per field; the details
  live in the links and the log.

## Wire protocol

Identical to the sketch, checked against its `onWrite` handlers:

- Service `6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10`
- Time `6f2d0002-…` — 7 bytes: year−2000, month, day, hour, minute, second,
  weekday. The deck requires seven bytes but ignores the last one; the app sends
  `Calendar.DAY_OF_WEEK` to fill it.
- Battery `6f2d0003-…` — percent, then `1` while charging or full
- Notification `6f2d0004-…` — `App|Title|Text`, UTF-8, clamped to 120 bytes
  (the sketch buffers 160)
- Identity `6f2d0005-…` — read only: the one-line JSON `DeckIdentity` parses
- Now playing `6f2d0006-…` — `state|position|duration|title|artist|album`, whole
  seconds, written once a second from `NowPacket`; `stop` clears the card

## Design decisions

- **Unfiltered scan, match afterwards**: the deck advertises the UUID, but the scan
  response can be dropped, so name matching is kept as a fallback.
- **One write in flight**, `ArrayDeque` plus a 1.5 s watchdog that unsticks the
  queue when a callback never arrives.
- **Two write paths**: `writeCharacteristic(ch, value, type)` on Android 13+,
  `setValue` + `writeCharacteristic(ch)` below.
- **Foreground service**, type `connectedDevice`, so scanning survives the screen
  going off. Android 14 needs the matching permission, which is declared.
- **`toolchain.sh` owns the build.** Reconstructing JDK 17, the SDK and Gradle by
  hand in a fresh sandbox was the slowest, least repeatable part of every session,
  and nothing in the repo recorded which versions produced the shipped APK. The
  script pins JDK 17, Gradle 8.7 and build-tools 34.0.0, installs them under one
  deletable directory, and treats the build as unfinished until the APK has been
  checked for signature, alignment, package and bundled sketch.
- **`clean assembleDebug` is the default there**, because a warm `assembleDebug`
  reports `BUILD SUCCESSFUL` with every task `UP-TO-DATE` and compiles nothing.
  The script also fails if `compileDebugJavaWithJavac` is `UP-TO-DATE`, so a
  green run means javac actually ran.
- **Notifications**: 5 s duplicate suppression, group summaries and quiet ongoing
  entries skipped, `CATEGORY_CALL` kept.
- **Auto-start on `onResume`** once notification access and Bluetooth are granted.
- **The bootloader handshake narrates itself.** `EspRom` takes an optional
  `Trace`; `BoardTab` keeps the last 80 lines and renders them as PROBE LOG.
  Lines come from the code that does the work, not from the UI guessing: the port
  opening, one line per baud rate with its own byte count, the first 32 bytes as
  hex when a rate stays quiet, chip magic, MAC, block milestones and both MD5s.
  Per-rate counters matter — a total across five rates cannot tell you which rate
  the board was talking at, and that is the whole question when a sync fails.
  COPY LOG puts it on the clipboard; nothing in it needs a debugger.

## Verified

- **The host suite takes 1.45 s, not 179 s.** `EspRom.Clock` is the seam: every
  deadline and every pause reads the clock, `test.sh` installs a virtual one and
  the stub port advances it, so the two checks that ran the real probe against a
  silent board — 57 s each — still try all five wirings and both windows. Measured
  on 2026-11-02: 179 s before, 1.45 s after, the same 126 checks then, 152 now green then, 135 now. `./check.sh`
  runs the fast set, `./check.sh --full` the release set.
- **The checkers' refusals are tested too, without a compiler.** Eleven
  `selftest_*.py` files build a throwaway repo — a stand-in `arduino-cli`, a
  stand-in `capture.sh`, a real `git init` — and make each gate refuse what it
  exists for: 137 branches over the eleven, from a stale flash size, a
  `HISTORICAL` size that must still pass, short and full md5s, a renamed link, an
  `ELSEWHERE` name, a moved source count and an undated reading, through asset
  drift and a descriptor naming a gone sketch, the branch verdicts (a patch
  already upstream, the same bytes renamed, a folder a merge would resurrect, a
  pushed alias), to a render whose pixels moved, a capture that wrote nothing, a stale screen
  count, an archive that rebuilds differently, two sketch images that disagree and a
  root whose `env.sh` sets only `PATH`. Measured on 2026-11-03: 3.0 s, 0.5 s, 0.5 s,
  1.6 s, 1.4 s, 0.9 s, 0.7 s, 0.3 s, 1.4 s, 0.3 s and 0.4 s — all eleven in
  **11.2 s** through
  `./check.sh --selftest`, and the pre-commit hook runs it when a
  `check_*.py` or `selftest_*.py` is staged. That is the gap it closes: the three
  newest docs refusals were found by a reader, because nothing had ever run them.
- **The committed tree builds on its own.** Measured on 2026-11-02: `git archive
  HEAD` into an empty directory plus a fresh `git init` — no built assets, no
  `.build_figures.json`, nothing in `dist/` the commit does not hold — passed the
  fast gate, compiled all 34 sources (**152 host checks**), and `toolchain.sh`
  built the APK to **180,126 bytes**, sha256 beginning `dd5b475f`; the gate was green
  afterwards, widget bundles and recorded bytes included. The docs step took
  **123 s** on that first run and ~1 s after, because `.build_figures.json` is a
  local cache: a fresh tree pays for both compiles once. A `git clone` of this
  worktree cannot be made on this host — git refuses a linked worktree as dubious
  ownership — so the export carries the same committed bytes by another route.
- **The two chats are one tree again.** Measured on 2026-11-02:
  `git merge-base --is-ancestor b3e8362 HEAD` exits 0, the deck branch holds no
  commit this branch lacks, and neither the short-named ref nor origin's copy holds
  anything either — the merge of `b3e8362` was already complete. What was wrong was
  the reading: `check_branches.py` compared against `origin/main`, which lagged both
  chats because nothing was pushed, so merged work kept coming back as "10 commits on
  top of origin/main, 11 paths it has no bytes for". It now compares against the
  branch checked out here, and a self-test case pins that a merged branch stays
  quiet even when `origin/main` is deliberately left behind.
- **A deck's tap now reaches the phone's player.** `DeckService` finds
  `6f2d0007-…` on the link, subscribes with `setCharacteristicNotification` and a
  CCCD write queued beside the other GATT operations, and both
  `onCharacteristicChanged` overloads hand the bytes to `LinkCommand` — plain
  Java, so `test.sh` covers the parser: the sketch's 20-byte buffer, the three
  actions, a positive uint32 serial, and a repeated serial counting as one tap.
  `NowPlaying.run` then presses the key on the session it is already mirroring.
  `test.sh` reads **all 152 host checks passed**, seventeen of them new; the APK
  rebuilt to **180,126 bytes**. **Nothing has run on a phone or a board**, so
  whether a chip's notify arrives and moves playback is unverified end to end.
- The last `javac 17` type check against the Android 14 (API 34) framework
  classes (`android-all`) covered the pre-split sources: 9 files, 22 classes, only
  deprecation notes from the pre-Android-13 `setValue`/`setWriteType` path.
  **That check predates the split into sections.** The current 34 sources are
  covered by `gradle assembleDebug` instead, which is what produced the shipped
  APK.
- Every relative Markdown link across the repo resolves.
- `gradle assembleDebug` (Gradle 8.7, AGP 8.6.1, SDK 35, JDK 17) builds a debug
  APK at `dist/PhoneDeckBridge-debug.apk` — 102,590 bytes as measured on the file
  in `dist/` after a `clean assembleDebug` on 2026-10-30, for the Off-site build;
  the artifact in `dist/` now is the one rebuilt on 2026-11-02 after the owner's
  mark moved out of the drawer and onto the top bar's centre line at 26dp, which
  followed the sketch header change, the probe work, the drawer's rework and the
  deck's sign-in — its exact bytes are the line further down this section — against
  the 112,473 that the same work produced before the second widget joined it. The two
  halves were measured on their own branches the same day before they met —
  146,290 bytes for the drawer alone, 149,494 for the sign-in alone — and this
  figure is the tree that has both.
  The earlier 128,924-byte APK unpacked to the same six entries with the same
  uncompressed sizes (211,027 vs 211,019 bytes), so the 31 kB difference is in how
  the archive was written, not in the code: both are `zipalign -c 4` clean and
  verify with the same v2 signature. `syncWidgets` ran: both descriptors and the
  sketches they name are inside the APK, `assets/widgets/phone_deck_cyd24R/` and
  `assets/widgets/spotify_deck_cyd24R/`, and `check_widget_assets.py` in the repo
  root compares those copies with `../<folder>/<sketch>.ino` byte for byte (phone
  deck md5 `62c7e6c3…`, Spotify `3b17f04a…` — `f234b68b…` before the 2026-11-02
  header rewrite, and `00bb1560…`, `eca02e93…`, `49f29bcb…` before the Bluetooth
  link, the callback change and the one-line answer — both in sync on 2026-11-02).
  The rewrite makes the phone-over-Bluetooth source the deck's first one and
  Spotify's own Web API the optional half; it arrived by merging the deck chat's
  branch, and the APK in `dist/` was rebuilt against it. That sketch also
  **compiles here**, measured on 2026-11-03 once `~/.arduino-cyd/env.sh` was
  sourced — `check_docs.py`'s gate built it on the `huge_app` partition and read
  1,878,645 B with 69,092 B of globals, beside the phone deck's unchanged
  1,168,314 B / 45,244 B. That script pins the root's own dirs, so the build cannot
  fall through to `~/.arduino15`, where the same sketch read 1,878,613 B — 48 B
  under with the globals identical, the build-root sensitivity the docs record.
  The deck chat's root reads 1,878,661 B, 16 B over. Neither board has run it.
- **The Spotify deck's sign-in finishes here.** `SpotifyCallbackActivity` is
  exported for `phonedesk://spotify-callback`, and `aapt2 dump xmltree` on the
  shipped APK shows the VIEW/DEFAULT/BROWSABLE filter with scheme `phonedesk` and
  host `spotify-callback`. It now makes the deck's one request itself —
  `http://<deck>/code?redir=app&code=…&json=1` — on a worker thread with a 5 s
  connect and a 30 s read timeout, and shows the deck's answer; a 409 is reported
  as a refusal and silence falls back to the browser. That made `INTERNET` and
  cleartext real requirements, so `toolchain.sh` now fails a build that lacks
  either instead of failing a build that has `INTERNET`, and its `aapt2 dump
  xmltree` check is what enforces the cleartext flag. `./toolchain.sh` passed end
  to end on 2026-11-01: `all 61 host checks passed`, as the suite stood then — it
  is 126 now that the probe order, the buttons' wiring, the remembered rate and
  the deck's tap path have their own checks (fifteen for the sign-in —
  nine for `SpotifyLink`'s parser, six for the address it builds, including a `+`
  escaped as `%2B` rather than a space), `BUILD SUCCESSFUL`, v2 signature,
  `zipalign -c 4`, 165,242 bytes on the merged tree (149,494 for the sign-in
  alone, before the drawer's rework joined it). **Nothing has run on a phone**, so the chooser
  gesture, the request to a real deck and the fallback are unverified.
- `./toolchain.sh` ran end to end from an **empty install root**
  (`OFFSITE_HOME=/tmp/offsite-fresh`) on 2026-10-30: it downloaded Temurin JDK
  17.0.21, Gradle 8.7, commandline-tools 11076708, then platform 34 and
  build-tools 34.0.0, and finished in under a minute. Build ran 35 tasks with
  `clean`, and the script's own checks passed: signature, `zipalign -c 4`,
  package `dev.phonedesk.companion`, minSdk 26, targetSdk 34 (35 now), and the
  bundled sketch's MD5 equal to the repo file. That run predates the rename to
  `phone_deck_cyd24R/` and the second widget, which is why it names one sketch.
- **Bundled content is now a property of the descriptors, not of a list in the
  build.** The shipped APK carries **two** widgets,
  `assets/widgets/phone_deck_cyd24R/` and `assets/widgets/spotify_deck_cyd24R/`,
  each a descriptor plus its sketch; adding `cyd_board_check` to a build under the
  previous list-based task was what proved two sketches could coexist, and that
  experiment is historical. What is general is the mechanism: a folder with a
  descriptor and a sketch, nothing to edit in Java or Gradle.
- A fresh-toolchain run of unchanged sources produced **97,769 bytes** against
  the then-committed 97,757, which is what dex build-time data looked like until the
  cause turned out to be AGP's Dependency Info Block; the fix is below. The probe changes shipped **99,533 bytes**, the flash-shelf
  changes **99,757 bytes**, the widget-descriptor change **102,590 bytes**, and the
  merged firmware plus the probe work **101,029 bytes**, and the icon, allowlist
  and edge-to-edge work **112,473 bytes**; the artifact in `dist/` now measures
  **202,353 bytes** on 2026-11-03, the rebuild carrying two bundled sketches, the
  drawer that names boards rather than projects, the sign-in that finishes in the
  app, the mirror, the three-step probe, the owner's logo as the icon, the mark
  centred on the top bar at 26dp as one PNG per density rather than above the
  drawer's wordmark, the gear and chevrons drawn as shapes, and the shelf that
  adds, edits, deletes and attaches a project and imports a file from its own
  picker. That is 20,115 bytes above the previous 182,238, and the rebuild overwrote
  that file, so the difference is not itemised here.
- **The archive is byte-reproducible now, on one machine.** AGP stamps a
  *Dependency Info Block* into the APK Signing Block and fills it differently on
  every build — same sources, different bytes. On 2026-11-03 two builds of this
  tree differed in 179 bytes of that block and nowhere else: the same 21 entries
  in the same order, the same bytes, the same v2 and v3 signatures (`0x504b4453`,
  AOSP issue 268071369). `toolchain.sh` now rewrites the archive with `zipalign`,
  which drops the signing block, and signs it again from the pinned debug key,
  whose RSA signatures are deterministic. Two full runs then shipped the same
  file, byte for byte: **202,353 bytes**, 4,367 of them the bigger signing
  block that re-signing writes. A different keystore signs different bytes, so
  reproducible means this toolchain and this key; `OFFSITE_KEYSTORE` overrides
  both halves. Quote the size to the nearest kB (198 kB), never as a checksum.
- **The release build type runs, and produces an unsigned archive.** Measured on
  2026-11-03: `gradle assembleRelease` exited 0 in 17 s, ran 43 tasks including
  `lintVitalRelease`, and wrote
  `app/build/outputs/apk/release/app-release-unsigned.apk` — versionCode 1,
  minSdk 26, targetSdk 35, and `apksigner verify` refusing it because `release`
  has no `signingConfig`. So a release is one signing step away rather than
  untested, and it needs a key of its own: signing it with the committed debug key
  would ship an identity whose password is in the tree, which is what
  `OFFSITE_KEYSTORE` exists to replace. The archive is 191,798 bytes (187 kB),
  about 10 kB under the debug APK because `debug` writes the larger signing block;
  both carry the same bundled sketches. That archive is build output rather than a
  kept file: the next `toolchain.sh` run starts with `clean` and takes it with it,
  and it is not committed anywhere, so it is rebuilt with `gradle assembleRelease`
  when it is wanted and checked again then.
- **The signing key ships with the tree.** `android-companion/keystore/debug.jks`
  is the project's own debug key — alias `androiddebugkey`, password `android`,
  RSA 2048, `CN=Android Debug` — committed so that two machines and two runs sign
  the same bytes, which is what makes the reproducibility above mean something off
  this VM. It is a debug key for a sideloaded app rather than a release identity,
  and `OFFSITE_KEYSTORE` overrides it for a real one. One consequence to know:
  every build before this was signed with whichever machine's debug key made it,
  so installing this APK over one of those fails with a signature mismatch until
  the old app is uninstalled.
- `aapt2 dump badging` reports package `dev.phonedesk.companion`, versionCode 1,
  minSdk 26, targetSdk 35, the expected permission set — now including
  `INTERNET`, the one outbound request to the deck —
  `application-icon: 'res/BW.xml'` (resource names are shortened) and
  `MainActivity` as the launchable activity. `SpotifyCallbackActivity` is the
  only other exported component and its filter is the custom-scheme one.
- **The build now assumes someone else may install it.** It ships an icon, a
  per-app notification allowlist and a `targetSdk 35` target. `toolchain.sh`
  installs platform 35 and build-tools 35.0.0 and asserts the two things a listing
  checks first: `targetSdkVersion:'35'`, and a non-empty `application-icon`.
  AGP moved 8.5.2 → 8.6.1 for it, which still runs on Gradle 8.7.
- The icon is one adaptive vector carrying the owner's own logo, not a redraw:
  `ic_launcher_foreground.xml` holds the artwork's four path strings verbatim
  (Android pathData is SVG's grammar) inside a `<group>` that only scales and
  centres them to the 72dp safe zone, so a launcher mask can crop the disc's outer
  edge but never the ghost inside it. The artwork is black ink on a white ground: a
  disc with the ghost cut out of it, and the ghost's eyes and mouth back in ink, so
  `ic_launcher_background` is that white ground rather than the app's black — the
  ghost only reads as a ghost against it. The bar's `logo_mark` is not that vector
  but a PNG per density, cut from the same file with the canvas's background
  removed: the artwork is one black spiral, the ghost sits in the gap between its
  turns, so every white pixel is canvas and the mark is the ink alone — the band
  fills the gap and the ghost reads as its absence, eyes and mouth still ink. It
  sits on `TopBar`'s centre line, between the crumb and the pill, because the band is
  the light ground the file was drawn on and the field is not — the crumb and the
  pill's group take equal halves of the spare width, which is what holds it there; `DrawerHome` stopped drawing it above the wordmark
  and gave that air back. Keying the white from the border left a white notch beside
  the mark's shoulder, keeping the white the ink encloses left a white plate behind
  the whole mark, and drawing it on the field left a black mark on blue: all three
  shipped here and all three were rejected by the owner looking at them. The eyes are
  about 8% of the artwork's width — 2px at mdpi, 6px on a 2.75x phone — so the mdpi
  capture is the harshest view. At 26dp the mark is shorter than the pill's 27dp, so
  the pill sets the bar's height and the header is only as tall as the link state. The monochrome variant is those same four subpaths with `evenOdd`,
  folded into one shape so the ghost and its marks survive being flattened to one
  tint. There is no bitmap density bucket, which is safe here only because
  `minSdk` is 26 — every device this installs on supports adaptive icons.
- `NoticeFilter` answers the oldest objection to this app: notification access
  exposes everything. It stores one boolean per package and filters in
  `onNotificationPosted`, and the manifest declares the LAUNCHER intent in
  `<queries>` because Android 11 would otherwise show an empty picker. Filtering
  starts only after the first SAVE, so an upgrade does not silently stop
  forwarding.
- Rotation is declared handled
  (`orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden`) rather
  than reloaded, and USB ownership moved out of the tab into a static claim:
  flash, probe, restart and monitor all claim `portOwner` before touching the port
  and release it in a `finally`. That is what makes BACK-out-and-return during a
  flash safe — the new tab reads `The port is busy (flash)` instead of writing a
  second image over the first. Flash progress is mirrored in statics and the
  finished sentence is handed to whichever tab renders next.
- `targetSdk 35` makes Android 15 draw every window edge to edge, so
  `MainActivity.applySystemBarInsets` pads the header and tab bar by the system
  bar insets **on 35 and above only**; below that the window still fits the bars
  and the padding would be doubled.

## Probe order: the loader, then BOOT + RESET, then the wirings

The 31 October and 1 November logs made one thing plain: every pulse was followed
by `boot:0x13 (SPI_FAST_FLASH_BOOT)`, so EN reset the chip and IO0 was high as it
left reset. The four-wiring sweep never addressed that — all four wirings drive
EN, so a board whose reset pair reaches nothing fails four times the same way,
which is what "552 B out, 0 B in" looked like on every round of every wiring.

PROBE and FLASH now walk three steps instead:

1. **Listen, touching nothing.** Packets only, which is all a chip already in its
   loader needs. If it answers, no reset and no wiring took part.
2. **BOOT + RESET by hand.** The log asks for BOOT held, RESET tapped, then BOOT
   released, and sends nothing for 20 s while it polls. The strap is a finger's and
   the reset is the board's own button, so the adapter's EN and IO0 legs are out of
   the circuit entirely — the one entry path a broken reset pair cannot break.
3. **The old four-wiring sweep**, unchanged, as a fallback for a board whose
   buttons cannot be reached.

`Sweep.phases` is 0 when step 1 or 2 answered, so the status line says
`BOOT + RESET · no wiring needed` instead of naming a wiring that never ran, and
the failure message counts BOOT + RESET before the wirings.

**MANUAL BOOT and LINES: SWAPPED are step 3's starting point**, not a setting the
sweep overrides. Whichever wiring those two buttons name is tried first and the
other three follow, and PROBE and FLASH both read them — before this, `connectAll()`
cleared both flags and the buttons changed nothing about a probe. The log says
which wiring the buttons selected before the sweep starts, and PROBE still adopts
the wiring that answered, so the button a board taught the app survives into the
next press.

FLASH calls the same three steps before it writes: entering the loader is the hard
part, and it does not get easier because an image is waiting. The confirm dialog
says to keep the board in reach for the buttons.

The host checks cover the new order — an awake loader taken with no line changed,
a step-2 answer after three silent packets credited to the hand rather than to a
wiring, and a silent board still reporting all four wirings at the end. **None of
the three steps has run on a phone since the change**; the last hardware run is
the one recorded above, before it.

**The rate is remembered too.** A handshake that answered at anything other than
115200 is kept in the tab's prefs (`probe_baud`) beside the wiring, because a
bridge whose crystal is off by enough only ever syncs at one rate; PROBE and FLASH
start there. Steps 1 and 2 ask at the remembered rate as well—otherwise a board
that only speaks 460800 would sit out step 2's whole window for a packet the
adapter cannot hear—and the other four rates still follow if it stops answering,
so a stale value costs one attempt rather than the probe. Before this the rate was
printed in the status line and dropped.

`./toolchain.sh` passed end to end on 2026-11-02 with the header mark in place, the
buttons still feeding the sweep with the rate remembered, and the tap path wired:
`all 152 host checks passed` (117 before those nine), `BUILD SUCCESSFUL`, v2
signature, `zipalign -c 4`, both bundled sketches in sync, **180,126 bytes** in
`dist/` — 356 bytes above the build before, which is the clock seam: `EspRom.Clock` and the
pause it routes, with the probe's logic untouched. The sketch did not move in this
rebuild.

The same command ran again after the mark was centred at 26dp, from a `clean`:
`all 152 host checks passed`, `BUILD SUCCESSFUL in 18s` with
`compileDebugJavaWithJavac` actually running, v2 signature, `zipalign -c 4` clean,
both bundled sketches byte-identical to the repo, all five mark densities matched
inside the APK, and `dist/` rewritten to the size recorded above. Nothing was
installed afterwards — no phone and no board has seen this build.

**The mark's five densities are now a checked property of the archive.** AGP
shortens resource paths, so the mark lands as `res/-S.png` rather than
`res/drawable-mdpi/logo_mark.png` and a `grep` for the name finds nothing — which
is exactly how this went wrong: a reader who checks that way concludes the mark is
missing when it is there, and neither of us notices a stale density. The step
reads every `res/*.png` out of the APK, hashes it, and requires each of the tree's
five files to be among those hashes, naming the density and its byte count when one
is absent. Run as a copy of that block over a copy of the APK whose mdpi entry was
swapped for the hdpi one, it refuses: `error: drawable-mdpi/logo_mark.png (796
bytes) is not in the APK`; over the real archive it prints all five and exits 0.

## Where the renders live

The nine frames and the chat strip are published as a static site, because a link a
person can tap is what the render rule in `CONVENTIONS.md` asks for and a workspace path
opens nothing. The strip is drawn by `publish_renders.py` from those same nine frames on
every run, so it is never a picture of an earlier capture.

| What | Where |
| --- | --- |
| Gallery (a scroll, one screen under the next) | https://site-c8050e9c1c3546fc9a5dfb219430a19b.freebuff.page/ |
| One screen | https://site-c8050e9c1c3546fc9a5dfb219430a19b.freebuff.page/home.png — likewise `home-open`, `projects`, `projects-open`, `projects-edit`, `projects-file`, `paired`, `serial`, `settings` |
| The strip (one row of the nine, for a single message) | https://site-c8050e9c1c3546fc9a5dfb219430a19b.freebuff.page/chat-sheet.jpg |
| Preview copy | https://site-c4870019b7c74299bae95043c45330f8.freebuff.page/ — the same bundle, deployed first and kept for a second look |
| Bundle source | [`artifacts/site/`](../artifacts/site/) (`worker.js`, `manifest.json`), built by [`artifacts/publish_renders.py`](../artifacts/publish_renders.py) into the gitignored `artifacts/site/public/` |
| Site | Freebuff Sites `3f6c1a9e-8b47-4d2c-9f11-5a7e2c4d8b60` — **production published**, preview published beside it |

The worker serves `env.ASSETS` and 404s everything else: there is no route, no state and
no secret on it, and the bundle is a copy of pixels git already holds, so the site cannot
show a screen the app no longer draws. `public/` is ignored by git — the frames beside it
are the source. Nothing in the app reads this host. `capture.sh` rebuilds the bundle every
time it installs frames, so the files the site serves cannot lag a capture; deploying them
is a separate step, and publishing still needs asking.

### What a capture costs

Measured on 2026-11-02, on this machine, per command:

| | Before | After |
| --- | --- | --- |
| One capture (`capture.sh`) | 26 s | **10 s** warm (21 s with a cold daemon) |
| The tall scroll sheet | 1 s | 1 s |
| The published bundle | <1 s | <1 s |
| The gate's own re-capture | 23 s | ~10 s warm |
| **One review loop** | **50 s** | **~21 s** |

The 15 s came from two things, both now in the tree: the test tasks declare that their
side-effect output never makes them current, so `--rerun-tasks` — which re-ran every
compile to redraw screens whose code had not changed — is gone; and the Gradle daemon is
kept instead of restarted per capture, with its JDK passed explicitly because this
environment's default `javaHome` names a path that does not exist. Seven frames came back byte-identical across the change, which is how it
was verified rather than assumed — the app drew seven screens then and has drawn nine
since.

An **emulator was considered and is not the answer here**: this VM has `/dev/kvm` but the
user is not in the `kvm` group, the emulator package and every system image are absent
(~1 GB to fetch), and it would render on a different font stack to produce a *device*
screenshot — a different claim from "what this app draws", which is what the renders are
for. The frames come from the app's own views through Android's renderer, so a wrong
padding still shows.

## Not verified

- The shipped APK is debug-signed with the project key in
  `android-companion/keystore/debug.jks` — a key the tree holds and you can keep,
  not a release identity. `assembleRelease` itself has run (see Verified); what has
  not is signing that archive with a key of your own, or installing either build
  over an older debug install.
- **Only the USB probe has run on a phone** (two probes, Android 17, CH340
  `1a86:7523`): tabs, SAF import, MediaStore save, clipboard, RSSI polling and the
  monitor layout are all unproven.
- No second CYD was available, so pinned-deck behaviour is untested against two
  boards.

## Open risks

- **Missing bundled sketch**: a non-Gradle build has no asset, and the BOARDS
  deck card says so. That is expected, not a bug.
- **Stale bond**: a disconnect carrying a non-zero status while the phone holds a
  bond now removes that bond (reflection over `removeBond`, at most once a minute)
  and reconnects, instead of rescanning a link the stack will keep refusing.
  **Unverified on a phone**: the decision is held by three host checks, the
  reflection call itself has never run.
- **Encryption**: the core defaults characteristics to
  `ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE`, so writes need no bond. A write
  refused with insufficient authentication now calls `createBond()` once per
  connection and lets the 1 Hz mirror re-send what it dropped. **Unverified on a
  phone.**
- **Battery optimisation** can kill the foreground service. SETTINGS now asks for
  this app's own exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, with the
  permission in the manifest) and falls back to the list, then to system settings.
  **Unverified on a phone.**
- **Empty notification text**: apps with fully custom layouts used to vanish,
  because neither `EXTRA_TITLE` nor `EXTRA_TEXT` was set. `NoticeText` now falls
  back through `EXTRA_BIG_TEXT`, `EXTRA_SUB_TEXT`, an inbox's first line and the
  ticker, and finally to the app's own name; five host checks hold the order.
  **Unverified on a phone**: the listener has not run since.

## Proving it on hardware

Nothing below has been run. It is the order that produces evidence instead of an
impression, and each step names the line to look for. Write down the deck's tag,
the APK's SHA and the exact line you saw: a step without a line is not a result,
and the deck is readable by everyone in the room, so a step that shows a private
message is also a finding.

1. **Flash the deck.** ArduinoDroid, with the sketch pasted from COPY (or the
   app's Flash tab once its uploader's handshake works). The phone deck takes the
   default partition; the Spotify deck needs `huge_app`, and each upload must
   carry the scheme its own sketch was built for.
2. **Link.** BOARDS, then the board's row. Expect `Connecting to <name>`,
   `Discovering services`, then `Linked to <label>` once the identity document is
   read, with the card's `packet <age>` staying under five seconds. A link with no
   identity read files the deck under its MAC instead — still a pass, but say so.
3. **Mirror.** Post a notification from an allowlisted app. The deck's notice panel
   should show it within a second and the app's `notices` count should rise. An app
   with a custom layout should now show at least its own name.
4. **Tap round trip** — the one thing never tested end to end. Tap play/pause on
   the deck. The subscription is written at link time, so
   `commands: subscription refused (n)` in the log means no chip can ever work; the
   notify is parsed as `<serial>|<action>` and the phone's media session gets the
   key. **The evidence is the deck's next mirror line changing, not the chip
   lighting**: the chip means the notify arrived.
5. **Stale bond.** With a bond left by an older build, connect. Expect
   `Stale pairing forgotten, retrying` and a link within a few seconds.
   `Stale pairing could not be removed` means the reflective `removeBond` is
   refused on this Android build — note the version and forget the entry in
   Settings by hand.
6. **Insufficient authentication.** A write that lands before a bond shows
   `Pairing to write` and one system pairing prompt. If writes are still refused
   afterwards, the `pair` branch was right and the bond is not completing.
7. **Battery.** SETTINGS, then BATTERY SETTINGS, should raise the system exemption
   dialog for Off-site rather than a list to search. Grant it, leave the bridge
   running overnight, and read the card's packet age in the morning: a stopped
   service shows as a link that died hours ago.

## Obvious next work

1. Build the APK in Android Studio, confirm tabs and link, then confirm COPY lands
   a compilable sketch in ArduinoDroid.
2. Start on boot (`BOOT_COMPLETED`) so the deck links without opening the app.
3. Remove deck rows when a notification is dismissed on the phone — needs a new
   characteristic or control byte in the notice payload; the sketch only appends.
4. Per-deck rename/forget in the saved list; the store already keys by MAC.
