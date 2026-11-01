# ESP32_Widgets

Small ESP32 gadgets that share one naming scheme and one companion app. Each
project is a folder with one `README.md` that says what it is, how to flash it,
the traps already hit, and what is verified and what is not.

The rules that shape those folders — hardware, build, palette, NVS, BLE identity,
docs — are in [CONVENTIONS.md](CONVENTIONS.md). Read it before adding a widget.

A sketch change is verified by [`check_headroom.py`](check_headroom.py), which
compiles the decks and fails one that no longer fits its partition;
[`selftest_headroom.py`](selftest_headroom.py) proves the check's own refusals in
about a second, with no toolchain installed.

Each chat works on its own branch in its own worktree, so
[`check_branches.py`](check_branches.py) names the branches holding work `main`
does not have — commits and bytes, no compile — and drops the ones whose content
it already has, which makes it the list to merge from before trusting a number
here.

| Project | Folder | What it is | Read |
| --- | --- | --- | --- |
| **Phone Deck** | [`phone_deck_cyd24R/`](phone_deck_cyd24R) | ESP32-2432S024R (2.4" CYD) phone dashboard: clock, battery and notifications | [README](phone_deck_cyd24R/README.md) |
| **Spotify Deck** | [`spotify_deck_cyd24R/`](spotify_deck_cyd24R) | The same board on the network: what you are playing on Spotify, with previous / play / next on the screen | [README](spotify_deck_cyd24R/README.md) |
| **ESP32_Widgets app** | [`android-companion/`](android-companion) | **Off-site**, the Android app: CYD monitor, BLE bridge, USB flasher and sketch shelf | [README](android-companion/README.md) · [HOWTO](android-companion/HOWTO.md) · [handoff](android-companion/HANDOFF.md) |

**Phone Deck** is a BLE peripheral exposing a small **Phone Link** GATT
service. Each board names itself after its own hardware — `Phone Deck <TAG>`,
where the tag is the chip's MAC — and the first phone to connect claims
it, so every board flashed is a distinct widget that answers to one phone. It
renders whatever it receives; it cannot read a phone on its own. Apple's ANCS is
iOS-only, so on Android the companion app is not optional.

The companion — **Off-site** — is more than a bridge: its **PAIRED** row monitors
the link (RSSI, uptime, packets, nearby and saved boards), **BOARDS** keeps the
sketches, including this repo's deck sketch copied into the APK at build time and
ready to paste into ArduinoDroid, and **SERIAL** drives the ESP32 over an OTG
cable. It files its projects under **ESP32_Widgets**, with this one as
**Phone Deck**.

A built debug APK is at [`android-companion/dist/PhoneDeckBridge-debug.apk`](android-companion/dist/PhoneDeckBridge-debug.apk).
A deck change reaches that APK by commit rather than by editing the app; the exact commands are in [DECK_HANDOFF.md](DECK_HANDOFF.md).

Nothing here needs Bluetooth pairing. The deck is a GATT peripheral, not a
keyboard, so it does not appear in the phone's Bluetooth settings list.

Widget folders are named `<topic>_deck_<board>`: `phone_deck_cyd24R/` and
`spotify_deck_cyd24R/` are one deck pinned to the 2.4" resistive CYD. The tag is
part of the folder, the sketch and the descriptor `id`, because one deck is meant
to exist on several panels at once — `cyd28R` and `cyd28C` next, then `ili9341_28`
on a devkit, `tdisplay_s3`. How to write a tag, and why one never carries a quote
or a dot, is in the [CONVENTIONS](CONVENTIONS.md#naming-topic_deck_board).
ArduinoDroid compiles one `.ino` whose name must match its folder, and the app's
Gradle task reads both names from `widget.json`, so a rename moves the folder and
the sketch together. This deck was `cyd_phone_deck/` until 2026-10-31, and
`phone_deck/` until the same day; a board flashed before either rename still
advertises `ESP32 Phone_Widget <TAG>`, which the app matches too.
