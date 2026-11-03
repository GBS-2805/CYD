# Spotify Deck

The second deck, and the only one that is both things at once. It joins your
WiFi, signs in to Spotify with a refresh token, asks the Web API what is playing
every three seconds, and draws the answer — **and** it is a Bluetooth peripheral
that reads the phone's own media session, so the same page works with no sign-in
at all. A linked phone wins: it is what the screen shows and what the chips ask.
The three chips under the track send **previous**, **play/pause** and **next** —
to the phone when one is linked, otherwise to whichever device on the account is
active.

This deck needs `PartitionScheme=huge_app`. Bluetooth does not fit beside WiFi,
mbedTLS and the setup server in the default 1,310,720-byte app slot: the linked
image is 1,878,661 bytes, 143 % of the 1,310,720-byte slot and 59 % of 3,145,728.
phone deck afterwards with the default scheme re-partitions the board back, so
each upload has to carry the scheme its own sketch was built for.

It is the same board as its sibling — **ESP32-2432S024R**, 2.4" resistive CYD —
and the same palette: two monochrome themes of twelve slots each. `THEME_INDEX =
1` selects **INK**, white ink on black; `0` selects **PAPER**. The six accent
slots are a grey ramp rather than hues, so the transport chip, an error and the
WiFi state separate by luminance instead of colour. Spotify's green is not on
this screen.

The folder is `spotify_deck_cyd24R` and the sketch is `spotify_deck_cyd24R.ino`. Anything a
person reads says **Spotify Deck**.

## What the WiFi path needs before it can do anything

1. A **2.4 GHz WiFi network**. The ESP32 has no 5 GHz radio; a network that only
   offers 5 GHz is invisible to it no matter what is typed in.
2. A **Spotify account** with playback control. The API refuses to start or stop
   playback on a free account, so the transport chips need Premium. Reading
   what is playing works either way.
3. **Something already playing** — a phone, the desktop app, a speaker. This is
   not a Spotify Connect device and cannot appear in the device picker. It
   drives a device that is already there.
4. A **Spotify developer app** for the Client ID. No client secret, and no
   refresh token minted elsewhere: the deck mints its own from its own page.

None of the four is needed for the Bluetooth path below: that needs a phone in the
room with the companion app running, and nothing else at all.

### The Spotify app (once, from the phone)

At <https://developer.spotify.com/dashboard>, **Create app**, then under Settings
add **two** redirect URIs and copy the **Client ID**:

| Redirect URI | Who catches it | Which path uses it |
| --- | --- | --- |
| `phonedesk://spotify-callback` | the companion app | **Sign in with the app**, one tap |
| `http://127.0.0.1:8080/callback` | nothing — the page fails to load | **or sign in in the browser**, then paste |

