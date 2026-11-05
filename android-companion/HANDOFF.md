# Handoff — Off-site (Android companion)

State as of the last session. [README.md](README.md) has the product view,
[HOWTO.md](HOWTO.md) the task steps; this file is the context behind the code.

## What exists

A single-module Android app in Java, **no AndroidX and no third-party
libraries**, so Gradle resolves only the Android Gradle Plugin. Package
`dev.phonedesk.companion`, `minSdk` 26, `targetSdk` 35.

`MainActivity` opens on **PAIRED** and hosts two controls and two sheets, the
arrangement the owner's site uses:

| File | Role |
| --- | --- |
| `MainActivity.java` | Header pill, the pull-down and the side panel, 1 s refresh loop, the carried scroll offset, permissions, SAF result |
| `PullDown.java` | The accordion under the header: `PAIRED`, `BOARDS`, `SERIAL`, in flow rather than over the page |
| `SidePanel.java` | The right-hand panel: `SETTINGS`, `BRIDGE`, `APPEARANCE`, and the palette at its foot |
| `Sheet.java` | The bottom sheet the two panel rows open: scrim, overline, 40dp close, hairline |
| `DeckLook.java` | One deck's palette and page, and the byte that page is written as |
| `LedMarquee.java` | Six square LEDs: dark for never paired, lit for paired, chasing for linked |
| `BridgeSheet.java` | The bridge's own start/stop, its state and the notes that used to sit under Settings |
| `AppearanceSheet.java` | The three palettes and the page toggle, named in words |
| `AppearanceStrip.java` | The site's two controls as one row: three band-coloured swatches with an ink dot, and a sun/crescent circle |
| `Appearance.java` | The stored palette and page, in its own preferences file |
| `TopBar.java` | The pull-down chevron and page name, the centred mark, the link pill and the burger: one row, built by `TopBar` so the render host can measure it |
| `DecksTab.java` | PAIRED: the link, then every deck as a tile of its own palette — marquee, inks, ringed swatch, page circle — and the unpaired decks below |
| `ProjectsTab.java` | Notebook: status chips, project cards, deck libraries, recipes |
| `BoardTab.java` | Target picker, USB probe/restart, imported images, flashing, serial monitor |
| `SetupTab.java` | SETTINGS: every permission in one list with a lit or dark LED, the notification filter, the last crash |
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
| `Ui.java` | Palette, square cards and chips, Poppins, buttons, duration/size formatting |
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
- **The scan asks for decks only**: filters on the chosen address, on the Phone
  Link service UUID and on every widget service the catalog knows (five at most,
  the controller's table is small), so other advertisers never wake the service
  while NEARBY still sees a second board. Two empty scans drop the filters — a
  legacy flash advertises no UUID — and the retry delay doubles to a 20 s ceiling
  in `LOW_POWER`. A link resets both.
- **One link at a time.** A second GATT link to another CYD was deliberately not
  built: the firmware accepts one phone, and juggling several links with Android's
  single-operation GATT API is not worth the complexity.
- Tapping LINK calls `connectTo(mac)`, which writes the MAC to `DeckStore` as the
  preferred deck. When a preference exists the scanner ignores other decks, so a
  neighbouring board cannot steal the connection.
- **UNLINK is a deliberate stop, not just a disconnect.** It forgets the record,
  clears the preference, closes the GATT link and sets a `paused` flag the scan
  callback reads: NEARBY keeps refreshing, but nothing links by itself until LINK
  is tapped or the service restarts. Without that flag the next scan re-linked the
  board the user had just forgotten and `DeckStore.remember` wrote the row straight
  back. A saved deck that is not linked carries FORGET instead of UNLINK, and
  `DeckRegistry.forget` runs beside `DeckStore.forget` so a network deck's shelf
  card goes with its row. The deck has the mirror-image control: `UNPAIR`, a hold
  on the footer chip, drops the phone's bond from the board's side.
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

### The shelf names its bytes

Every sketch card on the flash shelf now carries the sketch's own fingerprint
beside COPY — `154,522 B  ·  1a04a453`, its length and the first eight digits of
its md5. The bytes on that card are the bytes COPY pastes, and Arduino answers
with the size it compiled (`Sketch uses 1,179,690 bytes`), so a sketch pasted out
of an older install is visible before it is flashed rather than after: the two
numbers disagree. `SketchStamp` does the formatting and four host checks hold it,
including the md5 of `abc` and the thousands separator the docs use.

The trap it closes: this shelf copies the sketch bundled into the APK, so an
install that predates a fix keeps handing out the old text however many times it
is flashed. The stamp makes the version on the card something you can read.

Each sketch also declares `DECK_VERSION` (`1.0.1` on the phone deck, `1.0.0` on
the Spotify deck), which the card prints as
`v1.0.1` and the deck quotes in its identity as `"v"`. So the shelf says which
build a COPY would flash and the deck's own row says which build is linked —
the same string, read from the same declaration on the board.

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
  its descriptor and a catalogue card's words belong to `FirmwareCatalog`, and both
  are rewritten on the next read, so a save there would be undone. `ATTACH A FILE` opens
  the picker as a card in its place, listing what FILES holds and offering
  `IMPORT A FILE` for what it does not: a `.ino` becomes
  `COPY SOURCE` (the text ArduinoDroid compiles) and a `.bin` becomes `FLASH`,
  which opens SERIAL with that image marked `PICKED FROM THE SHELF`, and a folded
  card carries a `FILE` badge when it names one. A new record can start from the
  sketch instead: `IMPORT A .INO` in the form seeds the name from the file's own
  name and attaches it on `ADD`. The writing
  is still the app's own uploader, which has never synced, so a card can reach the
  flasher but cannot make it work — that gap is unchanged. The catalogue is the
  seeded cards, plus whatever this app added, plus whatever is imported into
  FILES. **A catalogue card carries no file at all**: Bruce is a browser flasher,
  so its record is marked `catalog`, a read heals one that was given a file (kind
  back to `IMAGE`, name cleared), and the card draws no `FILE` badge, no
  `ATTACH A FILE` and no `NEEDS` line — that field holds "Prebuilt image." on
  these seeds, which read like a library nobody had installed. **No field of a
  record starts null.** `seed()` builds the first-run records in memory and the
  shelf draws them before any JSON round trip could fill the gaps; the log line
  called `build.isEmpty()` on that null, so a fresh install drew `SHELF FAILED TO
  DRAW` until the app was restarted. `theOpenCardsComeBackWithTheRebuild` found it
  while it was being written for the theme switch; every string field starts empty
  now.

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
  ArduinoBLE or NimBLE-Arduino). The other three are prebuilt PlatformIO images — Bruce alone pins
  roughly 40 libraries — so their cards say "None" and point at the installer
  rather than pretending they can be compiled in ArduinoDroid.
