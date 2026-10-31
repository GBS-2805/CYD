# Agent notes

Read [CONVENTIONS.md](CONVENTIONS.md) before changing anything here. It is the
source of truth for how widgets in this repo are laid out, built, coloured,
documented and released; this file exists only to make sure you see it.

Non-negotiables if you read nothing else:

* One widget per folder, one `.ino` named after the folder, no extra headers.
* Libraries: Adafruit GFX, Adafruit ILI9341, XPT2046_Touchscreen — and never
  ArduinoBLE, whose `BLEDevice.h` shadows the ESP32 core's BLE headers.
* Every folder keeps one `README.md`. A finding true of the whole board goes into
  `CONVENTIONS.md`; one true of a single widget goes into that widget's README.
* Build with the widget's `toolchain.sh`, and report only what actually ran. Never
  claim a compile, a flash or a measurement that did not happen.
* Do not commit, push or deploy unless asked, and do not touch another widget's
  folder or another chat's worktree.

The Android app's Java belongs to its own chat; this tree takes sketch and asset
copies only.
