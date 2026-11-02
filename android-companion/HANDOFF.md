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
| `TopBar.java` | The crumb, the link pill and the gear: one row, built by `TopBar` so the render host can measure it |
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
- **Not built yet**: creating, editing and deleting projects, and attaching a
  file to a card. The catalogue is the four seeded projects plus anything
  imported into FILES.

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
- **Theme**: the deck's INK palette, copied into `Ui` slot for slot — `#000000`
  ground, `#0e0e0e` panels, `#1c1c1c` lifted panels, `#333333` hairlines, white
  ink, and a grey ramp where the coloured build had blue, violet, pink and green.
  `Ui.PINK` for an error and `Ui.GOOD` for success now differ by luminance, not
  hue, exactly as `batteryColor()` separates the three battery states on the deck.
  Monospace for every text and button (`Ui.text` / `Ui.action`).
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
  on 2026-11-02: 179 s before, 1.45 s after, the same 126 checks green. `./check.sh`
  runs the fast set, `./check.sh --full` the release set.
- **The checkers' refusals are tested too, without a compiler.** Five
  `selftest_*.py` files build a throwaway repo — a stand-in `arduino-cli`, a
  stand-in `capture.sh`, a real `git init` — and make each gate refuse what it
  exists for: 54 branches over the five checkers, from a stale flash size, a
  `HISTORICAL` size that must still pass, short and full md5s, a renamed link, an
  `ELSEWHERE` name, a moved source count and an undated reading, through asset
  drift and a descriptor naming a gone sketch, the branch verdicts (a patch
  already upstream, the same bytes renamed, a folder a merge would resurrect, a
  pushed alias), to a render whose pixels moved and a capture that wrote nothing.
  Measured on 2026-11-02: 1.3 s, 0.4 s, 0.5 s, 1.1 s and 0.9 s — all five in
  **4.2 s** through `./check.sh --selftest`, and the pre-commit hook runs it when a
  `check_*.py` or `selftest_*.py` is staged. That is the gap it closes: the three
  newest docs refusals were found by a reader, because nothing had ever run them.
- **A deck's tap now reaches the phone's player.** `DeckService` finds
  `6f2d0007-…` on the link, subscribes with `setCharacteristicNotification` and a
  CCCD write queued beside the other GATT operations, and both
  `onCharacteristicChanged` overloads hand the bytes to `LinkCommand` — plain
  Java, so `test.sh` covers the parser: the sketch's 20-byte buffer, the three
  actions, a positive uint32 serial, and a repeated serial counting as one tap.
  `NowPlaying.run` then presses the key on the session it is already mirroring.
  `test.sh` reads **all 126 host checks passed**, nine of them new; the APK
  rebuilt to **177,214 bytes**. **Nothing has run on a phone or a board**, so
  whether a chip's notify arrives and moves playback is unverified end to end.
- The last `javac 17` type check against the Android 14 (API 34) framework
  classes (`android-all`) covered the pre-split sources: 9 files, 22 classes, only
  deprecation notes from the pre-Android-13 `setValue`/`setWriteType` path.
  **That check predates the split into sections.** The current 30 sources are
  covered by `gradle assembleDebug` instead, which is what produced the shipped
  APK.
- Every relative Markdown link across the repo resolves.
- `gradle assembleDebug` (Gradle 8.7, AGP 8.6.1, SDK 35, JDK 17) builds a debug
  APK at `dist/PhoneDeckBridge-debug.apk` — 102,590 bytes as measured on the file
  in `dist/` after a `clean assembleDebug` on 2026-10-30, for the Off-site build;
  the artifact in `dist/` now is the one rebuilt on 2026-11-02 after the drawer
  gained the owner's mark above its wordmark, which followed the sketch header
  change, the probe work, the drawer's rework and the deck's sign-in —
  **177,214 bytes** on the file in
  `dist/`, against the 112,473 that the same work produced before the second
  widget joined it. The two
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
  **compiles here**, measured on 2026-11-02 once `~/.arduino-cyd/env.sh` was
  sourced — `check_docs.py`'s gate built it on the `huge_app` partition and read
  1,878,645 B with 69,092 B of globals, beside the phone deck's unchanged
  1,168,314 B / 45,244 B. Neither board has run it.
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
  the then-committed 97,757, so the APK is *not* byte-reproducible: dex files embed
  build-time data. The probe changes shipped **99,533 bytes**, the flash-shelf
  changes **99,757 bytes**, the widget-descriptor change **102,590 bytes**, and the
  merged firmware plus the probe work **101,029 bytes**, and the icon, allowlist
  and edge-to-edge work **112,473 bytes**; the artifact in `dist/` now measures
  **177,214 bytes** on 2026-11-02, the rebuild carrying two bundled sketches, the
  drawer that names boards rather than projects, the sign-in that finishes in the
  app, the mirror, the three-step probe, the owner's logo as the icon, and the same
  ghost again above the drawer's wordmark. Quote the size to the nearest kB
  (~170 kB), never as a checksum.
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
  edge but never the mouth. The logo draws the ghost as a *hole* in one black
  contour, which is why `ic_launcher_background` is the white the ghost is cut out
  of rather than the app's black. The monochrome variant is those same four
  subpaths with `evenOdd`, so the eyes and mouth survive being flattened to one
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
`all 126 host checks passed` (117 before those nine), `BUILD SUCCESSFUL`, v2
signature, `zipalign -c 4`, both bundled sketches in sync, **177,214 bytes** in
`dist/` — 356 bytes above the build before, which is the clock seam: `EspRom.Clock` and the
pause it routes, with the probe's logic untouched. The sketch did not move in this
rebuild.

## Not verified

- The **release** build type is unrun, and the shipped APK is debug-signed with
  the throwaway Android debug keystore rather than a key you control.
- **Only the USB probe has run on a phone** (two probes, Android 17, CH340
  `1a86:7523`): tabs, SAF import, MediaStore save, clipboard, RSSI polling and the
  monitor layout are all unproven.
- No second CYD was available, so pinned-deck behaviour is untested against two
  boards.

## Open risks

- **Missing bundled sketch**: a non-Gradle build has no asset, and the BOARDS
  deck card says so. That is expected, not a bug.
- **Stale bond** from the old HID build can still block GATT connects; the app does
  not call `removeBond()`. Forget the entry in Bluetooth settings.
- **Encryption**: the core defaults characteristics to
  `ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE`, so writes need no bond. If a write
  ever fails with insufficient authentication, add a `createBond()` before the
  first write.
- **Battery optimisation** can kill the foreground service; Setup links to the
  exemption screen but does not request it programmatically.
- **Empty notification text**: apps with fully custom layouts may post no title
  or text extras, and are skipped.

## Obvious next work

1. Build the APK in Android Studio, confirm tabs and link, then confirm COPY lands
   a compilable sketch in ArduinoDroid.
2. Start on boot (`BOOT_COMPLETED`) so the deck links without opening the app.
3. Remove deck rows when a notification is dismissed on the phone — needs a new
   characteristic or control byte in the notice payload; the sketch only appends.
4. Per-deck rename/forget in the saved list; the store already keys by MAC.