- **One-tap library setup, driven by the descriptor.** The LIBRARIES card under an
  INSIDE project reads `widget.libraries` and warns about anything in
  `widget.never`, one line per name because the reasons differ: ArduinoBLE shadows
  a core header and will not build, while NimBLE-Arduino would build but is the
  wrong API for a sketch written against Bluedroid. Each row has **INSTALL**
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
- **Palette**: the app draws the owner's site, three palettes by two pages.
  `Palette.Scheme` holds **green**, **moss** and **navy** as the stylesheet writes
  them — the page, the band, the words on that band and an accent — and
  `Appearance` stores which set and which page, applying both before the first view
  exists. **The band never turns over**: only the page does, so the header is the
  same in both modes and flipping the theme moves nothing in the chrome. That is
  also why the owner's mark is tinted with the band's own ink (`SRC_IN`, alpha kept,
  so the ghost inside the C survives): on lime it stays near-black, on moss and navy
  it is cream or powder, which is what the site does with `currentColor`.
  A card is a **hairline cell on the page** (`Ui.CARD` is the page); a chip is the
  same cell with an uppercase 11sp word in it; the one filled action is the band
  colour with the band's ink on it, and the accent is a **fill** — a live pill, a
  lit LED — with `Ui.ON_ACCENT` as its word. `GOOD` falls back to body ink when the
  accent cannot carry a word on the page (moss's lime on moss's cream is 1.03:1), and
  an error is written in body ink because the site has no red — the lit or dark LED
  beside a state is what carries it, so colour is never the only channel.
  **Poppins** replaced the monospace: the same three weights the site sets its prose
  in, subset with `fonts.py` to the glyphs this app prints, 31,744 B for all three
  faces in `res/font/`.
