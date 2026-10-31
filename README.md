# ESP32_Widgets

Small ESP32 gadgets that share one naming scheme and one companion app. Each
project is a folder with one `README.md` that says what it is, how to flash it,
the traps already hit, and what is verified and what is not.

The rules that shape those folders — hardware, build, palette, NVS, BLE identity,
docs — are in [CONVENTIONS.md](CONVENTIONS.md). Read it before adding a widget.

| Project | Folder | What it is | Read |
| --- | --- | --- | --- |
| **Phone Deck** | [`cyd_phone_deck/`](cyd_phone_deck) | ESP32-2432S024R (2.4" CYD) phone dashboard: clock, battery and notifications | [README](cyd_phone_deck/README.md) |
| **ESP32_Widgets app** | [`android-companion/`](android-companion) | **Off-site**, the Android app: CYD monitor, BLE bridge, USB flasher and sketch shelf | [README](android-companion/README.md) · [HOWTO](android-companion/HOWTO.md) · [handoff](android-companion/HANDOFF.md) |

**Phone Deck** is a BLE peripheral exposing a small **Phone Link** GATT
service. Each board names itself after its own hardware — `Phone Deck <TAG>`,
where the tag is the chip's MAC — and the first phone to connect claims
it, so every board flashed is a distinct widget that answers to one phone. It
renders whatever it receives; it cannot read a phone on its own. Apple's ANCS is
iOS-only, so on Android the companion app is not optional.

The companion — **Off-site** — is more than a bridge: its **DECKS** tab monitors
the link (RSSI, uptime, packets, nearby and saved boards), **PROJECTS** keeps the
sketches, including this repo's deck sketch copied into the APK at build time and
ready to paste into ArduinoDroid, and **BOARD** drives the ESP32 over an OTG
cable. It files its projects under **ESP32_Widgets**, with this one as
**Phone Deck**.

A built debug APK is at [`android-companion/dist/PhoneDeckBridge-debug.apk`](android-companion/dist/PhoneDeckBridge-debug.apk).

Nothing here needs Bluetooth pairing. The deck is a GATT peripheral, not a
keyboard, so it does not appear in the phone's Bluetooth settings list.

The folder `cyd_phone_deck/` and the file `cyd_phone_deck.ino` keep their old
names deliberately: ArduinoDroid compiles one `.ino` whose name must match its
folder, and the companion app's Gradle task copies that exact path into the APK.
Everything a person reads — BLE name, header wordmark, the app's project card,
these docs — says **Phone Deck**; a board flashed before the rename still
advertises `ESP32 Phone_Widget <TAG>`, which the app matches too.