Those two are what Spotify's rules leave. A plain-HTTP redirect is accepted only
for a loopback IP literal (`http://127.0.0.1:PORT`), `localhost` is refused, and
the board can never be the redirect target — which is why the deck serves the
page, keeps the PKCE verifier and does the token exchange on its own side. A
custom scheme is still accepted (`com.example://callback` "can still be used as
before", Spotify, February 2025) and it is the only kind of redirect a browser
will hand to an app, which is what makes the one-tap path possible. Leave the
client secret alone: the page signs in with PKCE (S256), which does not use one.

## The phone link, which needs none of the above

With no client id, no refresh token and no WiFi, the deck still boots into this
same player page: it advertises `<name> <TAG>` with the **Phone Link** service the
phone deck speaks, and the companion app writes what the phone is playing into it
once a second. That is the whole mirror — the deck draws it, extrapolates the bar
between lines, and shows `PHONE` in the header instead of `WIFI`.

| Characteristic | Direction | What this deck does with it |
| --- | --- | --- |
| `6f2d0002-…` time | phone → deck | accepted and dropped: this page has no clock |
| `6f2d0003-…` battery | phone → deck | stored; a low battery is the app's own business, not drawn yet |
| `6f2d0004-…` notice | phone → deck | accepted and dropped: there is no notice card here |
| `6f2d0005-…` identity | deck → phone | the same document `/deck.json` answers, with `"link":"ble"` |
| `6f2d0006-…` now playing | phone → deck | the mirror line, `state\|position\|duration\|title\|artist\|album` |
| `6f2d0007-…` command | deck → phone | **notify**: `<serial>\|prev`, `<serial>\|toggle`, `<serial>\|next` |

The first three exist because the app will not hold a link to a service that is
missing them, not because this page draws them. The last two are the useful pair.

**A chip is a request, never a change of state.** The deck has no player of its
own, so a tap raises a notify and lights the chip; the phone runs the key against
its live session and the next mirror line is what clears the light. Nothing here
writes a play state it cannot verify, which is why the page never lies about what
is playing. With no phone linked the chips fall back to Spotify's Web API, which
is the older path and still what a signed-in deck uses.

Latency, and what is left of it: the deck asks for a **7.5–15 ms** connection
interval with no slave latency when a phone connects
(`updateConnParams(remote_bda, 6, 12, 0, 400)`) against the stack's 30–50 ms
default — a request the phone may ignore. The phone's own cadence is the floor:
one line a second means up to a second of staleness by design.

## Pairing, and signing in from the app

The app can start the sign-in instead of you finding the deck's page. It needs the
deck's address once; after that the deck is a row on the shelf, and the row is
tracked by the board rather than by the address.

1. **Pair.** On the shelf, open **Spotify Deck**, tap **PAIR A DECK** and type the
   address the deck printed (the setup page's step 2 and the serial log at 115200
   both show it). The app reads `http://<deck-ip>/deck.json` and keeps what the
   deck says it is — `spotify_deck_cyd24R-A1B2C3`, where the tag is the low 24 bits
   of that board's efuse MAC. That id is what the shelf tracks, so two Spotify
   decks are two rows and a deck that changes address keeps one.
2. **Sign in.** Tap `SIGN IN`. The app asks the deck for its authorize URL
   (`/auth?app=1&json=1`) and opens it, so the reply still comes back to the app
   and the app still hands the code to the deck. The PKCE verifier and the token
   stay on the board: the app never sees either.
3. The deck exchanges the code, stores the refresh token and restarts. The row now
   reads *signed in*.

The row is only as live as the deck: one that is off, asleep or on another network
shows the last state it reported and how long ago that was. `/deck.json` answers in
setup **and** in the player, but `/auth`, `/code` and `/spotify` belong to setup — a
deck that is already playing has no sign-in page, so hold the footer `SETUP` chip
to get one back. The app says that rather than failing quietly.

## First run, and the setup page when you want it

A board with no stored credentials boots straight into the player page and links
to the phone over Bluetooth: no access point, no wizard, nothing to type. The
setup page is opt-in — hold the footer `SETUP` chip for 1.5 s, or type `setup` and
Enter at 115200 — and *then* the deck draws the SETUP card, raises an **open**
WiFi access point called `Spotify Deck`, and serves a page at `http://192.168.4.1`.
There is nothing to install on the phone and nothing to type into a monitor.

Ask for it when you want the deck to fetch music itself instead of asking the
phone.

1. Join `Spotify Deck` on the phone. Android warns that it has no internet —
   stay connected, the page is local.
2. Open `http://192.168.4.1` in the phone's browser.
3. Type the WiFi network name, its password (or tick *this network has no
   password*), and the Client ID, then **Save and join WiFi**.
4. The page says `wifi connected` and prints the deck's address on your network.
   Open `http://<deck-ip>/spotify` in a new tab, switch the phone back to your own
   WiFi, and reload it.
5. Tap **Sign in with the app** and approve the scopes. Spotify hands
   `phonedesk://spotify-callback?code=...&state=<deck-ip>` to the companion app
   (first time: pick it once in Android's chooser), which sends the code straight
   to the deck at `http://<deck-ip>/code?redir=app&code=...&json=1` and shows what
   the deck answered. Nothing is copied. If the deck does not answer — asleep, or
   the phone on the wrong network — the app opens that address in the browser
   instead, which is the page from before.
6. The deck exchanges that code itself, stores the refresh token, and reboots
   into the player.

Without the app, step 5 is **or sign in in the browser** and the address has to
walk back by hand: approve, let the browser land on
`127.0.0.1:8080/callback?code=...` — which *fails to load*, by design — copy the
whole address out of the bar and paste it into the box on the deck's page.

Either path ends the same way: the code goes one way over the deck's own TLS
connection and comes back as a refresh token in NVS. No token is typed, shown or
pasted, the page never renders a stored password or token back, and the form is
prefilled only with the network name and the client id.

Two things look like failures and are not:

* **The Spotify page will not load while the phone is on `Spotify Deck`.** The
  access point carries no internet; some phones route browser traffic over mobile
  data anyway and some do not. If yours does not, put the phone back on your own
  WiFi and open `http://<deck-ip>/spotify` — the address is on the page's step 2
  and on the deck's screen.
* **The page that lands on `127.0.0.1` is blank.** That is the browser fallback
  only. Nothing is listening there; the `code=` in the address bar is the whole
  point.
* **The app flashes past and shows a card.** That is the app path working: the
  activity reads the code out of Spotify's link, sends it to
  `http://<deck-ip>/code?redir=app&code=...&json=1`, and closes once the deck
  answers `signed-in`. A browser opening instead means the deck did not answer
  within 30 s and the code is going over the old way, which still works.

### The serial wizard (fallback)

Both surfaces run at once and whichever finishes first wins. With a monitor at
**115200**, the five questions are:

| Step | Question | Notes |
| --- | --- | --- |
| 1 | WiFi network name | case matters; blank is refused |
| 2 | WiFi password | echoed as `*`; an open network takes a second Enter |
| 3 | Spotify client id | blank is refused |
| 4 | Client secret | blank for the PKCE flow the setup page uses |
| 5 | Refresh token | echoed as `*`; blank is refused |

Step 5 is the reason this path still exists: a refresh token from any
authorization-code flow for the same client id can be typed in. The pairing rule
is the old one — a token minted with a secret needs the secret stored, a PKCE
token must not have one.

Answers go into NVS under the namespace `spotify` and the board reboots into the
deck. **Nothing is compiled in** — the sketch has no place for a password, and
the folder holds no credentials.

### Running setup again

Hold the footer `SETUP` chip for 1.5 s. The chip is outlined and dim because it
is a thing to hold, not a tap: a stray finger on the player does nothing. Type
`setup` + Enter in the monitor if you have one. Either way the access point and
the page come back at `192.168.4.1`, prefilled with the stored network name and
client id, and the stored password is used straight away so the token step can
reach Spotify. That is how the deck moves to another network or another Spotify
account.

### Forgetting everything

Two surfaces erase the whole `spotify` block — network, password, client id,
secret and token — and restart into setup, so a changed WiFi password cannot lock
the deck out of its own settings:

* the **Forget the stored WiFi and token** button at the bottom of the setup
  page;
* a **four-second hold on the screen while the board boots**. The panel counts it
  down and letting go cancels it, so it cannot happen by accident.

If WiFi is up but the token is wrong, the page shows `api 400` or `api 401` and
the serial log prints the response body's first 120 characters, which is where
Spotify says `invalid_grant`.

## Controls

| Action | Result |
| --- | --- |
| Tap `\|<<` | previous track |
| Tap `PLAY` / `PAUSE` | start or stop on the active device |
| Tap `>>\|` | next track |
| Tap the wordmark | redraw immediately |
| Hold the wordmark 1.5 s | touch calibration |
| Hold the footer `SETUP` chip 1.5 s | the setup page, for another network or account |
| Touch the screen at boot | touch calibration |
| Hold the screen 4 s at boot | forget the stored WiFi and token |
| `setup` + Enter in the monitor | WiFi and Spotify questions again |

Starting playback when nothing is active first transfers playback to the last
known device (`PUT /v1/me/player` with `device_ids`), then sends the command. If
the account has no device at all, the page says `NO ACTIVE DEVICE` and the chips
do nothing, which is the honest answer — there is nothing to start.

## Hardware

Pins are fixed in the sketch and identical to the phone deck:

| Signal | GPIO |
| --- | --- |
| TFT CS / DC / BL | 15 / 2 / 27 |
| Touch CS | 33 |
| SPI SCK / MISO / MOSI | 14 / 12 / 13 |
| RGB status LED (disabled) | 4 / 16 / 17 |

The RGB LED is three discrete LEDs between the GPIOs and 3.3 V, so each channel
lights when its pin is LOW; `disableOnboardLed()` drives all three HIGH at boot.
The backlight is simply switched fully on — this sketch has no brightness
control, unlike the phone deck's PWM one.

## Build

* ESP32 Arduino core **3.3.12**, which is what `toolchain.sh` installs; 2.0.17
  builds it too, and the at-a-glance list above carries both sets of numbers.
  WiFi, `WiFiServer`, `WiFiClientSecure` and `HTTPClient` come from the core,
  and so does the mbedTLS the PKCE digest uses; there is nothing extra to
  install for the network side or the setup page.
* Libraries: **Adafruit GFX Library**, **Adafruit ILI9341**,
  **XPT2046_Touchscreen**.
* Do **not** install **ArduinoBLE**. This sketch does not use BLE at all, and on
  a machine shared with the phone deck its `BLEDevice.h` shadows the core header.
* One file only: `spotify_deck_cyd24R.ino`, no extra headers.

```bash
cd spotify_deck_cyd24R
./toolchain.sh          # installs into ~/.arduino-cyd, then compiles
```

The script is the same one the phone deck ships and shares its install root, so
a machine that can build one can build both. It uses the **default partition
scheme**; see the measurement below for why that is enough.

## Flash it — from a computer, or from the phone

Both decks are ordinary Arduino sketches and both travel on the phone: the app
bundles this one too (`check_widget_assets.py` prints `2 widgets bundled, every
copy matches`, with this sketch at md5 `3b17f04a…`), so it can be pulled from the
same shelf as its sibling.

From a computer, `toolchain.sh` has already compiled it:

```bash
source ~/.arduino-cyd/env.sh
arduino-cli upload --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 spotify_deck_cyd24R
```

From the phone, with an OTG adapter and a USB **data** cable:

1. **BOARDS** → the **Spotify Deck** card → **COPY DROID**, then paste into a
   new ArduinoDroid sketch. If no such card is there, **IMPORT** the `.ino` from
   wherever you put it and open it from the flash shelf instead.
2. Keep it one tab named exactly `spotify_deck_cyd24R.ino`, and add no `.h`
   files: only the `.ino` is compiled.
3. Board **ESP32 Dev Module**; install only Adafruit GFX, Adafruit ILI9341 and
   XPT2046_Touchscreen. **ArduinoBLE must not be installed.**
4. Upload from ArduinoDroid. That is measured, not assumed: on 2026-10-31 the
   same phone, OTG adapter, cable and board flashed successfully from
   ArduinoDroid. The handshake that never synced that day belonged to the
   companion app's own uploader, so it says nothing about this one. A computer
   over the same cable is the fallback when the phone's upload will not take.
5. Nothing needs typing before a flash: no credentials live in the sketch, so the
   first boot draws the player page and shows `NO LINK` until a phone pairs. Hold
   the footer `SETUP` chip, or type `setup` at 115200, when you want the WiFi path;
   that is what raises the `Spotify Deck` access point.

There is no image to merge and no offset to set — this is a sketch upload, not a
factory `.bin`. Uploading a different partition scheme later re-partitions the
board under the other deck too.

## Measured, and not

A clean build in this repo on **2026-10-31** with `arduino-cli` 1.5.2-rc.1, core
**2.0.17** and `--warnings all`:

```
Sketch uses 967413 bytes (73%) of program storage space. Maximum is 1310720 bytes.
Global variables use 50324 bytes (15%) of dynamic memory, leaving 277356 bytes for local variables. Maximum is 327680 bytes.
```

That block is the path it ran on: the same file rebuilt on 2026-11-03 through a
fresh 2.0.17 root prints 967,493 bytes at this repo's path, 80 B higher for ten
characters of path — which is the 967,493 `CONVENTIONS.md` uses as its baseline.

That includes the setup web server, the PKCE exchange, the redrawn setup card and
both pinned roots (about 2.7 kB of PEM), and leaves **335 kB** of the default
scheme free, so dropping Bluedroid still pays for mbedTLS and a certificate chain
per host. Under `PartitionScheme=huge_app` it is 967,413 bytes, **30 %** of
3,145,728: a bigger room, not smaller code.

The 192 kB gap this paragraph used to draw against a phone build "without
Bluetooth" rested on 1,164,489 bytes, which no committed file produces: the
phone deck carries its BLE link from `d53664ec` on. The pair that rebuilds is
967,493 B above against 1,162,877 B on 2.0.17 — 195,384 B, 191 kB.

An earlier version of this note recorded 1,114,485 bytes (85 % of 1,310,720), 192 kB free and
a 49 kB gap to the phone deck for the same sketch. That figure does not
reproduce: the committed file links at 967,413 bytes with the same core, the same
`arduino-cli` and `--warnings all`, on a clean build path, twice over, so the
smaller number is the one that belongs here. The seven warnings below still
match, warning for warning.

Seven warnings under `--warnings all`, all accounted for: five are palette slots
(`C_ACCENT`, `C_ACCENT_DIM`, `C_PURPLE`, `C_YELLOW`, `C_GREEN`) that this widget
has no draw call for yet, kept because the house palette is twelve fixed slots;
the other two are `-Wformat-truncation` in `drawWrappedText`, older than this
change and the greedy wrap truncating where it intends to. The default warning
level hides both sets, and an incremental rebuild can also report a stale,
smaller link — these numbers come from a fresh build path.

**Nothing here has run on the board.** No flash of this sketch has been confirmed
and no request has ever left this chip. Specifically unverified: the access point and the setup
page on a real phone (including whether the phone keeps mobile data for the
Spotify tab while joined to the deck), the S256 challenge as Spotify receives it,
the code exchange and its pinned-certificate handshake to
`accounts.spotify.com`, SNTP setting the clock before the first request, the
four-second boot hold that forgets the credentials (and the 1.5 s `SETUP` chip
hold, on a panel whose mapping has not been checked), the CRLF fix against a
monitor that actually sends `\r\n`, the WiFi join and the 5 s reconnect, the
token refresh, the JSON reader against a real payload (the album-first trap below
is reasoned from Spotify's documented shape, not seen on the wire), the
calibration fit on a panel, and every tap target. The first flash is the test of
all of that. What *is* measured is the chain: `api.spotify.com` and
`accounts.spotify.com` were each verified against the root compiled into the
sketch from a desktop on 2026-10-31.

### Headroom

Measured here on 2026-11-02 with [`../check_docs.py`](../check_docs.py) `--refresh`,
core **3.3.12**, `PartitionScheme=huge_app` — the scheme this deck now needs, and
which `check_headroom.py` knows about per deck so the ceiling still comes from the
compiler:

| | used | free | ceiling |
| --- | --- | --- | --- |
| flash | 1,878,661 | **1,267,067** | 3,145,728 (59 % used) |
| DRAM | 69,092 globals | **258,588** | 327,680 |

The two figures are this tree's own build through `~/.arduino-cyd-3/env.sh` on
2026-11-02, the root whose `env.sh` pins its own data and library dirs. Sourcing
`~/.arduino-cyd/env.sh` instead sets only `PATH`, so that build goes through
`~/.arduino15` and prints **1,878,613 B** for the same sketch, 48 B under with the
globals identical — the build-root sensitivity
[`../CONVENTIONS.md`](../CONVENTIONS.md#build) records, not a different sketch, since
both read md5 `3b17f04a…`. Quote the root you measured on rather than reconciling the
last two digits.

Without Bluetooth the sketch was 1,125,781 bytes on the default scheme — the
pre-link file, md5 `00bb1560`, since today's has the link compiled in and there
is no flag to switch it off; rebuilt from that file on 2026-11-03 and it still
prints 1,125,781 B with 53,636 B of globals. The
link costs **about 735 kB** of flash and 15,456 B of globals, which is Bluedroid, not
this sketch's own code. On the default scheme the linked build reads 143 % and
will not link at all — that is the measurement that forced `huge_app`, and
[`toolchain.sh`](toolchain.sh) now pins it.

The check refuses a deck that overflows, that drops under **64 KiB** of free flash
or that leaves under **160 KiB** of DRAM — the last floor is just over the
153,600 B a full 320x240 RGB565 framebuffer needs, which this deck does not
allocate but a sibling does. It is still the wider of the two decks' margins, and
it is the 155 kB the 3.x WiFi stack cost that ate the rest: on 2.0.17 the same
sketch left 343,307 B. Re-measure with
`python3 ../check_headroom.py spotify_deck_cyd24R` from the repo root, after
sourcing a toolchain.

### At a glance

**Verified here** — none of this needs hardware:

* The committed sketch links under the documented core **2.0.17** at 967,413
  bytes (73 %), the measurement above, taken before the deck grew its
  `/deck.json` identity. 2.0.17 has not been re-measured since, so quote that as
  the pre-identity figure and not as the current size.
* Re-measured on 2026-11-01 with `arduino-cli` 1.5.2-rc.1, core **3.3.12** links
  the committed file at 1,125,781 bytes, 85 % of 1,310,720, with
  53,636 bytes of globals, 16 % of 327,680, `EXIT=0` under `--warnings all`. That
  is 2,540 B above the 1,123,241 an earlier root measured, and the difference is
  not the build path: a comment-only edit links to exactly the same size, while
  the identity characteristic the sketch gained since moves it by kilobytes. Read
  the figure as 1,123,241–1,125,781 B
  rather than a fixed count. The app callback path is **992 B** of it — 212 B for
  the second redirect URI and the `state` field, 780 B for the one-line answer the
  app now reads. The two cores differ
  by ~155 kB, which is the WiFi stack as IDF 5 links it rather than mbedTLS (see
  [the build note](../CONVENTIONS.md#build)); 3.3.12 is what `toolchain.sh` pins
  now, so that is where the release numbers come from, and 2.0.17 is kept as the
  A/B build rather than the shipping one.
* **It needs `huge_app` now, and that is the scheme `toolchain.sh` pins.**
  Without Bluetooth the 3.3.12 build was 1,125,781 bytes, 85 % of 1,310,720:
  measured from the pre-link sketch (`00bb1560`), not a flag on today's file.
  With the link it is 1,878,661 bytes: **143 %** of 1,310,720, **59 %** of 3,145,728.
  An earlier pair of runs differed by 48 B; the pre-commit hook and a direct
  compile both print 1,878,661 now, so the single figure is the measured one.
  `default.csv`
  sizes `app0` and `app1` at 1,310,720 each, so on the default scheme this deck no
  longer links at all — not a tighter fit, a refusal. Uploading it re-partitions
  the board, and flashing the phone deck afterwards puts the default scheme back.
* Seven warnings, all accounted for above.
* The bundled copy in `app/src/main/assets/widgets/spotify_deck_cyd24R/` matches
  this folder byte for byte after `:app:syncWidgets`, and
  `check_widget_assets.py` prints `2 widgets bundled, every copy matches`.
* Both pinned roots, each checked from a desktop against its host on
  2026-10-31.

**Unverified — only the board can settle these.** No sketch in this repo has
been flashed, so all of the following are still assumptions:

* **The whole Bluetooth path is new and has never run.** No phone has connected to
  this deck and no mirror line has been parsed on the board. The app side of a tap
  now exists — it subscribes to the command characteristic and runs
  `prev`/`toggle`/`next` against the phone's own media session — but no notify has
  been seen arriving on a phone, so a chip logging `[cmd] 3|toggle` coming back as
  playback is still untested. `PartitionScheme=huge_app` has also never been
  flashed to this board, and the setup server has never run beside the Bluetooth
  stack.
* The access point, the setup page on a real phone, and whether that phone keeps
  mobile data for the Spotify tab while it is joined to the deck.
* The S256 challenge as Spotify receives it, the code exchange, and the pinned
  handshake to `accounts.spotify.com`.
* SNTP setting the clock before the first request, the WiFi join and the 5 s
  reconnect, and the token refresh.
* Every touch path, on a panel whose mapping has never been read: the 1.5 s
  `SETUP` hold, the 1.5 s wordmark hold, the four-second boot hold, the
  calibration fit and every tap target.
* The CRLF fix against a monitor that really sends `\r\n`, and the depth-aware
  JSON reader against a real payload — the album-first trap is reasoned from
  Spotify's documented shape, not seen on the wire.
* The app path end to end: Android offering **Off-site** for
  `phonedesk://spotify-callback` and remembering the choice, the activity asking
  the deck at `http://<deck-ip>/code?redir=app&code=...&json=1` and getting
  `signed-in` back, the browser fallback when that request fails, and the deck
  exchanging a code minted for the custom-scheme URI rather than the loopback one.
  None of it has run on a phone. What has tests is the link parser and the address
  it builds — fifteen JVM checks in `android-companion/test/HostTests.java`.

## Traps worth keeping

* **An album's name comes before the track's.** Spotify's `item` object starts
  with `album`, whose own `name` and `artists` sit at depth two. A flat search
  for `"name"` labels every song with its album, and for a flat search for
  `"artists"` does the same with the album artist. That is why the JSON reader
  here is depth-aware: it only matches keys among the immediate members of the
  object it was handed.
* **A null `item` is not an error.** During an advert, or before anything has
  loaded, `item` is `null` and `currently_playing_type` is the only field that
  explains why. The reader prints `AD BREAK` and keeps polling.
* **204 is an answer.** `GET /v1/me/player` returns `204 No Content` when the
  account has no active device; a client that only understands 200 treats it as
  a failure and shows an error for a normal state.
* **The bar needs its own clock.** Polling every three seconds would make the
  progress bar jump in steps, so it is drawn from `progress_ms` plus the time
  since the snapshot arrived, and the page is only fully repainted when
  something actually changed.
* **Spotify has no device-code flow, and the callback can never be the board.**
  The "type a short code at spotify.com/pair" flow this deck wanted does not
  exist — it is still an open feature request — and Spotify's redirect rules
  allow plain HTTP only for a loopback literal. That pair of facts is why setup is
  a page the deck serves itself: the board holds the PKCE verifier, the phone
  holds the login, and only the authorization code crosses between them.
* **A custom scheme is the one redirect a browser will hand to an app.** That,
  not a nicer error page, is why the one-tap path exists: Chrome will never pass
  `http://127.0.0.1:8080` to another app, but it does pass
  `phonedesk://spotify-callback`. The scheme carries no host, so the deck puts its
  own address in `state`, the field Spotify echoes back untouched; the app makes
  one request to `http://<deck-ip>/code?redir=app&code=...&json=1` and asks for a
  one-line answer, and the browser only takes over when that request gets none.
* **A partition scheme belongs to the flash, not to the sketch.** A board carries
  one table at a time, so a deck built for `huge_app` and a deck built for the
  default both work — but only in the order they were flashed. This is why
  `toolchain.sh` pins the scheme instead of leaving it to the flag of the day, and
  why `check_headroom.py` holds a per-deck scheme rather than one global FQBN.
  That is why the APK now asks for INTERNET and cleartext: one outbound request to
  a LAN address, where the browser used to make it. Nothing secret rides in
  `state` — a forged one can only aim a code at the wrong LAN address, where the
  verifier on the board cannot complete it. That `GET /code` changes state, which
  is unusual and deliberate: the code is single-use, so a replayed load only earns
  `invalid_grant`.
* **One Enter used to answer two questions.** Monitors send `\r\n`, and a wizard
  that commits on both characters answers the next question with nothing typed.
  That is how the WiFi password prompt was skipped: the SSID committed on the
  `\r`, and the `\n` behind it passed the password step. Both the wizard and the
  `setup` command parser now drop a `\n` that follows a `\r`, and a blank WiFi
  password needs a second deliberate Enter instead of being accepted silently.
* **Two hostnames, two certificate authorities.** Measured from a desktop on
  2026-10-31: `api.spotify.com` chains to **DigiCert Global Root G2**,
  `accounts.spotify.com` to **Starfield Root Certificate Authority - G2**. Each
  client pins its own root with `setCACert()`, so one root for both would fail on
  the other host. Pinning a root rather than a leaf survives Spotify rotating its
  server certificates; it does not survive Spotify changing authority, which
  shows up as a failed handshake and `SPOTIFY UNREACHABLE` with `[poll] status 0`
  in the log.
* **A pinned certificate needs the right date.** The board boots at 1970 with no
  RTC battery, and every certificate then reads *not yet valid*. The deck asks
  SNTP (`pool.ntp.org`, `time.google.com`) and refuses to open a connection until
  the clock is plausible, drawing `SETTING CLOCK` rather than a misleading api
  error. A network that blocks NTP leaves it there — that is the one thing this
  design cannot work around without giving up validation.
* **The network runs on the other core.** Every request happens in a task, and
  the UI only ever reads a finished snapshot under a spinlock held for a
  memcpy. A TLS handshake in `loop()` would freeze the panel for a second on
  every poll, and taps would be dropped.
* **Secrets are echoed as `*` but stored in the clear** in NVS, like any other
  ESP32 credential block. Anyone with the board and `esptool` can read the
  refresh token. Revoke it from the Spotify dashboard if the board leaves your
  hands.

### Keeping the pins fresh

`refresh_pins.py` in this folder is the measurement above, mechanised:

```bash
python3 spotify_deck_cyd24R/refresh_pins.py            # rewrite the sketch if a root moved
python3 spotify_deck_cyd24R/refresh_pins.py --check    # report only; exit 1 when stale
```

It fetches each host's chain, walks it up to the issuing root in the machine's
trust store, and accepts that root only when `openssl s_client` validates the
host against it with `-verify_return_error -verify_hostname` — the same test the
board performs. It then compares the root's SHA-256 with the PEM already in the
sketch and rewrites the array only when they differ, so a run on a good day
changes nothing. Exit 2 means it could not get a trustworthy answer (no network,
a TLS-inspecting proxy, an issuer missing from the store), not that the pins are
stale.

So when the deck says `SPOTIFY UNREACHABLE`, run `--check` first: if it reports a
stale pin, Spotify changed authority, the sketch needs a reflash; if it says the
pins are in sync, the fault is the clock, the network or the token, and the
trouble table below applies. It needs `openssl` and nothing else.

## Limits worth knowing

* **Not a Spotify Connect device.** It cannot be picked as an output; it can only
  drive one that already exists, and playback control needs Premium.
* **Three-second lag**, plus one or two seconds of TLS on top. The progress bar
  hides the gap; the title does not.
* The token is refreshed a minute before it expires. A `401` also forces one
  refresh and one retry, and then gives up until the next poll.
* **The access point is open while the deck is in setup**, because the page is
  where the credentials are typed and a phone-only setup should not need a second
  password read off a 320 px screen. It exists only during setup, it never shows
  a stored secret, and anyone in range for those minutes can load the form.
* **The setup access point answers every DNS name with its own address.** That is
  what makes the phone offer "Sign in to network" on its own instead of staying on
  mobile data; without it the page has to be typed. The cost is that nothing
  resolves while the phone is joined to the deck, which is why the Spotify login
  step needs the phone back on your own WiFi. A phone using DNS-over-HTTPS ignores
  the hint entirely, so the address stays printed on the card and on the page.
* **The page has to move when the phone does.** Spotify's login needs the phone
  back on the home network, so every setup page carries a live `your WiFi
  address` line, polled from `/where` as soon as DHCP lands. Open that link in a
  new tab while still on the access point, switch the phone's WiFi, reload the
  tab, and the sign-in page is back without an address typed from the panel.
  It is the numeric address DHCP actually handed out, not a fixed name: mDNS
  measured 37 kB of flash for `spotify-deck.local`, and Android's browser has no
  resolver for `.local` anyway.
* **The PKCE verifier outlives the page, on purpose.** The gap between sending
  the challenge and pasting the code is spent on Spotify's site, and a reload or a
  power blip in that window used to waste the code because the verifier only
  lived in RAM. It is now written to NVS beside the credentials, reloaded when
  setup starts again, and dropped when it is used, when the network or client id
  is re-entered, and by either wipe. Like the refresh token it sits in the clear,
  so it is one more thing a reader of the flash can see.
* Nothing is cached: no album art, no history. The panel has no PSRAM and no
  framebuffer, so every page is drawn straight to the display.
* Touch calibration is shared with the phone deck (same `touchcal` namespace and
  the same version), so calibrating either sketch calibrates the board for both.

## Trouble

| Symptom | Cause | What to do |
| --- | --- | --- |
| SETUP card, no prompts | the monitor is on another port or baud | 115200, the CH340 port, then reset the board |
| `192.168.4.1` will not load | the phone is not joined to `Spotify Deck` | join it; Android's "no internet" warning is expected. The bottom line of the setup card says whether a phone joined and whether it asked for the page |
| Spotify sign-in page will not load | the phone is routing through the deck's access point | switch the phone to your own WiFi and use `http://<deck-ip>/spotify` |
| `not on WiFi yet` | the deck has not joined the network | wrong password, or a 5 GHz-only network; the page retries every 3 s |
| `Spotify said no` | the code expired (ten minutes) or the redirect URI does not match | start the sign-in again from the deck's page; both URIs above have to be listed on the Spotify app |
| approving does nothing, or `no app can open this link` | the companion app is missing, or the APK predates the callback | install the APK from [`android-companion/dist`](../android-companion/dist), or use the browser button and paste |
| the app opens, then the browser says the site cannot be reached | `state` named an address the phone cannot reach, usually after a network change | reopen `http://<deck-ip>/spotify` and sign in again |
| `SETTING CLOCK` for more than a few seconds | NTP is blocked on the network | allow UDP 123 out; without the date no certificate validates |
| `SPOTIFY UNREACHABLE`, serial `[poll] status 0` | the TLS handshake failed: a rotated CA, or no clock | check `[time]` in the log, then run `refresh_pins.py` |
| setup will not join after a WiFi password change | the stored network no longer works | press Forget on the page, or hold the screen 4 s at boot |
| `NO WIFI` | wrong SSID/password, or a 5 GHz-only network | run `setup` again; 2.4 GHz only |
| `api 401` | the refresh token was revoked or the client id/secret pair does not match it | mint a new token, or re-enter the id and secret |
| `api 403`, serial says `PREMIUM_REQUIRED` | free account | reading still works, playback control does not |
| `NO ACTIVE DEVICE` | nothing is playing anywhere | start Spotify on the phone or desktop first |
| bars do not fill | the firmware was built before a change | recompile; nothing is read from the app at runtime |
| taps land in the wrong place | the stored fit is from another panel | hold the wordmark 1.5 s and re-tap the three dots |

## widget.json: how the app finds this widget

`widget.json` in this folder is this sketch's side of the contract with the
companion app: `id: spotify_deck_cyd24R`, the sketch name, the project-card copy, the
libraries to install and the library to refuse. The app's Gradle task walks every
`*/widget.json` beside `android-companion/` and copies the descriptor and its
sketch into `app/src/main/assets/widgets/<id>/` at build time, so
`check_widget_assets.py` in the repo root must report both copies in sync — it
does, for this widget, from a `:app:syncWidgets` run on 2026-11-01.

There is **no `ble` block**: this deck has no Bluetooth link to find, so the
app's scan side has nothing to look for. Turning the descriptor into a project
card with its own setup steps is still unbuilt; the sketch, the descriptor and
the asset copy are done.