- **The floors are measured, not assumed.** `Palette` holds both themes (so the
  host suite can read them without Android) and `Contrast` does WCAG 2.1 arithmetic
  on them; `HostTests.contrast()` walks each theme's inks and holds body ink to
  4.5:1, hints and status words to 3:1, and a hairline to 1.5:1 as a line rather
  than a word, then checks the ramp still runs quietest to loudest. It also refuses
  a quarter-strength ink, so a theme cannot pass by making every value the same.
  **185 checks**, up from 164 when the second theme and the decks' palettes
  arrived and from 171 before `labels()` measured each label against its own fill.
  The decks' copies of those two pages are held to the app's by
  `check_widget_assets.py` (its two extra selftest branches break one page on
  purpose), and `artifacts/deck_palette.py` draws each deck's table at 320x240 into
  `artifacts/decks/` — a palette proof, not a capture of the glass. Both decks'
  pages are drawn too: `artifacts/deck_screens.py` ports the nine pages the two
  sketches can show into eighteen pages, one per theme, and `check_widget_assets.py`
  refuses a committed one that is not today's drawing.
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
  whether the board was silent or the bytes never left. The second, with counters,   showed **552 B out per round** (46 B × 12 syncs, so the bridge accepted every packet) with
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
- The pull-down carries the three destinations: **PAIRED** (live link), **BOARDS**
  (the notebook: status chips, cards, deck libraries) and **SERIAL** (target picker,
  USB probe/restart, images, flashing and the serial monitor). The side panel
  carries setup: **SETTINGS** (every permission in one list, the notification
  filter, the last crash), **DECKS**, **BRIDGE** and **APPEARANCE**, with the
  palette at its foot. Where you go is one list; how the app is set up is another.
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
- Label `6f2d0009-…` — read/write on **both** decks: the owner's name for the
  board, up to 16 printable ASCII bytes, stored in `identity` NVS and quoted in
  the document as `label`, so a Spotify deck renames the same way

## Design decisions

