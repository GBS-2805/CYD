# CYD Phone Deck

The deck is two halves, each in its own folder: the firmware and the Android app
that feeds it. Each ships a `HANDOFF.md` with state and open questions, and a
`HOWTO.md` for the everyday tasks. A third folder holds the self-check you flash
before trusting a board with either.

| Folder | What it is | Read |
| --- | --- | --- |
| [`cyd_phone_deck/`](cyd_phone_deck) | ESP32-2432S024R firmware: clock, battery and notifications on a 2.4" resistive CYD | [README](cyd_phone_deck/README.md) · [HOWTO](cyd_phone_deck/HOWTO.md) · [handoff](cyd_phone_deck/HANDOFF.md) |
| [`android-companion/`](android-companion) | **Off-site**, the Android app: CYD monitor, BLE bridge, USB flasher and sketch shelf | [README](android-companion/README.md) · [HOWTO](android-companion/HOWTO.md) · [handoff](android-companion/HANDOFF.md) |
| [`cyd_board_check/`](cyd_board_check) | ESP32-2432S024R self-check: display, touch, RGB LED and flash in one screen plus a serial report, before the deck firmware | [README](cyd_board_check/README.md) · [HOWTO](cyd_board_check/HOWTO.md) · [handoff](cyd_board_check/HANDOFF.md) |

The deck is a BLE peripheral exposing a small **Phone Link** GATT service. The
firmware advertises as "Phone Deck" and renders whatever it receives; it cannot
read a phone on its own. Apple's ANCS is iOS-only, so on Android the companion
app is not optional.

The companion — **Off-site** — is more than a bridge: its **DECKS** tab monitors
the link (RSSI, uptime, packets, nearby and saved boards), **PROJECTS** keeps the
sketches, including this repo's deck sketch copied into the APK at build time and
ready to paste into ArduinoDroid, and **BOARD** drives the ESP32 over an OTG
cable and reads back what it prints.

A built debug APK is at [`android-companion/dist/PhoneDeckBridge-debug.apk`](android-companion/dist/PhoneDeckBridge-debug.apk).

Nothing here needs Bluetooth pairing. The deck is a GATT peripheral, not a
keyboard, so it does not appear in the phone's Bluetooth settings list.
