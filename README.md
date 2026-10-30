# ESP32_Widgets

Small ESP32 gadgets that share one naming scheme and one companion app. Each
project is a folder with a `README.md`, a `HOWTO.md` and a `HANDOFF.md` that
records what is verified and what is not.

| Project | Folder | What it is | Read |
| --- | --- | --- | --- |
| **ESP32 Phone_Widget** | [`cyd_phone_deck/`](cyd_phone_deck) | ESP32-2432S024R (2.4" CYD) phone dashboard: clock, battery and notifications | [README](cyd_phone_deck/README.md) · [HOWTO](cyd_phone_deck/HOWTO.md) · [handoff](cyd_phone_deck/HANDOFF.md) |
| **Board check** | [`cyd_board_check/`](cyd_board_check) | Self-check for the same board: display, touch, RGB LED and flash in one screen plus a serial report | [README](cyd_board_check/README.md) · [HOWTO](cyd_board_check/HOWTO.md) · [handoff](cyd_board_check/HANDOFF.md) |
| **ESP32_Widgets app** | [`android-companion/`](android-companion) | **Off-site**, the Android app: CYD monitor, BLE bridge, USB flasher and sketch shelf | [README](android-companion/README.md) · [HOWTO](android-companion/HOWTO.md) · [handoff](android-companion/HANDOFF.md) |

**ESP32 Phone_Widget** is a BLE peripheral exposing a small **Phone Link** GATT
service. The firmware advertises as "ESP32 Phone_Widget" and renders whatever it
receives; it cannot read a phone on its own. Apple's ANCS is iOS-only, so on
Android the companion app is not optional.

The companion — **Off-site** — is more than a bridge: its **DECKS** tab monitors
the link (RSSI, uptime, packets, nearby and saved boards), **PROJECTS** keeps the
sketches, including this repo's deck sketch copied into the APK at build time and
ready to paste into ArduinoDroid, and **BOARD** drives the ESP32 over an OTG
cable. It files its projects under **ESP32_Widgets**, with this one as
**ESP32 Phone_Widget**.

A built debug APK is at [`android-companion/dist/PhoneDeckBridge-debug.apk`](android-companion/dist/PhoneDeckBridge-debug.apk).

Nothing here needs Bluetooth pairing. The deck is a GATT peripheral, not a
keyboard, so it does not appear in the phone's Bluetooth settings list.

The folder `cyd_phone_deck/` and the file `cyd_phone_deck.ino` keep their old
names deliberately: ArduinoDroid compiles one `.ino` whose name must match its
folder, and the companion app's Gradle task copies that exact path into the APK.
Everything a person reads — BLE name, header wordmark, these docs — says
**ESP32 Phone_Widget**.