- **Filtered scan, matched afterwards**: filters on the chosen address, the
  Phone Link UUID and every catalogued widget service (five at most, the
  controller's table is small) keep the radio quiet; `looksLikeDeck` still
  checks the name, so a deck whose scan response was dropped is not lost,
  and two empty scans drop the filters entirely.
- **One write in flight**, `ArrayDeque` plus a 1.5 s watchdog that unsticks the
  queue when a callback never arrives. The 1 Hz mirror is the one write sent
  without a response (`WRITE_TYPE_NO_RESPONSE`): it needs no acknowledgement, and
  its watchdog is 300 ms, so a stack that never calls back cannot hold the queue
  for a second and a half.
- **Time on the minute**: the tick waits for the phone's next minute boundary
  instead of keeping a 30 s phase of its own, so the deck's HH:MM flips when the
  phone's does. Battery rides on the same pass.
- **The stack re-arms, not the scanner**: after a first link, a drop tries
  `connectGatt(…, autoConnect = true, …)` for the board that was just lost, with a
  12 s watchdog that falls back to a scan. A cold start still scans, which is the
  quicker route to a first connection.
- **The id is fixed, the label is the owner's.** `id` (`<widget>-<TAG>`) is the
  store key, so renaming it is not offered: it would split one board into two rows.
  `label` renames the board; an unnamed one falls back to `Phone Deck ·<last four
  hex of its address>`, so two of one widget never read as the same row. RENAME on
  the linked row opens one field, 16 characters, and writes it; the write triggers
  an identity re-read, so the row and the linked card show the new name without a
  re-link, and a deck flashed before the characteristic says it cannot be renamed
  instead of writing into nothing.
- **MTU 247**, because the identity document is written from a 192-byte buffer; a
  deck that caps the MTU lower still answers. The connection is asked for
  `CONNECTION_PRIORITY_HIGH` on connect: the deck requests 7.5–15 ms, but the
  central decides.
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
  on 2026-11-02: 179 s before, 1.45 s after, the same 126 checks then, 185 now. `./check.sh`
  runs the fast set, `./check.sh --full` the release set.
- **The checkers' refusals are tested too, without a compiler.** Ten
  `selftest_*.py` files build a throwaway repo — a stand-in `arduino-cli`, a
  stand-in `capture.sh`, a real `git init` — and make each gate refuse what it
  exists for: 125 branches over the ten, from a stale flash size, a
  `HISTORICAL` size that must still pass, short and full md5s, a renamed link, an
  `ELSEWHERE` name, a moved source count and an undated reading, through asset
  drift and a descriptor naming a gone sketch, to a render whose pixels moved, a capture that wrote nothing, a stale screen
  count, an archive that rebuilds differently, two sketch images that disagree and a
  root whose `env.sh` sets only `PATH`. Measured on 2026-11-03: 3.3 s, 0.6 s, 0.6 s,
  1.7 s, 0.9 s, 0.7 s, 0.3 s, 1.4 s, 0.4 s and 0.4 s — all ten in
  **10.2 s** through
  `./check.sh --selftest`, and the pre-commit hook runs it when a
  `check_*.py` or `selftest_*.py` is staged. That is the gap it closes: the three
  newest docs refusals were found by a reader, because nothing had ever run them.
- **The app draws the site's three palettes, and every role is measured.**
  APPEARANCE (the panel's third sheet, and the same two controls at the panel's
  foot) offers GREEN, MOSS and NAVY as three band-coloured swatches with an ink dot
  and one sun/crescent circle for the page; the words underneath name the result,
  so the state is never colour alone. The choice is stored in its own `appearance`
  preferences file and applied before the first view exists, and changing it
  recreates the activity because every colour in this app is read when a view is
  constructed. **The band never turns over**: only the page does, so the header is
  identical in both modes, and the mark is tinted with the band's own ink — the
  site's `currentColor`, drawn here with `SRC_IN` so the ghost inside the C is
  kept. A deck's palette is still the deck's: two chips on each linked card on
  PAIRED write that board alone. **The page you were on comes back with it**:
  `onSaveInstanceState` carries the section and `onCreate` reopens it after the UI
  is built, so a palette change leaves you where you were. **The scroll offset
  comes back too**, carried by the activity (`KEY_SCROLL`) and applied on the first
  layout with a follow-up post: `ScrollView` restores its own offset before the
  rebuilt child has any height, so its answer was clamped away, and
  `theScrollComesBackWithTheRebuild` is what caught that. `ProjectsTab` still puts
  the open card ids in the activity's bundle, because a fold has no view property
  behind it. All six palette/page combinations are held to the same floors: 220
  host checks, among them each palette's body, quiet and hairline roles measured on
  both pages and every label measured against the fill under it.
- **The committed tree builds on its own.** Measured on 2026-11-02: `git archive
  HEAD` into an empty directory plus a fresh `git init` — no built assets, no
  `.build_figures.json`, nothing in `dist/` the commit does not hold — passed the
  fast gate, compiled every source (**152 host checks**), and `toolchain.sh`
  built the APK to **180,126 bytes**, sha256 beginning `dd5b475f`; the gate was green
  afterwards, widget bundles and recorded bytes included. The docs step took
  **123 s** on that first run and ~1 s after, because `.build_figures.json` is a
  local cache: a fresh tree pays for both compiles once. A `git clone` of this
  worktree cannot be made on this host — git refuses a linked worktree as dubious
  ownership — so the export carries the same committed bytes by another route.
- **The two-chat machinery is deleted, and the one reading it fixed stays here.**
  Measured on 2026-11-02: `git merge-base --is-ancestor b3e8362 HEAD` exits 0 and the
  deck branch held no commit this branch lacked — the merge of `b3e8362` was already
  complete. What was wrong was the comparison: the branch census measured against
  `origin/main`, which lagged because nothing was pushed, so merged work kept coming
  back as "10 commits on top of origin/main, 11 paths it has no bytes for". On
  2026-11-03 the census and its selftest are deleted, and so is the root
  `DECK_HANDOFF.md`: one chat in one tree has no second tree to compare and no
  hand-off to make, so both described parties that no longer exist. The lesson they
  carried is the half worth keeping — compare against the branch the work is on, not
  a remote that lags.
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
  **That check predates the split into sections.** The current sources are
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
  deck md5 `1a04a453…`, Spotify `3b17f04a…` — `f234b68b…` before the 2026-11-02
  header rewrite, and `00bb1560…`, `eca02e93…`, `49f29bcb…` before the Bluetooth
  link, the callback change and the one-line answer — both in sync on 2026-11-02).
  The rewrite makes the phone-over-Bluetooth source the deck's first one and
  Spotify's own Web API the optional half; it arrived by merging a branch that was
  folded in on 2026-11-03, and the APK in `dist/` was rebuilt against it. That sketch also
  **compiles here**, measured on 2026-11-04 once `~/.arduino-cyd/env.sh` was
  sourced — `check_docs.py`'s gate built it on the `huge_app` partition and read
  1,891,309 B with 69,164 B of globals, beside the phone deck's
  1,179,690 B / 45,332 B. Sourcing `~/.arduino-cyd/env.sh` instead sets only
  `PATH`, so that build goes through `~/.arduino15` and reads 1,880,053 B — 48 B
  under with the globals identical, the build-root sensitivity the docs record.
  The root that first printed that figure reads 1,878,661 B, 16 B over. Neither board has run it.
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
  **231,025 bytes** on 2026-11-04, the rebuild carrying two bundled sketches, the
  drawer that names boards rather than projects, the sign-in that finishes in the
  app, the mirror, the three-step probe, the owner's logo as the icon, the mark
  centred on the top bar at 26dp as one PNG per density rather than above the
  drawer's wordmark, the gear and chevrons drawn as shapes, and the shelf that
  adds, edits, deletes and attaches a project and imports a file from its own
  picker. The typeface change then rebuilt it the same day at **231,025 bytes**
  (226 kB): the two sketches now carry their own bitmap faces, and the step from
  the 210,545-byte archive is mostly that data plus the redrawn pages.
