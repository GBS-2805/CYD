# Conventions — ESP32-2432S024R widgets

House rules for every project in this repo. They exist so a new widget — or a new
chat — does not have to rediscover them. If a rule stops being true, change it
here in the same change as the code that broke it.

**Scope: the ESP32-2432S024R only** — the 2.4" CYD with a resistive panel, an
ESP-WROOM-32 and a CH340 USB bridge. A board with a different panel, touch
controller or bridge gets its own section here first.

## One folder per widget

* Folder and sketch share a name: `phone_deck_cyd24R/phone_deck_cyd24R.ino`.
  ArduinoDroid compiles one `.ino` whose name must match its folder, so renaming
  either breaks the phone workflow and the app's build. The name carries the
  board it runs on — see [Naming](#naming-topic_deck_board) below.
* One sketch, one tab, **no extra headers**. A pasted `.ino` cannot see a header
  beside it.
* Each **widget** folder has one `README.md`: what it is, how to flash it, the
  traps, and what is verified. No `HOWTO.md`, no `HANDOFF.md`, no `DECK_HANDOFF.md`
  inside one — a task that needs steps gets them in that README. The app folder is
  the one exception, because it carries three documents with three audiences and
  one `README.md` holding all of them was unreadable:
  [`android-companion/README.md`](android-companion/README.md) is the product and
  its build, [`HOWTO.md`](android-companion/HOWTO.md) is the step-by-step a person
  follows on the phone, and [`HANDOFF.md`](android-companion/HANDOFF.md) is the
  state behind the code — what exists, what is verified, what is not. The root
  keeps [`DECK_HANDOFF.md`](DECK_HANDOFF.md) for the same reason: the deck-to-app
  hand-off crosses both trees, so it belongs to neither folder.
* Add the row to the [root README](README.md) table in the same change.
* Widgets bundled into the APK also carry [`widget.json`](phone_deck_cyd24R/widget.json)
  (`schema: 1`, with `id`, `name`, `sketch`, `board`, `tagline`, `body`, `notes`,
  `libraries`, `never`, and a `ble` block when it speaks BLE). The app copies the
  sketch at build time, so `android-companion/app/src/main/assets/widgets/<id>/`
  must hold a byte-identical copy. That tree is **gitignored build output** —
  Gradle rewrites it before every build and nothing under it belongs in a commit,
  so a sketch edit wants a re-sync, not a staged file. Check the copy with
  [`check_widget_assets.py`](check_widget_assets.py) after any sketch edit: it
  fails when a descriptor names a missing sketch, when a bundled copy has drifted,
  or when `assets/widgets/` holds a widget no descriptor claims.

### Naming: `<topic>_deck_<board>`

One deck, many boards, so the board is part of the name — in the folder, the
sketch and the descriptor `id`, which always move together:

| Tag | For | Example |
| --- | --- | --- |
| `cyd24R`, `cyd28R`, `cyd28C` | an all-in-one CYD: inches × 10, then the touch panel — `R` resistive, `C` capacitive | `phone_deck_cyd24R` |
| `ili9341_28`, `st7735_16` | a bare panel on a devkit: controller, then inches × 10; the touch letter only when a touch panel is wired | `weather_deck_ili9341_28` |
| `tdisplay_s3` | a module whose module name *is* the board | `maps_deck_tdisplay_s3` |

* Lowercase ASCII, never a `"`, a `.` or a space: a quote breaks shell quoting and
  Gradle asset paths, a dot reads as a version, a space splits the asset folder.
  Inches are × 10 as digits, so `cyd24R` is the 2.4" resistive CYD.
* The spoken form — `2.4" CYD (resistive)` — lives in `widget.json`'s `name`,
  never in an identifier.
* One folder, one sketch, one `id`, one board. Two sizes of one deck are two
  widgets that share prose, not one widget that switches panel at compile time:
  the phone holds one project per sketch and the app keys its shelf by filename.
* `board` in the descriptor keeps the model number where there is one
  (`2432S024R`); the tag is the human summary of the same fact.
* Variants differ only in a `BOARD` block at the top of the sketch — pins,
  rotation, driver, backlight, touch — so a diff between two tags stays small and
  a fix is the same small patch in every copy.

## The board

| Signal | GPIO |
| --- | --- |
| TFT CS / DC / backlight | 15 / 2 / 27 |
| Touch CS | 33 |
| SPI SCK / MISO / MOSI | 14 / 12 / 13 |
| RGB status LED | 4 / 16 / 17 |

* The RGB LED is three discrete LEDs between the GPIOs and 3.3 V, so a channel
  lights when its pin is **LOW**. Drive all three HIGH at boot; a floating channel
  glows cyan.
* The backlight is on its own switch at the edge, not a button, and can be PWM'd
  on GPIO 27.
* Buttons available to a sketch: none. BOOT and RESET are for flashing only.
* **No PSRAM, and the module marking is the evidence**: ESP32-WROOM-32 stamped
  `N4XX` is 4 MB of flash and no PSRAM. On a board that does have it, `none found`
  still proves nothing, because `esp32:esp32:esp32` defaults to `PSRAM=disabled`.
  Read the marking, never a product listing.
* **Budget for 4 MB of flash and no PSRAM.** The default partition scheme gives
  the app 1,310,720 bytes and the deck takes 1,168,314 of them (89 % as reported
  by the compiler, after the mirror). DRAM is 327,680 bytes with ~300 kB of heap
  free at boot once the display and touch drivers are up; a 320x240 RGB565
  framebuffer is 153,600, so there is room for one, not two. Treat "it fits" as a
  measurement from `Sketch uses ...`, never an assumption.
* **A behaviour that becomes optional dates every doc that calls it required.**
  When the Spotify deck stopped needing a token, its header and its "first run"
  section still described a credential as the way in. A number check cannot see
  that. When a default, a first boot or a required input changes, grep the folder
  for the old requirement in the same change and rewrite it as the option it now
  is.
* **A control that ignores taps is usually a stale mapping, not a bad control.**
  The deck's `+` chip took no taps while `-` worked and a recalibration fixed it.
  Keep targets at least ~40 px on the short side, clear of the outer 8 px, with a
  few pixels of slop. The panel is resistive and single-touch: a second tap while
  one is latched is a bounce, not input.

## Build

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 my_widget
```

* Board package `esp32:esp32`. Both decks default to core **3.3.12** and compile
  clean on 2.0.17 too; keep a second install root for the old core when you want
  an A/B measurement, because one root holds one core and the compiler picks the
  newest installed platform.
* **The default path is proven end to end.** `ARDUINO_HOME=~/.arduino-cyd-3
  ./toolchain.sh` in a fresh root installed the core and libraries and built both
  sketches, exit 0: phone deck 1,165,222 B, 88 % of 1,310,720, / 45,060 B
  globals, Spotify 1,122,281 B, 85 % of 1,310,720, / 53,572 B globals. Those are
  48 B and 32 B above the same sketches built straight through `arduino-cli` from
  another path — the build-root sensitivity above, not a different toolchain. The
  mirror has since moved the phone deck to 1,168,314 B, 89 % of 1,310,720, /
  45,244 B globals and the Spotify sketch to 1,125,781 B, 85 % of 1,310,720, /
  53,636 B globals, as [`check_headroom.py`](check_headroom.py) read both of them
  on 2026-11-01 — that Spotify file is the one before the Bluetooth link
  (`00bb1560`), not a flag on today's.
* **The numbers in these docs are checked too.** [`check_docs.py`](check_docs.py)
  builds the decks and reads the markdown, and fails when a quoted flash size or
  sketch md5 is not one of today's builds — the drift that twice left a README
  describing last week's sketch — or when a relative link names a file that is
  gone. It also requires each widget README to carry its
  own deck's current size, globals and md5. It keeps its last measurement in a
  gitignored `.build_figures.json` and reuses it only while that deck's sketch,
  its FQBN and the compiler behind it are all unchanged, so a prose-only commit
  costs a second instead of a minute; `--refresh` rebuilds anyway, and every run
  names the decks that came from the file. A past build may stay in the prose,
  but only by name in that file's `HISTORICAL` table, next to the reason, and
  a sentence that calls one rebuildable has to deny it within a dozen words of
  the number and of the verb — punctuation between them is not a word, and a
  path counts once: the builds in `NO_SKETCH` have no committed sketch behind
  them, and "the pair that rebuilds" credited a phone file this repository
  has never held until a reader went looking for it. A 2.0.17 figure is not
  one of those rows: it lives in `old_core.json` beside the md5 of the sketch
  that printed it, the checker stops vouching for it the moment that sketch
  moves, and `./check.sh --full` rebuilds on that core and refuses a build that
  disagrees with it — so an old core's number cannot outlive its file.
* **Quote the run, not a range.** A figure names the run it came from and is what
  that run printed. When two builds of one sketch disagree — a pre-commit compile
  and a direct one printed 1,878,581 and 1,878,629 for the same file — say which
  one the doc quotes, or quote the one you can repeat. Never publish a span
  measured at one end: `1,878,613-1,878,661` sat in five places with only the top
  end ever observed, and the next build printed 1,878,661 twice.
* **A size names the root that printed it, and here that root is
  `~/.arduino-cyd`.** The current 3.3.12 figures in these docs — phone deck
  1,168,314 B / 45,244 B of globals and the linked Spotify deck 1,878,645 B /
  69,092 B on `huge_app` — are that root's, rebuilt through it on 2026-11-03 with
  `arduino-cli` 1.5.2-rc.1. It is the root worth naming because its `env.sh` pins
  its own data, download and user dirs; it did not always, and while the script
  set only `PATH` the same build went through `~/.arduino15` and read 1,168,282 B
  and 1,878,613 B — 32 B and 48 B under with the globals identical. The deck
  chat's `~/.arduino-cyd-3` reads 1,168,314 B and 1,878,661 B, 16 B over on the
  linked deck, and `/tmp/arduino-cyd-3` was read at 1,168,170 B and 1,878,469 B,
  144 B and 176 B under. None of that is sketch drift, because the
  md5s match: it is the build path, so source the `env.sh` whose figures you are
  quoting, say which root when two disagree, and re-measure a merged number here
  instead of accepting another tree's last two digits. The unlinked Spotify figure
  belongs to a sketch rather than to a root: it is the pre-link file
  (`00bb1560`), not a flag on today's, and rebuilt from that file on 2026-11-03 it
  printed 1,125,781 B, 85 % of 1,310,720, with 53,636 B of globals; through
  `/tmp/arduino-cyd-3` the same file reads 1,125,669 B, the globals identical.
  Both deck toolchains write that file byte for byte identically, and the gate
  refuses a root whose `env.sh` does not pin the directories — the copy this VM
  already held was written before they did, which is how one root name came to
  mean two builds.
* **The margin is checked, not assumed.** [`check_headroom.py`](check_headroom.py)
  compiles each deck with the deck FQBN and reads the compiler's own `Maximum is
  …` figures, so both ceilings — 1,310,720 B of flash and 327,680 B of DRAM —
  come from the build instead of a hard-coded number: switch to `huge_app` and
  the flash one follows. It fails a deck that overflows, that drops under 64 KiB
  of flash (`--min-free-kb`), or that leaves under **160 KiB of DRAM**
  (`--min-free-ram-kb`), because a full 320x240 RGB565 framebuffer is 153,600 of
  those bytes and a deck that cannot allocate one is stuck. Every run prints
  used, free and globals per deck, so erosion shows up even when nothing trips.
* **The host tests run the probe on a clock they drive.** `EspRom` reads every
  deadline and every pause through `EspRom.Clock`, real by default; `test.sh`
  installs a virtual clock and the stub port advances it, so a check that runs the
  real probe against a silent board still tries all five wirings and both windows
  without waiting for them. Measured on 2026-11-02: 179 s before the seam, 1.45 s
  after, the same 126 checks then, 152 now. The seam exists because the alternative — a shorter
  window in the test — would test a different probe.
* **That check runs on commit.** [`.githooks/pre-commit`](.githooks/pre-commit)
  runs the whole fast gate — [`check.sh`](check.sh) — on every commit, so no
  commit lands with a fast step red: docs, widget assets, the index, the host
  tests. Compiling the decks whose `<folder>/<folder>.ino` is staged happens in
  the same pass and refuses a commit whose sketch no longer fits its partition,
  and a staged `check_*.py` or `selftest_*.py` runs `./check.sh --selftest` as
  well. A step whose prerequisite is missing — no `arduino-cli`, no built assets,
  no `javac` — says which one instead of passing quietly, and `check_docs.py`
  reads its cache when neither a sketch nor the toolchain moved, so a docs-only
  commit is quick and still refuses a figure from a build that no longer exists.
  `git commit --no-verify` is the deliberate escape hatch. It needs
  `git config core.hooksPath .githooks` once per clone. A machine with no deck
  toolchain at all says the check did not run and lets the commit through, so a
  fresh clone is never blocked; but a root that exists with no runnable
  `arduino-cli` in it (`~/.arduino-cyd-3`, `~/.arduino-cyd` or `$ARDUINO_HOME`)
  is a half-finished install, and that refuses the commit and prints three ways
  out — finish the install, point `ARDUINO_CLI` at a compiler, or accept the gap
  with `HEADROOM_ALLOW_MISSING_TOOLCHAIN=1` for that one commit. A merge runs
  the same script through
  [`pre-merge-commit`](.githooks/pre-merge-commit), because git runs no
  `pre-commit` for the commit it makes at the end of a merge; a fast-forward
  creates no commit and so checks nothing. A refused merge is left staged rather
  than undone — git prints `Not committing merge` — so finishing it with
  `git commit` runs the gate again.
* **The gate's refusals are tested without a compiler.** Every checker passes
  on this tree every day, so its failure paths are the part nobody exercises —
  and the last three docs refusals were found by a reader, not by the check. Each
  `selftest_*.py` builds a throwaway repo in a temp directory — a stand-in
  `arduino-cli`, a stand-in `capture.sh`, a real `git init` — and asserts what the
  checker refuses: 43 docs branches, 9 asset branches, 10 margin branches, 23 old-core
  ones, 12 branch ones (a patch already upstream, the same bytes under a new name, a folder a
  merge would resurrect, a pushed branch and its local alias), 9 render ones (a
  moved pixel, a wrong size, a corrupt PNG, a capture that failed), 6 on the map itself
  (a checker with no selftest, a selftest naming a checker that is gone, a
  selftest with no checker, a tree where every pair is present, and a message
  no branch asserts) and 4 on
  the gate itself (a root that pins its directories, a root whose `env.sh` sets only
  `PATH`, the default root order, and a stale default root). Measured on
  2026-11-03: 3.0 s, 0.5 s, 0.5 s, 1.5 s, 1.4 s, 0.8 s, 0.3 s and 0.3 s — all eight in
  **8.4 s** through `./check.sh --selftest`, on a machine with no toolchain. On this shared
  VM the same command has also taken 4.4 s and 106.5 s an hour apart, which is
  the machine's load rather than the checkers', so read a slow run as load
  before assuming a checker got slower. The pre-commit hook runs
  that command whenever a `check_*.py` or `selftest_*.py` is staged, so a gate
  refactor cannot land having quietly stopped refusing. The decks themselves only
  ever walk the passing path; the overflow path was proved once with a 1.3 MB
  throwaway sketch, and that is not a test anyone can rerun. `check_map.py`
  prints which branch pins which refusal, as the fast gate's `map` step. It
  refuses the pairing - a checker with no selftest, a selftest naming a checker
  that is gone, a selftest with no checker - and every message no branch
  asserts, which is why that list is empty today: a refusal with no branch
  behind it fails the gate instead of sitting in a report. That counts a
  message appended to a problem list and one written with
  `print(..., file=sys.stderr)`, so a refusal cannot slip past by choosing the
  other way to say it. The match is word
  for word, with placeholders, sizes and md5s standing in for the value filled
  in at run time — a run of words, because a printed size can be "123 bytes" —
  because a `want` string is a substring of a formatted message. The scan is
  deliberately small: it reads the literal a checker appends to a problem list
  or prints to stderr, which is how every refusal here is written. A message a
  helper returns, one built with `str.format` or `+`, one chosen into a variable
  or one built by a call is invisible to it, and that is the price of a gate
  small enough to read rather than one that follows every shape and then needs
  a branch per shape.
* **Before you change a sketch, run both.** From the repo root:

  ```bash
  python3 selftest_headroom.py                                    # the refusals, ~1 s
  source /tmp/arduino-cyd-3/env.sh && ./check_headroom.py         # the decks, ~17 s warm
  ```

  The first says the check still refuses what it should, in about a second and
  on a machine with no toolchain; the second is the measurement you will quote.
  Without the `source` line the second exits 2 saying it found no `arduino-cli`,
  because it will not guess a compiler — that is why the line is written down
  instead of remembered. The `source` line is enough on its own because `env.sh`
  also exports `ARDUINO_DIRECTORIES_DATA`, `_DOWNLOADS` and `_USER`: an
  `arduino-cli` given no `--config-file` falls back to `~/.arduino15` rather than
  the root it came from, so on a machine that keeps 2.0.17 beside 3.3.12 it would
  silently build the wrong core and report 73 % where the release reads 85 %.
  Both together took **18 s** here, on core 3.3.12 with the build cache warm; on
  2026-11-01, after the mirror, `check_headroom.py` over both decks read
  1,168,314 B (142,406 B free) and 1,125,781 B (184,939 B free), exit 0. A second
  install root read 1,168,170 B (142,550 B free) and 1,125,669 B (185,051 B free)
  for the same two sketches — 144 B and 112 B lower with the globals identical,
  which is the build-root sensitivity above rather than drift in the sketches.
* **The root is not at the path this file used to name.** On 2026-11-02 the deck
  toolchain on this VM was at **`/tmp/arduino-cyd-3`** — `bin/arduino-cli`,
  `env.sh`, core **3.3.12** — while `~/.arduino-cyd-3`, which the command above
  named until now, does not exist here. So a bare `check_headroom.py` exits 2 with
  `no arduino-cli found`, and that is a **misplaced root, not a broken install**:
  source the `env.sh` that exists. `/tmp` is wiped between sessions, so the root is
  furniture, not state — a session that finds no `arduino-cli` re-provisions it with
  [`phone_deck_cyd24R/toolchain.sh`](phone_deck_cyd24R/toolchain.sh) instead of
  concluding the decks cannot be checked.
* **What the root has to hold.** One core, that chip's compiler, the libraries and
  `esptool_py`. Measured on 2026-11-02: **7.8 G** installed; **3.3 G** without the
  toolchains for chips this board is not — `esp-rv32` 2.1 G, the eight other
  `esp32*-libs` 2.3 G, both `gdb`s 247 M, `openocd-esp32` 11 M, 4.6 G of silicon
  that is not on the desk; **1.6 G** once the 1.7 G `staging/` of archives goes,
  since `packages/` already holds what was unpacked. What stays: `esp-x32` 1.1 G,
  `esp32-libs` 184 M, `esptool_py`, `mkspiffs`, `mklittlefs`. Both decks then built
  cold, exit 0, twice, on the same figures, so the trim is measured: 94 % → 58 %
  disk. A second 3.3.12 root is a copy, not an A/B — `~/.arduino-cyd` held 3.3.12
  at 6.1 G (4.6 G of it those chip toolchains), 1.6 G cut the same way, and still
  compiled both decks from its own `env.sh`.
* **The A/B root is the 2.0.17 one, and its Python has to see `serial`.** Cut the
  same way, `~/.arduino15` went 2.4 G → **1.2G** on 2026-11-02 (662 M
  `riscv32-esp-elf-gcc`, 413 M of S2/S3 compilers, the `gdb`s, `openocd`) and still
  built both decks on 2.0.17. Its `esptool_py` 4.5.1 imports `serial`, which only
  the 3.3.12 root ships, in `pylibs/`; without it every compile dies on
  `ModuleNotFoundError: No module named 'serial'` before a sketch is touched, which
  reads like a broken toolchain and is not. So the A/B root carries its own
  `env.sh`: data dirs pointed at itself, `PYTHONPATH` at the 3.3.12 root's
  `pylibs/`, `arduino-cli` in its `bin/`, core 2.0.17. On 2026-11-03 this VM had no
  2.0.17 at all — `~/.arduino15` here is the fallback data dir, holds 3.3.12 and has
  no `env.sh` — so it was re-provisioned as **`~/.arduino-cyd-2`** (own `data`,
  `downloads` and `user`, the 3.3.12 libraries copied in). Source the one that
  exists:

  ```bash
  source ~/.arduino-cyd-2/env.sh && ./check_headroom.py   # both decks, on 2.0.17
  ```

  `./check.sh --full` runs that build as its **`old core`** step and compares the
  result with `old_core.json`, so a figure cannot outlive the sketch that printed
  it; it says so when no A/B root is found. The file is also checked with no
  compiler at all: `./check_old_core.py --record`, the fast gate's `old record`
  step, requires every deck it names to be here with one row and an md5 for a
  sketch that is in the index, because an md5 of a file a clone would not have
  describes bytes nobody can check out, and it hashes that sketch and compares:
  a record that still describes an older file is refused with no compiler at
  all. Both gates read the file through
  `check_old_core.read_record()` and judge its rows with `row_shape()`, so a
  rule about the record cannot hold in one gate and not the other. The same
  holds for which file a deck is: every checker asks `check_headroom.sketch_of()`
  and hashes through `check_headroom.md5()`, so a quoted figure, a bundled copy
  and a recorded md5 are always the same bytes.
* **A core upgrade costs whatever the sketch's stack costs, not a fixed overhead.**
  Measured on 2026-10-31, one machine, `--warnings all`, fresh build paths,
  same source, going 2.0.17 → 3.3.12: the Bluetooth-only phone deck grows
  1,162,8xx → 1,165,174 B (about **2.3 kB**), while the WiFi/TLS Spotify deck
  grows 967,493 → 1,122,249 B (**+154,756 B**), 73 % → **85 %** of 1,310,720 —
  the default app partition. Not mbedTLS getting fatter: named `mbedtls_*` symbols
  only go 83,854 → 91,309 B and `wifi`/`lwip` 83,470 → 87,007 B. It is how IDF 5
  assembles the network stack — the 3.3.12 image links
  `network_provisioning`, `esp_driver_uart`/`gpio`/`i2c`, `esp_mm` and `espnow`
  that the 2.0.17 image never referenced, its `libnet80211`, `liblwip` and
  `libwpa_supplicant` are each larger, and more of the core's WiFi code is inline
  in headers, so the sketch's own object grows with it. So never scale one
  deck's core delta onto another: recompile and quote `Sketch uses …`, and check
  the partition before assuming a sketch still fits. The Spotify README's 967,413
  is the 2026-10-31 file (`ef0681b`) on a path ten characters shorter; at this
  repo's path it prints 967,493, and the committed Spotify sketch, which has
  grown the Bluetooth link and the identity since, prints 1,748,037 B on 2.0.17
  — over the 1,310,720-byte default slot.
* **Why 3.x is the default, and what would reverse it.** Core 2.0.17 ships
  mbedTLS 2.28.7, a branch that reached end of life at the end of 2024, and the
  Spotify deck's whole job is TLS to a third party — an unpatched TLS stack
  holding OAuth tokens is the argument that matters here, not flash. 3.3.12
  ships mbedTLS **3.6.6**, an LTS with fixes promised until at least March 2027;
  Espressif's own 2.0.x support ended in July 2024; and no flash of either deck
  has been confirmed, so today is the cheapest this migration will ever be — both
  already link clean on both cores. The price is **155 kB** in the WiFi deck
  (335 kB → 181 kB of its app slot) and ~3 kB of RAM; the Bluetooth deck pays
  2.3 kB. Reverse only for a measured reason: if the WiFi deck runs out of flash,
  use `huge_app` (3 MB slot, 36 % used today) rather than an unsupported TLS
  stack. **Still unmeasured: heap during the TLS handshake on 3.x** — the first
  thing to watch when the Spotify deck reaches hardware, since 53,636 B of
  globals leaves 268 kB and mbedTLS 3.x is the larger consumer.
* Libraries, and only these unless the widget genuinely needs more: `Adafruit GFX
  Library`, `Adafruit ILI9341`, `XPT2046_Touchscreen`.
* **Never install ArduinoBLE.** Its `BLEDevice.h` shadows the core's header and
  the build dies in a wall of errors. BLE comes from the core (Bluedroid) —
  `BLEDevice`, `BLEServer`, `BLEClient`.
* Use the widget's [`toolchain.sh`](phone_deck_cyd24R/toolchain.sh) rather than
  assembling a toolchain per chat; it is idempotent and keeps everything under one
  `ARDUINO_HOME`.
* Report what actually ran. "Compiles" means a compile exited 0 in this repo.
* **Flashing from the phone is ArduinoDroid's job, not the app's.** On
  2026-10-31 the same phone, OTG adapter, cable and board flashed successfully
  from ArduinoDroid, which is the measurement that matters for the sketch: copy
  it into ArduinoDroid and upload from there. What failed the same day was the
  companion app's *own* uploader — its ROM handshake got no sync at 115200,
  57600, 230400, 460800 or 74880 baud in either DTR/RTS polarity while 552 B left
  the phone per round. Keep the two apart: a deck flashes from the phone today,
  and the app's USB flasher is the thing that cannot write a board until its
  packets arrive. A computer over the same cable is the fallback, and it is also
  the test that separates the app's transmit path from the bridge. That record
  does not name the image that flashed — no log of the session survives in either
  tree — so it proves the phone, the adapter and the board are up to a flash;
  it does not prove that either deck has run. Do not re-litigate either without a
  new measurement.

## Palette

Widgets that draw a UI keep their palette **in the sketch**, as a table:

```cpp
static constexpr Palette THEMES[] = {
  { 0x10A1 /* #14150f */, 0x2103 /* #20211a */, /* ... twelve entries */ },
};
static constexpr uint8_t THEME_INDEX = 1;   // which one is live
```

* Twelve fields, fixed order and fixed jobs: ground, panel, lifted panel,
  hairline, ink, dim ink, then accent, accent in shadow, action (the one button),
  marker, count, and "good". The field names are historical — `C_PINK` may be grey
  — so reason about the **job**, not the name.
* Status rules must keep the last three distinguishable in every theme, by
  luminance rather than hue: charging strongest against the ground, the warning
  state next, the healthy state quietest.
* **Ship monochrome.** Two themes: 0 PAPER (black on white) and 1 INK (white on
  black), six accent roles as a grey ramp, so a new widget needs no colour
  decisions to be readable. A widget that genuinely needs colour adds a theme to
  its own table and says why in its README.
* Keep the `0x5655 /* #rrggbb */` shape: the comment is how the palette is read
  and reviewed, and a reformat that drops it leaves a wall of numbers.
* The twelve `C_` names are plain variables loaded from the chosen theme, so a
  picker can reassign them at runtime without touching a draw call.

## NVS

* One Preferences namespace per concern: `settings` for user-visible settings,
  `touchcal` for the calibration fit, `identity` for the BLE owner.
* Version any stored structure and check the version on read — the fit carries
  `CAL_MAP_VERSION`; bump it when the shape changes.
* Validate on load (clamp ranges, bounds-check indices) so a corrupt byte cannot
  leave the widget with no escape.
* Diagnostic sketches never write to NVS.

## BLE identity

* Each board is its own widget: advertise `<Name> <TAG>` with the tag from the
  efuse MAC, and let the first phone to connect claim it.
* Keep every name the widget has ever advertised in the app's `nameMatch`, or
  boards flashed before a rename stop being found.
* Service and characteristic UUIDs are per deck, never reused across decks, with
  one deliberate exception: the **Phone Link** service (`6f2d0001-…` and its six
  characteristics) is spoken by every deck that talks to the companion app,
  because the app has one scan and one implementation of it. That is what lets one
  phone drive whichever BLE deck is flashed. What stays per deck is the identity
  document — widget, name, role, board — and the advertised name, which is what
  `nameMatch` reads.
* A deck that speaks the service must expose **all** of time, battery and notice,
  not just the ones it draws: the app refuses a link to a server missing any of
  them, and a refusal looks exactly like a dead chip on the glass.
* Nothing here pairs in the phone's Bluetooth settings: these are GATT
  peripherals, not keyboards. A design that makes Android treat the board as an
  input device gets the link dropped.

## Deck identity

* **A deck is a board, not an address.** The identity is the low 24 bits of the
  efuse MAC as six hex digits — `A1B2C3` — the same tag the Bluetooth deck puts in
  the name it advertises. A network deck puts it in its id instead:
  `spotify_deck_cyd24R-A1B2C3`. Two boards of one widget are two decks; a re-flash
  keeps the id, so the address is only ever a cache of where that id was last
  seen.
* **A network deck answers `GET /deck.json`** with one line of JSON: `id`,
  `widget`, `tag`, `name`, `role`, `board`, `ip`, `setup`, `signed`. Nothing
  secret — no token, no password, no client id — so the app can ask any address
  who lives there.
* **It is served in setup and in the player.** Setup already runs the page server
  on port 80; the player runs a listener that answers this one path and 404s
  everything else, because the sign-in pages belong to setup. Its read gives up
  after a second, so a stray connection cannot hold the poll loop.
* **The sign-in URL is the deck's.** `GET /auth?app=1&json=1` answers the
  authorize URL as text — `409` and `refused: <why>` when it cannot build one — so
  an app can open it without scraping a page. The PKCE verifier never leaves the
  board, which is exactly why the app asks for the URL instead of minting its own.
* **The app keys its records by id and rewrites the address.** The shelf's card
  for a widget with a `lan` block pairs by typed address, reads `/deck.json` to
  learn which board answered, and stores `{id, widget, tag, address, state, age}`.
  An id seen at a new address moves its record; a *different* id seen at a known
  address replaces the stale one, because two decks cannot honestly share an
  address at the same moment.
* A descriptor declares its network face in a `lan` block (`role`, `identity`,
  `signIn`, `page`), so the shelf draws that section from the widget instead of
  from a list in Java.
* **A Bluetooth deck publishes the same document**, read-only, in characteristic
  `6f2d0005-…` of its Phone Link service: `id`, `widget`, `tag`, `name`, `role`,
  `board` and `link`, and only a network deck adds `ip`, `setup` and `signed`. The
  app's `DeckIdentity` parses either, so one reader holds both decks, and the
  phone deck is keyed by `<widget>-<TAG>` too: `DeckStore` keeps the MAC inside
  the record as the address, never as the key. A board that has not published a
  document (an older flash, a read that failed) is filed under its MAC and
  upgraded the first time it answers, so the list migrates itself.
* **An identity document is longer than a default ATT MTU.** It is ~150 bytes
  against the 20 usable at MTU 23, so the app asks for 185 before discovering
  services and asks again after `onMtuChanged`; a truncated read names no widget
  and falls back to the address rather than inventing a half id.

## The two chats

Two chats work this repository, each in its own worktree on its own
`freebuff/<id>` branch. This section is the whole agreement between them; every
other mention of it in this repo is a pointer back here.

| Chat | Owns | May do without asking |
| --- | --- | --- |
| **this chat**, `freebuff/e9252a57…` | both decks, this file, the root checkers and all of `android-companion/` | build, measure and edit any of it; merge the deck chat's branch |
| **the deck chat**, `freebuff/60e73bbb…` | its own worktree and branch | edit anything in its own tree; merge this branch; read this tree |

* **The app's Java is this chat's.** It was written in the deck chat while the
  split lasted, which made a deck change that needed one line of Java a two-branch
  dance and left the two worktrees quoting different numbers. `android-companion/`
  is edited here now, by the chat that edits the sketches.
* **A change crosses as a commit and an SHA, never as a file copy.** The chat with
  work to give commits on its own branch, pushes, and names the SHA; the receiving
  chat merges it. A merge shows both sides of a disagreement, where a copy
  silently drops whichever version was written second. Nothing is copied into
  another chat's worktree — not `android-companion/`, not a sketch, not a doc —
  and nothing is checked out over files the owner has changed since.
* **Neither chat writes into the other's worktree**, and no branch checked out
  somewhere else is force-pushed. A file that appears in a tree without a commit
  is drift, not a handoff: `git status` there is that tree's owner to explain
  before anything is merged over it.
* **A rule has one home.** Anything true of the whole board or of both chats lives
  in this file; anything true of one widget lives in that widget's `README.md`;
  everywhere else a rule is a link, never a restatement. Two copies disagree
  eventually, and then neither is trusted.
* **A checker beats a paragraph.** If a rule can be tested, it becomes an assertion
  in a root checker and the prose shrinks to a pointer at it — a rule that cannot
  drift does not need repeating.
* **The owner edits; the other reports.** A failure in another chat's area is
  reported with the command that printed it and the SHA it printed on, never
  quietly fixed in that tree.
* **A measurement is true of the tree that printed it.** Merge this branch before
  quoting a number, and run [`check_branches.py`](check_branches.py) when a doc and
  a tree seem to disagree.
* **Exact bytes live in one file.**
  [`android-companion/HANDOFF.md`](android-companion/HANDOFF.md) keeps the exact APK
  size with the date it was measured; every other file rounds it to the nearest kB,
  so a rebuild is one edit. A number a chat did not print is quoted with its date
  or not at all.
* **Commit before going quiet.** Uncommitted work is what makes an edit look
  ownerless a session later, so anything left uncommitted gets a line in that
  tree's `HANDOFF.md` saying what and why.

## Working in this repo

* One widget per change. A change touching two widget folders is two changes.
* **Run the gates from one command.** [`check.sh`](check.sh) is the fast set a
  commit must pass — docs (figures, links, prose), widget assets, the 2.0.17
  record's shape (no compiler needed), the host tests, and the branch list as a
  note rather than a failure — and `./check.sh --full`
  adds deck headroom on 3.3.12, the 2.0.17 decks compared with `old_core.json`,
  the eight render captures and the APK build, and `./check.sh --selftest` runs
  the eight `selftest_*.py` files in about eight seconds. It also refuses a source file that is in the worktree but not in the
  index: every commit here stages tracked files, which cannot pick up a new one,
  and `LinkCommand.java` was referenced by two tracked classes for a day while a
  clone of the branch would not have compiled. The individual
  checkers keep their names because the pre-commit hook calls two of them with
  staged paths; nothing else needs six remembered invocations, and a round that
  runs one command cannot skip a step by forgetting it.
* **Two chats share this repo, and [`## The two chats`](#the-two-chats) is the
  whole agreement.** Nothing needs the other chat's permission: the table is the
  permission.
* **Another chat's work reaches this tree by merge, never by copy.** Each chat is
  a worktree on its own `freebuff/<id>` branch, so a commit made over there
  leaves `main` green and these numbers true for the tree that printed them, and
  nothing else notices the two disagree. [`check_branches.py`](check_branches.py)
  names the branches carrying commits `main` does not have yet, with the worktree
  that has one checked out; it reads refs and blobs and never a worktree, so it
  answers even for a tree this chat cannot open. Look at it before believing a doc
  that seems stale, and merge rather than hand-editing the numbers, because the
  other branch has usually already re-measured them. It judges content, not just
  commits: a branch whose changed files are the same bytes `main` already has — a
  folder renamed here, work rebased over — is reported as nothing to fold, and
  one whose patches are all upstream drops out the same way, so the list stays
  what a merge would actually add. What it says now (2026-11-02, after the base became the branch checked out
  here): **nothing is pending**. The deck chat's tip `b3e8362` is an ancestor of
  this branch — `git merge-base --is-ancestor b3e8362 HEAD` exits 0 — and neither
  its short-named ref nor origin's copy holds anything this tree lacks. The same
  branch read as "10 commits past `origin/main`, 11 paths it has no bytes for"
  only because `origin/main` lags both trees while nothing is pushed, which is
  why the base is the worktree now. `origin/main` still lags because nothing in
  either tree is pushed. Read the paths before merging anyway: an older branch can
  *look* like work and be a downgrade, which is what `app/phone-deck-build` was at
  87 commits back, 21 paths under `cyd_phone_deck/`, a folder this tree renamed
  away. The check names that folder instead of guessing, because merging it would
  bring the folder back — read the paths before merging one, and prefer the branch
  that measured the numbers.
* Nothing is pushed or deployed without being asked. The repo stays private.
* **One kB is 1,024 bytes, and it is written down once, here.** Every size in
  these docs is binary: 192,970 B is "188 kB", 343,307 B is "335 kB", rounded to
  the nearest kB with the exact bytes beside it in a table or in `HANDOFF.md`.
  Decimal kilobytes crept in and read as plausible — 343 kB for 343,307 B, 753 kB
  for 752,864 B, and "1,123–1,126 kB" for a range that was in bytes — so the
  unit is stated rather than assumed. `KiB` is never used, because it would say
  the same thing twice. A figure that is an estimate with no byte count behind it
  (`~300 kB` of heap) stays as it was measured, and disk sizes are whatever `du`
  printed (7.8 G), because neither is flash.
* Numbers in docs are one board's measurements, not a contract. Say which.
* **Every commit sweeps every file for what it just made untrue.** A sketch, a
  doc, a README, a script comment and these rules are all descriptions of
  something, and the description is the part that rots without anyone noticing.
  Before committing, read the change for what it contradicts — a quoted number,
  a "still carries", a "not yet", a step that has become optional, a rule whose
  behaviour moved — and fix it in that commit, or write down why the old text
  stays. The rules are included: a bullet that no longer describes the code is a
  bug in the bullet, not a fact about the code. `check_docs.py` holds the quoted
  sizes, md5s and links; everything else is a reader's job, so do it before the
  reader does.
* **Say it in words a person who was not there can use.** A commit subject names
  what changed and why it matters, in the vocabulary the docs use — no ticket
  shorthand, no "misc", no list of touched files standing in for a reason. The
  hand-off note, the chat update and the commit body are one sentence seen from
  three screens.

* Never document a step as working because it should. The failed handshake is
  recorded with the baud rates and byte counts tried — including which handshake
  failed: the app's, not ArduinoDroid's — and that precision is what stops the
  next attempt from retreating to a cable it does not need.

### The render pipeline, as it stands

One command per stage, and each one is checked rather than assumed:

| Stage | Command | Cost on 2026-11-02 |
| --- | --- | --- |
| Capture, install, rebuild the tall sheet and the site bundle | `android-companion/capture.sh` | 12 s warm (21 s cold) |
| Verify the committed frames still match the app | inside `./check.sh --full` | 9.7 s |
| Put them online | deploy `artifacts/site/manifest.json` | seconds, and asked for |

* **The capture runs the app, not a drawing.** `CaptureTest` builds `TopBar` and each
  screen, measures at 393 dp, lays them out and draws with Android's renderer; a state
  reached by tapping is reached by tapping, and the clock and the link snapshot are
  frozen so a frame reads the same on every run. Eight frames, `artifacts/renders/`.
* **The gate captures again on purpose.** `check_renders.py` does not trust the PNGs a
  previous command installed; it draws them again and compares pixels, which is why
  "current" means something. That is the one duplicate in the loop and it stays.
* **Side effects are declared, not worked around.** The test writes PNGs as a side
  effect, so Gradle cannot see its output disappear; the test tasks say they are never
  up to date (`app/build.gradle`) instead of every compile being re-run by
  `--rerun-tasks`. The daemon is kept and its JDK passed explicitly.
* **The published page is a scroll, not a contact sheet.**  `publish_renders.py` stacks
  the eight at phone width with a hairline and one caption between them, so dragging
  down walks the app; the strip (`chat-sheet.jpg`) exists for a single message, not for
  reading. The addresses are recorded in
  [`android-companion/HANDOFF.md`](android-companion/HANDOFF.md#where-the-renders-live).
* **An emulator is not the upgrade it sounds like.** It would answer a different
  question — a device screenshot with the phone's fonts and status bar — while the
  layout arithmetic is already the app's own; and on this machine it is not even
  runnable (no `kvm` group, no emulator package, no system image). What captures cannot
  show is behaviour: touch, ripples, scroll physics, the states behind code a JVM cannot
  reach. That is the phone's job, and
  [`HANDOFF.md`](android-companion/HANDOFF.md) keeps that list honest.

## App UI

The app is a drawer (`DrawerHome`) with three rows: `PAIRED` (the row that
opens the link and the bridge), `BOARDS` (the one that opens the shelf) and
`SERIAL`. The first two were labelled `DECKS` and `PROJECTS` until 2026-11-01;
the names behind them did not move — `DecksTab`, `ProjectsTab`, `SECTION_DECKS`,
`SECTION_PROJECTS` and the `"decks"`/`"projects"` prefs keys still describe what
the screens are — so a grep for the old words finds code, not copy.
`SETTINGS` is deliberately not a fourth: it is configuration rather
than navigation, so it hangs off the gear at the outer end of the top bar, past
the link pill. The bottom tab bar is gone on purpose, because a drawer that *is*
the front page needs no second navigation.

* **The top bar carries the owner's mark, centred on the band.** `TopBar` puts
  `R.drawable.logo_mark` on the centre line of the row every screen sits under —
  the crumb's half of the spare width and the pill's half are the same weight, so a
  fixed-width mark between them cannot drift off centre whatever either of them
  says — and `DrawerHome` no longer draws it: the band is the light ground the artwork was drawn
  on, so the file needs no plate behind it and the ghost is the band itself showing
  through the C. The artwork is **one black spiral** — the C — with the ghost sitting
  in the gap between its turns, so every white pixel in the file is canvas and the
  mark is the ink and nothing else. Keying that white, flooding it from the border or
  outlining the disc are three ways to invent a boundary the file does not have; the
  first two kept a white notch beside the shoulder or a white plate behind the whole
  mark, and both shipped here before a reader rejected them. On the field it was also
  a black mark on blue, which is the third reason it belongs on the band. It ships as
  one PNG per density, `mdpi` 26px through `xxxhdpi` 104px, written by
  [`artifacts/logo_icon.py`](artifacts/logo_icon.py) from the file's four `d` strings
  unchanged: a raster because clearing a background is a boundary in the render
  rather than arithmetic on the path, and five exact sizes because one bitmap
  rescaled by the framework is resampled on the device. The archive is checked
  rather than assumed: `./toolchain.sh` hashes every `res/*.png` in the built APK
  and requires each of the five to be among them, by content because AGP shortens
  resource paths and a grep for the name finds nothing. The eyes are about 8% of
  the artwork's width — 2px at mdpi, 6px on a 2.75x phone — so the mdpi capture is
  the harshest view of them, and it is the view to check before calling them missing.
  At 26dp the mark is deliberately shorter than the pill's 27dp: the pill sets the
  bar's height, so the header is as tall as the link state and no taller, and the
  bar's vertical padding is equal above and below so the mark, the crumb, the pill
  and the gear all sit on one centre line. The wordmark below takes back the air the
  mark used to hold.

* **Iconography is drawables, not characters.** The gear and the two chevrons are
  the places where a glyph was standing in for an icon, and both were a gamble on
  the phone's monospace face: U+2699 could arrive as emoji colour or as nothing, and
  U+2039 sat on the text baseline at the font's weight rather than the row's. A real
  icon is `res/drawable/*.xml` — a 24dp grid, filled black — loaded through
  `Ui.icon`, which sizes it square and tints it `SRC_IN`, so one asset serves every
  ink and a lit state is a second `Ui.tint` rather than a second file. The drawer's
  `+`/`−` stay characters on purpose: they are arithmetic signs on a line, not a
  shape standing in for a thing.


* **BOARDS expands to the board alone.** One row per distinct board, labelled
  exactly as the shelf's crumb labels it — `2.4" CYD (resistive)` — and tapping
  that row opens the shelf. The projects are not repeated underneath it: their
  rows live on the shelf beside their bodies, logs and actions, and a copy in
  the drawer could only ever carry a name and a status. A second board is a
  second row, not a second screen.
* **The shelf is alphabetical and folded.** `String.compareToIgnoreCase` on the
  project name, so a new project lands where a reader would look for it. One
  accordion per project: collapsed shows kind, status and name; body, log and
  actions open on tap. Keep the fold state in `ProjectsTab`, not in the store.
  The screen states where it is — `BOARDS › <board>` — from
  `WidgetCatalog.boardLabel`, the same call the drawer's row is drawn from, so
  the crumb and the drawer cannot drift apart.
* **The shelf can be edited in place, and the form is a card.** `ADD A PROJECT`
  and the `EDIT` chip open the same editor with the record's four fields — name,
  tagline, status, notes. A dialog would be a second window the capture cannot
  draw, so the render gate would have nothing to compare; a card is also what the
  shelf already is. A card that ships inside the app has no `EDIT`: its name,
  tagline, body and libraries belong to `widget.json` and the store refreshes
  them on every read, so a field there would forget what it was told. `DELETE`
  exists only for records this app made (`user:` ids) for the same reason, and
  a record added here is `SKETCH` kind, which is why `INSTALLER` and `SOURCE`
  appear only when a link is set and `RECIPE` only on an `IMAGE`.
* **Libraries are one disclaimer at the top of the shelf**, read from the widget
  descriptors and never retyped in Java. Each library is a button that copies the
  exact name ArduinoDroid's library list searches for, and the ArduinoBLE warning
  stays under it.
* **PAIRED lists boards, not addresses.** One row per deck, titled with its name
  and detailed `tag · linked <age>`; the linked card above it says
  `tag · MAC · packet <age>`. The tag is the half of the id that cannot move, and
  the MAC appears once, as the address the live link runs over — so a board that
  re-pairs to a new address keeps its row.
* **A network deck is paired by address and tracked by tag.** A `lan` widget's
  card carries a `DECK` block: one row per paired board (`tag · address · state ·
  age`), with `SIGN IN` and `FORGET`, then `PAIR A DECK`, which asks for the
  address the deck printed. `SIGN IN` fetches the deck's own authorize URL and
  opens it, so the custom scheme still returns into this app; a deck that is
  signed in and out of setup says so instead of asking a page it does not serve.
* **Every UI change ships a render, and the render is linked before anything
  ships.** Draw the screens it touches with `android-companion/capture.sh`, which runs the
  app's own views through Android's renderer, and keep the PNGs in
  `artifacts/renders/`. Then *show* them: put each PNG somewhere that serves the
  bytes over public `https` and post it as a labelled link —
  `[Home, the drawer](https://host/abc.png)` — for every screen the change
  touched, and only those. **Shape matters as much as the file.** The owner reads
  this chat on a phone, and that client renders neither Markdown images nor bare
  URLs: `![home](…)` and a naked `https://…` on its own line both arrive as
  letters on a screen, and a workspace path is worse still, because it opens
  nothing anywhere but here. What arrives is a *label* wrapped around a public
  URL, and the host has to still be serving it when he taps. **The link is a
  gate, not a courtesy: nothing is pushed or published
  until the owner has a link to a viewable render of what changed.** And that link
  is a real `https` address: a chat client cannot open
  `artifacts/renders/home.png`, so [`artifacts/publish_renders.py`](artifacts/publish_renders.py)
  copies the committed frames into `artifacts/site/public/` with a gallery page, and
  that bundle is deployed as the static site below. So the step after a capture is
  `python3 artifacts/publish_renders.py`, then the deploy, then one *labelled* link
  per touched screen — `[Home, the drawer](https://site-c8050e9c1c3546fc9a5dfb219430a19b.freebuff.page/home.png)`. Publishing the renders
  is part of shipping them; publishing the code still needs asking. The fast gate
  itself draws the eight frames whenever a staged file could move a pixel — the
  app's Java, its resources or the capture — so a commit cannot leave them behind;
  posting the link is the one step no checker can do for you. "It compiles"
  says nothing about what a page looks like, and a change nobody looked at is a
  change to be undone later. A change that alters something which is not one of
  those eight screens — the launcher icon, a design page, `dist/index.html`, the
  app's own copy in `artifacts/` — gets a sheet of its own under `artifacts/` and
  the same link, because the rule is "what a user sees ships with its picture",
  not "the renderer's eight frames". The PNGs are the page's own images —
  `tokens.json`'s `screens` maps each frame to one — so a change that redraws a
  screen regenerates that render and commits it with the code and the page. A
  render is a capture of the app, not a device screenshot — say which.
* **A render is a capture from the app.** The frames in `artifacts/renders/` are
  taken by [`android-companion/capture.sh`](android-companion/capture.sh), whose
  `CaptureTest` instantiates `TopBar` and the screen class, lays them out and draws
  them with Android's own renderer on the JVM (Robolectric, native graphics). So the
  pixels carry the platform's fonts, the real vector drawables and the platform's
  text layout: a padding, a weight, a dp size or a row height is the app's own
  arithmetic, a moved row cannot leave a stale picture behind, and the gear is
  `R.drawable.ic_gear` — a drawable, because U+2699 and the two chevrons were
  characters the monospace face might not carry at all. A state the app
  reaches by tapping is reached by tapping — `home-open` clicks BOARDS,
  `projects-open` clicks the first project's header — and a frame is the full height
  of what the screen builds, so a scrolled-away row is still in the picture. The
  clock is frozen for the capture, because a shelf that says `3s ago` would otherwise
  say a different number every run and the gate would flake on a non-change. It is
  not a device screenshot: nothing here has a phone attached, so say "captured", not
  "screenshotted", when the difference matters. [`check_renders.py`](check_renders.py)
  keeps the gate loud: it captures again and fails when a committed PNG no longer
  matches the pixels the app draws, comparing pixels rather than timestamps because
  git stores no mtime.
* **Music is mirrored, not fetched.** Nothing on this board holds a Spotify token
  and nothing asks the user to make a developer app: the companion app reads the
  phone's media session (already permitted by the notification access it needs for
  notifications) and writes one line — `state|position|duration|title|artist|album`
  — down the link at 1 Hz. The deck draws what it was told and never changes
  playback itself. A deck with a transport row makes a chip a **request**: it
  raises a notify on `6f2d0007-…` and the phone runs the key against its own
  session, so the lit chip means the round trip, not the outcome, and the next
  mirror line is what proves it. The phone's half is `DeckService`: it calls
  `setCharacteristicNotification` and writes the CCCD through the same one-op
  queue as everything else, parses the line in `LinkCommand` (20 bytes, three
  actions, a uint32 that restarts at 1 on reboot, so a repeated value is one tap),
  and runs it on its own `MediaController` — `toggle` reading `PlaybackState`
  rather than remembering what it last sent. A deck with no phone linked keeps the Web API
  path, where the chip really is a command. This is also why the
  two decks differ: the WiFi deck signs itself in and can play on its own, and a
  Bluetooth deck shows the phone's own player instead. Cost of the mirror: one
  characteristic, ~120 bytes of RAM, and an optional dependency on a phone in the
  room.

## Adding a widget

1. `mkdir my_deck_cyd24R`, put `my_deck_cyd24R.ino` in it — one file, no
   headers, and the board tag on both.
2. Copy a sibling's shape: `README.md` (and `toolchain.sh` if it needs its own).
3. Compile it, then write down what you verified and what you did not, in the
   README's first paragraph of notes.
4. Add the row to the [root README](README.md) table.
5. If the app bundles it, add `widget.json` plus the synced asset copy and run
   `check_widget_assets.py`.
