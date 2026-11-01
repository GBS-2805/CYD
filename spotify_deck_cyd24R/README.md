# Spotify Deck

The second deck, and the first one that is not a Bluetooth peripheral. It has no
companion app and no phone link: it joins your WiFi, signs in to Spotify with a
refresh token, asks the Web API what is playing every three seconds, and draws
the answer. The three chips under the track send **previous**, **play/pause** and
**next** back to whichever device on the account is active.

It is the same board as its sibling — **ESP32-2432S024R**, 2.4" resistive CYD —
and the same palette: two monochrome themes of twelve slots each. `THEME_INDEX =
1` selects **INK**, white ink on black; `0` selects **PAPER**. The six accent
slots are a grey ramp rather than hues, so the transport chip, an error and the
WiFi state separate by luminance instead of colour. Spotify's green is not on
this screen.

The folder is `spotify_deck_cyd24R` and the sketch is `spotify_deck_cyd24R.ino`. Anything a
person reads says **Spotify Deck**.

## What it needs before it can do anything

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

### The Spotify app (once, from the phone)

At <https://developer.spotify.com/dashboard>, **Create app**, then under Settings
add the redirect URI `http://127.0.0.1:8080/callback` and copy the **Client ID**.
Spotify refuses a plain-HTTP redirect unless it points at a loopback literal, so
that address is the only one that works — and it can never reach the board, which
is exactly why the deck serves the page itself and does the token exchange on
the other end. Leave the client secret alone: the page signs in with PKCE
(S256), which does not use one.

## First run: the setup page

With no stored credentials the deck draws a SETUP card, raises an **open** WiFi
access point called `Spotify Deck`, and serves a page at `http://192.168.4.1`.
There is nothing to install on the phone and nothing to type into a monitor.

1. Join `Spotify Deck` on the phone. Android warns that it has no internet —
   stay connected, the page is local.
2. Open `http://192.168.4.1` in the phone's browser.
3. Type the WiFi network name, its password (or tick *this network has no
   password*), and the Client ID, then **Save and join WiFi**.
4. The page turns into step 2, says `wifi connected`, and shows the deck's
   address on your network. Tap **Open Spotify sign-in** and approve the scopes.
5. The browser lands on `127.0.0.1:8080/callback?code=...` and *fails to load*.
   That is the design: copy the whole address from the address bar, paste it into
   the box on the deck's page, and press **Finish setup**.
6. The deck exchanges that code itself, stores the refresh token, and reboots
   into the player. Put the phone back on your own WiFi.

No token is typed, shown or pasted: the code goes one way over the deck's own
TLS connection and comes back as a refresh token in NVS. The page never renders
a stored password or token back, and the form is prefilled only with the network
name and the client id.

Two things look like failures and are not:

* **The Spotify page will not load while the phone is on `Spotify Deck`.** The
  access point carries no internet; some phones route browser traffic over mobile
  data anyway and some do not. If yours does not, put the phone back on your own
  WiFi and open `http://<deck-ip>/spotify` — the address is on the page's step 2
  and on the deck's screen.
* **The page that lands on `127.0.0.1` is blank.** Nothing is listening there;
  the `code=` in the address bar is the whole point.

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

* ESP32 Arduino core **2.0.17** (what the numbers below are from). WiFi,
  `WiFiServer`, `WiFiClientSecure` and `HTTPClient` come from the core, and so
  does the mbedTLS the PKCE digest uses; there is nothing extra to install for
  the network side or the setup page.
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

## Measured, and not

A clean build in this repo on **2026-10-31** with `arduino-cli` 1.5.2-rc.1, core
**2.0.17** and `--warnings all`:

```
Sketch uses 967413 bytes (73%) of program storage space. Maximum is 1310720 bytes.
Global variables use 50324 bytes (15%) of dynamic memory, leaving 277356 bytes for local variables. Maximum is 327680 bytes.
```

That includes the setup web server, the PKCE exchange, the redrawn setup card and
both pinned roots (about 2.7 kB of PEM), and leaves **343 kB** of the default
scheme free — 197 kB under the phone deck's Bluetooth build, 1,164,489 bytes
measured the same way on the same machine, so dropping Bluedroid still pays for
mbedTLS and a certificate chain per host. Under `PartitionScheme=huge_app` it is
967,413 bytes, **30 %** of 3,145,728: a bigger room, not smaller code.

An earlier version of this note recorded 1,114,485 bytes (85 %), 191 kB free and
a 47 kB gap to the phone deck for the same sketch. That figure does not
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

**Nothing here has run on the board.** No sketch has been flashed and no request
has ever left this chip. Specifically unverified: the access point and the setup
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

### At a glance

**Verified here** — none of this needs hardware:

* The committed sketch links under the documented core **2.0.17** at 967,413
  bytes (73 %), the measurement above. Re-measured on 2026-10-31 with
  `arduino-cli` 1.5.2-rc.1, core **3.3.12** links the committed file at
  1,122,249 bytes (85 %) with 53,572 bytes of globals (16 %), `EXIT=0` under
  `--warnings all`; a comment-only edit links to exactly the same size, so the
  figure is a stable build rather than a stale build path. The two cores differ
  by ~155 kB, which is the WiFi stack as IDF 5 links it rather than mbedTLS (see
  [the build note](../CONVENTIONS.md#build)); 2.0.17 is the one `toolchain.sh`
  pins, so that is where the release numbers come from.
* **It still fits on 3.3.12, on about a third of the slack:** 1,122,249 bytes
  against the 1,310,720-byte app slot the default scheme gives leaves
  **188,471 B (14 %)** free, where 2.0.17 leaves 343,307 B (26 %). `default.csv`
  sizes `app0` and `app1` at 1,310,720 each, so a wireless update faces the same
  ceiling; under `huge_app` the same build reads 36 % — a bigger room, not
  smaller code.
* Seven warnings, all accounted for above.
* The bundled copy in `app/src/main/assets/widgets/spotify_deck_cyd24R/` matches
  this folder byte for byte after `:app:syncWidgets`, and
  `check_widget_assets.py` prints `2 widgets bundled, every copy matches`.
* Both pinned roots, each checked from a desktop against its host on
  2026-10-31.

**Unverified — only the board can settle these.** No sketch in this repo has
been flashed, so all of the following are still assumptions:

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
| `Spotify said no` | the code expired (ten minutes) or the redirect URI does not match | tap Open Spotify sign-in again and paste the new address |
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
does, for this widget, from a `:app:syncWidgets` run on 2026-10-31.

There is **no `ble` block**: this deck has no Bluetooth link to find, so the
app's scan side has nothing to look for. Turning the descriptor into a project
card with setup steps of its own is the app chat's work; the sketch and the
asset copy are done here.