- **The archive is byte-reproducible now, on one machine.** AGP stamps a
  *Dependency Info Block* into the APK Signing Block and fills it differently on
  every build — same sources, different bytes. On 2026-11-03 two builds of this
  tree differed in 179 bytes of that block and nowhere else: the same 21 entries
  in the same order, the same bytes, the same v2 and v3 signatures (`0x504b4453`,
  AOSP issue 268071369). `toolchain.sh` now rewrites the archive with `zipalign`,
  which drops the signing block, and signs it again from the pinned debug key,
  whose RSA signatures are deterministic. Two full runs then shipped the same
  file, byte for byte: **202,353 bytes** in the run before the exact-rect chips,
  4,367 of them the bigger signing
  block that re-signing writes. A different keystore signs different bytes, so
  reproducible means this toolchain and this key; `OFFSITE_KEYSTORE` overrides
  both halves. Quote the size to the nearest kB (222 kB), never as a checksum.
- **The release build type runs, and its archive is signed by a key that is not in
  the tree.** Measured on 2026-11-03: `clean assembleRelease` exits 0 in 16 s, 44
  actionable tasks with 41 executed, including `lintVitalRelease`. The archive
  Gradle writes is unsigned, because the release type has no `signingConfig`, so
  [`release.sh`](release.sh) aligns and signs it. Rebuilt on 2026-11-04 after the label, once for the phone deck and again for
  the Spotify one twice more, for the bond self-heal, again after the now page's
  taps were mapped to its own BACK controls, again when both cards began naming
  the 3 MB partition, again when the library box began refusing NimBLE-Arduino, and
  again when a new project could start from an imported sketch: the unsigned file
  is 227,458 bytes, `release.sh` signs it with a 4096-bit key and writes
  **243,311 bytes** (238 kB), and the debug APK in `dist/` was 239,217 bytes then.
  Rebuilt on 2026-11-05 after the restyle and the three Poppins faces, the debug
  APK was 272,209 bytes (266 kB), sha256 beginning `15ca87b1` — 32,992 bytes for
  the fonts and the new views. The cutout insets rebuilt it again the same day to
  **276,305 bytes** (270 kB), sha256 beginning `9cae9c60`: 4,096 bytes over the
  earlier archive, with `classes.dex` up 664 bytes (272,444 to 273,108) and the
  rest packaging, and 51,375 bytes under the 327,680-byte ceiling. The code was
  the same in the release pair — `classes.dex` 249,492 bytes and
  `resources.arsc` 1,980 bytes either way — so its 4,094-byte difference is
  packaging, alignment and the signing block rather than a different build, and
  no one figure is a guide to the others. It is versionCode 1,
  minSdk 26, targetSdk 35, not debuggable, and it carries both sketches in its
  assets. The signer is read back out of the archive and refused if it comes back
  as the debug identity instead. The unsigned file is build output rather than a
  kept one: the next `toolchain.sh` run starts with `clean` and takes it with it.
- **The release key lives outside the repository, which is the opposite rule from
  the debug key.** `release.sh --new-key` makes a PKCS12 keystore holding an RSA
  4096 key under `~/.offsite-release/` — `release.jks`, a `pass` file beside it at
  mode 600 whose value is generated and printed nowhere, and the signed archive in
  `dist/` — and the script refuses a keystore or password path that resolves
  inside the tree. Nothing about it is committed, so the signed bytes cannot be
  rebuilt from a clone: they are a file to hand over, not an artifact to track, and
  `~/.offsite-release/dist/PhoneDeckBridge-1.0-release.apk` is where this run put
  them. A release-signed build also cannot install over a debug-signed one, since a
  different certificate is exactly what an update refuses.
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
  `MainActivity.applySystemBarInsets` adds the window insets to the band, the page
  and both overlays **on 35 and above only**; below that the window still fits the
  bars and the padding would be doubled. The inset is the union of `systemBars()`
  and `displayCutout()`, so a top-centre punch-hole (a Galaxy Flip's camera) moves
  the band's words clear while its fill stays behind the hole, and the sides are
  read too for a cutout that lands on the long edge in landscape. The side panel is
  the case that needed it most: its close circle sits where the clock is, and its
  foot where the gesture handle is. The status bar icons follow the band on 15+,
  which is the colour behind them there. Not measured on a device with a cutout;
  the geometry is the platform's insets, and `CaptureTest` dispatches a 44 dp top
  hole and a 30 dp side hole through the activity to hold it.

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

**The 2026-11-03 log put the fault in the app's own send path, and that is why the
flasher stays.** The probe found the chip in its ROM loader after every pulse, at
four wirings and five rates in both control polarities: `rst:0x1
(POWERON_RESET)` and a banner ending `boot:0x3 (DOWNLOAD_BOOT…) waiting for
download`. Each round handed 552 bytes to the bridge at 115200 — twelve 46-byte
frames — and 276 at the slower rates, and no rate, wiring or polarity drew a
reply. A readable banner proves the chip's transmit side, the framing and the
rate; ArduinoDroid flashing this board from this same phone, cable, adapter and
CH340 proves the rest of the path, down to the ROM. So the one link left
unproven is this app's send code: `UsbSerialCh340.write()` hands slices to
`bulkTransfer`, throws only when the transfer reports nothing sent, and `bytesOut`
counts what the port accepted, not what left the phone. The open question is not
how another program does it — the protocol is esptool's and this driver follows
`usb-serial-for-android`'s register sequence with flow control already off — but
where a write the phone accepts stops travelling. The flasher is kept rather than
deleted, at the owner's request, because a fix would belong in the app. **The
loopback that would say whether those writes leave the phone has not been run:** it
needs a spare CH340 module with TX jumpered to RX, and no USB host exists on this
machine to try one. The steps and what each result would mean are in
[`HOWTO.md`](HOWTO.md), so the first person with a module and a phone produces a
measurement rather than a session note.


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
| One screen | https://site-c8050e9c1c3546fc9a5dfb219430a19b.freebuff.page/paired.png — likewise `pull-down`, `side-panel`, `bridge-sheet`, `appearance-sheet`, `projects`, `projects-open`, `serial`, `settings` |
| The strip (one row of the nine, for a desktop message — not a phone view) | https://site-c8050e9c1c3546fc9a5dfb219430a19b.freebuff.page/chat-sheet.jpg |
| Preview copy | https://site-c4870019b7c74299bae95043c45330f8.freebuff.page/ — the same bundle, deployed first and kept for a second look |
| Bundle source | [`artifacts/site/`](../artifacts/site/) (`worker.js`, `manifest.json`), built by [`artifacts/publish_renders.py`](../artifacts/publish_renders.py) into the gitignored `artifacts/site/public/` |
| Site | Freebuff Sites `3f6c1a9e-8b47-4d2c-9f11-5a7e2c4d8b60` — **production published**, preview published beside it |

The worker serves `env.ASSETS` and 404s everything else: there is no route, no state and
no secret on it, and the bundle is a copy of pixels git already holds, so the site cannot
show a screen the app no longer draws. `public/` is ignored by git — the frames beside it
are the source. Nothing in the app reads this host. `capture.sh` rebuilds the bundle every
time it installs frames, so the files the site serves cannot lag a capture; deploying them
is a separate step, and publishing still needs asking. The phone view is the labelled
per-screen links above, not the gallery and not the strip: the strip is a single row
nine frames wide, so a phone shrinks each frame to a thumbnail.

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

- **Nothing has installed either build.** The shipped APK is debug-signed with the
  project key in `android-companion/keystore/debug.jks` — a key the tree holds, so
  it is a reproducibility fixture rather than an identity. A release archive is now
  signed by a key `release.sh` made on this VM (see Verified), and neither archive
  has been installed, on a phone or over an older debug install. That release key
  is also this machine's: a release that outlives this VM needs a key you keep,
  because a cloned tree cannot make the same signature. No store listing was
  attempted.
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
- **Auto-reconnect, scan filter and backoff**: the `autoConnect` re-arm, the
  address/UUID filters, the 20 s ceiling and the 300 ms unacked watchdog have never
  run on a phone. What to look for: `Connecting to <name>` within a second of
  walking back into range with no `Scanning for decks` in between.
- **Deck label**: a rename is written to `6f2d0009-…`, stored in NVS, quoted in the
  identity document and shown by the app; a blank write clears it. Both sketches
  expose it, so the write is the same on either deck. **Unverified on
  hardware** — the sanitiser is exercised only by compiling it.
- **Deck-side bond drop**: both sketches remove their half of a bond on HCI
  `0x05`, `0x06` or `0x3E`, so the next connect pairs afresh. The serial line is
  `[ble] bond dropped (keys gone) <addr>` on the phone deck and
  `[link] bond dropped (keys gone)` on the Spotify deck. **Unverified on
  hardware** — step 6 under [Proving it on hardware](#proving-it-on-hardware)
  is the test that decides it.
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
6. **Both ends forget a bond**, once per deck. Link the deck, then in the
   phone's Bluetooth settings forget it — the phone deck under its advertised
   name, the Spotify deck under `Spotify Deck <tag>` — and press LINK again in
   the app. On the deck's serial at 115200 expect the deck's own line first:
   `[ble] bond dropped (keys gone) <addr>` on the phone deck,
   `[link] bond dropped (keys gone)` on the Spotify deck, each followed by
   `<prefix> pairing ok` and `<prefix> phone connected`, with the app back on a
   link and the name it had before. The app's half can announce itself
   too (`Stale pairing forgotten, retrying`); a link that comes up with no
   bond-dropped line on either side is still a pass — the phone re-paired on its
   own — but say which of the two happened, because only the first proves the
   deck's branch ran. Do not forget twice inside five seconds: the deck ignores
   the second on purpose, and that is the rate limit, not a failure. Last, hold
   PLAY 3 s on the Spotify deck (or type `unpair`) and confirm the manual escape
   still removes the bond and advertises again.
7. **Walk away and back.** Carry the phone out of range until the deck reads
   OFFLINE, then return. Expect `Connecting to <name>` without a `Scanning for
   decks` line in between, and a link within a few seconds. `Disconnected, scanning`
   instead means the autoConnect arm was refused and the scan took over; either way
   note which one, because the two have different battery costs.
8. **Insufficient authentication.** A write that lands before a bond shows
   `Pairing to write` and one system pairing prompt. If writes are still refused
   afterwards, the `pair` branch was right and the bond is not completing.
9. **Battery.** SETTINGS, then BATTERY SETTINGS, should raise the system exemption
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
