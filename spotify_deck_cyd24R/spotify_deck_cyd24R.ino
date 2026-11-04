/*
 * Spotify Deck - ESP32-2432S024R (2.4" resistive touch CYD)
 *
 * The second deck, and it has two sources rather than one.
 *
 * THE PHONE, OVER BLUETOOTH - no credentials, nothing to set up
 *   The phone's companion app reads the media session it already has and writes
 *   one line a second down a Phone Link service (state, position, duration,
 *   title, artist, album), so the deck shows whatever the phone is playing with
 *   no Spotify client id, no refresh token and no network. Tapping a chip raises
 *   a notify back up the same link and the phone runs that key against its own
 *   player, so the chips drive the phone's player, not the account. On a board
 *   with no stored credentials this is the whole deck: it boots straight into
 *   the player page, and nothing asks the user to make a developer app.
 *
 * SPOTIFY ITSELF, OVER WIFI - optional, needs a refresh token
 *   With stored credentials the deck also signs in on its own: it polls the Web
 *   API for what is playing, draws the same page, and the chips fall back to the
 *   account's active device when no phone is linked. This is the original deck,
 *   and it is the only half that needs a client id and a token.
 *
 * WIFI AND CREDENTIALS
 *   A sketch cannot invent a WiFi password, so nothing is baked in here. On a
 *   board with no credentials the deck becomes its own WiFi access point and
 *   serves a setup page at http://192.168.4.1: the phone joins the access point,
 *   types the WiFi name, the WiFi password and a Spotify client id, then taps
 *   through Spotify's own login and pastes back the address it landed on. The
 *   deck exchanges that code itself (PKCE, S256) and stores the refresh token in
 *   NVS (namespace "spotify"), then reboots into the deck. No laptop, no curl.
 *
 *   The serial wizard is still there as a fallback, at 115200 baud, one line at
 *   a time: WiFi name, WiFi password, client id, optional client secret, refresh
 *   token. Both surfaces run at once and whichever finishes first wins. Type
 *   "setup" + Enter in the serial monitor at any time to run either again; that
 *   is also how the deck moves to another network. The README says where a
 *   client id and a refresh token come from.
 *
 * Libraries needed (only these three; WiFi, HTTPClient and the TLS client are
 * part of the ESP32 core):
 *   - Adafruit GFX Library
 *   - Adafruit ILI9341
 *   - XPT2046_Touchscreen (Paul Stoffregen)
 *
 * Do not install ArduinoBLE - it is not used here, and on either deck its
 * BLEDevice.h shadows the core's BLE headers.
 *
 * Controls:
 *   - Tap |<< , PLAY/PAUSE or >>| . A linked phone runs that key against its own
 *     player; with no phone linked and credentials stored, the account's active
 *     device gets the Web API call instead.
 *   - Tap the wordmark to force a refresh.
 *   - Hold the wordmark for 1.5 s (or touch the screen at boot) to calibrate.
 *
 * Everything network-facing runs in its own task on the other core and hands the
 * UI a finished snapshot, so a TLS handshake never stalls a tap. The display is
 * only ever touched from loop().
 */

#include <Arduino.h>
#include <SPI.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <WiFiUdp.h>  // captive-portal DNS while the access point is up
#include <HTTPClient.h>
#include <Preferences.h>
#include <ctype.h>
#include <math.h>
#include <string.h>
#include <time.h>
#include <mbedtls/base64.h>  // PKCE: SHA-256 digest, then base64url
#include <mbedtls/md.h>

// Bluetooth, from the core (Bluedroid) — never ArduinoBLE, whose BLEDevice.h
// shadows these headers and turns the build into a wall of errors. This deck is
// still a WiFi client; the phone in the room is now a second source, because the
// Spotify Web API wants a developer app and that door is shut.
#include <BLEDevice.h>
#include <esp_gap_ble_api.h>
#include <BLE2902.h>
#include <BLEServer.h>
#include <BLEUtils.h>

#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include <XPT2046_Touchscreen.h>

// ---------------------------------------------------------------- hardware ---

// ESP32-2432S024R (2.4-inch CYD) pins. The LCD and the resistive touch
// controller share the SPI bus and have separate chip selects.
static constexpr int PIN_TFT_CS = 15;
static constexpr int PIN_TFT_DC = 2;
static constexpr int PIN_TFT_RST = -1;  // display reset is tied to board reset
static constexpr int PIN_TFT_BL = 27;
static constexpr int PIN_TOUCH_CS = 33;
static constexpr int PIN_SPI_SCK = 14;
static constexpr int PIN_SPI_MISO = 12;
static constexpr int PIN_SPI_MOSI = 13;

// The CYD's RGB status LED is three discrete LEDs wired between the GPIOs and
// 3.3 V, so a channel lights when its pin is driven LOW. It is not a WS2812.
static constexpr int PIN_LED_R = 4;
static constexpr int PIN_LED_G = 16;
static constexpr int PIN_LED_B = 17;

// Raw ranges used only for the factory-default mapping, i.e. before the first
// calibration. Run the calibration screen to replace them for your panel.
static constexpr int TOUCH_MIN_X = 250;
static constexpr int TOUCH_MAX_X = 3850;
static constexpr int TOUCH_MIN_Y = 250;
static constexpr int TOUCH_MAX_Y = 3850;

static constexpr int SCREEN_W = 320;
static constexpr int SCREEN_H = 240;

// ---------------------------------------------------------------- palette ---
// The same two themes the phone deck ships: 0 PAPER is black ink on an off-white
// page, 1 INK is the same page inverted, and the last six slots step the ink
// rather than hue. Nothing here spends a colour on green just because the service
// has one.
//
// The first six are ground, panel, lifted panel, hairline, ink and dim ink. The
// last six keep their jobs: accent for the status line, accentDim for that
// colour in shadow, pink for the one action and for an error, purple for the
// section dot, yellow for a count, green for a healthy state.
//
// Each entry is written 0x5655 /* #rrggbb */ so the palette can be read,
// compared against a design or carried into another sketch without decoding 565
// by hand. The value is what the panel shows; the two must agree.

struct Palette {
  uint16_t bg, tile, tileHi, edge, text, dim;
  uint16_t accent, accentDim, pink, purple, yellow, green;
};

static constexpr Palette THEMES[] = {
  // 0 PAPER - black ink on an off-white page
  { 0xF79D /* #f1f0eb */, 0xEF3C /* #e8e7e2 */, 0xDEFA /* #dddcd7 */, 0xB595 /* #b3b1ac */,
    0x0000 /* #000000 */, 0x5ACA /* #5b5a56 */, 0x0000 /* #000000 */, 0xAD54 /* #a9a8a3 */,
    0x18C3 /* #1a1a18 */, 0x7BCE /* #7a7974 */, 0x0000 /* #000000 */, 0x4A69 /* #4e4d49 */ },
  // 1 INK - the same page inverted: off-white ink on black
  { 0x0000 /* #000000 */, 0x1082 /* #101010 */, 0x18E3 /* #1c1c1a */, 0x39C6 /* #3a3a37 */,
    0xF79D /* #f1f0eb */, 0xAD34 /* #a8a7a2 */, 0xF79D /* #f1f0eb */, 0x6B4C /* #6a6a66 */,
    0xFFFF /* #ffffff */, 0x8C50 /* #8c8b86 */, 0xF79D /* #f1f0eb */, 0xB5B6 /* #b6b5b0 */ },
};

static constexpr uint8_t THEME_COUNT = sizeof(THEMES) / sizeof(THEMES[0]);
// The palette is a setting the companion app writes, not a build flag: the board
// keeps it in NVS and repaints, so both decks follow one phone without a
// re-flash. 1 INK is the default because a dark room is the usual one.
static constexpr uint8_t THEME_DEFAULT = 1;  // INK
static uint8_t themeIndex = THEME_DEFAULT;

// The twelve names the drawing code already uses. Plain variables rather than
// constexpr, so a write from the phone can reassign them without touching a draw
// call; applyTheme() is the only place that does, and it is defined below the
// types because the generated prototypes land before the first function here.
static uint16_t C_BG;
static uint16_t C_TILE;
static uint16_t C_TILE_HI;
static uint16_t C_EDGE;
static uint16_t C_TEXT;
static uint16_t C_DIM;
static uint16_t C_ACCENT;
static uint16_t C_ACCENT_DIM;
static uint16_t C_PINK;
static uint16_t C_PURPLE;
static uint16_t C_YELLOW;
static uint16_t C_GREEN;

static void applyTheme(uint8_t index);

static constexpr uint8_t CORNER_CARD = 10;
static constexpr uint8_t CORNER_BUTTON = 8;

Adafruit_ILI9341 tft(PIN_TFT_CS, PIN_TFT_DC, PIN_TFT_RST);
XPT2046_Touchscreen touch(PIN_TOUCH_CS);  // IRQ is optional; we poll instead

// Types are declared before the first function on purpose: the Arduino build
// generates prototypes and inserts them at that point, so every type used in a
// signature must already be visible above it. Keeping them in this file also
// keeps the sketch a single file, which is what ArduinoDroid and the Arduino
// IDE expect when you paste it in as one sketch.

struct Rect {
  int16_t x, y, w, h;
};

struct TouchPoint {
  int x, y;
};

// screenX = a*rawX + b*rawY + c , screenY = d*rawX + e*rawY + f
struct TouchMapping {
  float a, b, c;
  float d, e, f;
};

enum Screen : uint8_t { SCR_NOW, SCR_SETUP, SCR_CAL };

// What the network task last learned. One flat struct on purpose: it is copied
// under a spinlock that is only ever held for a memcpy, never across a request.
struct NowPlaying {
  bool ready;        // a response has been parsed at least once
  bool playing;
  bool hasDevice;
  bool activeDevice;
  uint16_t status;   // last HTTP status, 0 when the request never happened
  uint32_t progressMs;
  uint32_t durationMs;
  uint32_t updatedMs;  // millis() when this snapshot arrived
  char title[72];
  char artist[56];
  char device[40];
  char deviceId[64];
};

// Commands travel one way, from a tap to the network task.
static constexpr uint8_t CMD_PREV = 1;
static constexpr uint8_t CMD_TOGGLE = 2;
static constexpr uint8_t CMD_NEXT = 3;

// ----------------------------------------------------------- calibration ---

TouchMapping mapping;
Preferences calPrefs;

// Bumped whenever a stored touch fit should stop being trusted. A mismatch
// makes loadMapping() fall back to the factory default and re-opens the
// calibration screen on boot, which is what a fresh flash needs. The number
// matches the phone deck's so one board's fit is reused by both sketches.
static constexpr uint8_t CAL_MAP_VERSION = 2;
bool mappingStored = false;

Screen screen = SCR_NOW;
uint8_t calIndex = 0;
TouchPoint calRaw[3];
uint32_t calTapMs = 0;
uint32_t rawUiMs = 0;

// Three taps, in this order: top-left, top-right, bottom-left. The first pair
// gives the raw direction of screen +x, the third gives +y, and that is all an
// affine map needs.
static constexpr int CAL_INSET = 26;
static const int CAL_TARGET_X[3] = {CAL_INSET, SCREEN_W - 1 - CAL_INSET,
                                    CAL_INSET};
static const int CAL_TARGET_Y[3] = {CAL_INSET, CAL_INSET,
                                    SCREEN_H - 1 - CAL_INSET};

// Mapping assumed before the panel is calibrated: raw axes rotated 90 degrees
// against the landscape display, which is how most 2.4-inch CYDs behave.
static void defaultMapping(TouchMapping &m) {
  const float spanX = static_cast<float>(TOUCH_MAX_X - TOUCH_MIN_X);
  const float spanY = static_cast<float>(TOUCH_MAX_Y - TOUCH_MIN_Y);
  m.a = 0.0f;
  m.b = -(SCREEN_W - 1) / spanY;
  m.c = (SCREEN_W - 1) * static_cast<float>(TOUCH_MAX_Y) / spanY;
  m.d = (SCREEN_H - 1) / spanX;
  m.e = 0.0f;
  m.f = -(SCREEN_H - 1) * static_cast<float>(TOUCH_MIN_X) / spanX;
}

static void loadMapping() {
  calPrefs.begin("touchcal", true);
  const bool stored = calPrefs.getUChar("ver", 0) == CAL_MAP_VERSION;
  if (stored) {
    mapping.a = calPrefs.getFloat("a", 0.0f);
    mapping.b = calPrefs.getFloat("b", 0.0f);
    mapping.c = calPrefs.getFloat("c", 0.0f);
    mapping.d = calPrefs.getFloat("d", 0.0f);
    mapping.e = calPrefs.getFloat("e", 0.0f);
    mapping.f = calPrefs.getFloat("f", 0.0f);
  }
  calPrefs.end();
  mappingStored = stored;
  if (!stored) defaultMapping(mapping);
}

static void saveMapping(const TouchMapping &m) {
  calPrefs.begin("touchcal", false);
  calPrefs.putUChar("ver", CAL_MAP_VERSION);
  calPrefs.putFloat("a", m.a);
  calPrefs.putFloat("b", m.b);
  calPrefs.putFloat("c", m.c);
  calPrefs.putFloat("d", m.d);
  calPrefs.putFloat("e", m.e);
  calPrefs.putFloat("f", m.f);
  calPrefs.end();
}

// Three taps fix the affine map exactly: the top-left tap is the origin and the
// other two are the raw vectors for screen +x and +y. Inverting that 2x2 is the
// whole fit, and it handles a rotated, mirrored or axis-swapped panel without a
// case for each.
static bool fitMapping(const TouchPoint raw[3], TouchMapping &m) {
  const float ex = raw[1].x - raw[0].x;
  const float ey = raw[1].y - raw[0].y;
  const float fx = raw[2].x - raw[0].x;
  const float fy = raw[2].y - raw[0].y;
  const float det = ex * fy - fx * ey;
  if (fabsf(det) < 250000.0f) return false;  // two taps landed on one spot

  m.a = (SCREEN_W - 1) * fy / det;
  m.b = -(SCREEN_W - 1) * fx / det;
  m.d = -(SCREEN_H - 1) * ey / det;
  m.e = (SCREEN_H - 1) * ex / det;
  m.c = -(m.a * raw[0].x + m.b * raw[0].y);
  m.f = -(m.d * raw[0].x + m.e * raw[0].y);
  return true;
}

static void mapTouch(int rawX, int rawY, int &x, int &y) {
  x = static_cast<int>(lroundf(mapping.a * rawX + mapping.b * rawY + mapping.c));
  y = static_cast<int>(lroundf(mapping.d * rawX + mapping.e * rawY + mapping.f));
  x = constrain(x, 0, SCREEN_W - 1);
  y = constrain(y, 0, SCREEN_H - 1);
}

// ------------------------------------------------------------ credentials ---
// Namespace "spotify", versioned: a change to the shape bumps CRED_VERSION and
// an old block is ignored rather than half-read.

static constexpr uint8_t CRED_VERSION = 1;
static constexpr size_t CRED_SSID = 33;
static constexpr size_t CRED_PASS = 64;
static constexpr size_t CRED_ID = 65;
static constexpr size_t CRED_SECRET = 65;
static constexpr size_t CRED_TOKEN = 320;

struct Credentials {
  char ssid[CRED_SSID];
  char pass[CRED_PASS];
  char clientId[CRED_ID];
  char clientSecret[CRED_SECRET];
  char refresh[CRED_TOKEN];
};

Credentials creds;
Preferences credPrefs;

static bool credsComplete() {
  return creds.ssid[0] != '\0' && creds.clientId[0] != '\0' &&
         creds.refresh[0] != '\0';
}

static void loadCredentials() {
  memset(&creds, 0, sizeof(creds));
  credPrefs.begin("spotify", true);
  if (credPrefs.getUChar("ver", 0) == CRED_VERSION) {
    String s;
    s = credPrefs.getString("ssid", "");
    strlcpy(creds.ssid, s.c_str(), sizeof(creds.ssid));
    s = credPrefs.getString("pass", "");
    strlcpy(creds.pass, s.c_str(), sizeof(creds.pass));
    s = credPrefs.getString("cid", "");
    strlcpy(creds.clientId, s.c_str(), sizeof(creds.clientId));
    s = credPrefs.getString("csec", "");
    strlcpy(creds.clientSecret, s.c_str(), sizeof(creds.clientSecret));
    s = credPrefs.getString("rtok", "");
    strlcpy(creds.refresh, s.c_str(), sizeof(creds.refresh));
  }
  credPrefs.end();
  Serial.printf("[creds] ssid %s, client id %s, refresh token %s\n",
                creds.ssid[0] ? "set" : "missing",
                creds.clientId[0] ? "set" : "missing",
                creds.refresh[0] ? "set" : "missing");
}

static void saveCredentials() {
  credPrefs.begin("spotify", false);
  credPrefs.putUChar("ver", CRED_VERSION);
  credPrefs.putString("ssid", creds.ssid);
  credPrefs.putString("pass", creds.pass);
  credPrefs.putString("cid", creds.clientId);
  credPrefs.putString("csec", creds.clientSecret);
  credPrefs.putString("rtok", creds.refresh);
  credPrefs.end();
}

// The PKCE verifier has to outlive the page. Spotify walks the user through its
// own login between the challenge going out and the code coming back, which is
// long enough for a reload or a power blip to clear RAM, and a verifier that no
// longer matches the challenge makes the pasted code unusable. It is kept in the
// same block as the credentials and removed as soon as it has done its job.
static void saveVerifier(const String &verifier) {
  credPrefs.begin("spotify", false);
  credPrefs.putString("pkce", verifier);
  credPrefs.end();
}

static String loadVerifier() {
  credPrefs.begin("spotify", true);
  const String out = credPrefs.getString("pkce", "");
  credPrefs.end();
  return out;
}

static void clearVerifier() {
  credPrefs.begin("spotify", false);
  credPrefs.remove("pkce");
  credPrefs.end();
}

// Drops the whole block: network, client id, secret and token. Reached from the
// setup page and from a long hold on the panel at boot, so a forgotten password
// never needs a serial monitor.
static void wipeCredentials() {
  memset(&creds, 0, sizeof(creds));
  credPrefs.begin("spotify", false);
  credPrefs.clear();
  credPrefs.end();
  Serial.println("[setup] credentials and token erased from NVS");
}

// --------------------------------------------------------------- drawing ---
// Two text helpers, both copied from the phone deck because they encode the only
// thing that matters on a 320 px row: measure before drawing.

// ------------------------------------------------------------- typeface ---
// Two faces cut from DejaVu Sans Mono by artifacts/deck_font.py, pasted in rather
// than included: a pasted sketch cannot see a header beside it. Both are aligned
// by cap top, so text at y starts its capitals at y exactly as the 5x7 font did.
// Body is 11 px (7 px advance, 8 px caps); display is the bold at 20 px (12 px
// advance, 15 px caps). Nothing scales them: a heading is a different face, not
// the body doubled.

static const uint8_t DeckFontBodyBits[] PROGMEM = {
    0x55, 0x51, 0x55, 0x50, 0x14, 0x41, 0xF9, 0x42, 0x9F, 0x94, 0x28, 0x23,
    0xA9, 0x47, 0x14, 0xBE, 0x21, 0x00, 0x61, 0x21, 0x90, 0x02, 0x09, 0x85,
    0x06, 0x38, 0x81, 0x03, 0x05, 0x11, 0x93, 0x3A, 0x54, 0x09, 0x49, 0x22,
    0x40, 0x42, 0x22, 0x22, 0x22, 0x24, 0x11, 0x43, 0x8E, 0x50, 0x40, 0x10,
    0x23, 0xF0, 0x81, 0x00, 0x4A, 0x00, 0x70, 0x50, 0x04, 0x20, 0x84, 0x10,
    0x82, 0x10, 0x40, 0x39, 0x24, 0x51, 0x55, 0x14, 0x8E, 0xE1, 0x08, 0x42,
    0x10, 0x9F, 0x39, 0x20, 0xC2, 0x10, 0x84, 0x1F, 0x39, 0x30, 0x8E, 0x08,
    0x10, 0xDE, 0x18, 0x30, 0xA0, 0x44, 0x8F, 0x82, 0x04, 0x79, 0x04, 0x1E,
    0x0C, 0x10, 0x9E, 0x39, 0x84, 0x1E, 0x4D, 0x14, 0xCE, 0x7C, 0x20, 0x82,
    0x10, 0x42, 0x08, 0x39, 0x34, 0xCE, 0x4D, 0x14, 0xCE, 0x39, 0x24, 0x53,
    0x3C, 0x10, 0x9C, 0x50, 0x50, 0x48, 0x04, 0xA0, 0x04, 0x63, 0x01, 0x80,
    0x40, 0xFC, 0x03, 0xF0, 0x40, 0x70, 0x31, 0xC4, 0x00, 0xF0, 0xC4, 0x42,
    0x10, 0x04, 0x38, 0x89, 0x14, 0xEA, 0x54, 0xA7, 0x20, 0x60, 0x70, 0x30,
    0x60, 0xA1, 0x44, 0x8F, 0x91, 0x42, 0x78, 0x89, 0x33, 0xC4, 0x48, 0x91,
    0x3C, 0x39, 0x94, 0x10, 0x41, 0x06, 0x4E, 0x71, 0x24, 0x51, 0x45, 0x14,
    0x9C, 0xFC, 0x21, 0xF8, 0x42, 0x1F, 0xFC, 0x21, 0xF8, 0x42, 0x10, 0x39,
    0x84, 0x10, 0x4D, 0x14, 0x4E, 0x45, 0x14, 0x5F, 0x45, 0x14, 0x51, 0xF9,
    0x08, 0x42, 0x10, 0x9F, 0x38, 0x20, 0x82, 0x08, 0x20, 0x9C, 0x44, 0x91,
    0x43, 0x85, 0x09, 0x13, 0x22, 0x82, 0x08, 0x20, 0x82, 0x08, 0x3E, 0xC5,
    0x9B, 0xB5, 0xA9, 0x50, 0xA1, 0x42, 0x45, 0x96, 0x55, 0x55, 0x74, 0xD3,
    0x39, 0x34, 0x51, 0x45, 0x14, 0xCE, 0xF2, 0x28, 0xA2, 0xF2, 0x08, 0x20,
    0x39, 0x34, 0x51, 0x45, 0x14, 0xCE, 0x08, 0x78, 0x99, 0x12, 0x47, 0x89,
    0x11, 0x22, 0x39, 0x04, 0x18, 0x08, 0x14, 0xCE, 0xFC, 0x20, 0x40, 0x81,
    0x02, 0x04, 0x08, 0x45, 0x14, 0x51, 0x45, 0x14, 0xCE, 0xC4, 0x89, 0x12,
    0x42, 0x85, 0x0C, 0x08, 0x87, 0x0A, 0x51, 0xA6, 0x4D, 0x9B, 0x24, 0x44,
    0xD0, 0xA0, 0x83, 0x05, 0x11, 0x62, 0xC4, 0x98, 0xA1, 0x81, 0x02, 0x04,
    0x08, 0x7C, 0x08, 0x20, 0x81, 0x04, 0x10, 0x3E, 0xF2, 0x49, 0x24, 0x9C,
    0x41, 0x02, 0x08, 0x10, 0x40, 0x82, 0x04, 0x62, 0x22, 0x22, 0x22, 0x26,
    0x30, 0x51, 0x10, 0xFE, 0x42, 0x78, 0x33, 0xD1, 0x4D, 0xF0, 0x84, 0x21,
    0xED, 0xC6, 0x33, 0xF0, 0x7E, 0x21, 0x0C, 0x3C, 0x04, 0x10, 0x4F, 0x4D,
    0x14, 0x53, 0x3C, 0x39, 0x17, 0xD0, 0x40, 0xF0, 0x39, 0x09, 0xF2, 0x10,
    0x84, 0x20, 0x3D, 0x34, 0x51, 0x4C, 0xF0, 0x9E, 0x84, 0x21, 0x69, 0xC6,
    0x31, 0x88, 0x10, 0x00, 0x1C, 0x10, 0x41, 0x04, 0x7C, 0x20, 0x06, 0x22,
    0x22, 0x22, 0xE0, 0x82, 0x08, 0x26, 0xB3, 0x8B, 0x24, 0x88, 0x60, 0x82,
    0x08, 0x20, 0x82, 0x04, 0x1C, 0x6C, 0xA8, 0x50, 0xA1, 0x42, 0x80, 0xB4,
    0xE3, 0x18, 0xC4, 0x39, 0x34, 0x51, 0x4C, 0xE0, 0xB6, 0xE3, 0x19, 0xFA,
    0x10, 0x3D, 0x34, 0x51, 0x4C, 0xF0, 0x41, 0x79, 0x84, 0x10, 0x41, 0x00,
    0x74, 0x30, 0x61, 0x78, 0x20, 0x87, 0xC8, 0x20, 0x82, 0x07, 0x8C, 0x63,
    0x19, 0xBC, 0x44, 0x89, 0x21, 0x43, 0x06, 0x00, 0x83, 0x09, 0x53, 0xA6,
    0xCD, 0x00, 0x44, 0x50, 0xC1, 0x82, 0x88, 0x80, 0x44, 0x88, 0xA1, 0x43,
    0x02, 0x0C, 0x30, 0xF8, 0x88, 0x84, 0x7C, 0x31, 0x08, 0x4C, 0x30, 0x84,
    0x21, 0x80, 0x55, 0x55, 0x54, 0xC1, 0x08, 0x43, 0x10, 0x84, 0x26, 0x00,
    0x60, 0x38,
};

static const GFXglyph DeckFontBodyGlyphs[] PROGMEM = {
    {0, 0, 0, 7, 0, 0},
    {0, 2, 8, 7, 2, 0},
    {2, 4, 3, 7, 1, 0},
    {4, 7, 8, 7, 0, 0},
    {11, 5, 10, 7, 1, 0},
    {18, 7, 8, 7, 0, 0},
    {25, 7, 8, 7, 0, 0},
    {32, 2, 3, 7, 2, 0},
    {33, 3, 10, 7, 2, -1},
    {37, 4, 10, 7, 1, -1},
    {42, 6, 6, 7, 0, 0},
    {47, 7, 5, 7, 0, 2},
    {52, 3, 3, 7, 2, 6},
    {54, 4, 1, 7, 1, 4},
    {55, 2, 2, 7, 2, 6},
    {56, 6, 9, 7, 0, 0},
    {63, 6, 8, 7, 0, 0},
    {69, 5, 8, 7, 1, 0},
    {74, 6, 8, 7, 0, 0},
    {80, 6, 8, 7, 0, 0},
    {86, 7, 8, 7, 0, 0},
    {93, 6, 8, 7, 0, 0},
    {99, 6, 8, 7, 0, 0},
    {105, 6, 8, 7, 0, 0},
    {111, 6, 8, 7, 0, 0},
    {117, 6, 8, 7, 0, 0},
    {123, 2, 6, 7, 2, 2},
    {125, 3, 7, 7, 2, 2},
    {128, 7, 5, 7, 0, 2},
    {133, 7, 3, 7, 0, 3},
    {136, 7, 5, 7, 0, 2},
    {141, 5, 8, 7, 1, 0},
    {146, 7, 10, 7, 0, 0},
    {155, 7, 8, 7, 0, 0},
    {162, 7, 8, 7, 0, 0},
    {169, 6, 8, 7, 0, 0},
    {175, 6, 8, 7, 0, 0},
    {181, 5, 8, 7, 1, 0},
    {186, 5, 8, 7, 1, 0},
    {191, 6, 8, 7, 0, 0},
    {197, 6, 8, 7, 0, 0},
    {203, 5, 8, 7, 1, 0},
    {208, 6, 8, 7, 0, 0},
    {214, 7, 8, 7, 0, 0},
    {221, 6, 8, 7, 1, 0},
    {227, 7, 8, 7, 0, 0},
    {234, 6, 8, 7, 0, 0},
    {240, 6, 8, 7, 0, 0},
    {246, 6, 8, 7, 1, 0},
    {252, 6, 9, 7, 0, 0},
    {259, 7, 8, 7, 0, 0},
    {266, 6, 8, 7, 0, 0},
    {272, 7, 8, 7, 0, 0},
    {279, 6, 8, 7, 0, 0},
    {285, 7, 8, 7, 0, 0},
    {292, 7, 8, 7, 0, 0},
    {299, 7, 8, 7, 0, 0},
    {306, 7, 8, 7, 0, 0},
    {313, 7, 8, 7, 0, 0},
    {320, 3, 10, 7, 2, -1},
    {324, 6, 9, 7, 0, 0},
    {331, 4, 10, 7, 1, -1},
    {336, 7, 3, 7, 0, 0},
    {339, 7, 1, 7, 0, 10},
    {340, 4, 2, 7, 1, -1},
    {341, 6, 6, 7, 0, 2},
    {346, 5, 9, 7, 1, -1},
    {352, 5, 6, 7, 1, 2},
    {356, 6, 9, 7, 0, -1},
    {363, 6, 6, 7, 0, 2},
    {368, 5, 9, 7, 1, -1},
    {374, 6, 8, 7, 0, 2},
    {380, 5, 9, 7, 1, -1},
    {386, 6, 9, 7, 0, -1},
    {393, 4, 11, 7, 1, -1},
    {399, 6, 9, 7, 1, -1},
    {406, 6, 9, 7, 0, -1},
    {413, 7, 6, 7, 0, 2},
    {419, 5, 6, 7, 1, 2},
    {423, 6, 6, 7, 0, 2},
    {428, 5, 8, 7, 1, 2},
    {433, 6, 8, 7, 0, 2},
    {439, 6, 6, 7, 1, 2},
    {444, 5, 6, 7, 1, 2},
    {448, 6, 8, 7, 0, 0},
    {454, 5, 6, 7, 1, 2},
    {458, 7, 6, 7, 0, 2},
    {464, 7, 6, 7, 0, 2},
    {470, 7, 6, 7, 0, 2},
    {476, 7, 8, 7, 0, 2},
    {483, 5, 6, 7, 1, 2},
    {487, 5, 10, 7, 1, -1},
    {494, 2, 11, 7, 2, -1},
    {497, 5, 10, 7, 1, -1},
    {504, 7, 2, 7, 0, 4},
};

const GFXfont DeckFontBody PROGMEM = {(uint8_t *)DeckFontBodyBits, (GFXglyph *)DeckFontBodyGlyphs, 32, 126, 14};

static const uint8_t DeckFontDisplayBits[] PROGMEM = {
    0x66, 0x66, 0x66, 0x66, 0x66, 0x00, 0x66, 0x60, 0xE7, 0xE7, 0xE7, 0xE7,
    0xE7, 0x06, 0x60, 0xE6, 0x0C, 0xC0, 0xCC, 0x7F, 0xF7, 0xFF, 0x19, 0x81,
    0x98, 0x19, 0x8F, 0xFE, 0xFF, 0xE3, 0x30, 0x33, 0x07, 0x30, 0x67, 0x00,
    0x0C, 0x03, 0x01, 0xF1, 0xFE, 0x7D, 0x9B, 0x06, 0xC1, 0xF0, 0x3F, 0x03,
    0xE0, 0xDC, 0x37, 0x6D, 0xDF, 0xE3, 0xF0, 0x30, 0x0C, 0x03, 0x00, 0x38,
    0x07, 0xC0, 0xCC, 0x0C, 0xC0, 0x7C, 0x23, 0x8E, 0x01, 0x80, 0x60, 0x18,
    0x07, 0x1C, 0x43, 0xE0, 0x33, 0x03, 0x30, 0x3E, 0x01, 0xC0, 0x0F, 0x01,
    0xF8, 0x38, 0x83, 0x80, 0x1C, 0x01, 0xC0, 0x3E, 0x07, 0xE3, 0x67, 0x3E,
    0x3F, 0xE3, 0xE6, 0x1E, 0x71, 0xE3, 0xFE, 0x1F, 0xF0, 0x66, 0x66, 0x60,
    0x1C, 0x63, 0x8C, 0x31, 0xC7, 0x1C, 0x71, 0xC7, 0x1C, 0x70, 0xC3, 0x0E,
    0x18, 0x70, 0xE1, 0x87, 0x0C, 0x38, 0xE3, 0x8E, 0x18, 0x63, 0x8E, 0x38,
    0xE3, 0x1C, 0x63, 0x80, 0x0C, 0x03, 0x04, 0xC9, 0xFE, 0x3F, 0x0F, 0xC7,
    0xF9, 0x32, 0x0C, 0x03, 0x00, 0x06, 0x00, 0x60, 0x06, 0x00, 0x60, 0x7F,
    0xE7, 0xFE, 0x06, 0x00, 0x60, 0x06, 0x00, 0x60, 0x7B, 0xDC, 0xE6, 0x30,
    0x7E, 0x7E, 0x7E, 0xFF, 0xFF, 0x00, 0xC0, 0x60, 0x18, 0x0C, 0x03, 0x01,
    0x80, 0x60, 0x30, 0x0C, 0x06, 0x01, 0x80, 0xC0, 0x30, 0x18, 0x06, 0x03,
    0x80, 0x1E, 0x0F, 0xC7, 0x39, 0xC6, 0xE1, 0xF8, 0x7E, 0x1F, 0xB7, 0xED,
    0xF8, 0x7E, 0x1D, 0xC6, 0x73, 0x8F, 0xC1, 0xE0, 0x1E, 0x0F, 0xC1, 0xB8,
    0x07, 0x00, 0xE0, 0x1C, 0x03, 0x80, 0x70, 0x0E, 0x01, 0xC0, 0x38, 0x07,
    0x00, 0xE0, 0xFF, 0x9F, 0xF0, 0x3E, 0x3F, 0xCE, 0x38, 0x0E, 0x03, 0x80,
    0xE0, 0x38, 0x1C, 0x0E, 0x07, 0x83, 0xC1, 0xE0, 0x70, 0x3F, 0xEF, 0xF8,
    0x3E, 0x1F, 0xE6, 0x38, 0x06, 0x01, 0x80, 0xE1, 0xF0, 0x7C, 0x03, 0x80,
    0x70, 0x1E, 0x07, 0xC3, 0xBF, 0xE3, 0xE0, 0x07, 0x80, 0xF0, 0x3E, 0x0F,
    0xC1, 0xB8, 0x67, 0x1C, 0xE3, 0x1C, 0xC3, 0x9F, 0xFB, 0xFF, 0x01, 0xC0,
    0x38, 0x07, 0x00, 0xE0, 0x7F, 0x9F, 0xE6, 0x01, 0x80, 0x60, 0x1F, 0x87,
    0xF1, 0x0E, 0x03, 0x80, 0x70, 0x1C, 0x06, 0xC3, 0xBF, 0xC3, 0xE0, 0x0F,
    0x0F, 0xE7, 0x09, 0xC0, 0x60, 0x3B, 0xCF, 0xFB, 0xCF, 0xF1, 0xF8, 0x76,
    0x1D, 0xC7, 0x73, 0x8F, 0xE1, 0xF0, 0xFF, 0xFF, 0xF0, 0x38, 0x0E, 0x07,
    0x81, 0xC0, 0x70, 0x38, 0x0E, 0x03, 0x81, 0xC0, 0x70, 0x1C, 0x0E, 0x03,
    0x80, 0x1E, 0x1F, 0xE7, 0x39, 0x86, 0x61, 0x9C, 0xE3, 0xF0, 0xFC, 0x73,
    0xB8, 0x7E, 0x1F, 0x87, 0x73, 0x9F, 0xE1, 0xF0, 0x3E, 0x1F, 0xC7, 0x3B,
    0x8E, 0xE1, 0xF8, 0x7E, 0x3F, 0xCF, 0x7F, 0xCF, 0x70, 0x18, 0x0E, 0x43,
    0x9F, 0xC3, 0xC0, 0xFF, 0xFF, 0x00, 0xFF, 0xFF, 0x7B, 0xDE, 0xF0, 0x01,
    0xEF, 0x7B, 0x9C, 0xC0, 0x00, 0x20, 0x1E, 0x07, 0xE3, 0xF0, 0x78, 0x07,
    0x80, 0x3F, 0x00, 0x7E, 0x01, 0xE0, 0x02, 0x7F, 0xE7, 0xFE, 0x00, 0x00,
    0x00, 0x7F, 0xE7, 0xFE, 0x40, 0x07, 0x80, 0x7E, 0x00, 0xFC, 0x01, 0xE0,
    0x1E, 0x0F, 0xC7, 0xF0, 0x78, 0x04, 0x00, 0x3E, 0x7F, 0xB1, 0xC0, 0x60,
    0x70, 0x70, 0x70, 0x30, 0x38, 0x1C, 0x0E, 0x00, 0x03, 0x81, 0xC0, 0xE0,
    0x0F, 0x81, 0xFC, 0x38, 0xE7, 0x06, 0x63, 0xEC, 0x7E, 0xCE, 0x6C, 0xC6,
    0xCC, 0x6C, 0xC6, 0xCE, 0x6E, 0x7E, 0x63, 0xE7, 0x00, 0x38, 0x61, 0xFE,
    0x07, 0xC0, 0x0F, 0x00, 0xF0, 0x0F, 0x01, 0xF8, 0x1F, 0x81, 0x98, 0x19,
    0x83, 0x9C, 0x39, 0xC3, 0xFC, 0x3F, 0xE7, 0x0E, 0x70, 0xE7, 0x0E, 0xE0,
    0x70, 0xFF, 0x1F, 0xF3, 0x8F, 0x70, 0xEE, 0x1D, 0xC7, 0x3F, 0xC7, 0xFC,
    0xE1, 0xDC, 0x3B, 0x87, 0x70, 0xEE, 0x1D, 0xFF, 0xBF, 0xC0, 0x0F, 0x8F,
    0xF3, 0x8D, 0xC0, 0x70, 0x1C, 0x07, 0x03, 0x80, 0x70, 0x1C, 0x07, 0x01,
    0xC0, 0x38, 0xCF, 0xF0, 0xF8, 0xFE, 0x1F, 0xE3, 0x8E, 0x70, 0xEE, 0x1D,
    0xC3, 0xB8, 0x77, 0x0E, 0xE1, 0xDC, 0x3B, 0x87, 0x70, 0xEE, 0x39, 0xFE,
    0x3F, 0x80, 0x7F, 0xDF, 0xF7, 0x01, 0xC0, 0x70, 0x1C, 0x07, 0xF9, 0xFE,
    0x70, 0x1C, 0x07, 0x01, 0xC0, 0x70, 0x1F, 0xF7, 0xFC, 0x7F, 0xDF, 0xF7,
    0x01, 0xC0, 0x70, 0x1C, 0x07, 0xF9, 0xFE, 0x70, 0x1C, 0x07, 0x01, 0xC0,
    0x70, 0x1C, 0x07, 0x00, 0x0F, 0x07, 0xF9, 0xE3, 0x38, 0x0F, 0x01, 0xC0,
    0x38, 0x07, 0x1E, 0xE3, 0xDC, 0x1B, 0xC3, 0x38, 0x67, 0x9C, 0x7F, 0x83,
    0xE0, 0xE1, 0xF8, 0x7E, 0x1F, 0x87, 0xE1, 0xF8, 0x7F, 0xFF, 0xFF, 0xE1,
    0xF8, 0x7E, 0x1F, 0x87, 0xE1, 0xF8, 0x7E, 0x1C, 0x7F, 0x9F, 0xE0, 0xC0,
    0x30, 0x0C, 0x03, 0x00, 0xC0, 0x30, 0x0C, 0x03, 0x00, 0xC0, 0x30, 0x0C,
    0x1F, 0xE7, 0xF8, 0x3F, 0x9F, 0xC0, 0xE0, 0x70, 0x38, 0x1C, 0x0E, 0x07,
    0x03, 0x81, 0xC0, 0xE0, 0x7C, 0x7F, 0xF9, 0xF8, 0xE1, 0xDC, 0x7B, 0x8E,
    0x73, 0x8E, 0xE1, 0xFC, 0x3F, 0x87, 0xF0, 0xFF, 0x1C, 0xE3, 0x9E, 0x71,
    0xCE, 0x3D, 0xC3, 0xB8, 0x38, 0xE0, 0x38, 0x0E, 0x03, 0x80, 0xE0, 0x38,
    0x0E, 0x03, 0x80, 0xE0, 0x38, 0x0E, 0x03, 0x80, 0xE0, 0x3F, 0xEF, 0xF8,
    0x70, 0xE7, 0x9E, 0x79, 0xE7, 0x9E, 0x79, 0xE6, 0xF6, 0x6F, 0x66, 0xF6,
    0x67, 0x66, 0x66, 0x60, 0x66, 0x06, 0x60, 0x66, 0x06, 0x60, 0x60, 0xE1,
    0xFC, 0x7F, 0x1F, 0xE7, 0xF9, 0xFE, 0x7E, 0xDF, 0xB7, 0xED, 0xF9, 0xFE,
    0x7F, 0x9F, 0xE3, 0xF8, 0xFE, 0x1C, 0x0F, 0x01, 0xF8, 0x39, 0xC7, 0x0E,
    0x70, 0xE7, 0x0E, 0x70, 0xE7, 0x0E, 0x70, 0xE7, 0x0E, 0x70, 0xE7, 0x0E,
    0x39, 0xC1, 0xF8, 0x0F, 0x00, 0x7F, 0x0F, 0xF1, 0x8F, 0x30, 0xE6, 0x1C,
    0xC3, 0x98, 0xF3, 0xFC, 0x7F, 0x0C, 0x01, 0x80, 0x30, 0x06, 0x00, 0xC0,
    0x18, 0x00, 0x0F, 0x01, 0xF8, 0x39, 0xC7, 0x0E, 0x70, 0xE7, 0x0E, 0x70,
    0xE7, 0x0E, 0x70, 0xE7, 0x0E, 0x70, 0xE7, 0x0E, 0x39, 0xC1, 0xFC, 0x0F,
    0x80, 0x1C, 0x00, 0x80, 0xFF, 0x1F, 0xF3, 0x8E, 0x70, 0xEE, 0x1D, 0xC3,
    0xB8, 0xE7, 0xF8, 0xFF, 0x1C, 0xE3, 0x8E, 0x71, 0xCE, 0x1D, 0xC3, 0xB8,
    0x38, 0x1F, 0x1F, 0xE7, 0x1B, 0x82, 0xE0, 0x3C, 0x07, 0xC0, 0xFC, 0x0F,
    0x80, 0xF0, 0x1E, 0x07, 0xE3, 0xFF, 0xE3, 0xF0, 0x7F, 0xE7, 0xFE, 0x06,
    0x00, 0x60, 0x06, 0x00, 0x60, 0x06, 0x00, 0x60, 0x06, 0x00, 0x60, 0x06,
    0x00, 0x60, 0x06, 0x00, 0x60, 0x06, 0x00, 0xE1, 0xF8, 0x7E, 0x1F, 0x87,
    0xE1, 0xF8, 0x7E, 0x1F, 0x87, 0xE1, 0xF8, 0x7E, 0x1F, 0x87, 0xF3, 0xDF,
    0xE3, 0xF0, 0x70, 0xE7, 0x0E, 0x70, 0xE7, 0x0E, 0x30, 0xC3, 0x9C, 0x39,
    0xC3, 0x9C, 0x19, 0xC1, 0x98, 0x1F, 0x81, 0xF8, 0x1F, 0x80, 0xF0, 0x0F,
    0x00, 0xE0, 0x77, 0x03, 0xB8, 0x1D, 0xC0, 0xE6, 0x67, 0x33, 0xB1, 0xBD,
    0x8D, 0xEC, 0x6F, 0x63, 0xEF, 0x1E, 0x78, 0xF3, 0xC7, 0x9E, 0x1C, 0xF0,
    0xE3, 0x00, 0x70, 0xE7, 0x0E, 0x39, 0xC3, 0x9C, 0x1F, 0x81, 0xF8, 0x0F,
    0x00, 0xF0, 0x0F, 0x01, 0xF8, 0x1F, 0x83, 0x9C, 0x39, 0xC7, 0x0E, 0x70,
    0x70, 0xE0, 0x77, 0x0E, 0x70, 0xE3, 0x9C, 0x39, 0xC1, 0xF8, 0x1F, 0x80,
    0xF0, 0x0F, 0x00, 0x60, 0x06, 0x00, 0x60, 0x06, 0x00, 0x60, 0x06, 0x00,
    0xFF, 0xDF, 0xF8, 0x0F, 0x01, 0xE0, 0x78, 0x1E, 0x03, 0xC0, 0xF0, 0x1C,
    0x07, 0x81, 0xE0, 0x38, 0x0F, 0x01, 0xFF, 0xBF, 0xF0, 0xFB, 0xEE, 0x38,
    0xE3, 0x8E, 0x38, 0xE3, 0x8E, 0x38, 0xE3, 0x8E, 0x38, 0xFB, 0xE0, 0xE0,
    0x18, 0x06, 0x00, 0xC0, 0x30, 0x06, 0x01, 0x80, 0x30, 0x0C, 0x01, 0x80,
    0x60, 0x0C, 0x03, 0x00, 0x60, 0x18, 0x03, 0x7D, 0xF1, 0xC7, 0x1C, 0x71,
    0xC7, 0x1C, 0x71, 0xC7, 0x1C, 0x71, 0xC7, 0x7D, 0xF0, 0x0F, 0x00, 0xF0,
    0x1F, 0x83, 0x9C, 0x60, 0x60, 0xFF, 0xF7, 0xFF, 0x80, 0xE1, 0xC3, 0x06,
    0x1F, 0x87, 0xF8, 0x83, 0x80, 0x71, 0xFE, 0x7F, 0xDE, 0x3B, 0x87, 0x71,
    0xEF, 0xFC, 0x7B, 0x80, 0xE0, 0x1C, 0x03, 0x80, 0x70, 0x0E, 0xF1, 0xFF,
    0x3C, 0xF7, 0x8E, 0xE1, 0xDC, 0x3B, 0x87, 0x78, 0xEF, 0x3D, 0xFF, 0x3B,
    0xC0, 0x0F, 0x0F, 0xE7, 0x89, 0xC0, 0x70, 0x1C, 0x07, 0x01, 0xC0, 0x78,
    0x8F, 0xE1, 0xF0, 0x00, 0xE0, 0x1C, 0x03, 0x80, 0x71, 0xEE, 0x7F, 0xDE,
    0x7B, 0x8F, 0x70, 0xEE, 0x1D, 0xC3, 0xB8, 0xF7, 0x9E, 0x7F, 0xC7, 0xB8,
    0x0F, 0x83, 0xFC, 0x38, 0xE7, 0x0E, 0x7F, 0xE7, 0xFE, 0x70, 0x07, 0x00,
    0x38, 0x23, 0xFE, 0x0F, 0x80, 0x07, 0xC3, 0xF1, 0xC0, 0x70, 0x7F, 0xDF,
    0xF1, 0xC0, 0x70, 0x1C, 0x07, 0x01, 0xC0, 0x70, 0x1C, 0x07, 0x01, 0xC0,
    0x1E, 0xE7, 0xFC, 0xE7, 0xB8, 0xF7, 0x0E, 0xE1, 0xDC, 0x3B, 0x8F, 0x79,
    0xE7, 0xFC, 0x7B, 0x80, 0x72, 0x1C, 0x7F, 0x87, 0xE0, 0x70, 0x1C, 0x07,
    0x01, 0xC0, 0x77, 0x1F, 0xE7, 0x39, 0xC6, 0x71, 0x9C, 0x67, 0x19, 0xC6,
    0x71, 0x9C, 0x67, 0x18, 0x0E, 0x01, 0xC0, 0x38, 0x00, 0x00, 0x00, 0xFC,
    0x1F, 0x80, 0x70, 0x0E, 0x01, 0xC0, 0x38, 0x07, 0x00, 0xE0, 0x1C, 0x3F,
    0xF7, 0xFE, 0x0E, 0x0E, 0x0E, 0x00, 0x00, 0x3E, 0x3E, 0x0E, 0x0E, 0x0E,
    0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0xFE, 0xFC, 0x70, 0x0E,
    0x01, 0xC0, 0x38, 0x07, 0x1C, 0xE7, 0x1D, 0xC3, 0xF0, 0x7E, 0x0F, 0xC1,
    0xDC, 0x3B, 0x87, 0x38, 0xE3, 0x9C, 0x70, 0x7E, 0x0F, 0xC0, 0x38, 0x07,
    0x00, 0xE0, 0x1C, 0x03, 0x80, 0x70, 0x0E, 0x01, 0xC0, 0x38, 0x07, 0x00,
    0xE0, 0x1F, 0xC1, 0xF8, 0x7D, 0xC7, 0xFE, 0x66, 0x66, 0x66, 0x66, 0x66,
    0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x60, 0x77, 0x1F, 0xE7,
    0x39, 0xC6, 0x71, 0x9C, 0x67, 0x19, 0xC6, 0x71, 0x9C, 0x67, 0x18, 0x0F,
    0x03, 0xFC, 0x39, 0xC7, 0x0E, 0x70, 0xE7, 0x0E, 0x70, 0xE7, 0x0E, 0x39,
    0xC3, 0xFC, 0x0F, 0x00, 0xEF, 0x1F, 0xF3, 0xCF, 0x78, 0xEE, 0x1D, 0xC3,
    0xB8, 0x77, 0x8E, 0xF3, 0xDF, 0xF3, 0xBC, 0x70, 0x0E, 0x01, 0xC0, 0x38,
    0x00, 0x1E, 0xE7, 0xFD, 0xE7, 0xB8, 0xF7, 0x0E, 0xE1, 0xDC, 0x3B, 0x8F,
    0x79, 0xE7, 0xFC, 0x7B, 0x80, 0x70, 0x0E, 0x01, 0xC0, 0x38, 0x77, 0x9F,
    0xE7, 0x89, 0xC0, 0x70, 0x1C, 0x07, 0x01, 0xC0, 0x70, 0x1C, 0x07, 0x00,
    0x1E, 0x1F, 0xE7, 0x09, 0xC0, 0x7C, 0x0F, 0xC0, 0xF8, 0x06, 0x43, 0x9F,
    0xE3, 0xF0, 0x1C, 0x07, 0x01, 0xC3, 0xFE, 0xFF, 0x87, 0x01, 0xC0, 0x70,
    0x1C, 0x07, 0x01, 0xC0, 0x70, 0x1F, 0x83, 0xE0, 0x63, 0x98, 0xE6, 0x39,
    0x8E, 0x63, 0x98, 0xE6, 0x39, 0x8E, 0x73, 0x9F, 0xE3, 0xF8, 0x70, 0xE7,
    0x0E, 0x30, 0xC3, 0x9C, 0x39, 0xC1, 0x9C, 0x19, 0x81, 0xF8, 0x1F, 0x80,
    0xF0, 0x0F, 0x00, 0xC0, 0x37, 0x03, 0xB8, 0x1C, 0xCC, 0xC6, 0x66, 0x37,
    0xB1, 0xBD, 0x8F, 0xBC, 0x39, 0xE1, 0xCE, 0x0E, 0x70, 0x70, 0xE3, 0x9C,
    0x1F, 0x81, 0xF8, 0x0F, 0x00, 0xF0, 0x0F, 0x01, 0xF8, 0x39, 0xC3, 0x9C,
    0x70, 0xE0, 0x70, 0xE7, 0x0E, 0x30, 0xE3, 0x9C, 0x39, 0xC1, 0xD8, 0x1F,
    0x81, 0xF8, 0x0F, 0x00, 0xF0, 0x07, 0x00, 0xE0, 0x0E, 0x07, 0xC0, 0x78,
    0x00, 0x7F, 0xDF, 0xF0, 0x38, 0x1E, 0x0F, 0x07, 0x83, 0xC0, 0xE0, 0x70,
    0x1F, 0xF7, 0xFC, 0x07, 0x83, 0xE0, 0xE0, 0x30, 0x0C, 0x03, 0x00, 0xC0,
    0x70, 0x78, 0x1E, 0x01, 0xC0, 0x30, 0x0C, 0x03, 0x00, 0xC0, 0x38, 0x0F,
    0x81, 0xE0, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
    0x78, 0x1F, 0x01, 0xC0, 0x30, 0x0C, 0x03, 0x00, 0xC0, 0x38, 0x07, 0x81,
    0xE0, 0xE0, 0x30, 0x0C, 0x03, 0x00, 0xC0, 0x70, 0x7C, 0x1E, 0x00, 0x3C,
    0x27, 0xFE, 0x43, 0xC0,
};

static const GFXglyph DeckFontDisplayGlyphs[] PROGMEM = {
    {0, 0, 0, 12, 0, 0},
    {0, 4, 15, 12, 4, 0},
    {8, 8, 5, 12, 2, 0},
    {13, 12, 15, 12, 0, 0},
    {36, 10, 18, 12, 1, 0},
    {59, 12, 15, 12, 0, 0},
    {82, 12, 15, 12, 0, 0},
    {105, 4, 5, 12, 4, 0},
    {108, 6, 18, 12, 3, 0},
    {122, 6, 18, 12, 3, 0},
    {136, 10, 10, 12, 1, 0},
    {149, 12, 10, 12, 0, 4},
    {164, 5, 6, 12, 3, 11},
    {168, 8, 3, 12, 2, 8},
    {171, 4, 4, 12, 4, 11},
    {173, 10, 16, 12, 1, 0},
    {193, 10, 15, 12, 1, 0},
    {212, 11, 15, 12, 1, 0},
    {233, 10, 15, 12, 1, 0},
    {252, 10, 15, 12, 1, 0},
    {271, 11, 15, 12, 1, 0},
    {292, 10, 15, 12, 1, 0},
    {311, 10, 15, 12, 1, 0},
    {330, 10, 15, 12, 1, 0},
    {349, 10, 15, 12, 1, 0},
    {368, 10, 15, 12, 1, 0},
    {387, 4, 10, 12, 4, 5},
    {392, 5, 12, 12, 3, 5},
    {400, 12, 10, 12, 0, 4},
    {415, 12, 6, 12, 0, 6},
    {424, 12, 10, 12, 0, 4},
    {439, 9, 15, 12, 2, 0},
    {456, 12, 17, 12, 0, 1},
    {482, 12, 15, 12, 0, 0},
    {505, 11, 15, 12, 1, 0},
    {526, 10, 15, 12, 1, 0},
    {545, 11, 15, 12, 1, 0},
    {566, 10, 15, 12, 1, 0},
    {585, 10, 15, 12, 1, 0},
    {604, 11, 15, 12, 1, 0},
    {625, 10, 15, 12, 1, 0},
    {644, 10, 15, 12, 1, 0},
    {663, 9, 15, 12, 1, 0},
    {680, 11, 15, 12, 1, 0},
    {701, 10, 15, 12, 2, 0},
    {720, 12, 15, 12, 0, 0},
    {743, 10, 15, 12, 1, 0},
    {762, 12, 15, 12, 0, 0},
    {785, 11, 15, 12, 1, 0},
    {806, 12, 17, 12, 0, 0},
    {832, 11, 15, 12, 1, 0},
    {853, 10, 15, 12, 1, 0},
    {872, 12, 15, 12, 0, 0},
    {895, 10, 15, 12, 1, 0},
    {914, 12, 15, 12, 0, 0},
    {937, 13, 15, 12, 0, 0},
    {962, 12, 15, 12, 0, 0},
    {985, 12, 15, 12, 0, 0},
    {1008, 11, 15, 12, 1, 0},
    {1029, 6, 18, 12, 4, 0},
    {1043, 10, 16, 12, 1, 0},
    {1063, 6, 18, 12, 2, 0},
    {1077, 12, 5, 12, 0, 0},
    {1085, 13, 2, 12, 0, 18},
    {1089, 6, 4, 12, 2, -1},
    {1092, 11, 11, 12, 0, 4},
    {1108, 11, 15, 12, 1, 0},
    {1129, 10, 11, 12, 1, 4},
    {1143, 11, 15, 12, 0, 0},
    {1164, 12, 11, 12, 0, 4},
    {1181, 10, 15, 12, 1, 0},
    {1200, 11, 15, 12, 0, 4},
    {1221, 10, 15, 12, 1, 0},
    {1240, 11, 16, 12, 1, -1},
    {1262, 8, 20, 12, 1, -1},
    {1282, 11, 15, 12, 1, 0},
    {1303, 11, 15, 12, 0, 0},
    {1324, 12, 11, 12, 0, 4},
    {1341, 10, 11, 12, 1, 4},
    {1355, 12, 11, 12, 0, 4},
    {1372, 11, 15, 12, 1, 4},
    {1393, 11, 15, 12, 0, 4},
    {1414, 10, 11, 12, 2, 4},
    {1428, 10, 11, 12, 1, 4},
    {1442, 10, 14, 12, 1, 1},
    {1460, 10, 11, 12, 1, 4},
    {1474, 12, 11, 12, 0, 4},
    {1491, 13, 11, 12, 0, 4},
    {1509, 12, 11, 12, 0, 4},
    {1526, 12, 15, 12, 0, 4},
    {1549, 10, 11, 12, 1, 4},
    {1563, 10, 18, 12, 1, 0},
    {1586, 4, 20, 12, 4, 0},
    {1596, 10, 18, 12, 1, 0},
    {1619, 12, 3, 12, 0, 7},
};

const GFXfont DeckFontDisplay PROGMEM = {(uint8_t *)DeckFontDisplayBits, (GFXglyph *)DeckFontDisplayGlyphs, 32, 126, 24};

static const uint8_t DeckFontClockBits[] PROGMEM = {
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x70, 0x00, 0xFF, 0xFF, 0xF0,
    0xFF, 0x0F, 0xF0, 0xFF, 0x0F, 0xF0, 0xFF, 0x0F, 0xF0, 0xFF, 0x0F, 0x01,
    0xC7, 0x00, 0x71, 0xC0, 0x3C, 0x70, 0x0E, 0x3C, 0x03, 0x8E, 0x0F, 0xFF,
    0xF3, 0xFF, 0xFC, 0xFF, 0xFF, 0x07, 0x1C, 0x01, 0xC7, 0x00, 0xE3, 0xC0,
    0x38, 0xE0, 0xFF, 0xFF, 0x3F, 0xFF, 0xCF, 0xFF, 0xF0, 0x71, 0xC0, 0x1C,
    0x70, 0x0F, 0x1C, 0x03, 0x8F, 0x00, 0xE3, 0x80, 0x03, 0x00, 0x0C, 0x00,
    0x30, 0x03, 0xF8, 0x3F, 0xF9, 0xFF, 0xE7, 0xB3, 0xBC, 0xC0, 0xF3, 0x03,
    0xEC, 0x07, 0xF0, 0x1F, 0xF0, 0x3F, 0xF0, 0x3F, 0xE0, 0x3F, 0xC0, 0xCF,
    0x03, 0x3C, 0x0C, 0xF7, 0x37, 0xDF, 0xFE, 0x7F, 0xF0, 0x7F, 0x80, 0x30,
    0x00, 0xC0, 0x03, 0x00, 0x0C, 0x00, 0x1E, 0x00, 0x0F, 0xE0, 0x07, 0x38,
    0x01, 0x87, 0x00, 0x61, 0xC0, 0x18, 0x70, 0x07, 0x38, 0x00, 0xFE, 0x06,
    0x1E, 0x07, 0x00, 0x07, 0x00, 0x07, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E,
    0x07, 0x82, 0x03, 0xF8, 0x01, 0xCE, 0x00, 0x61, 0xC0, 0x18, 0x70, 0x06,
    0x1C, 0x01, 0xCE, 0x00, 0x3F, 0x80, 0x07, 0x80, 0x03, 0xF0, 0x00, 0xFF,
    0x80, 0x3F, 0xF0, 0x0F, 0x84, 0x01, 0xE0, 0x00, 0x3E, 0x00, 0x03, 0xC0,
    0x00, 0x7C, 0x00, 0x0F, 0x80, 0x03, 0xF8, 0x00, 0xFF, 0x80, 0x1F, 0xF1,
    0xE7, 0x9F, 0x3C, 0xF1, 0xF7, 0x9E, 0x1E, 0xF3, 0xC3, 0xFC, 0x78, 0x3F,
    0x8F, 0x03, 0xE1, 0xF8, 0xFC, 0x1F, 0xFF, 0x81, 0xFF, 0xF8, 0x0F, 0xCF,
    0x80, 0xFF, 0xFF, 0xFF, 0xFF, 0x07, 0x07, 0x83, 0x83, 0xC1, 0xC1, 0xE0,
    0xF0, 0x78, 0x78, 0x3C, 0x1E, 0x0F, 0x07, 0x83, 0xC1, 0xE0, 0xF0, 0x78,
    0x3C, 0x1E, 0x07, 0x83, 0xC1, 0xE0, 0x78, 0x3C, 0x0E, 0x07, 0x81, 0xC0,
    0x70, 0x3C, 0x0E, 0x07, 0x81, 0xC0, 0xF0, 0x78, 0x1E, 0x0F, 0x07, 0x83,
    0xC1, 0xE0, 0xF0, 0x78, 0x3C, 0x1E, 0x0F, 0x07, 0x83, 0xC1, 0xE1, 0xE0,
    0xF0, 0x70, 0x78, 0x38, 0x3C, 0x1C, 0x00, 0x01, 0x80, 0x01, 0x80, 0x21,
    0x84, 0x71, 0x8E, 0x7F, 0xBE, 0x1F, 0xF8, 0x07, 0xE0, 0x07, 0xE0, 0x1F,
    0xF8, 0x7D, 0xBE, 0x71, 0x8E, 0x21, 0x84, 0x01, 0x80, 0x01, 0x80, 0x01,
    0xE0, 0x00, 0x78, 0x00, 0x1E, 0x00, 0x07, 0x80, 0x01, 0xE0, 0x00, 0x78,
    0x07, 0xFF, 0xF9, 0xFF, 0xFE, 0x7F, 0xFF, 0x80, 0x78, 0x00, 0x1E, 0x00,
    0x07, 0x80, 0x01, 0xE0, 0x00, 0x78, 0x00, 0x1E, 0x00, 0x3C, 0x78, 0xF1,
    0xE3, 0xCF, 0x9E, 0x3C, 0x71, 0xE0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x79,
    0xE7, 0x9E, 0x78, 0x00, 0x0E, 0x00, 0x1E, 0x00, 0x1C, 0x00, 0x1C, 0x00,
    0x38, 0x00, 0x38, 0x00, 0x70, 0x00, 0x70, 0x00, 0xE0, 0x00, 0xE0, 0x01,
    0xE0, 0x01, 0xC0, 0x03, 0xC0, 0x03, 0x80, 0x07, 0x80, 0x07, 0x00, 0x07,
    0x00, 0x0E, 0x00, 0x0E, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x3C, 0x00, 0x38,
    0x00, 0x78, 0x00, 0x70, 0x00, 0x07, 0xE0, 0x0F, 0xF0, 0x1F, 0xF8, 0x3E,
    0x7C, 0x3C, 0x3C, 0x7C, 0x3E, 0x78, 0x1E, 0x78, 0x1E, 0x79, 0x9E, 0x7B,
    0xDE, 0x7B, 0xDE, 0x79, 0x9E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78,
    0x1E, 0x7C, 0x3E, 0x3C, 0x3C, 0x3E, 0x7C, 0x1F, 0xF8, 0x0F, 0xF0, 0x07,
    0xE0, 0x1F, 0xC0, 0xFF, 0x81, 0xFF, 0x03, 0x1E, 0x00, 0x3C, 0x00, 0x78,
    0x00, 0xF0, 0x01, 0xE0, 0x03, 0xC0, 0x07, 0x80, 0x0F, 0x00, 0x1E, 0x00,
    0x3C, 0x00, 0x78, 0x00, 0xF0, 0x01, 0xE0, 0x03, 0xC0, 0x07, 0x80, 0x0F,
    0x03, 0xFF, 0xF7, 0xFF, 0xEF, 0xFF, 0xC0, 0x1F, 0xC0, 0xFF, 0xE1, 0xFF,
    0xE3, 0x83, 0xE4, 0x03, 0xC0, 0x07, 0x80, 0x0F, 0x80, 0x1E, 0x00, 0x3C,
    0x00, 0xF8, 0x03, 0xE0, 0x0F, 0x80, 0x1F, 0x00, 0x7C, 0x01, 0xF0, 0x07,
    0xC0, 0x1F, 0x00, 0x7C, 0x01, 0xF0, 0x03, 0xFF, 0xF7, 0xFF, 0xEF, 0xFF,
    0xC0, 0x0F, 0xE0, 0x7F, 0xF8, 0x7F, 0xFC, 0x78, 0x7C, 0x00, 0x3E, 0x00,
    0x3E, 0x00, 0x3C, 0x00, 0x7C, 0x07, 0xF8, 0x07, 0xE0, 0x07, 0xF8, 0x00,
    0x7C, 0x00, 0x3E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x40,
    0x3E, 0x70, 0x7C, 0x7F, 0xFC, 0x7F, 0xF8, 0x1F, 0xE0, 0x00, 0xF8, 0x00,
    0xF8, 0x01, 0xF8, 0x01, 0xF8, 0x03, 0xF8, 0x07, 0xF8, 0x07, 0x78, 0x0F,
    0x78, 0x1E, 0x78, 0x1C, 0x78, 0x3C, 0x78, 0x78, 0x78, 0x78, 0x78, 0xF0,
    0x78, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x78, 0x00, 0x78, 0x00,
    0x78, 0x00, 0x78, 0x00, 0x78, 0x7F, 0xF8, 0xFF, 0xF1, 0xFF, 0xE3, 0x80,
    0x07, 0x00, 0x0E, 0x00, 0x1C, 0x00, 0x38, 0x00, 0x7F, 0xC0, 0xFF, 0xC1,
    0xFF, 0xC2, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0x80, 0x0F, 0x00, 0x1E, 0x00,
    0x3D, 0x00, 0xFB, 0x83, 0xE7, 0xFF, 0x8F, 0xFE, 0x07, 0xF0, 0x00, 0x01,
    0xF8, 0x07, 0xFC, 0x1F, 0xFC, 0x1F, 0x0C, 0x3C, 0x04, 0x3C, 0x00, 0x78,
    0x00, 0x79, 0xF0, 0x7F, 0xF8, 0x7F, 0xFC, 0x7E, 0x3E, 0x7C, 0x1E, 0x7C,
    0x1E, 0x7C, 0x1E, 0x7C, 0x1E, 0x7C, 0x1E, 0x3C, 0x1E, 0x3C, 0x1E, 0x3E,
    0x3E, 0x1F, 0xFC, 0x0F, 0xF8, 0x03, 0xE0, 0x7F, 0xFE, 0xFF, 0xFD, 0xFF,
    0xF8, 0x01, 0xF0, 0x03, 0xC0, 0x0F, 0x80, 0x1E, 0x00, 0x7C, 0x00, 0xF8,
    0x01, 0xE0, 0x07, 0xC0, 0x0F, 0x80, 0x1E, 0x00, 0x7C, 0x00, 0xF0, 0x03,
    0xE0, 0x07, 0xC0, 0x0F, 0x00, 0x3E, 0x00, 0x78, 0x00, 0xF0, 0x03, 0xE0,
    0x00, 0x07, 0xE0, 0x1F, 0xF8, 0x3F, 0xFC, 0x3C, 0x3C, 0x3C, 0x3C, 0x78,
    0x1E, 0x38, 0x1E, 0x3C, 0x3C, 0x3C, 0x3C, 0x1F, 0xF8, 0x0F, 0xF0, 0x1F,
    0xF8, 0x3C, 0x3C, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78,
    0x1E, 0x7C, 0x3E, 0x3F, 0xFC, 0x1F, 0xF8, 0x07, 0xE0, 0x07, 0xC0, 0x1F,
    0xF0, 0x3F, 0xF8, 0x7C, 0x7C, 0x78, 0x3C, 0x78, 0x3E, 0x78, 0x3E, 0x78,
    0x1E, 0x78, 0x3E, 0x78, 0x3E, 0x78, 0x3E, 0x7C, 0x7E, 0x3F, 0xFE, 0x1F,
    0xFE, 0x0F, 0xDE, 0x00, 0x1E, 0x00, 0x3C, 0x20, 0x3C, 0x30, 0x78, 0x3F,
    0xF8, 0x3F, 0xF0, 0x1F, 0xC0, 0x79, 0xE7, 0x9E, 0x78, 0x00, 0x00, 0x00,
    0x00, 0x1E, 0x79, 0xE7, 0x9E, 0x3C, 0x78, 0xF1, 0xE3, 0xC0, 0x00, 0x00,
    0x00, 0x00, 0x01, 0xE3, 0xC7, 0x8F, 0x1E, 0x7C, 0xF1, 0xE3, 0x87, 0x00,
    0x00, 0x01, 0x00, 0x0F, 0x00, 0x3F, 0x01, 0xFF, 0x07, 0xFC, 0x3F, 0xE0,
    0xFF, 0x00, 0xFC, 0x00, 0xF8, 0x00, 0xFF, 0x00, 0x3F, 0xE0, 0x07, 0xFC,
    0x01, 0xFF, 0x00, 0x3F, 0x00, 0x0F, 0x00, 0x01, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xC0, 0x00, 0xF0, 0x00, 0xFC, 0x00, 0xFF, 0x80, 0x3F, 0xE0,
    0x07, 0xFC, 0x00, 0xFF, 0x00, 0x1F, 0x00, 0x1F, 0x00, 0xFF, 0x07, 0xFC,
    0x3F, 0xE0, 0xFF, 0x80, 0xFE, 0x00, 0xF0, 0x00, 0xC0, 0x00, 0x1F, 0x83,
    0xFF, 0x3F, 0xFD, 0xC1, 0xE0, 0x0F, 0x80, 0x7C, 0x03, 0xC0, 0x3E, 0x03,
    0xE0, 0x3E, 0x03, 0xE0, 0x1E, 0x00, 0xF0, 0x0F, 0x00, 0x78, 0x03, 0xC0,
    0x00, 0x00, 0x00, 0x07, 0x80, 0x3C, 0x01, 0xE0, 0x0F, 0x00, 0x01, 0xF8,
    0x03, 0xFF, 0x03, 0xFF, 0xC3, 0xE1, 0xE3, 0xC0, 0x79, 0xC0, 0x1D, 0xC1,
    0xEE, 0xE3, 0xFF, 0x71, 0xFF, 0xF1, 0xE3, 0xF8, 0xE0, 0xFC, 0x70, 0x7E,
    0x38, 0x3F, 0x1C, 0x1F, 0x8E, 0x0F, 0xC7, 0x8F, 0x71, 0xFF, 0xB8, 0xFF,
    0xDE, 0x1E, 0xE7, 0x00, 0x03, 0xC0, 0x00, 0xF8, 0x18, 0x3F, 0xFC, 0x07,
    0xFE, 0x00, 0xFC, 0x00, 0x03, 0xF0, 0x00, 0xFC, 0x00, 0x3F, 0x00, 0x1F,
    0xE0, 0x07, 0xF8, 0x01, 0xFE, 0x00, 0x73, 0x80, 0x3C, 0xF0, 0x0F, 0x3C,
    0x03, 0xCF, 0x00, 0xF1, 0xC0, 0x78, 0x78, 0x1E, 0x1E, 0x07, 0xFF, 0x83,
    0xFF, 0xF0, 0xFF, 0xFC, 0x3C, 0x0F, 0x0F, 0x03, 0xC7, 0xC0, 0xF9, 0xE0,
    0x1E, 0x78, 0x07, 0x9E, 0x01, 0xE0, 0x7F, 0xF0, 0x7F, 0xFC, 0x7F, 0xFC,
    0x78, 0x3E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x3E, 0x7F, 0xFC,
    0x7F, 0xF0, 0x7F, 0xFC, 0x78, 0x3E, 0x78, 0x1E, 0x78, 0x1F, 0x78, 0x0F,
    0x78, 0x0F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x3E, 0x7F, 0xFE, 0x7F, 0xFC,
    0x7F, 0xF0, 0x03, 0xF0, 0x3F, 0xF1, 0xFF, 0xCF, 0xC7, 0x3C, 0x05, 0xF0,
    0x07, 0x80, 0x1E, 0x00, 0xF8, 0x03, 0xE0, 0x0F, 0x80, 0x3E, 0x00, 0xF8,
    0x03, 0xE0, 0x07, 0x80, 0x1E, 0x00, 0x7C, 0x00, 0xF0, 0x13, 0xF1, 0xC7,
    0xFF, 0x0F, 0xFC, 0x0F, 0xC0, 0xFF, 0x81, 0xFF, 0xC3, 0xFF, 0xC7, 0x87,
    0xCF, 0x07, 0xDE, 0x0F, 0xBC, 0x0F, 0x78, 0x1E, 0xF0, 0x3F, 0xE0, 0x7F,
    0xC0, 0xFF, 0x81, 0xFF, 0x03, 0xFE, 0x07, 0xFC, 0x0F, 0x78, 0x1E, 0xF0,
    0x7D, 0xE0, 0xFB, 0xC3, 0xE7, 0xFF, 0x8F, 0xFE, 0x1F, 0xF0, 0x00, 0xFF,
    0xFD, 0xFF, 0xFB, 0xFF, 0xF7, 0xC0, 0x0F, 0x80, 0x1F, 0x00, 0x3E, 0x00,
    0x7C, 0x00, 0xFF, 0xF9, 0xFF, 0xF3, 0xFF, 0xE7, 0xC0, 0x0F, 0x80, 0x1F,
    0x00, 0x3E, 0x00, 0x7C, 0x00, 0xF8, 0x01, 0xF0, 0x03, 0xE0, 0x07, 0xFF,
    0xEF, 0xFF, 0xDF, 0xFF, 0x80, 0x7F, 0xFC, 0xFF, 0xF9, 0xFF, 0xF3, 0xC0,
    0x07, 0x80, 0x0F, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x7F, 0xF8, 0xFF, 0xF1,
    0xFF, 0xE3, 0xC0, 0x07, 0x80, 0x0F, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x78,
    0x00, 0xF0, 0x01, 0xE0, 0x03, 0xC0, 0x07, 0x80, 0x0F, 0x00, 0x00, 0x01,
    0xF8, 0x07, 0xFE, 0x1F, 0xFE, 0x1F, 0x0E, 0x3E, 0x02, 0x3C, 0x00, 0x7C,
    0x00, 0x7C, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x7F, 0x78,
    0x7F, 0x78, 0x7F, 0x7C, 0x0F, 0x7C, 0x0F, 0x3C, 0x0F, 0x3E, 0x0F, 0x1F,
    0x0F, 0x1F, 0xFF, 0x07, 0xFE, 0x01, 0xF8, 0xF0, 0x3D, 0xE0, 0x7B, 0xC0,
    0xF7, 0x81, 0xEF, 0x03, 0xDE, 0x07, 0xBC, 0x0F, 0x78, 0x1E, 0xFF, 0xFD,
    0xFF, 0xFB, 0xFF, 0xF7, 0x81, 0xEF, 0x03, 0xDE, 0x07, 0xBC, 0x0F, 0x78,
    0x1E, 0xF0, 0x3D, 0xE0, 0x7B, 0xC0, 0xF7, 0x81, 0xEF, 0x03, 0xDE, 0x07,
    0x80, 0x7F, 0xFD, 0xFF, 0xF7, 0xFF, 0xC1, 0xE0, 0x07, 0x80, 0x1E, 0x00,
    0x78, 0x01, 0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80,
    0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x1F, 0xFF,
    0x7F, 0xFD, 0xFF, 0xF0, 0x0F, 0xFC, 0x3F, 0xF0, 0xFF, 0xC0, 0x1F, 0x00,
    0x7C, 0x01, 0xF0, 0x07, 0xC0, 0x1F, 0x00, 0x7C, 0x01, 0xF0, 0x07, 0xC0,
    0x1F, 0x00, 0x7C, 0x01, 0xF0, 0x07, 0xC0, 0x1F, 0x40, 0x7D, 0x81, 0xE7,
    0x0F, 0x9F, 0xFE, 0x7F, 0xF0, 0x3F, 0x00, 0x78, 0x1F, 0x3C, 0x0F, 0x9E,
    0x0F, 0x8F, 0x0F, 0x87, 0x8F, 0x83, 0xC7, 0x81, 0xE7, 0xC0, 0xF7, 0xC0,
    0x7F, 0xC0, 0x3F, 0xE0, 0x1F, 0xF8, 0x0F, 0xFC, 0x07, 0xFF, 0x03, 0xE7,
    0x81, 0xE3, 0xE0, 0xF0, 0xF0, 0x78, 0x7C, 0x3C, 0x1F, 0x1E, 0x0F, 0x8F,
    0x03, 0xE7, 0x81, 0xF3, 0xC0, 0x7C, 0xF8, 0x03, 0xE0, 0x0F, 0x80, 0x3E,
    0x00, 0xF8, 0x03, 0xE0, 0x0F, 0x80, 0x3E, 0x00, 0xF8, 0x03, 0xE0, 0x0F,
    0x80, 0x3E, 0x00, 0xF8, 0x03, 0xE0, 0x0F, 0x80, 0x3E, 0x00, 0xF8, 0x03,
    0xE0, 0x0F, 0x80, 0x3F, 0xFF, 0xFF, 0xFF, 0xFF, 0xF0, 0xFC, 0x3F, 0xFC,
    0x3F, 0xFC, 0x3F, 0xFC, 0x3F, 0xFE, 0x7F, 0xFE, 0x7F, 0xFE, 0x7F, 0xF6,
    0x6F, 0xF7, 0xEF, 0xF7, 0xEF, 0xF7, 0xEF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3,
    0xCF, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0,
    0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0x7C, 0x1E, 0x7C, 0x1E, 0x7C, 0x1E, 0x7E,
    0x1E, 0x7E, 0x1E, 0x7E, 0x1E, 0x7F, 0x1E, 0x7F, 0x1E, 0x7F, 0x9E, 0x7B,
    0x9E, 0x7B, 0x9E, 0x79, 0xDE, 0x79, 0xDE, 0x79, 0xFE, 0x78, 0xFE, 0x78,
    0xFE, 0x78, 0x7E, 0x78, 0x7E, 0x78, 0x7E, 0x78, 0x3E, 0x78, 0x3E, 0x78,
    0x3E, 0x07, 0xE0, 0x0F, 0xF8, 0x1F, 0xFC, 0x3E, 0x7C, 0x7C, 0x3E, 0x78,
    0x1E, 0x78, 0x1E, 0x78, 0x1E, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8,
    0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x7C,
    0x3E, 0x3E, 0x7C, 0x1F, 0xFC, 0x0F, 0xF8, 0x07, 0xE0, 0xFF, 0xC1, 0xFF,
    0xF3, 0xFF, 0xF7, 0xC3, 0xEF, 0x83, 0xDF, 0x07, 0xFE, 0x0F, 0xFC, 0x1F,
    0xF8, 0x3F, 0xF0, 0x7B, 0xE1, 0xF7, 0xFF, 0xEF, 0xFF, 0x9F, 0xF8, 0x3E,
    0x00, 0x7C, 0x00, 0xF8, 0x01, 0xF0, 0x03, 0xE0, 0x07, 0xC0, 0x0F, 0x80,
    0x1F, 0x00, 0x00, 0x07, 0xE0, 0x0F, 0xF8, 0x1F, 0xF8, 0x3E, 0x7C, 0x7C,
    0x3E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8,
    0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0x78, 0x1E, 0x78, 0x1E, 0x78,
    0x1E, 0x7C, 0x3E, 0x3E, 0x7C, 0x1F, 0xFC, 0x0F, 0xF8, 0x07, 0xF8, 0x00,
    0x7C, 0x00, 0x3E, 0x00, 0x1C, 0x00, 0x18, 0x7F, 0xE0, 0x1F, 0xFE, 0x07,
    0xFF, 0xC1, 0xE1, 0xF8, 0x78, 0x3E, 0x1E, 0x07, 0x87, 0x81, 0xE1, 0xE0,
    0x78, 0x78, 0x3E, 0x1E, 0x1F, 0x07, 0xFF, 0x81, 0xFF, 0xC0, 0x7F, 0xF0,
    0x1E, 0x3E, 0x07, 0x87, 0x81, 0xE1, 0xF0, 0x78, 0x3C, 0x1E, 0x0F, 0x87,
    0x81, 0xE1, 0xE0, 0x7C, 0x78, 0x0F, 0x1E, 0x03, 0xE0, 0x07, 0xF0, 0x1F,
    0xFC, 0x3F, 0xFC, 0x7C, 0x1C, 0x78, 0x0C, 0x78, 0x04, 0x78, 0x00, 0x7C,
    0x00, 0x7F, 0x00, 0x3F, 0xE0, 0x0F, 0xF8, 0x03, 0xFC, 0x00, 0x7E, 0x00,
    0x3E, 0x00, 0x1E, 0x00, 0x1E, 0x40, 0x1E, 0x70, 0x3E, 0x78, 0x7E, 0x7F,
    0xFC, 0x3F, 0xF8, 0x0F, 0xE0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x03,
    0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03,
    0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03,
    0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03,
    0xC0, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78,
    0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78,
    0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78,
    0x1E, 0x7C, 0x3E, 0x3F, 0xFC, 0x1F, 0xF8, 0x07, 0xE0, 0x78, 0x07, 0x9F,
    0x01, 0xE7, 0xC0, 0xF8, 0xF0, 0x3C, 0x3C, 0x0F, 0x0F, 0x03, 0xC3, 0xE0,
    0xF0, 0x78, 0x78, 0x1E, 0x1E, 0x07, 0x87, 0x81, 0xE1, 0xE0, 0x3C, 0xF8,
    0x0F, 0x3C, 0x03, 0xCF, 0x00, 0xF3, 0xC0, 0x3C, 0xF0, 0x07, 0xF8, 0x01,
    0xFE, 0x00, 0x7F, 0x80, 0x1F, 0xE0, 0x03, 0xF0, 0x00, 0xFC, 0x00, 0xF0,
    0x03, 0xDE, 0x00, 0x7B, 0xC0, 0x0F, 0x78, 0x01, 0xE7, 0x00, 0x3C, 0xE0,
    0x07, 0x1C, 0x78, 0xE3, 0xCF, 0x1C, 0x79, 0xE3, 0x8F, 0x3E, 0x71, 0xEF,
    0xDE, 0x3D, 0xFB, 0xC3, 0xBF, 0x78, 0x76, 0x6F, 0x0F, 0xCF, 0xC1, 0xF9,
    0xF8, 0x3F, 0x3F, 0x07, 0xE3, 0xE0, 0xF8, 0x7C, 0x1F, 0x0F, 0x81, 0xE1,
    0xF0, 0x3C, 0x3E, 0x00, 0x78, 0x07, 0x9F, 0x03, 0xE3, 0xC0, 0xF0, 0x78,
    0x78, 0x1F, 0x3E, 0x03, 0xCF, 0x00, 0xFF, 0xC0, 0x1F, 0xE0, 0x07, 0xF8,
    0x00, 0xFC, 0x00, 0x1E, 0x00, 0x0F, 0xC0, 0x03, 0xF0, 0x01, 0xFE, 0x00,
    0x7F, 0x80, 0x3F, 0xF0, 0x0F, 0x3E, 0x07, 0x87, 0x83, 0xE1, 0xF0, 0xF0,
    0x3C, 0x7C, 0x0F, 0x9E, 0x01, 0xE0, 0xF8, 0x07, 0xDF, 0x01, 0xE7, 0xC0,
    0xF8, 0xF0, 0x3C, 0x3E, 0x1F, 0x07, 0x87, 0x81, 0xF3, 0xE0, 0x3C, 0xF0,
    0x0F, 0xFC, 0x01, 0xFE, 0x00, 0x7F, 0x80, 0x0F, 0xC0, 0x03, 0xF0, 0x00,
    0x78, 0x00, 0x1E, 0x00, 0x07, 0x80, 0x01, 0xE0, 0x00, 0x78, 0x00, 0x1E,
    0x00, 0x07, 0x80, 0x01, 0xE0, 0x00, 0x78, 0x00, 0x7F, 0xFF, 0x7F, 0xFF,
    0x7F, 0xFF, 0x00, 0x3F, 0x00, 0x3E, 0x00, 0x7E, 0x00, 0xFC, 0x00, 0xF8,
    0x01, 0xF8, 0x03, 0xF0, 0x03, 0xE0, 0x07, 0xE0, 0x0F, 0xC0, 0x0F, 0x80,
    0x1F, 0x80, 0x3F, 0x00, 0x3E, 0x00, 0x7E, 0x00, 0x7C, 0x00, 0x7F, 0xFF,
    0x7F, 0xFF, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0xF0, 0xF0, 0xFF, 0xFF, 0xFF, 0x70, 0x00, 0x78, 0x00, 0x38,
    0x00, 0x3C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x07,
    0x00, 0x07, 0x00, 0x07, 0x80, 0x03, 0x80, 0x03, 0xC0, 0x01, 0xC0, 0x01,
    0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0x70, 0x00, 0x70, 0x00, 0x38, 0x00,
    0x38, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1E, 0x00, 0x0E, 0xFF, 0xFF, 0xFF,
    0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F,
    0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0xFF, 0xFF, 0xFF,
    0x01, 0xE0, 0x00, 0xFC, 0x00, 0x7F, 0x80, 0x3F, 0xF0, 0x0F, 0x3C, 0x07,
    0x87, 0x83, 0xC0, 0xF1, 0xE0, 0x1E, 0xFF, 0xFF, 0xDF, 0xFF, 0xFB, 0xFF,
    0xFF, 0x00, 0xF0, 0x3C, 0x0F, 0x03, 0x80, 0xE0, 0x38, 0x0F, 0xF0, 0x3F,
    0xFC, 0x3F, 0xFC, 0x30, 0x3E, 0x00, 0x1E, 0x0F, 0xFE, 0x3F, 0xFE, 0x7F,
    0xFE, 0x7C, 0x1E, 0xF8, 0x1E, 0xF8, 0x1E, 0xF8, 0x3E, 0x7C, 0x7E, 0x7F,
    0xFE, 0x3F, 0xDE, 0x0F, 0x9E, 0xF0, 0x01, 0xE0, 0x03, 0xC0, 0x07, 0x80,
    0x0F, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x78, 0xF0, 0xFF, 0xF9, 0xFF, 0xF3,
    0xF1, 0xF7, 0xC1, 0xEF, 0x83, 0xFF, 0x07, 0xFC, 0x0F, 0xF8, 0x1F, 0xF8,
    0x3F, 0xF0, 0x7F, 0xE0, 0xF7, 0xE3, 0xEF, 0xFF, 0x9F, 0xFF, 0x3C, 0x78,
    0x00, 0x07, 0xF0, 0x7F, 0xF3, 0xFF, 0xCF, 0x82, 0x7C, 0x01, 0xE0, 0x07,
    0x80, 0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80, 0x1F, 0x00, 0x3E, 0x08,
    0xFF, 0xF1, 0xFF, 0xC1, 0xFC, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x78, 0x00,
    0xF0, 0x01, 0xE0, 0x03, 0xC0, 0x07, 0x87, 0xCF, 0x3F, 0xDE, 0x7F, 0xFD,
    0xF1, 0xFB, 0xC1, 0xF7, 0x83, 0xFF, 0x07, 0xFE, 0x07, 0xFC, 0x0F, 0xF8,
    0x3E, 0xF0, 0x7D, 0xE0, 0xFB, 0xE3, 0xF3, 0xFF, 0xE7, 0xFB, 0xC3, 0xE7,
    0x80, 0x07, 0xE0, 0x1F, 0xF8, 0x3F, 0xFC, 0x7C, 0x3E, 0x7C, 0x1E, 0x78,
    0x1E, 0xF8, 0x1F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x78, 0x00, 0x78,
    0x00, 0x7C, 0x06, 0x3F, 0xFE, 0x1F, 0xFE, 0x07, 0xF8, 0x01, 0xFC, 0x0F,
    0xF0, 0x7F, 0xC1, 0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x1F, 0xFF, 0x7F,
    0xFD, 0xFF, 0xF0, 0x78, 0x01, 0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01,
    0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80, 0x1E, 0x00,
    0x78, 0x00, 0x0F, 0x9E, 0x1F, 0xFE, 0x3F, 0xFE, 0x7C, 0x7E, 0x7C, 0x3E,
    0x78, 0x3E, 0x78, 0x1E, 0xF8, 0x1E, 0xF8, 0x1E, 0x78, 0x1E, 0x78, 0x3E,
    0x7C, 0x3E, 0x7C, 0x7E, 0x3F, 0xFE, 0x1F, 0xFE, 0x0F, 0x9E, 0x00, 0x1E,
    0x00, 0x3E, 0x30, 0x7C, 0x3F, 0xFC, 0x3F, 0xF8, 0x0F, 0xE0, 0x78, 0x01,
    0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80, 0x1E, 0x78,
    0x7F, 0xF9, 0xFF, 0xE7, 0xC7, 0x9E, 0x1F, 0x78, 0x7D, 0xE1, 0xF7, 0x87,
    0xDE, 0x1F, 0x78, 0x7D, 0xE1, 0xF7, 0x87, 0xDE, 0x1F, 0x78, 0x7D, 0xE1,
    0xF7, 0x87, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07,
    0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F, 0xC0, 0x7F, 0xC0, 0x7F,
    0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07,
    0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0xFF, 0xFE, 0xFF,
    0xFE, 0xFF, 0xFE, 0x03, 0xC0, 0x78, 0x0F, 0x01, 0xE0, 0x3C, 0x00, 0x00,
    0x00, 0x00, 0x3F, 0xC7, 0xF8, 0xFF, 0x01, 0xE0, 0x3C, 0x07, 0x80, 0xF0,
    0x1E, 0x03, 0xC0, 0x78, 0x0F, 0x01, 0xE0, 0x3C, 0x07, 0x80, 0xF0, 0x1E,
    0x03, 0xC0, 0x78, 0x1F, 0x7F, 0xEF, 0xF9, 0xFE, 0x00, 0x78, 0x00, 0x78,
    0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78,
    0x3E, 0x78, 0x7C, 0x78, 0xF8, 0x79, 0xF0, 0x7B, 0xE0, 0x7F, 0x80, 0x7F,
    0x80, 0x7F, 0xC0, 0x7F, 0xE0, 0x79, 0xE0, 0x78, 0xF0, 0x78, 0xF8, 0x78,
    0x78, 0x78, 0x7C, 0x78, 0x3E, 0x78, 0x1E, 0xFF, 0x80, 0xFF, 0x80, 0xFF,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0xC0, 0x07,
    0xFE, 0x03, 0xFE, 0x00, 0xFE, 0xE7, 0x3C, 0xFF, 0xFE, 0xFF, 0xFF, 0xF3,
    0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3,
    0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3,
    0xCF, 0x79, 0xE1, 0xFF, 0xE7, 0xFF, 0x9F, 0x1E, 0x78, 0x7D, 0xE1, 0xF7,
    0x87, 0xDE, 0x1F, 0x78, 0x7D, 0xE1, 0xF7, 0x87, 0xDE, 0x1F, 0x78, 0x7D,
    0xE1, 0xF7, 0x87, 0xDE, 0x1F, 0x07, 0xE0, 0x1F, 0xF8, 0x3F, 0xFC, 0x3C,
    0x3E, 0x7C, 0x1E, 0x78, 0x1E, 0x78, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0x78,
    0x1F, 0x78, 0x1E, 0x7C, 0x1E, 0x3C, 0x3E, 0x3F, 0xFC, 0x1F, 0xF8, 0x07,
    0xE0, 0xF1, 0xE1, 0xFF, 0xF3, 0xFF, 0xE7, 0xE3, 0xEF, 0x83, 0xDF, 0x07,
    0xFE, 0x0F, 0xF8, 0x1F, 0xF0, 0x3F, 0xF0, 0x7F, 0xE0, 0xFF, 0xC1, 0xEF,
    0xC7, 0xDF, 0xFF, 0x3F, 0xFE, 0x78, 0xF0, 0xF0, 0x01, 0xE0, 0x03, 0xC0,
    0x07, 0x80, 0x0F, 0x00, 0x1E, 0x00, 0x00, 0x0F, 0x9E, 0x7F, 0xBC, 0xFF,
    0xFB, 0xE3, 0xF7, 0x83, 0xEF, 0x07, 0xFE, 0x0F, 0xFC, 0x0F, 0xF8, 0x1F,
    0xF0, 0x7D, 0xE0, 0xFB, 0xC1, 0xF7, 0xC7, 0xE7, 0xFF, 0xCF, 0xF7, 0x87,
    0xCF, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x78, 0x00, 0xF0, 0x01, 0xE0, 0x03,
    0xC0, 0xF9, 0xF7, 0xFF, 0xFF, 0xFF, 0xFC, 0x3F, 0xC0, 0x7C, 0x03, 0xE0,
    0x1F, 0x00, 0xF8, 0x07, 0xC0, 0x3E, 0x01, 0xF0, 0x0F, 0x80, 0x7C, 0x03,
    0xE0, 0x1F, 0x00, 0x0F, 0xE0, 0xFF, 0xE7, 0xFF, 0x9E, 0x04, 0x78, 0x01,
    0xF0, 0x07, 0xF8, 0x1F, 0xFC, 0x1F, 0xF8, 0x0F, 0xE0, 0x07, 0xC0, 0x0F,
    0x60, 0x79, 0xFF, 0xE7, 0xFF, 0x07, 0xF8, 0x07, 0x80, 0x0F, 0x00, 0x1E,
    0x00, 0x3C, 0x00, 0x78, 0x0F, 0xFF, 0xDF, 0xFF, 0xBF, 0xFF, 0x07, 0x80,
    0x0F, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x78, 0x00, 0xF0, 0x01, 0xE0, 0x03,
    0xC0, 0x07, 0x80, 0x0F, 0x80, 0x1F, 0xF8, 0x1F, 0xF0, 0x1F, 0xE0, 0xF8,
    0x7F, 0xE1, 0xFF, 0x87, 0xFE, 0x1F, 0xF8, 0x7F, 0xE1, 0xFF, 0x87, 0xFE,
    0x1F, 0xF8, 0x7F, 0xE1, 0xFF, 0x87, 0xFE, 0x1F, 0x78, 0xFD, 0xFF, 0xF3,
    0xFF, 0xC7, 0x9F, 0xF8, 0x1F, 0x78, 0x1E, 0x78, 0x1E, 0x7C, 0x1E, 0x3C,
    0x3C, 0x3C, 0x3C, 0x3C, 0x3C, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x0E,
    0x70, 0x0F, 0xF0, 0x0F, 0xF0, 0x07, 0xE0, 0x07, 0xE0, 0x07, 0xE0, 0xF0,
    0x03, 0xDE, 0x00, 0x7B, 0xC0, 0x0F, 0x38, 0x01, 0xC7, 0x1E, 0x38, 0xE3,
    0xC7, 0x1E, 0x79, 0xE3, 0xCF, 0xBC, 0x3B, 0xF7, 0x07, 0x7E, 0xE0, 0xEC,
    0xDC, 0x1F, 0x9F, 0x83, 0xF3, 0xF0, 0x3E, 0x7E, 0x07, 0x87, 0x80, 0xF0,
    0xF0, 0x3E, 0x1F, 0x0F, 0x87, 0xC1, 0xF3, 0xE0, 0x3C, 0xF0, 0x07, 0xFC,
    0x01, 0xFE, 0x00, 0x3F, 0x00, 0x07, 0x80, 0x03, 0xF0, 0x00, 0xFE, 0x00,
    0x7F, 0x80, 0x3E, 0xF0, 0x1F, 0x3E, 0x07, 0x87, 0x83, 0xE1, 0xF1, 0xF0,
    0x3E, 0x7C, 0x07, 0x9F, 0x03, 0xE3, 0xC0, 0xF0, 0xF8, 0x3C, 0x1E, 0x1F,
    0x07, 0x87, 0x81, 0xF3, 0xE0, 0x3C, 0xF0, 0x0F, 0x3C, 0x01, 0xFF, 0x00,
    0x7F, 0x80, 0x1F, 0xE0, 0x03, 0xF0, 0x00, 0xFC, 0x00, 0x1F, 0x00, 0x07,
    0x80, 0x01, 0xE0, 0x00, 0xF8, 0x00, 0x7C, 0x00, 0xFF, 0x00, 0x3F, 0x80,
    0x0F, 0x80, 0x00, 0x7F, 0xFD, 0xFF, 0xF7, 0xFF, 0xC0, 0x1F, 0x00, 0xF8,
    0x07, 0xC0, 0x3E, 0x01, 0xF0, 0x0F, 0x80, 0x7C, 0x03, 0xE0, 0x1F, 0x80,
    0x7C, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0xF8, 0x0F, 0xE0, 0x3F,
    0x81, 0xF0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80, 0x1E,
    0x00, 0x78, 0x03, 0xE0, 0x7F, 0x01, 0xF8, 0x07, 0xF0, 0x03, 0xE0, 0x07,
    0x80, 0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01,
    0xE0, 0x07, 0xC0, 0x0F, 0xE0, 0x3F, 0x80, 0x3E, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7E,
    0x01, 0xFC, 0x07, 0xF0, 0x03, 0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01,
    0xE0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01, 0xF0, 0x03, 0xF8, 0x07, 0xE0,
    0x3F, 0x81, 0xF0, 0x07, 0x80, 0x1E, 0x00, 0x78, 0x01, 0xE0, 0x07, 0x80,
    0x1E, 0x00, 0x78, 0x01, 0xE0, 0x0F, 0x81, 0xFC, 0x07, 0xF0, 0x1F, 0x00,
    0x3E, 0x01, 0x7F, 0xC3, 0xFF, 0xFF, 0xC1, 0xFE, 0x00, 0x7C,
};

static const GFXglyph DeckFontClockGlyphs[] PROGMEM = {
    {0, 0, 0, 18, 0, 0},
    {0, 4, 22, 18, 7, 0},
    {11, 12, 8, 18, 3, 0},
    {23, 18, 20, 18, 0, 2},
    {68, 14, 26, 18, 2, 0},
    {114, 18, 22, 18, 0, 0},
    {164, 19, 22, 18, 0, 0},
    {217, 4, 8, 18, 7, 0},
    {221, 9, 27, 18, 5, -1},
    {252, 9, 27, 18, 4, -1},
    {283, 16, 14, 18, 1, 0},
    {311, 18, 15, 18, 0, 5},
    {345, 7, 10, 18, 5, 17},
    {354, 10, 4, 18, 4, 11},
    {359, 6, 5, 18, 6, 17},
    {363, 16, 25, 18, 1, 0},
    {413, 16, 22, 18, 1, 0},
    {457, 15, 22, 18, 2, 0},
    {499, 15, 22, 18, 1, 0},
    {541, 16, 22, 18, 1, 0},
    {585, 16, 22, 18, 1, 0},
    {629, 15, 22, 18, 2, 0},
    {671, 16, 22, 18, 1, 0},
    {715, 15, 22, 18, 1, 0},
    {757, 16, 22, 18, 1, 0},
    {801, 16, 22, 18, 1, 0},
    {845, 6, 16, 18, 6, 6},
    {857, 7, 21, 18, 5, 6},
    {876, 16, 16, 18, 1, 5},
    {908, 16, 9, 18, 1, 8},
    {926, 16, 16, 18, 1, 5},
    {958, 13, 22, 18, 3, 0},
    {994, 17, 25, 18, 0, 2},
    {1048, 18, 22, 18, 0, 0},
    {1098, 16, 22, 18, 1, 0},
    {1142, 14, 22, 18, 2, 0},
    {1181, 15, 22, 18, 2, 0},
    {1223, 15, 22, 18, 2, 0},
    {1265, 15, 22, 18, 2, 0},
    {1307, 16, 22, 18, 1, 0},
    {1351, 15, 22, 18, 2, 0},
    {1393, 14, 22, 18, 2, 0},
    {1432, 14, 22, 18, 1, 0},
    {1471, 17, 22, 18, 1, 0},
    {1518, 14, 22, 18, 3, 0},
    {1557, 16, 22, 18, 1, 0},
    {1601, 16, 22, 18, 1, 0},
    {1645, 16, 22, 18, 1, 0},
    {1689, 15, 22, 18, 2, 0},
    {1731, 16, 26, 18, 1, 0},
    {1783, 18, 22, 18, 1, 0},
    {1833, 16, 22, 18, 1, 0},
    {1877, 16, 22, 18, 1, 0},
    {1921, 16, 22, 18, 1, 0},
    {1965, 18, 22, 18, 0, 0},
    {2015, 19, 22, 18, 0, 0},
    {2068, 18, 22, 18, 0, 0},
    {2118, 18, 22, 18, 0, 0},
    {2168, 16, 22, 18, 1, 0},
    {2212, 8, 27, 18, 6, -1},
    {2239, 16, 25, 18, 1, 0},
    {2289, 8, 27, 18, 4, -1},
    {2316, 18, 8, 18, 0, 0},
    {2334, 19, 3, 18, 0, 26},
    {2342, 9, 6, 18, 3, -2},
    {2349, 16, 16, 18, 1, 6},
    {2381, 15, 23, 18, 2, -1},
    {2425, 14, 16, 18, 2, 6},
    {2453, 15, 23, 18, 1, -1},
    {2497, 16, 16, 18, 1, 6},
    {2529, 14, 23, 18, 2, -1},
    {2570, 16, 22, 18, 1, 6},
    {2614, 14, 23, 18, 2, -1},
    {2655, 16, 24, 18, 2, -2},
    {2703, 11, 30, 18, 2, -2},
    {2745, 16, 23, 18, 2, -1},
    {2791, 16, 23, 18, 1, -1},
    {2837, 16, 16, 18, 1, 6},
    {2869, 14, 16, 18, 2, 6},
    {2897, 16, 16, 18, 1, 6},
    {2929, 15, 22, 18, 2, 6},
    {2971, 15, 22, 18, 1, 6},
    {3013, 13, 16, 18, 4, 6},
    {3039, 14, 16, 18, 2, 6},
    {3067, 15, 21, 18, 1, 1},
    {3107, 14, 16, 18, 2, 6},
    {3135, 16, 16, 18, 1, 6},
    {3167, 19, 16, 18, 0, 6},
    {3205, 18, 16, 18, 0, 6},
    {3241, 18, 22, 18, 0, 6},
    {3291, 14, 16, 18, 2, 6},
    {3319, 14, 28, 18, 2, -1},
    {3368, 4, 30, 18, 7, -1},
    {3383, 14, 28, 18, 2, -1},
    {3432, 16, 5, 18, 1, 10},
};

const GFXfont DeckFontClock PROGMEM = {(uint8_t *)DeckFontClockBits, (GFXglyph *)DeckFontClockGlyphs, 32, 126, 36};


static const GFXfont *fontFor(uint8_t size) {
  if (size >= 4) return &DeckFontClock;
  return size >= 2 ? &DeckFontDisplay : &DeckFontBody;
}

// Body lines advance 13 px and display lines 26: the descenders hang below the
// cap box, so both are taller than the 8 * size the 5x7 font needed.
static uint8_t lineHeightFor(uint8_t size) { return size >= 2 ? 26 : 13; }

// The faces belong to this sketch, so read them here instead of through
// Adafruit_GFX. drawChar() has no background colour ("THIS IS ON PURPOSE AND BY
// DESIGN"), so a repainted label left the old glyphs underneath, and its byte
// order is the library's business rather than ours. Each glyph here is one
// bitstream, MSB first, continuing straight from the end of a row into the next
// with no padding - the same bytes artifacts/deck_screens.py blits into the
// frames, so the glass and the picture of it are one drawing.
static const GFXglyph *glyphFor(const GFXfont *font, char c) {
  if ((uint8_t)c < 32 || (uint8_t)c > 126) c = '?';
  return &font->glyph[(uint8_t)c - 32];
}

static uint16_t textAdvance(const GFXfont *font, const char *text) {
  uint16_t pen = 0;
  for (const char *p = text; *p; ++p) pen += pgm_read_byte(&glyphFor(font, *p)->xAdvance);
  return pen;
}

// The ink box getTextBounds() used to answer, measured from the same tables:
// x0..x1 and y0..y1 relative to the cursor, empty when the text has no ink.
static void textInk(const GFXfont *font, const char *text, int16_t *x0, int16_t *x1,
                    int16_t *y0, int16_t *y1) {
  int16_t left = 0, right = 0, top = 0, bottom = 0;
  bool any = false;
  uint16_t pen = 0;
  for (const char *p = text; *p; ++p) {
    const GFXglyph *g = glyphFor(font, *p);
    const uint8_t w = pgm_read_byte(&g->width), h = pgm_read_byte(&g->height);
    if (w && h) {
      const int16_t l = pen + (int8_t)pgm_read_byte(&g->xOffset);
      const int16_t r = l + w;
      const int16_t t = (int8_t)pgm_read_byte(&g->yOffset);
      const int16_t b = t + h;
      if (!any) { left = l; right = r; top = t; bottom = b; any = true; }
      else {
        if (l < left) left = l;
        if (r > right) right = r;
        if (t < top) top = t;
        if (b > bottom) bottom = b;
      }
    }
    pen += pgm_read_byte(&g->xAdvance);
  }
  *x0 = left; *x1 = right; *y0 = top; *y1 = bottom;
}

// One string, one cell per glyph cleared to bg first: no stale pixels, no
// dependence on how a library version walks a bitmap.
static void blitText(const GFXfont *font, const char *text, int x, int y, uint16_t fg,
                     uint16_t bg) {
  uint16_t pen = 0;
  for (const char *p = text; *p; ++p) {
    const GFXglyph *g = glyphFor(font, *p);
    const uint8_t w = pgm_read_byte(&g->width), h = pgm_read_byte(&g->height);
    const int16_t gx = x + pen + (int8_t)pgm_read_byte(&g->xOffset);
    const int16_t gy = y + (int8_t)pgm_read_byte(&g->yOffset);
    if (w && h) {
      const uint16_t offset = pgm_read_word(&g->bitmapOffset);
      if (bg != fg) tft.fillRect(gx, gy, w, h, bg);
      uint16_t bit = 0;
      for (uint8_t row = 0; row < h; ++row) {
        int16_t run = -1;
        for (uint8_t col = 0; col < w; ++col, ++bit) {
          const uint8_t byte = font->bitmap[offset + (bit >> 3)];
          const bool on = byte & (0x80 >> (bit & 7));
          if (on && run < 0) run = col;
          else if (!on && run >= 0) {
            tft.drawFastHLine(gx + run, gy + row, col - run, fg);
            run = -1;
          }
        }
        if (run >= 0) tft.drawFastHLine(gx + run, gy + row, w - run, fg);
      }
    }
    pen += pgm_read_byte(&g->xAdvance);
  }
}

static void drawTextAt(const char *text, int x, int y, uint8_t size, uint16_t fg,
                       uint16_t bg) {
  blitText(fontFor(size), text, x, y, fg, bg);
}

static void drawTextCentered(const char *text, Rect r, uint8_t size, uint16_t fg,
                             uint16_t bg) {
  int16_t x0 = 0, x1 = 0, y0 = 0, y1 = 0;
  textInk(fontFor(size), text, &x0, &x1, &y0, &y1);
  blitText(fontFor(size), text, r.x + (r.w - (x1 - x0)) / 2 - x0,
           r.y + (r.h - (y1 - y0)) / 2 - y0, fg, bg);
}

static void drawTextRight(const char *text, int right, int y, uint8_t size,
                          uint16_t fg, uint16_t bg) {
  int16_t x0 = 0, x1 = 0, y0 = 0, y1 = 0;
  textInk(fontFor(size), text, &x0, &x1, &y0, &y1);
  blitText(fontFor(size), text, right - (x1 - x0) - x0, y, fg, bg);
}

// Greedy word wrap, the same one the deck uses. `maxLines` is a hard stop: a
// title longer than its card is cut, not run over the progress bar.
static void drawWrappedText(const char *text, int x, int y, int width,
                            uint8_t size, uint16_t fg, uint16_t bg,
                            int maxLines) {
  const int lineHeight = lineHeightFor(size);
  char line[80];
  line[0] = '\0';
  size_t used = 0;
  int lines = 0;
  const char *p = text;

  while (*p != '\0' && lines < maxLines) {
    while (*p == ' ') ++p;
    if (*p == '\0') break;

    char word[40];
    size_t wi = 0;
    while (*p != '\0' && *p != ' ' && wi < sizeof(word) - 1) word[wi++] = *p++;
    word[wi] = '\0';

    char candidate[110];
    snprintf(candidate, sizeof(candidate), "%s%s%s", line, used > 0 ? " " : "",
             word);
    int16_t bx = 0, by = 0;
    uint16_t bw = 0, bh = 0;
    tft.setFont(fontFor(size));
    tft.getTextBounds(candidate, 0, 0, &bx, &by, &bw, &bh);

    if (static_cast<int>(bw) > width && used > 0) {
      drawTextAt(line, x, y + lines * lineHeight, size, fg, bg);
      ++lines;
      snprintf(line, sizeof(line), "%s", word);
      used = strlen(line);
    } else {
      snprintf(line, sizeof(line), "%s", candidate);
      used = strlen(line);
    }
  }
  if (used > 0 && lines < maxLines) {
    drawTextAt(line, x, y + lines * lineHeight, size, fg, bg);
  }
}

static void disableOnboardLed() {
  pinMode(PIN_LED_R, OUTPUT);
  pinMode(PIN_LED_G, OUTPUT);
  pinMode(PIN_LED_B, OUTPUT);
  digitalWrite(PIN_LED_R, HIGH);
  digitalWrite(PIN_LED_G, HIGH);
  digitalWrite(PIN_LED_B, HIGH);
}

// --------------------------------------------------------------- snapshot ---

static NowPlaying live;
static bool liveReady = false;
static portMUX_TYPE snapMux = portMUX_INITIALIZER_UNLOCKED;

// Defined with the Bluetooth link below. A linked phone is the source of truth
// while it is there: it is the player the buttons on this glass actually move,
// so the network task must not overwrite what it just said.
static bool mirroredFresh(uint32_t withinMs);

static void publishNowPlaying(const NowPlaying &n) {
  if (mirroredFresh(5000)) return;
  portENTER_CRITICAL(&snapMux);
  live = n;
  liveReady = true;
  portEXIT_CRITICAL(&snapMux);
}

static void readNowPlaying(NowPlaying &out, bool &ready) {
  portENTER_CRITICAL(&snapMux);
  out = live;
  ready = liveReady;
  portEXIT_CRITICAL(&snapMux);
}

// ------------------------------------------------------------- json reader ---
// A tiny reader, because a widget gets three libraries and no ArduinoJson. It
// only has to answer "what is the value of this key in this object", so it
// walks the text once per question and never builds a tree. Depth is what makes
// it correct on Spotify's payload: the track object starts with its album,
// whose own "name" and "artists" come first, so a plain search for "name" would
// label every song with its album title.

static const char *skipWs(const char *p, const char *end) {
  while (p < end && (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')) ++p;
  return p;
}

static const char *skipString(const char *p, const char *end) {
  if (p >= end || *p != '"') return p;
  ++p;
  while (p < end) {
    if (*p == '\\') {
      p += 2;
      continue;
    }
    if (*p == '"') return p + 1;
    ++p;
  }
  return end;
}

static const char *skipValue(const char *p, const char *end) {
  p = skipWs(p, end);
  if (p >= end) return end;
  if (*p == '"') return skipString(p, end);
  if (*p == '{' || *p == '[') {
    const char open = *p;
    const char close = (open == '{') ? '}' : ']';
    int depth = 0;
    while (p < end) {
      if (*p == '"') {
        p = skipString(p, end);
        continue;
      }
      if (*p == open) {
        ++depth;
      } else if (*p == close) {
        --depth;
        if (depth == 0) return p + 1;
      }
      ++p;
    }
    return end;
  }
  while (p < end && *p != ',' && *p != '}' && *p != ']') ++p;
  return p;
}

// Finds `key` among the immediate members of the object starting at objStart and
// reports the raw span of its value.
static bool findKey(const char *objStart, const char *objEnd, const char *key,
                    const char **valueStart, const char **valueEnd) {
  if (objStart == nullptr || objEnd == nullptr) return false;
  if (objStart >= objEnd || *objStart != '{') return false;

  const size_t keyLen = strlen(key);
  const char *p = skipWs(objStart + 1, objEnd);
  while (p < objEnd && *p == '"') {
    const char *keyStart = p + 1;
    const char *afterKey = skipString(p, objEnd);
    if (afterKey <= keyStart) return false;
    const char *keyStop = afterKey - 1;  // the closing quote
    p = skipWs(afterKey, objEnd);
    if (p >= objEnd || *p != ':') return false;
    const char *vStart = skipWs(p + 1, objEnd);
    const char *vEnd = skipValue(vStart, objEnd);
    if (static_cast<size_t>(keyStop - keyStart) == keyLen &&
        strncmp(keyStart, key, keyLen) == 0) {
      *valueStart = vStart;
      *valueEnd = vEnd;
      return true;
    }
    p = skipWs(vEnd, objEnd);
    if (p < objEnd && *p == ',') p = skipWs(p + 1, objEnd);
  }
  return false;
}

// ISO-8859-1 to ASCII, for the 0xC0-0xFF block. The panel font is ASCII, so a
// name with an accent loses the accent instead of turning into two glyphs. A
// zero entry means "no good ASCII stand-in" and the character is dropped.
static const char LATIN1_ASCII[65] =
    "AAAAAAACEEEEIIII"
    "DNOOOOO\0OUUUUYTs"
    "aaaaaaaceeeeiiii"
    "dnooooo\0ouuuuyty";

static int hexDigit(const char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return 0;
}

static char asciiForCodepoint(const uint32_t cp) {
  if (cp >= 0xC0 && cp <= 0xFF) return LATIN1_ASCII[cp - 0xC0];
  switch (cp) {
    case 0xA0: return ' ';
    case 0x2018:
    case 0x2019: return '\'';
    case 0x201C:
    case 0x201D: return '"';
    case 0x2013:
    case 0x2014: return '-';
    case 0x2026: return '.';
    default: return 0;
  }
}

// Copies a JSON string value into `out` as printable ASCII. Escapes are undone,
// UTF-8 is decoded, and every codepoint the font cannot draw is either
// transliterated (accents) or dropped (emoji). Surrogate pairs are dropped
// whole: half an emoji is two question marks, which is worse than a space.
static void jsonStringInto(const char *vs, const char *ve, char *out,
                           size_t outLen) {
  if (outLen == 0) return;
  out[0] = '\0';
  if (vs == nullptr || ve == nullptr || vs >= ve || *vs != '"') return;

  size_t o = 0;
  const char *p = vs + 1;
  while (p < ve && *p != '"' && o + 1 < outLen) {
    uint32_t cp = 0;
    if (*p == '\\' && p + 1 < ve) {
      ++p;
      switch (*p) {
        case 'n': cp = '\n'; break;
        case 't': cp = '\t'; break;
        case 'r': cp = '\r'; break;
        case 'b': cp = '\b'; break;
        case 'f': cp = '\f'; break;
        case 'u': {
          uint32_t v = 0;
          int digits = 0;
          ++p;
          while (digits < 4 && p < ve && isxdigit(static_cast<unsigned char>(*p))) {
            v = (v << 4) | static_cast<uint32_t>(hexDigit(*p));
            ++p;
            ++digits;
          }
          --p;  // the ++p after the switch lands past the escape
          const bool high = v >= 0xD800 && v <= 0xDBFF;
          const bool low = v >= 0xDC00 && v <= 0xDFFF;
          cp = (high || low) ? 0 : v;
          break;
        }
        default: cp = static_cast<unsigned char>(*p); break;
      }
      ++p;
    } else {
      const unsigned char b = static_cast<unsigned char>(*p++);
      if (b < 0x80) {
        cp = b;
      } else if ((b & 0xE0) == 0xC0) {
        cp = b & 0x1F;
        for (int i = 0; i < 1 && p < ve; ++i)
          cp = (cp << 6) | (static_cast<unsigned char>(*p++) & 0x3F);
      } else if ((b & 0xF0) == 0xE0) {
        cp = b & 0x0F;
        for (int i = 0; i < 2 && p < ve; ++i)
          cp = (cp << 6) | (static_cast<unsigned char>(*p++) & 0x3F);
      } else if ((b & 0xF8) == 0xF0) {
        cp = b & 0x07;
        for (int i = 0; i < 3 && p < ve; ++i)
          cp = (cp << 6) | (static_cast<unsigned char>(*p++) & 0x3F);
      } else {
        cp = 0;
      }
    }

    if (cp == 0) continue;
    char c = 0;
    if (cp < 0x80) {
      c = (cp >= 0x20 && cp != 0x7F) ? static_cast<char>(cp) : 0;
    } else {
      c = asciiForCodepoint(cp);
    }
    if (c) out[o++] = c;
  }
  out[o] = '\0';
}

static bool keyString(const char *objStart, const char *objEnd, const char *key,
                      char *out, size_t outLen) {
  const char *vs = nullptr;
  const char *ve = nullptr;
  if (!findKey(objStart, objEnd, key, &vs, &ve)) {
    if (outLen) out[0] = '\0';
    return false;
  }
  jsonStringInto(vs, ve, out, outLen);
  return true;
}

static long keyLong(const char *objStart, const char *objEnd, const char *key,
                    long fallback) {
  const char *vs = nullptr;
  const char *ve = nullptr;
  if (!findKey(objStart, objEnd, key, &vs, &ve)) return fallback;
  char text[24];
  jsonStringInto(vs, ve, text, sizeof(text));
  return strtol(text, nullptr, 10);
}

static bool keyBool(const char *objStart, const char *objEnd, const char *key,
                    bool fallback) {
  const char *vs = nullptr;
  const char *ve = nullptr;
  if (!findKey(objStart, objEnd, key, &vs, &ve)) return fallback;
  if (vs < ve && *vs == 't') return true;
  if (vs < ve && *vs == 'f') return false;
  return fallback;
}

// ---------------------------------------------------------------- network ---
// Spotify's two hostnames do not share a certificate authority. Measured from a
// desktop on 2026-10-31:
//
//   api.spotify.com       *.spotify.com <- DigiCert Global G2 TLS RSA SHA256
//                                          2020 CA1 <- DigiCert Global Root G2
//   accounts.spotify.com  <- Certainly Intermediate R1
//                         <- Starfield Root Certificate Authority - G2
//
// So each client pins the root of its own chain and validates. Pinning a root
// (not a leaf) survives Spotify rotating its server certificates, but it does
// break if Spotify changes authority: the symptom is a handshake failure and a
// deck that reports SPOTIFY UNREACHABLE, not a wrong-looking payload.
//
// Validation also needs the real date. The board boots at 1970 and has no RTC
// battery, so every certificate reads as "not yet valid" until SNTP answers;
// that is why clockGood() is on the path of every request.
//
// Do not edit the PEMs by hand. refresh_pins.py beside this sketch re-measures
// both chains, accepts a root only if openssl validates the host against it, and
// rewrites these two arrays only when the fingerprint changed.

static const char SPOTIFY_API_CA[] = R"CA(
-----BEGIN CERTIFICATE-----
MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBh
MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
MjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUxMjAwMDBaMGExCzAJBgNVBAYTAlVT
MRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5j
b20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI
2/Ou8jqJkTx65qsGGmvPrC3oXgkkRLpimn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx
1x7e/dfgy5SDN67sH0NO3Xss0r0upS/kqbitOtSZpLYl6ZtrAGCSYP9PIUkY92eQ
q2EGnI/yuum06ZIya7XzV+hdG82MHauVBJVJ8zUtluNJbd134/tJS7SsVQepj5Wz
tCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyMUNGPHgm+F6HmIcr9g+UQ
vIOlCsRnKPZzFBQ9RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQABo0IwQDAP
BgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV
5uNu5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBnKJRvDkhj6zHd6mcY
1Yl9PMWLSn/pvtsrF9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4
NeF22d+mQrvHRAiGfzZ0JFrabA0UWTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NG
Fdtom/DzMNU+MeKNhJ7jitralj41E6Vf8PlwUHBHQRFXGU7Aj64GxJUTFy8bJZ91
8rGOmaFvE7FBcf6IKshPECBV1/MUReXgRPTqh5Uykw7+U0b6LJ3/iyK5S9kJRaTe
pLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyNxF1WW6LZZm6zNTfl
MrY=
-----END CERTIFICATE-----
)CA";

static const char SPOTIFY_TOKEN_CA[] = R"CA(
-----BEGIN CERTIFICATE-----
MIID3TCCAsWgAwIBAgIBADANBgkqhkiG9w0BAQsFADCBjzELMAkGA1UEBhMCVVMx
EDAOBgNVBAgTB0FyaXpvbmExEzARBgNVBAcTClNjb3R0c2RhbGUxJTAjBgNVBAoT
HFN0YXJmaWVsZCBUZWNobm9sb2dpZXMsIEluYy4xMjAwBgNVBAMTKVN0YXJmaWVs
ZCBSb290IENlcnRpZmljYXRlIEF1dGhvcml0eSAtIEcyMB4XDTA5MDkwMTAwMDAw
MFoXDTM3MTIzMTIzNTk1OVowgY8xCzAJBgNVBAYTAlVTMRAwDgYDVQQIEwdBcml6
b25hMRMwEQYDVQQHEwpTY290dHNkYWxlMSUwIwYDVQQKExxTdGFyZmllbGQgVGVj
aG5vbG9naWVzLCBJbmMuMTIwMAYDVQQDEylTdGFyZmllbGQgUm9vdCBDZXJ0aWZp
Y2F0ZSBBdXRob3JpdHkgLSBHMjCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
ggEBAL3twQP89o/8ArFvW59I2Z154qK3A2FWGMNHttfKPTUuiUP3oWmb3ooa/RMg
nLRJdzIpVv257IzdIvpy3Cdhl+72WoTsbhm5iSzchFvVdPtrX8WJpRBSiUZV9Lh1
HOZ/5FSuS/hVclcCGfgXcVnrHigHdMWdSL5stPSksPNkN3mSwOxGXn/hbVNMYq/N
Hwtjuzqd+/x5AJhhdM8mgkBj87JyahkNmcrUDnXMN/uLicFZ8WJ/X7NfZTD4p7dN
dloedl40wOiWVpmKs/B/pM293DIxfJHP4F8R+GuqSVzRmZTRouNjWwl2tVZi4Ut0
HZbUJtQIBFnQmA4O5t78w+wfkPECAwEAAaNCMEAwDwYDVR0TAQH/BAUwAwEB/zAO
BgNVHQ8BAf8EBAMCAQYwHQYDVR0OBBYEFHwMMh+n2TB/xH1oo2Kooc6rB1snMA0G
CSqGSIb3DQEBCwUAA4IBAQARWfolTwNvlJk7mh+ChTnUdgWUXuEok21iXQnCoKjU
sHU48TRqneSfioYmUeYs0cYtbpUgSpIB7LiKZ3sx4mcujJUDJi5DnUox9g61DLu3
4jd/IroAow57UvtruzvE03lRTs2Q9GcHGcg8RnoNAX3FWOdt5oUwF5okxBDgBPfg
8n/Uqgr/Qh037ZTlZFkSIHc40zI+OIF1lnP6aI+xy84fxez6nH7PfrHxBy22/L/K
pL/QlwVKvOoYKAKQvVR4CSFx09F9HdkWsKlhPdAKACL8x3vLCWRFCztAgfd9fDL1
mMpYjn0q7pBZc2T5NnReJaH1ZgUufzkVqSr7UIuOhWn0
-----END CERTIFICATE-----
)CA";

static constexpr uint32_t POLL_MS = 3000;
static constexpr uint16_t HTTP_TIMEOUT_MS = 10000;

static WiFiClientSecure tls;       // api.spotify.com
static WiFiClientSecure tokenTls;  // accounts.spotify.com, from the poller
static QueueHandle_t cmdQueue = nullptr;

static bool clockReady = false;
static bool clockAsked = false;

// Asks SNTP once, then just reads the clock. Never blocks: the network task
// calls it every pass, and the deck draws SETTING CLOCK until it answers.
static bool clockGood() {
  if (clockReady) return true;
  if (!clockAsked) {
    clockAsked = true;
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    Serial.println("[time] asking NTP; TLS needs the date");
  }
  const time_t now = time(nullptr);
  // 2023-11-14: comfortably after any certificate's notBefore, and forever away
  // from the 1970 the board starts at when SNTP has not answered.
  if (now > 1700000000) {
    clockReady = true;
    Serial.printf("[time] %s", ctime(&now));
    return true;
  }
  return false;
}
static String gAccessToken;
static uint32_t gTokenExpiryMs = 0;
static bool netStarted = false;

static bool tokenValid() {
  if (gAccessToken.length() == 0) return false;
  // Signed difference, so the comparison survives millis() wrapping at 49 days.
  return static_cast<int32_t>(gTokenExpiryMs - millis()) > 0;
}

static bool refreshAccessToken() {
  if (WiFi.status() != WL_CONNECTED) return false;
  if (!clockGood()) {
    Serial.println("[auth] no clock yet; a certificate check would fail");
    return false;
  }

  HTTPClient http;
  http.setTimeout(HTTP_TIMEOUT_MS);
  http.setReuse(false);
  if (!http.begin(tokenTls, "accounts.spotify.com", 443, "/api/token", true)) {
    Serial.println("[auth] could not open the token endpoint");
    return false;
  }
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  // A confidential app sends client id and secret as Basic auth; a PKCE app has
  // no secret and sends the id in the body instead. Both end up here.
  if (creds.clientSecret[0] != '\0') {
    http.setAuthorization(creds.clientId, creds.clientSecret);
  }

  String body = "grant_type=refresh_token&refresh_token=";
  body += creds.refresh;  // base64url, so no percent-encoding needed
  if (creds.clientSecret[0] == '\0') {
    body += "&client_id=";
    body += creds.clientId;
  }

  const int code = http.POST(body);
  const String payload = http.getString();
  http.end();

  if (code != 200) {
    Serial.printf("[auth] token refresh failed: %d %s\n", code,
                  payload.substring(0, 120).c_str());
    return false;
  }

  char token[CRED_TOKEN];
  token[0] = '\0';
  const char *start = payload.c_str();
  const char *end = start + payload.length();
  if (!keyString(start, end, "access_token", token, sizeof(token))) {
    Serial.println("[auth] no access_token in the response");
    return false;
  }
  gAccessToken = token;

  const long expires = keyLong(start, end, "expires_in", 3600);
  // Renew a minute early: a poll that starts at the last second would spend the
  // whole request on a token that expires mid-flight.
  const uint32_t life = static_cast<uint32_t>(expires > 120 ? expires - 60 : 60);
  gTokenExpiryMs = millis() + life * 1000UL;
  Serial.printf("[auth] access token for %lus\n",
                static_cast<unsigned long>(life));
  return true;
}

// One API call, with the 401 retry every token flow needs.
static int apiCall(const char *method, const String &path, const char *body,
                   String &out) {
  if (!clockGood()) return -1;
  for (int attempt = 0; attempt < 2; ++attempt) {
    if (!tokenValid() && !refreshAccessToken()) return -1;

    HTTPClient http;
    http.setTimeout(HTTP_TIMEOUT_MS);
    http.setReuse(false);
    if (!http.begin(tls, "api.spotify.com", 443, path, true)) return -1;
    http.addHeader("Authorization", String("Bearer ") + gAccessToken);
    if (body != nullptr) http.addHeader("Content-Type", "application/json");

    int code;
    if (body != nullptr) {
      code = http.sendRequest(method, reinterpret_cast<uint8_t *>(
                                           const_cast<char *>(body)),
                              strlen(body));
    } else {
      code = http.sendRequest(method);
    }
    out = (code >= 200 && code < 300) ? http.getString() : String();
    http.end();

    if (code != 401) return code;
    Serial.println("[auth] 401, refreshing the token and retrying once");
    gAccessToken = "";
    gTokenExpiryMs = 0;
  }
  return 401;
}

static void parseNowPlaying(const String &payload, NowPlaying &n) {
  const char *start = payload.c_str();
  const char *end = start + payload.length();

  n.playing = keyBool(start, end, "is_playing", false);
  n.progressMs = static_cast<uint32_t>(keyLong(start, end, "progress_ms", 0));

  const char *itemStart = nullptr;
  const char *itemEnd = nullptr;
  const bool hasItem = findKey(start, end, "item", &itemStart, &itemEnd) &&
                       itemStart < itemEnd && *itemStart == '{';
  if (hasItem) {
    keyString(itemStart, itemEnd, "name", n.title, sizeof(n.title));
    n.durationMs = static_cast<uint32_t>(
        keyLong(itemStart, itemEnd, "duration_ms", 0));

    const char *artistsStart = nullptr;
    const char *artistsEnd = nullptr;
    if (findKey(itemStart, itemEnd, "artists", &artistsStart, &artistsEnd) &&
        artistsStart < artistsEnd && *artistsStart == '[') {
      const char *first = skipWs(artistsStart + 1, artistsEnd);
      if (first < artistsEnd && *first == '{') {
        const char *firstEnd = skipValue(first, artistsEnd);
        keyString(first, firstEnd, "name", n.artist, sizeof(n.artist));
      }
    }
  } else {
    // An advert, a podcast the account cannot stream, or nothing loaded at all.
    // "currently_playing_type" is the only field that distinguishes them, and
    // none of them can be described by a title on this screen.
    char kind[24];
    keyString(start, end, "currently_playing_type", kind, sizeof(kind));
    strlcpy(n.title, kind[0] ? "AD BREAK" : "NOTHING PLAYING",
            sizeof(n.title));
  }

  const char *deviceStart = nullptr;
  const char *deviceEnd = nullptr;
  if (findKey(start, end, "device", &deviceStart, &deviceEnd) &&
      deviceStart < deviceEnd && *deviceStart == '{') {
    n.hasDevice = true;
    n.activeDevice = keyBool(deviceStart, deviceEnd, "is_active", false);
    keyString(deviceStart, deviceEnd, "name", n.device, sizeof(n.device));
    keyString(deviceStart, deviceEnd, "id", n.deviceId, sizeof(n.deviceId));
  }
}

static void pollNowPlaying() {
  NowPlaying next;
  memset(&next, 0, sizeof(next));
  next.updatedMs = millis();

  String payload;
  if (!clockGood()) {
    // Nothing to send yet: with a 1970 clock every certificate reads as "not yet
    // valid", which would surface as an api error instead of the real cause.
    next.ready = true;
    strlcpy(next.title, "SETTING CLOCK", sizeof(next.title));
    strlcpy(next.artist, "TLS needs the date from WiFi", sizeof(next.artist));
    publishNowPlaying(next);
    return;
  }
  const int code = apiCall("GET", "/v1/me/player", nullptr, payload);

  if (code == 204) {
    // Spotify answers 204 when the account has no active device at all.
    next.ready = true;
    strlcpy(next.title, "NO ACTIVE DEVICE", sizeof(next.title));
    strlcpy(next.artist, "open Spotify somewhere", sizeof(next.artist));
  } else if (code == 200) {
    next.ready = true;
    parseNowPlaying(payload, next);
  } else {
    next.status = static_cast<uint16_t>(code > 0 ? code : 0);
    strlcpy(next.title, WiFi.status() == WL_CONNECTED ? "SPOTIFY UNREACHABLE"
                                                      : "NO WIFI",
            sizeof(next.title));
  }

  next.updatedMs = millis();
  publishNowPlaying(next);
  if (code != 200 && code != 204) {
    Serial.printf("[poll] status %d\n", code);
  }
}

static void sendCommand(const uint8_t cmd) {
  NowPlaying n;
  bool ready = false;
  readNowPlaying(n, ready);

  // Starting playback needs a target when nothing is active; without an id
  // there is nothing to transfer to and the tap is a no-op the screen explains.
  if (!n.activeDevice && n.deviceId[0] != '\0' &&
      (cmd == CMD_TOGGLE || cmd == CMD_NEXT || cmd == CMD_PREV)) {
    char body[160];
    snprintf(body, sizeof(body), "{\"device_ids\":[\"%s\"],\"play\":true}",
             n.deviceId);
    String ignored;
    const int code = apiCall("PUT", "/v1/me/player", body, ignored);
    Serial.printf("[cmd] transfer to %s: %d\n", n.device, code);
    if (code < 200 || code >= 300) return;
  }

  String ignored;
  int code = -1;
  switch (cmd) {
    case CMD_TOGGLE:
      code = apiCall("PUT",
                     n.playing ? "/v1/me/player/pause" : "/v1/me/player/play",
                     "{}", ignored);
      break;
    case CMD_NEXT:
      code = apiCall("POST", "/v1/me/player/next", nullptr, ignored);
      break;
    case CMD_PREV:
      code = apiCall("POST", "/v1/me/player/previous", nullptr, ignored);
      break;
    default: return;
  }
  Serial.printf("[cmd] %u -> %d\n", static_cast<unsigned>(cmd), code);
}

// Set by the setup wizard, which rewrites the credential block and drives WiFi
// itself. The poller has to stay out of both while that is true, or it can read
// a half-written string and send it.
static volatile bool setupActive = false;

static void netTask(void *) {
  // The WiFi stack keeps its own copy of the credentials; letting it write NVS
  // as well would duplicate the block and slow every connect.
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setHostname("spotify-deck");
  tls.setCACert(SPOTIFY_API_CA);
  tokenTls.setCACert(SPOTIFY_TOKEN_CA);
  WiFi.begin(creds.ssid, creds.pass);
  Serial.printf("[wifi] joining \"%s\"\n", creds.ssid);

  uint32_t lastReconnectMs = millis();
  for (;;) {
    if (setupActive) {
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }

    if (WiFi.status() != WL_CONNECTED && millis() - lastReconnectMs > 5000) {
      lastReconnectMs = millis();
      Serial.printf("[wifi] status %d, reconnecting\n",
                    static_cast<int>(WiFi.status()));
      WiFi.reconnect();
    }

    uint8_t cmd = 0;
    const bool haveCmd =
        cmdQueue != nullptr &&
        xQueueReceive(cmdQueue, &cmd, pdMS_TO_TICKS(POLL_MS)) == pdTRUE;

    if (WiFi.status() != WL_CONNECTED) {
      NowPlaying offline;
      memset(&offline, 0, sizeof(offline));
      offline.updatedMs = millis();
      strlcpy(offline.title, "NO WIFI", sizeof(offline.title));
      strlcpy(offline.artist, "type setup in the serial monitor",
              sizeof(offline.artist));
      publishNowPlaying(offline);
      continue;
    }

    if (haveCmd) sendCommand(cmd);
    pollNowPlaying();
  }
}

static void ensureNetTask() {
  if (netStarted || !credsComplete()) return;
  netStarted = true;
  // 12 kB because the mbedTLS handshake happens on this stack; 8 kB is where
  // these sketches usually crash halfway through a Spotify request.
  xTaskCreatePinnedToCore(netTask, "spotify", 12288, nullptr, 1, nullptr, 0);
}

// -------------------------------------------------------------- setup card ---

static constexpr uint8_t SETUP_STEPS = 5;
static const char *SETUP_SHORT[SETUP_STEPS] = {
    "WiFi network name",
    "WiFi password",
    "Spotify client id",
    "Client secret (may be blank)",
    "Spotify refresh token",
};
static const char *SETUP_SERIAL[SETUP_STEPS] = {
    "WiFi network name (SSID):",
    "WiFi password (open network: press Enter again with nothing typed):",
    "Spotify client id (Spotify dashboard -> your app -> Settings):",
    "Client secret (blank if your app is a PKCE app with no secret):",
    "Spotify refresh token (see the README; it is the long string):",
};

static uint8_t setupStep = 0;
static char setupLine[CRED_TOKEN];
static size_t setupLen = 0;
// Sending CRLF for one Enter used to answer two questions at once, and the
// WiFi password was the field that silently took the empty answer. Remembering
// the '\r' lets the '\n' after it be swallowed instead of committed.
static bool setupSawCR = false;
// A blank WiFi password is legal, so it takes a second deliberate Enter rather
// than being accepted because a keystroke went missing.
static bool setupBlankPassOK = false;

static void startWebSetup();  // defined below, called by the wizard

// The answer as typed so far: secrets are echoed as asterisks, the rest as
// themselves. Redrawn per keystroke so the panel and the monitor agree about
// what the board has received.
static void drawTypedLine() {
  char typed[40];
  const size_t shown =
      setupLen < sizeof(typed) - 1 ? setupLen : sizeof(typed) - 1;
  const bool secret = setupStep == 1 || setupStep == 3 || setupStep == 4;
  for (size_t i = 0; i < shown; ++i) typed[i] = secret ? '*' : setupLine[i];
  typed[shown] = '\0';
  tft.fillRect(26, 154, 264, 18, C_TILE);
  drawTextAt(typed, 28, 159, 1, C_TEXT, C_TILE);
}

// Whether the phone side ever arrived, and whether it ever asked for the page.
// "The address will not load" is either a phone that never joined the access
// point or a browser that never sent a request, and the two need different
// fixes; counting both separates them with no serial monitor. Painted by
// drawSetupLink(), defined with the web server below.
static uint16_t setupConnections = 0;  // TCP connections that reached port 80
static int setupStationsShown = -1;
static uint16_t setupConnectionsShown = 0xFFFF;
static void drawSetupLink();

static void drawSetup() {
  screen = SCR_SETUP;
  tft.fillScreen(C_BG);
  tft.fillRect(0, 0, SCREEN_W, 32, C_BG);
  drawTextAt("SPOTIFY DECK", 12, 10, 2, C_TEXT, C_BG);
  drawTextRight("SETUP", SCREEN_W - 12, 13, 1, C_DIM, C_BG);
  tft.drawFastHLine(0, 31, SCREEN_W, C_EDGE);

  drawTextAt("on the phone, no laptop", 12, 40, 1, C_DIM, C_BG);
  drawTextAt("1  join WiFi \"Spotify Deck\"", 12, 56, 1, C_TEXT, C_BG);
  drawTextAt("2  open http://192.168.4.1", 12, 70, 1, C_TEXT, C_BG);
  drawTextAt("3  fill in the page", 12, 84, 1, C_TEXT, C_BG);

  // The card starts on the page margin like every other card: 12..308, text 10 in.
  tft.fillRoundRect(12, 102, 296, 78, CORNER_CARD, C_TILE);
  tft.drawRoundRect(12, 102, 296, 78, CORNER_CARD, C_EDGE);

  char step[28];
  snprintf(step, sizeof(step), "OR SERIAL  %u/%u",
           static_cast<unsigned>(setupStep + 1),
           static_cast<unsigned>(SETUP_STEPS));
  drawTextAt(step, 22, 110, 1, C_DIM, C_TILE);
  drawWrappedText(SETUP_SHORT[setupStep], 22, 126, 272, 1, C_TEXT, C_TILE, 2);

  // What has been typed so far, in the field it goes into.
  tft.drawRoundRect(22, 152, 272, 22, 6, C_EDGE);
  drawTypedLine();

  drawTextAt("192.168.4.1", 12, 190, 1, C_DIM, C_BG);
  if (WiFi.status() == WL_CONNECTED) {
    const String ip = WiFi.localIP().toString();
    drawTextRight(ip.c_str(), SCREEN_W - 12, 190, 1, C_DIM, C_BG);
  }
  setupStationsShown = -1;  // fillScreen() wiped the strip: force a repaint
  drawSetupLink();
}

static void printSetupPrompt() {
  Serial.printf("\n[setup] %u/%u %s\n", static_cast<unsigned>(setupStep + 1),
                static_cast<unsigned>(SETUP_STEPS), SETUP_SERIAL[setupStep]);
  Serial.print("> ");
}

static void startSetupWizard() {
  setupActive = true;
  setupStep = 0;
  setupLen = 0;
  setupLine[0] = '\0';
  setupBlankPassOK = false;
  setupSawCR = false;
  startWebSetup();
  drawSetup();
  Serial.println("\n[setup] optional: WiFi and a Spotify account, so the deck can fetch music itself.");
  Serial.println("[setup] Phone: join \"Spotify Deck\" and open http://192.168.4.1");
  Serial.println("[setup] or type each answer here and press Enter, one at a time.");
  printSetupPrompt();
}

// Copies the current answer into its home and moves on. Required fields are
// checked here rather than after four more questions, so a blank WiFi name is
// retyped immediately.
static bool commitSetupLine() {
  setupLine[setupLen] = '\0';
  switch (setupStep) {
    case 0:
      if (setupLen == 0) return false;
      strlcpy(creds.ssid, setupLine, sizeof(creds.ssid));
      break;
    case 1:
      if (setupLen == 0 && !setupBlankPassOK) return false;
      strlcpy(creds.pass, setupLine, sizeof(creds.pass));
      break;
    case 2:
      if (setupLen == 0) return false;
      strlcpy(creds.clientId, setupLine, sizeof(creds.clientId));
      break;
    case 3:
      strlcpy(creds.clientSecret, setupLine, sizeof(creds.clientSecret));
      break;
    case 4:
      if (setupLen == 0) return false;
      strlcpy(creds.refresh, setupLine, sizeof(creds.refresh));
      break;
    default:
      return false;
  }
  return true;
}

static void pumpSetupWizard() {
  while (Serial.available()) {
    const int raw = Serial.read();
    if (raw < 0) break;
    const char c = static_cast<char>(raw);

    // One Enter arrives as "\r\n" from most monitors. Committing both would
    // answer this question and the next one with a single keystroke, which is
    // exactly how the WiFi password used to be skipped: the SSID committed on
    // the '\r', and the password question was answered by the '\n' behind it.
    if (c == '\n' && setupSawCR) {
      setupSawCR = false;
      continue;
    }
    setupSawCR = (c == '\r');

    if (c == '\r' || c == '\n') {
      Serial.println();
      if (!commitSetupLine()) {
        if (setupStep == 1) {
          // The only field that may legitimately be empty, so it asks twice
          // instead of trusting that the answer was not lost to a keystroke.
          Serial.println("[setup] nothing typed. If the network is open, press Enter");
          Serial.println("[setup] again; otherwise type the password.");
          setupBlankPassOK = true;
          printSetupPrompt();
          continue;
        }
        Serial.println("[setup] that one cannot be blank, try again");
        printSetupPrompt();
        continue;
      }
      ++setupStep;
      setupLen = 0;
      setupLine[0] = '\0';
      setupBlankPassOK = false;
      if (setupStep >= SETUP_STEPS) {
        saveCredentials();
        Serial.println("[setup] saved to NVS, rebooting into the deck");
        delay(400);
        ESP.restart();
        return;
      }
      drawSetup();
      printSetupPrompt();
    } else if (c == 8 || c == 127) {  // backspace, as both monitors send it
      setupBlankPassOK = false;
      if (setupLen > 0) {
        --setupLen;
        Serial.print("\b \b");
        drawTypedLine();
      }
    } else if (c >= 32 && c < 127 && setupLen + 1 < sizeof(setupLine)) {
      setupBlankPassOK = false;
      setupLine[setupLen++] = c;
      Serial.print(setupStep == 1 || setupStep == 3 || setupStep == 4 ? '*' : c);
      drawTypedLine();
    }
  }
}

// -------------------------------------------------------------- web setup ---
// Spotify accepts a plain-HTTP redirect only when it points at a loopback
// literal, so the callback can never land on the board: the phone's browser
// lands on 127.0.0.1:8080 and that page fails to load. What the board can do is
// host the form, keep the PKCE verifier and exchange the code itself, which is
// what this section does. The phone needs no app, no laptop, and never handles
// a token.
//
//   GET  /         the form (network, password, client id) - or the sign-in page
//   GET  /net      the form, even when a client id is already stored
//   POST /save     store the network and the client id, join the network
//   GET  /spotify  join state, the Spotify link and the paste box
//   GET  /auth     302 to Spotify with a fresh S256 challenge (app=1: app scheme);
//                  &json=1 answers that URL as text, so the app can open it
//   POST /code     exchange the pasted code, store the token, reboot
//   GET  /code     the same exchange for the app, which hands its code back
//                  through the phone's browser instead of opening a socket;
//                  &json=1 asks for a one-line answer instead of a page
//   GET  /deck.json  what this board is - served in setup *and* in the player
//
// The access point is open on purpose: the page is where the credentials are
// typed, it exists only while the deck is in setup, and it never renders a
// stored password or token back.

static constexpr char WEB_AP_SSID[] = "Spotify Deck";
// Two redirect URIs, because they answer different questions. A loopback literal
// is the only plain-HTTP address Spotify still accepts, and it is what the paste
// path uses: the browser lands on the phone itself and its bar holds the address
// to copy. A custom scheme is the one redirect a browser can hand to an app,
// which is what turns the app path into one tap; Spotify's February 2025 note
// keeps custom schemes working, and the dashboard lists this one beside the
// loopback one.
static constexpr char OAUTH_REDIRECT_PASTE[] = "http://127.0.0.1:8080/callback";
static constexpr char OAUTH_REDIRECT_APP[] = "phonedesk://spotify-callback";
static constexpr char OAUTH_SCOPES[] =
    "user-read-playback-state user-modify-playback-state "
    "user-read-currently-playing";

static WiFiServer setupServer(80);
static WiFiServer idServer(80);  // player mode: /deck.json and nothing else
static bool idServerUp = false;
static WiFiClientSecure webTls;  // not the poller's client: another task owns that
static bool webSetupActive = false;
static String pkceVerifier;
static uint32_t setupRebootMs = 0;

// A label is one JSON string inside one read: printable ASCII, no quote and no
// backslash, trimmed at both ends and capped at 16 bytes.
static void sanitizeLabel(char *out, size_t cap, const char *in) {
  size_t n = 0;
  if (in == nullptr || cap == 0) return;
  for (const char *p = in; *p != 0 && n + 1 < cap; ++p) {
    const unsigned char c = static_cast<unsigned char>(*p);
    if (c < 32 || c > 126 || c == 34 || c == 92) continue;
    out[n] = static_cast<char>(c);
    ++n;
  }
  out[n] = 0;
  while (n > 0 && out[n - 1] == ' ') {
    --n;
    out[n] = 0;
  }
  size_t lead = 0;
  while (out[lead] == ' ') ++lead;
  if (lead > 0) memmove(out, out + lead, n - lead + 1);
}

// ---------------------------------------------------------------- identity ---
// A deck is a board, not an address. DHCP moves, so the app keys its records by
// `<widget>-<TAG>`, the same low 24 bits of the efuse MAC that the Bluetooth deck
// puts in the name it advertises. Two Spotify decks are two ids, and re-flashing
// a board keeps its id while only the address changes.
static constexpr char DECK_WIDGET[] = "spotify_deck_cyd24R";
static constexpr char DECK_NAME[] = "Spotify Deck";
static constexpr char DECK_BOARD[] = "2432S024R";
static constexpr char DECK_ROLE[] = "spotify";
// The build's name, quoted by both documents so the app can say which firmware a
// deck is running. Bump it whenever the sketch changes in a way a reader could
// notice.
static constexpr char DECK_VERSION[] = "1.0.0";
static char deckTag[7] = "000000";

static void initDeckIdentity() {
  snprintf(deckTag, sizeof(deckTag), "%06X",
           static_cast<unsigned>(ESP.getEfuseMac() & 0xFFFFFF));
}

static String deckId() { return String(DECK_WIDGET) + "-" + deckTag; }

static String htmlEscape(const char *text) {
  String out;
  if (text == nullptr) return out;
  for (const char *p = text; *p != '\0'; ++p) {
    switch (*p) {
      case '&': out += "&amp;"; break;
      case '<': out += "&lt;"; break;
      case '>': out += "&gt;"; break;
      case '"': out += "&quot;"; break;
      case '\'': out += "&#39;"; break;
      default: out += *p; break;
    }
  }
  return out;
}

static String urlEncode(const String &in) {
  // Not "HEX": Print.h defines that as a macro for the number 16.
  static const char HEX_DIGITS[] = "0123456789ABCDEF";
  String out;
  out.reserve(in.length() * 3);
  for (size_t i = 0; i < in.length(); ++i) {
    const unsigned char c = static_cast<unsigned char>(in[i]);
    if (isalnum(c) || c == '-' || c == '.' || c == '_' || c == '~') {
      out += static_cast<char>(c);
    } else {
      out += '%';
      out += HEX_DIGITS[c >> 4];
      out += HEX_DIGITS[c & 0x0F];
    }
  }
  return out;
}

static String urlDecodeString(const String &in) {
  String out;
  out.reserve(in.length());
  for (size_t i = 0; i < in.length(); ++i) {
    const char c = in[i];
    if (c == '+') {
      out += ' ';
    } else if (c == '%' && i + 2 < in.length() &&
               isxdigit(static_cast<unsigned char>(in[i + 1])) &&
               isxdigit(static_cast<unsigned char>(in[i + 2]))) {
      const char hex[3] = {in[i + 1], in[i + 2], 0};
      out += static_cast<char>(strtol(hex, nullptr, 16));
      i += 2;
    } else {
      out += c;
    }
  }
  return out;
}

// One field out of an application/x-www-form-urlencoded body. Values are
// decoded here, so callers get the text the person typed.
static bool formField(const String &body, const char *key, char *out,
                      size_t outLen) {
  if (outLen > 0) out[0] = '\0';
  int from = 0;
  while (from <= static_cast<int>(body.length())) {
    int amp = body.indexOf('&', from);
    if (amp < 0) amp = body.length();
    const String pair = body.substring(from, amp);
    const int equals = pair.indexOf('=');
    const String name = (equals < 0) ? pair : pair.substring(0, equals);
    if (name == key) {
      const String value =
          (equals < 0) ? String("") : urlDecodeString(pair.substring(equals + 1));
      strlcpy(out, value.c_str(), outLen);
      return true;
    }
    from = amp + 1;
  }
  return false;
}

// RFC 7636 code verifier: 64 characters from the unreserved set, which is long
// enough that guessing it is not the weak link.
static String makeVerifier() {
  static const char ALPHABET[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
  String out;
  out.reserve(64);
  uint32_t bits = esp_random();
  int left = 32;
  for (int i = 0; i < 64; ++i) {
    if (left < 6) {
      bits = esp_random();
      left = 32;
    }
    out += ALPHABET[bits & 63];
    bits >>= 6;
    left -= 6;
  }
  return out;
}

static String base64Url(const uint8_t *data, size_t len) {
  unsigned char encoded[64];
  size_t encodedLen = 0;
  if (mbedtls_base64_encode(encoded, sizeof(encoded), &encodedLen, data, len) !=
      0) {
    return String();
  }
  String out;
  out.reserve(encodedLen);
  for (size_t i = 0; i < encodedLen; ++i) {
    const char c = static_cast<char>(encoded[i]);
    if (c == '=') break;  // base64url drops the padding
    out += (c == '+') ? '-' : (c == '/') ? '_' : c;
  }
  return out;
}

// mbedtls_md() rather than mbedtls_sha256(): the one-shot SHA-256 changed its
// return type between mbedTLS 2 and 3, and this call has the same shape in both.
static String pkceChallenge(const String &verifier) {
  const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (info == nullptr) return String();

  uint8_t digest[32];
  if (mbedtls_md(info, reinterpret_cast<const unsigned char *>(verifier.c_str()),
                 verifier.length(), digest) != 0) {
    return String();
  }
  return base64Url(digest, sizeof(digest));
}

static void sendPageHeader(WiFiClient &client, const char *title,
                           bool refreshing) {
  client.print(F("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                 "Cache-Control: no-store\r\nConnection: close\r\n\r\n"));
  client.print(F("<!doctype html><html><head><meta charset=\"utf-8\">"
                 "<meta name=\"viewport\" content=\"width=device-width,"
                 "initial-scale=1\">"));
  if (refreshing) client.print(F("<meta http-equiv=\"refresh\" content=\"3\">"));
  client.print(F("<title>"));
  client.print(title);
  client.print(F("</title><style>"
                 "body{font:16px/1.5 ui-monospace,Menlo,Consolas,monospace;margin:0;"
                 "padding:18px;background:#fff;color:#111}"
                 "h1{font-size:19px;letter-spacing:.1em;margin:0 0 2px}"
                 "p{margin:12px 0}.small{font-size:13px;color:#555}"
                 "label{display:block;margin:16px 0 5px;font-size:12px;color:#555;"
                 "letter-spacing:.04em}"
                 "input[type=text],input[type=password]{width:100%;"
                 "box-sizing:border-box;padding:12px;font:inherit;border:1px solid "
                 "#bbb;border-radius:8px;background:#fff;color:#111}"
                 "input[type=submit],a.go{display:block;width:100%;"
                 "box-sizing:border-box;padding:14px;margin-top:20px;font:inherit;"
                 "font-weight:700;text-align:center;text-decoration:none;border:0;"
                 "border-radius:8px;background:#111;color:#fff}"
                 ".row{display:flex;gap:10px;align-items:center;margin-top:14px}"
                 ".row label{margin:0;font-size:14px;color:#111}"
                 "b{color:#111}.ok{color:#0a6}.bad{color:#b00}"
                 "input.danger{background:#fff;color:#b00;"
                 "border:1px solid #b00;font-weight:400}"
                 "</style></head><body>"));
}

// Every page carries the deck's address on the *home* network, polled live, so
// the step that needs the phone back on its own WiFi does not need an address
// typed from the panel: open this link in a new tab before switching, reload it
// after, and the sign-in page is there again.
static void sendPageFooter(WiFiClient &client) {
  client.print(F("<p class=small id=home>your WiFi address: waiting for the "
                 "deck to join&hellip;</p>"
                 "<p class=small>Open that link in a new tab first: it fails "
                 "until the phone is back on your own WiFi, then reload it.</p>"
                 "<script>(function(){"
                 "var el=document.getElementById('home');"
                 "function tick(){var x=new XMLHttpRequest();"
                 "x.open('GET','/where',true);"
                 "x.onload=function(){var w;"
                 "try{w=JSON.parse(x.responseText);}catch(e){return;}"
                 "if(w&&w.home){el.innerHTML='your WiFi address: "
                 "<a href=\"http://'+w.home+'/spotify\" target=_blank "
                 "rel=noopener>http://'+w.home+'/spotify</a>';}};"
                 "x.send();}"
                 "tick();setInterval(tick,3000);})();</script>"));
  client.print(F("<p class=small>Spotify Deck &middot; "
                 "<a href=/net>change the network</a></p></body></html>"));
}

static void sendNetworkPage(WiFiClient &client) {
  sendPageHeader(client, "Spotify Deck setup", false);
  client.print(F("<h1>SPOTIFY DECK</h1>"
                 "<p class=small>Step 1 of 2 &middot; the deck joins your WiFi, "
                 "then Spotify's own page signs it in.</p>"));
  client.print(F("<form method=post action=/save>"));
  client.print(F("<label>WiFi network name</label>"
                 "<input type=text name=ssid autocomplete=off autocapitalize=off "
                 "autocorrect=off spellcheck=false value=\""));
  client.print(htmlEscape(creds.ssid));
  client.print(F("\" required>"));
  client.print(F("<label>WiFi password</label>"
                 "<input type=password name=pass autocomplete=off>"));
  client.print(F("<div class=row><input type=checkbox name=open value=1 "
                 "id=open><label for=open>this network has no password</label>"
                 "</div>"));
  client.print(F("<label>Spotify client id</label>"
                 "<input type=text name=cid autocomplete=off autocapitalize=off "
                 "autocorrect=off spellcheck=false value=\""));
  client.print(htmlEscape(creds.clientId));
  client.print(F("\" required>"));
  client.print(F("<input type=submit value=\"Save and join WiFi\"></form>"));
  client.print(F("<p class=small>2.4 GHz only &mdash; the ESP32 has no 5 GHz "
                 "radio. The client id is on developer.spotify.com/dashboard: "
                 "create an app, then add both "
                 "<b>http://127.0.0.1:8080/callback</b> and "
                 "<b>phonedesk://spotify-callback</b> as redirect URIs &mdash; "
                 "the second is the one the app catches. No client secret is "
                 "needed.</p>"));
  client.print(F("<form method=post action=/wipe>"
                 "<input type=submit class=danger "
                 "value=\"Forget the stored WiFi and token\"></form>"));
  sendPageFooter(client);
  Serial.println("[setup] served the network form");
}

static void sendSignInPage(WiFiClient &client) {
  const bool online = WiFi.status() == WL_CONNECTED;
  sendPageHeader(client, "Spotify sign in", !online);
  client.print(F("<h1>SPOTIFY DECK</h1>"));
  if (!online) {
    client.print(F("<p class=bad>not on WiFi yet</p><p class=small>still joining "
                   "<b>"));
    client.print(htmlEscape(creds.ssid));
    client.print(F("</b>. This page refreshes itself; if it stays here the "
                   "password is probably wrong.</p>"));
  } else {
    client.print(F("<p>wifi <b>connected</b> "));
    client.print(WiFi.localIP().toString());
    client.print(F("</p>"));
    client.print(F("<a class=go href='/auth?app=1'>Sign in with the app</a>"));
    client.print(F("<p class=small>Approve the request on Spotify's page and the "
                   "app finishes setup for you: it catches the reply and hands "
                   "the code to the deck, so there is nothing to copy. Its "
                   "redirect URI <b>phonedesk://spotify-callback</b> has to be "
                   "listed for the app in the Spotify dashboard, beside "
                   "<b>http://127.0.0.1:8080/callback</b>.</p>"));
    client.print(F("<a class=go style=\"background:#fff;color:#111;"
                   "border:1px solid #111\" href=/auth target=_blank "
                   "rel=noopener>or sign in in the browser</a>"));
    client.print(F("<p class=small>Without the app the page that opens is "
                   "<b>127.0.0.1:8080</b> and fails to load &mdash; that is "
                   "expected. Copy its address out of the browser bar and paste "
                   "it below.</p>"));
    client.print(F("<form method=post action=/code>"
                   "<label>the address it landed on</label>"
                   "<input type=text name=code autocomplete=off autocapitalize=off "
                   "autocorrect=off spellcheck=false "
                   "placeholder=\"http://127.0.0.1:8080/callback?code=...\">"
                   "<input type=submit value=\"Finish setup\"></form>"));
    client.print(F("<p class=small>If the Spotify page will not load, the phone "
                   "is sending it through the deck. Put the phone back on your "
                   "own WiFi and open <b>http://"));
    client.print(WiFi.localIP().toString());
    client.print(F("/spotify</b>.</p>"));
  }
  sendPageFooter(client);
  Serial.println("[setup] served the sign-in page");
}

static void sendDonePage(WiFiClient &client) {
  sendPageHeader(client, "Spotify Deck", false);
  client.print(F("<h1>SPOTIFY DECK</h1><p class=ok><b>Signed in.</b></p>"
                 "<p>The token is stored and the deck is restarting. You can put "
                 "the phone back on your own WiFi now.</p>"));
  sendPageFooter(client);
}

static void sendMessagePage(WiFiClient &client, const char *heading,
                            const String &text, bool bad) {
  sendPageHeader(client, "Spotify Deck", false);
  client.print(F("<h1>SPOTIFY DECK</h1><p class="));
  client.print(bad ? F("bad") : F("ok"));
  client.print(F("><b>"));
  client.print(htmlEscape(heading));
  client.print(F("</b></p><p class=small>"));
  client.print(htmlEscape(text.c_str()));
  client.print(F("</p><p><a href=/spotify>back to sign-in</a> &middot; "
                 "<a href=/net>network</a></p>"));
  sendPageFooter(client);
}

// The one request the board makes on the user's behalf. Its own socket, because
// the poller owns the other one, and it pins the token host's root the same way
// refreshAccessToken() does.
static bool exchangeCode(const String &code, const char *redirectUri,
                         String &detail) {
  if (!clockGood()) {
    detail =
        "the deck's clock is not set yet, and a certificate check needs the date. "
        "Give it a few seconds and press Finish setup again.";
    return false;
  }

  HTTPClient http;
  http.setTimeout(HTTP_TIMEOUT_MS);
  http.setReuse(false);
  if (!http.begin(webTls, "accounts.spotify.com", 443, "/api/token", true)) {
    detail = "the deck could not open the token endpoint";
    return false;
  }
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  if (creds.clientSecret[0] != '\0') {
    http.setAuthorization(creds.clientId, creds.clientSecret);
  }

  String body = "grant_type=authorization_code&code=";
  body += urlEncode(code);
  body += "&redirect_uri=";
  body += urlEncode(redirectUri);
  body += "&client_id=";
  body += creds.clientId;
  if (pkceVerifier.length() > 0) {
    body += "&code_verifier=";
    body += pkceVerifier;
  }

  const int status = http.POST(body);
  const String payload = http.getString();
  http.end();
  Serial.printf("[setup] token exchange: %d %s\n", status,
                payload.substring(0, 120).c_str());

  char token[CRED_TOKEN];
  token[0] = '\0';
  const char *start = payload.c_str();
  const char *end = start + payload.length();
  if (status != 200 ||
      !keyString(start, end, "refresh_token", token, sizeof(token))) {
    char reason[160];
    reason[0] = '\0';
    keyString(start, end, "error_description", reason, sizeof(reason));
    if (reason[0] == '\0') keyString(start, end, "error", reason, sizeof(reason));
    detail = String("HTTP ") + status + ": " +
             (reason[0] != '\0' ? reason : "no explanation in the reply");
    return false;
  }
  strlcpy(creds.refresh, token, sizeof(creds.refresh));
  saveCredentials();
  Serial.println("[setup] refresh token stored");
  return true;
}

static void handleWebSave(WiFiClient &client, const String &body) {
  char ssid[CRED_SSID];
  char pass[CRED_PASS];
  char cid[CRED_ID];
  char open[8];
  formField(body, "ssid", ssid, sizeof(ssid));
  formField(body, "pass", pass, sizeof(pass));
  formField(body, "cid", cid, sizeof(cid));
  const bool openNetwork =
      formField(body, "open", open, sizeof(open)) && open[0] == '1';

  if (ssid[0] == '\0') {
    sendMessagePage(client, "No network name",
                    "Type the WiFi network's name exactly as the phone shows it.",
                    true);
    return;
  }
  if (pass[0] == '\0' && !openNetwork) {
    sendMessagePage(client, "No password",
                    "Type the WiFi password, or tick \"this network has no "
                    "password\" if it really has none.",
                    true);
    return;
  }
  if (cid[0] == '\0') {
    sendMessagePage(client, "No client id",
                    "Create an app at developer.spotify.com/dashboard and copy "
                    "its Client ID.",
                    true);
    return;
  }

  strlcpy(creds.ssid, ssid, sizeof(creds.ssid));
  creds.pass[0] = '\0';
  if (!openNetwork) strlcpy(creds.pass, pass, sizeof(creds.pass));
  strlcpy(creds.clientId, cid, sizeof(creds.clientId));
  saveCredentials();
  // A verifier belongs to the client id that sent its challenge, so re-entering
  // the network or the id drops it.
  pkceVerifier = "";
  clearVerifier();
  Serial.printf("[setup] \"%s\" and the client id saved, joining\n", creds.ssid);

  WiFi.begin(creds.ssid, creds.pass);
  drawSetup();

  // 303, so a refresh of the sign-in page does not post the form again.
  client.print(F("HTTP/1.1 303 See Other\r\nLocation: /spotify\r\n"
                 "Content-Length: 0\r\nConnection: close\r\n\r\n"));
}

// The app asks for a one-line answer (`json=1`) instead of a page, so it can say
// what happened and close: `signed-in` means the token is stored, `refused: ...`
// is the reason it is not. A browser gets the page it always got, which is also
// what the app falls back to when no answer arrives at all.
static void codeReply(WiFiClient &client, bool plainReply, const char *heading,
                      const String &text, bool bad) {
  if (!plainReply) {
    if (!bad) {
      sendDonePage(client);
      return;
    }
    sendMessagePage(client, heading, text, true);
    return;
  }
  const String body =
      bad ? String(F("refused: ")) + text + "\n" : String(F("signed-in\n"));
  client.print(F("HTTP/1.1 "));
  client.print(bad ? F("409 Conflict") : F("200 OK"));
  client.print(F("\r\nContent-Type: text/plain; charset=utf-8\r\n"
                 "Cache-Control: no-store\r\nContent-Length: "));
  client.print(body.length());
  client.print(F("\r\nConnection: close\r\n\r\n"));
  client.print(body);
}

// `fields` is a form body or a query string; the two have the same shape, and
// the app path arrives as a query string: the app makes that one request itself
// now, and the browser is only the fallback when the deck does not answer.
static void handleWebCode(WiFiClient &client, const String &fields,
                          bool appRedirect, bool plainReply) {
  char pasted[1024];
  if (!formField(fields, "code", pasted, sizeof(pasted)) || pasted[0] == '\0') {
    codeReply(client, plainReply, "Nothing pasted",
              "Paste the whole address the browser landed on, or just its "
              "code= value.",
              true);
    return;
  }
  if (WiFi.status() != WL_CONNECTED) {
    codeReply(client, plainReply, "Not on WiFi yet",
              "The deck has to be on your network before it can exchange the "
              "code. Wait for the sign-in page to say connected.",
              true);
    return;
  }

  String text(pasted);
  text.trim();
  // A pasted address carries the code after "code=", percent-encoded; a bare
  // code is used as typed, because decoding a '+' inside it would corrupt it.
  String code = text;
  if (text.indexOf("code=") >= 0) {
    code = text.substring(text.indexOf("code=") + 5);
    const int amp = code.indexOf('&');
    if (amp >= 0) code.remove(amp);
    code = urlDecodeString(code);
  }
  code.trim();
  if (code.length() < 8) {
    codeReply(client, plainReply, "That is not a code",
              "It should look like AQ... and be hundreds of characters long.",
              true);
    return;
  }

  String detail;
  const char *redirectUri =
      appRedirect ? OAUTH_REDIRECT_APP : OAUTH_REDIRECT_PASTE;
  if (!exchangeCode(code, redirectUri, detail)) {
    codeReply(client, plainReply, "Spotify said no", detail, true);
    return;
  }
  // One code, one verifier: a second paste must start a fresh challenge rather
  // than reuse the pair that already worked.
  pkceVerifier = "";
  clearVerifier();
  codeReply(client, plainReply, "", "", false);
  setupRebootMs = millis() + 1500;
}

// Forget everything, from the page: the network, the client id and the token.
// The board then restarts into setup with the access point back up.
static void handleWebWipe(WiFiClient &client) {
  wipeCredentials();  // clears the namespace, so the stored verifier goes too
  pkceVerifier = "";
  sendPageHeader(client, "Spotify Deck", false);
  client.print(F("<h1>SPOTIFY DECK</h1><p class=ok><b>Forgotten.</b></p>"
                 "<p>The network, the client id and the token are gone. The deck "
                 "is restarting into setup, and this page comes back at "
                 "<b>http://192.168.4.1</b> in a few seconds.</p>"));
  sendPageFooter(client);
  setupRebootMs = millis() + 1500;
}

// Builds the authorize URL and stores the verifier it belongs to. An empty
// result comes with `heading` and `why` set, because both callers have to say
// what was wrong rather than hand back nothing.
static String buildAuthUrl(bool appRedirect, const char *&heading,
                           const char *&why) {
  heading = "No client id";
  why = "Save the network and the client id on the first page first.";
  if (creds.clientId[0] == '\0') return "";

  pkceVerifier = makeVerifier();
  // Stored before the redirect: everything after this point happens on the
  // phone, out of the deck's control.
  saveVerifier(pkceVerifier);
  const String challenge = pkceChallenge(pkceVerifier);
  heading = "No challenge";
  why = "The SHA-256 behind the PKCE challenge could not be built.";
  if (challenge.length() == 0) return "";

  const char *redirectUri =
      appRedirect ? OAUTH_REDIRECT_APP : OAUTH_REDIRECT_PASTE;
  String url = "https://accounts.spotify.com/authorize?response_type=code&client_id=";
  url += urlEncode(creds.clientId);
  url += "&redirect_uri=";
  url += urlEncode(redirectUri);
  // A custom scheme carries no address, so the app has to be told where the deck
  // is, and state is the one field Spotify echoes back untouched. Nothing secret
  // rides here: the PKCE verifier stays on the board, so a forged state can only
  // aim a code at the wrong LAN address, where it fails.
  url += "&state=";
  url += urlEncode(WiFi.localIP().toString());
  url += "&scope=";
  url += urlEncode(OAUTH_SCOPES);
  url += "&code_challenge_method=S256&code_challenge=";
  url += challenge;
  return url;
}

static void handleWebAuth(WiFiClient &client, bool appRedirect) {
  const char *heading = "";
  const char *why = "";
  const String url = buildAuthUrl(appRedirect, heading, why);
  if (url.length() == 0) {
    sendMessagePage(client, heading, why, true);
    return;
  }

  Serial.println("[setup] handing the phone to Spotify");
  client.print(F("HTTP/1.1 302 Found\r\nLocation: "));
  client.print(url);
  client.print(F("\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
}

// The same URL as text, for the app. The PKCE verifier stays on the board, so the
// app cannot mint this itself — but it can open what the deck hands it, which is
// the whole of "sign in from the app". A browser asking without json=1 still gets
// the 302 above.
static void sendAuthUrl(WiFiClient &client, bool appRedirect) {
  const char *heading = "";
  const char *why = "";
  const String url = buildAuthUrl(appRedirect, heading, why);
  if (url.length() == 0) {
    client.print(F("HTTP/1.1 409 Conflict\r\nContent-Type: text/plain\r\n"
                   "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                   "refused: "));
    client.print(why);
    client.print('\n');
    return;
  }
  client.print(F("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                 "Cache-Control: no-store\r\nConnection: close\r\n\r\n"));
  client.print(url);
  client.print('\n');
}

// What the app asks for to know which deck it is talking to. No token, no
// password, no client id: an id, the widget that is flashed, the address and the
// two states that decide what the app can offer. Served in setup and in the
// player, because a deck that is already playing is exactly the one the app
// needs to keep apart from the deck on the bench.
static void sendDeckJson(WiFiClient &client) {
  client.print(F("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                 "Cache-Control: no-store\r\nConnection: close\r\n\r\n"));
  client.print(F("{\"id\":\""));
  client.print(deckId());
  client.print(F("\",\"widget\":\""));
  client.print(DECK_WIDGET);
  client.print(F("\",\"tag\":\""));
  client.print(deckTag);
  client.print(F("\",\"name\":\""));
  client.print(DECK_NAME);
  client.print(F("\",\"role\":\""));
  client.print(DECK_ROLE);
  client.print(F("\",\"board\":\""));
  client.print(DECK_BOARD);
  client.print(F("\",\"v\":\""));
  client.print(DECK_VERSION);
  client.print(F("\",\"ip\":\""));
  if (WiFi.status() == WL_CONNECTED) client.print(WiFi.localIP().toString());
  client.print(F("\",\"setup\":"));
  client.print(setupActive ? F("true") : F("false"));
  client.print(F(",\"signed\":"));
  client.print(creds.refresh[0] != '\0' ? F("true") : F("false"));
  client.print(F("}\n"));
}

// What the page's live line asks for. no-store, because the whole point is the
// address that DHCP handed out a moment ago.
static void sendWhere(WiFiClient &client) {
  client.print(F("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                 "Cache-Control: no-store\r\nConnection: close\r\n\r\n"));
  client.print(F("{\"ap\":\""));
  client.print(WiFi.softAPIP().toString());
  client.print(F("\",\"home\":\""));
  if (WiFi.status() == WL_CONNECTED) client.print(WiFi.localIP().toString());
  client.print(F("\",\"name\":\"spotify-deck.local\"}"));
}

// One request per call, read with a deadline instead of blocking forever: a
// phone that walks out of range mid-request must not freeze the panel.
static void handleWebRequest(WiFiClient &client) {
  const uint32_t startMs = millis();

  String requestLine;
  while (requestLine.length() == 0 &&
         static_cast<int32_t>(millis() - startMs) < 2000) {
    if (!client.available()) {
      delay(5);
      continue;
    }
    requestLine = client.readStringUntil('\n');
  }
  requestLine.trim();

  const int firstSpace = requestLine.indexOf(' ');
  const int secondSpace =
      firstSpace < 0 ? -1 : requestLine.indexOf(' ', firstSpace + 1);
  if (firstSpace < 0 || secondSpace < 0) {
    Serial.println("[setup] unreadable request");
    return;
  }
  const String method = requestLine.substring(0, firstSpace);
  String path = requestLine.substring(firstSpace + 1, secondSpace);
  String query;
  const int queryAt = path.indexOf('?');
  if (queryAt >= 0) {
    query = path.substring(queryAt + 1);
    path.remove(queryAt);
  }

  // /where is a page polling itself every few seconds; counting it would turn
  // the card's diagnostic line into a counter of that poll.
  if (path != "/where" && setupConnections < 999) setupConnections++;

  size_t contentLength = 0;
  while (static_cast<int32_t>(millis() - startMs) < 2000) {
    const String line = client.readStringUntil('\n');
    if (line.length() == 0) break;
    String header = line;
    header.trim();
    if (header.length() == 0) break;
    if (header.startsWith("Content-Length:")) {
      contentLength = static_cast<size_t>(header.substring(15).toInt());
    }
  }

  String body;
  if (contentLength > 0 && contentLength < 4096) {
    body.reserve(contentLength);
    while (body.length() < contentLength &&
           static_cast<int32_t>(millis() - startMs) < 2000) {
      if (client.available()) {
        body += static_cast<char>(client.read());
      } else {
        delay(2);
      }
    }
  }

  Serial.printf("[setup] %s %s\n", method.c_str(), path.c_str());

  char flag[8];
  flag[0] = '\0';
  const bool appRedirect =
      formField(query, "app", flag, sizeof(flag)) && flag[0] == '1';
  const bool appCode = formField(query, "redir", flag, sizeof(flag)) &&
                       strcmp(flag, "app") == 0;
  const bool plainReply =
      formField(query, "json", flag, sizeof(flag)) && flag[0] == '1';

  if (method == "POST" && path == "/save") {
    handleWebSave(client, body);
  } else if (method == "POST" && path == "/code") {
    handleWebCode(client, body, false, false);
  } else if (method == "GET" && path == "/code") {
    // A GET that changes state, deliberately: the code is single-use, so a
    // replayed load only earns a second `invalid_grant`. The app asks for a
    // one-line answer (`json=1`) so it can report the result and close; a browser
    // loading the same address — the fallback — still gets the page.
    handleWebCode(client, query, appCode, plainReply);
  } else if (method == "POST" && path == "/wipe") {
    handleWebWipe(client);
  } else if (method == "GET" && path == "/auth") {
    if (plainReply) {
      sendAuthUrl(client, appRedirect);
    } else {
      handleWebAuth(client, appRedirect);
    }
  } else if (method == "GET" && path == "/deck.json") {
    sendDeckJson(client);
  } else if (method == "GET" && path == "/net") {
    sendNetworkPage(client);
  } else if (method == "GET" && path == "/spotify") {
    sendSignInPage(client);
  } else if (method == "GET" && path == "/where") {
    sendWhere(client);
  } else if (creds.clientId[0] != '\0') {
    sendSignInPage(client);
  } else {
    sendNetworkPage(client);
  }
}

// ----------------------------------------------------------- captive portal ---
// A phone only offers "Sign in to network" if it can resolve its own probe name
// and then gets HTML back. Answering every DNS question with the access point's
// address does the first half; the request then lands on the setup server, whose
// catch-all already serves the page for any path. Phones that use DNS-over-HTTPS
// ignore the hint, so the card and the page still print the address to type.
static WiFiUDP dnsUdp;
static bool dnsUp = false;

static void startCaptiveDns() {
  if (dnsUp) return;
  dnsUp = dnsUdp.begin(53);
  Serial.println(dnsUp ? "[setup] captive DNS on port 53"
                       : "[setup] captive DNS could not bind port 53");
}

static void pumpCaptiveDns() {
  if (!dnsUp) return;
  const int size = dnsUdp.parsePacket();
  if (size <= 0) return;
  uint8_t query[512];
  if (size > static_cast<int>(sizeof(query))) return;  // never answer half a query
  if (dnsUdp.read(query, sizeof(query)) != size) return;
  if (size < 17) return;  // short header, no room for a question

  uint8_t reply[512];
  memset(reply, 0, sizeof(reply));
  memcpy(reply, query, 12);
  reply[2] = 0x81;            // response, recursion desired echoed, no error
  reply[3] = 0x80;            // recursion available
  reply[8] = reply[9] = 0;    // no authority records
  reply[10] = reply[11] = 0;  // no additional records; drops the EDNS OPT

  // Walk the question name. A malformed one is dropped rather than answered
  // with a name pointer into whatever bytes happen to follow.
  int pos = 12;
  bool ended = false;
  while (pos < size) {
    const uint8_t label = query[pos];
    if (label == 0) {
      ended = true;
      break;
    }
    if ((label & 0xC0) != 0) return;  // compression is not legal in a question
    pos += 1 + label;
  }
  if (!ended || pos + 5 > size) return;

  const uint16_t qtype =
      (static_cast<uint16_t>(query[pos + 1]) << 8) | query[pos + 2];
  const int questionEnd = pos + 5;
  memcpy(reply + 12, query + 12, questionEnd - 12);
  int outLen = questionEnd;

  // A gets this deck's address. AAAA and the rest get a valid empty reply, which
  // makes the phone fall back to asking for A instead of giving up.
  if (qtype == 1) {
    static const uint8_t ANSWER[] = {0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01,
                                     0x00, 0x00, 0x00, 0x3C, 0x00, 0x04};
    if (outLen + static_cast<int>(sizeof(ANSWER)) + 4 >
        static_cast<int>(sizeof(reply))) {
      return;
    }
    memcpy(reply + outLen, ANSWER, sizeof(ANSWER));
    outLen += sizeof(ANSWER);
    const IPAddress ip = WiFi.softAPIP();
    reply[outLen++] = ip[0];
    reply[outLen++] = ip[1];
    reply[outLen++] = ip[2];
    reply[outLen++] = ip[3];
    reply[7] = 1;  // one answer record
  }

  dnsUdp.beginPacket(dnsUdp.remoteIP(), dnsUdp.remotePort());
  dnsUdp.write(reply, outLen);
  dnsUdp.endPacket();
}

// Player mode answers one path and nothing else: the sign-in pages belong to
// setup, so a deck that is already playing has no /auth, /code or /spotify to
// reach. Every other path is a 404, and the read gives up after a second so a
// stray connection cannot hold the panel's poll loop.
static void ensureIdServer() {
  if (idServerUp || setupActive || WiFi.status() != WL_CONNECTED) return;
  idServer.begin();
  idServer.setNoDelay(true);
  idServerUp = true;
  Serial.printf("[id] answering /deck.json as %s on %s\n", deckId().c_str(),
                WiFi.localIP().toString().c_str());
}

static void pumpIdServer() {
  if (!idServerUp) return;
  WiFiClient client = idServer.accept();
  if (!client) return;

  const uint32_t startMs = millis();
  String requestLine;
  while (requestLine.length() == 0 &&
         static_cast<int32_t>(millis() - startMs) < 1000) {
    if (!client.available()) {
      delay(2);
      continue;
    }
    requestLine = client.readStringUntil('\n');
  }
  requestLine.trim();
  const int firstSpace = requestLine.indexOf(' ');
  const int secondSpace =
      firstSpace < 0 ? -1 : requestLine.indexOf(' ', firstSpace + 1);
  String path = secondSpace > firstSpace
                    ? requestLine.substring(firstSpace + 1, secondSpace)
                    : String("");
  const int query = path.indexOf('?');
  if (query >= 0) path.remove(query);

  if (path == "/deck.json") {
    sendDeckJson(client);
  } else {
    client.print(F("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n"
                   "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                   "a signed-in deck answers only /deck.json\n"));
  }
  client.stop();
}

static void pumpWebSetup() {
  if (!webSetupActive) return;
  if (setupRebootMs != 0 &&
      static_cast<int32_t>(setupRebootMs - millis()) <= 0) {
    Serial.println("[setup] rebooting into the deck");
    ESP.restart();
  }
  WiFiClient client = setupServer.accept();  // available() is the deprecated name
  if (!client) return;
  handleWebRequest(client);
  client.stop();
  delay(2);
}

static void startWebSetup() {
  // The player-mode listener holds port 80 until setup owns it again.
  if (idServerUp) {
    idServer.end();
    idServerUp = false;
  }
  webSetupActive = true;
  WiFi.persistent(false);
  WiFi.mode(WIFI_AP_STA);
  WiFi.setSleep(false);
  WiFi.setHostname("spotify-deck");
  WiFi.softAP(WEB_AP_SSID);  // open: the page is where the credentials are typed
  setupServer.begin();
  setupServer.setNoDelay(true);
  setupConnections = 0;      // a re-run starts the count over
  setupStationsShown = -1;
  startCaptiveDns();
  // A reboot between the challenge and the paste must not waste the code.
  if (pkceVerifier.length() == 0) pkceVerifier = loadVerifier();
  // No mDNS: it measured 37 kB of flash for a .local name, and Android's browser
  // has no resolver for it. The numeric address from /where is what works.
  // A re-run keeps the stored network up, so the token exchange can still reach
  // Spotify without retyping a password.
  if (creds.ssid[0] != '\0') WiFi.begin(creds.ssid, creds.pass);
  // The token endpoint pins its own root, and that certificate only validates
  // once SNTP has set the date.
  webTls.setCACert(SPOTIFY_TOKEN_CA);
  clockGood();

  Serial.printf("[setup] access point \"%s\", page http://%s\n", WEB_AP_SSID,
                WiFi.softAPIP().toString().c_str());
  if (creds.ssid[0] != '\0') {
    Serial.printf("[setup] also joining \"%s\"\n", creds.ssid);
  }
  Serial.println("[setup] the serial wizard still works; whichever finishes first wins.");
}

// Repainted only when one of the two numbers moves, so it costs nothing while
// the page sits idle. Zero stations means the phone never joined the access
// point; stations but no connections means it joined and its browser never asked
// for the page, which is a phone-side setting, not this sketch.
static void drawSetupLink() {
  if (!webSetupActive) return;
  const int stations = WiFi.softAPgetStationNum();
  if (stations == setupStationsShown &&
      setupConnections == setupConnectionsShown) {
    return;
  }
  setupStationsShown = stations;
  setupConnectionsShown = setupConnections;

  char line[40];
  uint16_t ink = C_TEXT;
  if (stations == 0) {
    strlcpy(line, "no phone joined yet", sizeof(line));
    ink = C_DIM;
  } else if (setupConnections == 0) {
    strlcpy(line, "phone joined, page not asked", sizeof(line));
  } else {
    snprintf(line, sizeof(line), "phone joined, %u page load%s",
             static_cast<unsigned>(setupConnections),
             setupConnections == 1 ? "" : "s");
  }
  tft.fillRect(0, 224, SCREEN_W, 16, C_BG);
  drawTextAt(line, 12, 228, 1, ink, C_BG);
  Serial.printf("[setup] %d phone(s) on the access point, %u page connection(s)\n",
                stations, static_cast<unsigned>(setupConnections));
}

// ---------------------------------------------------------- bluetooth link ---
// This deck speaks the Phone Link service its sibling speaks, so one companion
// app drives whichever board is flashed: it writes time, battery and notices, and
// it writes what the phone is playing. That mirror is the point — it is the one
// source of music that needs no client id.
//
// One thing goes the other way. A transport chip raises a notify on the command
// characteristic and the phone answers it against its own player. The deck never
// changes playback itself: a chip is a request, and the mirror line that comes
// back is the proof the phone took it.

static constexpr char LINK_SERVICE[] = "6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10";
static constexpr char LINK_TIME[] = "6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10";
static constexpr char LINK_BATTERY[] = "6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10";
static constexpr char LINK_NOTICE[] = "6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10";
// The owner's name, read and written: the same characteristic the phone deck
// exposes, so the app's RENAME works on this board too.
static constexpr char LINK_LABEL[] = "6f2d0009-9a3c-4b57-9d21-6a1e0c4f8b10";
static constexpr char LINK_IDENTITY[] = "6f2d0005-9a3c-4b57-9d21-6a1e0c4f8b10";
static constexpr char LINK_NOW[] = "6f2d0006-9a3c-4b57-9d21-6a1e0c4f8b10";
static constexpr char LINK_CMD[] = "6f2d0007-9a3c-4b57-9d21-6a1e0c4f8b10";
// The palette, one byte, read and written by the companion app.
static constexpr char LINK_THEME[] = "6f2d0008-9a3c-4b57-9d21-6a1e0c4f8b10";
// Set from the Bluedroid task, applied by the loop: two tasks assigning the same
// twelve colours is how a page ends up half paper and half ink.
static constexpr uint8_t THEME_PENDING_NONE = 0xFF;
static volatile uint8_t themePending = THEME_PENDING_NONE;

static volatile bool linkConnected = false;
static volatile uint32_t linkMirrorMs = 0;
static volatile uint8_t linkBatteryPct = 0;
static volatile bool linkCharging = false;
// Which chip was asked, and when. Lit for the round trip; the next full redraw
// clears it, and the mirror line that moved is what proves the phone answered.
static volatile uint8_t linkChip = 0;
static volatile uint32_t linkChipMs = 0;
// Who holds the link, so a hold can hang up on them and delete the bond. Kept
// as bytes rather than a String: it is read from a Bluedroid callback.
// The owner's name for this board, the same label the phone deck keeps: up to 16
// printable ASCII bytes in the identity namespace. Empty means unnamed, and the
// app falls back to the tag.
static char deckLabel[17] = "";
Preferences labelPrefs;

static uint8_t linkPeerBda[6] = {0, 0, 0, 0, 0, 0};
static volatile bool linkPeerKnown = false;

static bool mirroredFresh(uint32_t withinMs) {
  if (!linkConnected) return false;
  const uint32_t at = linkMirrorMs;
  return at != 0 && millis() - at < withinMs;
}

// One field of the mirror line, copied and cut on a UTF-8 boundary. A `|` cannot
// reach here: the split happens first, so a stray one only shortens a field.
static void copyField(const char *from, char *to, size_t cap) {
  if (to == nullptr || cap == 0) return;
  to[0] = '\0';
  if (from == nullptr) return;
  size_t n = strlen(from);
  if (n >= cap) {
    n = cap - 1;
    while (n > 0 && (static_cast<unsigned char>(from[n]) & 0xC0) == 0x80) --n;
  }
  memcpy(to, from, n);
  to[n] = '\0';
}

// The phone's line: `state|position|duration|title|artist|album`, all seconds.
// Anything unreadable is zero or empty rather than a guess, so a bad line shows
// `--:--` instead of a duration the deck invented.
static void applyMirrorLine(const char *line) {
  if (line == nullptr || *line == '\0') return;

  char buf[184];
  copyField(line, buf, sizeof(buf));

  char *field[6] = {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr};
  uint8_t count = 0;
  field[count++] = buf;
  for (char *p = buf; *p != '\0' && count < 6; ++p) {
    if (*p == '|') {
      *p = '\0';
      field[count++] = p + 1;
    }
  }
  if (count < 3) return;

  NowPlaying n;
  memset(&n, 0, sizeof(n));
  n.ready = true;
  n.playing = strcmp(field[0], "play") == 0;
  n.updatedMs = millis();
  if (strcmp(field[0], "stop") != 0) {
    n.progressMs = static_cast<uint32_t>(strtoul(field[1], nullptr, 10)) * 1000UL;
    n.durationMs = static_cast<uint32_t>(strtoul(field[2], nullptr, 10)) * 1000UL;
    if (count > 3) copyField(field[3], n.title, sizeof(n.title));
    if (count > 4) copyField(field[4], n.artist, sizeof(n.artist));
  }
  // The footer names what is playing it. The mirror does not say which app, but it
  // does say it is the phone, and a blank there would read as a broken deck.
  copyField("phone", n.device, sizeof(n.device));

  linkMirrorMs = millis();
  portENTER_CRITICAL(&snapMux);
  live = n;
  liveReady = true;
  portEXIT_CRITICAL(&snapMux);
}

// --------------------------------------------------------------- now page ---

static const Rect PREV_BTN = {12, 152, 96, 52};
static const Rect PLAY_BTN = {112, 152, 96, 52};
static const Rect NEXT_BTN = {212, 152, 96, 52};
static const Rect WORDMARK = {0, 0, 220, 32};
// Footer chip: hold it to open setup. 30 px tall and 112 wide, because a
// resistive panel needs a target a finger cannot miss at the screen's edge.
static const Rect SETUP_BTN = {196, 206, 112, 30};
static bool setupChipLit = false;

// Where a tap counts, as opposed to where the chip is drawn. A resistive panel
// reports a tap several pixels off and its x mapping is least linear at the
// screen's edges, so the three transport chips own the whole row -- the 8 px
// gaps are split between the neighbours -- and the footer chip takes 24 px of
// slack on its inner edge, the one facing the panel's middle. The phone deck
// pads its back buttons the same way for the same reason.
static const Rect PREV_HIT = {0, 144, 108, 68};
static const Rect PLAY_HIT = {108, 144, 104, 68};
static const Rect NEXT_HIT = {212, 144, 108, 68};
static const Rect SETUP_HIT = {176, 198, 144, 42};
static bool unpairPromptShown = false;

static void formatMs(uint32_t ms, char *out, size_t outLen) {
  const uint32_t total = ms / 1000UL;
  snprintf(out, outLen, "%lu:%02lu", static_cast<unsigned long>(total / 60UL),
           static_cast<unsigned long>(total % 60UL));
}

static void drawChip(Rect r, const char *label, uint8_t size, bool filled) {
  const uint16_t face = filled ? C_PINK : C_TILE;
  const uint16_t ink = filled ? C_BG : C_TEXT;
  tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_BUTTON, face);
  tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_BUTTON, C_EDGE);
  drawTextCentered(label, r, size, ink, face);
}

// Outlined and dim on purpose: it is a thing to hold, not a button to tap, and a
// stray finger should never leave the player.
static void drawSetupChip(bool lit) {
  const uint16_t face = lit ? C_TEXT : C_TILE;
  const uint16_t ink = lit ? C_BG : C_DIM;
  tft.fillRoundRect(SETUP_BTN.x, SETUP_BTN.y, SETUP_BTN.w, SETUP_BTN.h,
                    CORNER_BUTTON, face);
  tft.drawRoundRect(SETUP_BTN.x, SETUP_BTN.y, SETUP_BTN.w, SETUP_BTN.h,
                    CORNER_BUTTON, C_EDGE);
  drawTextCentered("SETUP", SETUP_BTN, 1, ink, face);
}

static void drawHeader() {
  tft.fillRect(0, 0, SCREEN_W, 32, C_BG);
  drawTextAt("SPOTIFY DECK", 12, 10, 2, C_TEXT, C_BG);
  // A linked phone is the thing that can actually play, so it is what the header
  // reports; WiFi is the older path and only worth a word when it is all there is.
  if (linkConnected) {
    drawTextRight("PHONE", SCREEN_W - 12, 13, 1, C_DIM, C_BG);
  } else {
    const bool up = WiFi.status() == WL_CONNECTED;
    drawTextRight(up ? "WIFI" : "NO LINK", SCREEN_W - 12, 13, 1,
                  up ? C_DIM : C_PINK, C_BG);
  }
  tft.drawFastHLine(0, 31, SCREEN_W, C_EDGE);
}

static void drawProgress(const NowPlaying &n) {
  uint32_t pos = n.progressMs;
  // Between polls the bar runs on its own clock, otherwise it sits still for
  // three seconds at a time and looks broken.
  if (n.playing) pos += millis() - n.updatedMs;
  const uint32_t dur = n.durationMs;
  if (dur > 0 && pos > dur) pos = dur;
  if (dur == 0) pos = 0;

  tft.fillRect(22, 112, 272, 6, C_TILE);
  tft.fillRoundRect(22, 112, 272, 6, 3, C_TILE_HI);
  if (dur > 0) {
    const int filled = static_cast<int>((static_cast<uint64_t>(pos) * 272ULL) / dur);
    if (filled > 0) tft.fillRoundRect(22, 112, filled, 6, 3, C_TEXT);
  }

  char elapsed[10];
  char total[10];
  formatMs(pos, elapsed, sizeof(elapsed));
  formatMs(dur, total, sizeof(total));
  drawTextAt(elapsed, 22, 121, 1, C_DIM, C_TILE);
  drawTextRight(total, 298, 121, 1, C_DIM, C_TILE);
}

static void drawNow() {
  screen = SCR_NOW;
  tft.fillScreen(C_BG);
  drawHeader();

  NowPlaying n;
  bool ready = false;
  readNowPlaying(n, ready);

  tft.fillRoundRect(12, 40, 296, 104, CORNER_CARD, C_TILE);
  tft.drawRoundRect(12, 40, 296, 104, CORNER_CARD, C_EDGE);

  const char *title = n.title[0] ? n.title
                                 : (ready ? "NOTHING PLAYING" : "CONNECTING...");
  drawWrappedText(title, 22, 50, 272, 2, C_TEXT, C_TILE, 2);
  drawTextAt(n.artist[0] ? n.artist : "waiting for Spotify", 22, 94, 1, C_DIM,
             C_TILE);
  drawProgress(n);

  const bool waiting = linkConnected && linkChipMs != 0 && millis() - linkChipMs < 4000;
  drawChip(PREV_BTN, "|<<", 2, waiting && linkChip == CMD_PREV);
  drawChip(PLAY_BTN, n.playing ? "PAUSE" : "PLAY", 2, true);
  drawChip(NEXT_BTN, ">>|", 2, waiting && linkChip == CMD_NEXT);

  tft.fillRect(0, 204, SCREEN_W, 36, C_BG);
  drawTextAt(n.device[0] ? n.device : "no output device", 12, 210, 1, C_DIM, C_BG);
  if (n.status >= 400) {
    char err[20];
    snprintf(err, sizeof(err), "api %u", static_cast<unsigned>(n.status));
    drawTextAt(err, 12, 224, 1, C_PINK, C_BG);
  } else if (WiFi.status() != WL_CONNECTED) {
    drawTextAt("no wifi", 12, 224, 1, C_PINK, C_BG);
  } else {
    drawTextAt("hold to change it", 12, 224, 1, C_DIM, C_BG);
  }
  drawSetupChip(setupChipLit);
}

// ------------------------------------------------------- link characteristics ---
// The service the companion app insists on before it holds a link: time, battery
// and notices are what the phone deck draws with. This deck shows the battery in
// its footer, wraps no clock and has no notice card, so those two are accepted and
// dropped rather than drawn — the app does not care what the board does with them,
// only that the characteristic is there.

class LinkTimeCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override { (void)c; }
};

class LinkBatteryCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    const uint8_t *raw = c->getData();
    if (raw == nullptr || c->getLength() < 1) return;
    linkBatteryPct = raw[0] > 100 ? 100 : raw[0];
    linkCharging = c->getLength() > 1 && raw[1] != 0;
  }
};

class LinkNoticeCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override { (void)c; }
};

class LinkNowCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    const uint8_t *raw = c->getData();
    const size_t len = c->getLength();
    if (raw == nullptr || len == 0) return;
    char line[184];
    const size_t n = len < sizeof(line) - 1 ? len : sizeof(line) - 1;
    memcpy(line, raw, n);
    line[n] = '\0';
    applyMirrorLine(line);
  }
};

// 0 PAPER or 1 INK, one byte. The write only records which palette was asked for;
// the loop applies it, saves it and repaints. The read answers where the board
// actually is, so the app can light the right chip without guessing.
class LinkThemeCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    const uint8_t *raw = c->getData();
    if (raw == nullptr || c->getLength() < 1) return;
    if (raw[0] >= THEME_COUNT) return;
    themePending = raw[0];
  }

  void onRead(BLECharacteristic *c) override {
    // Not const: core 2.0.17's setValue() takes a uint8_t*, and this sketch has to
    // link on both cores.
    uint8_t index = themeIndex;
    c->setValue(&index, 1);
  }
};

static BLECharacteristic *linkCommandChar = nullptr;
static uint32_t linkSerial = 0;

// The bond lives at both ends. When the phone deletes its half - a forgotten
// pairing, a reinstall, a handset that no longer holds the keys - this board's
// half refuses every retry with an authentication error, and the only way back
// used to be the PLAY hold. These are the HCI reasons that mean the keys are
// gone: PIN or key missing, authentication failure, and a link that never
// established.
static bool bondIsStale(uint8_t reason) {
  return reason == 0x05 || reason == 0x06 || reason == 0x3E;
}

static bool bondDroppedOnce = false;
static uint32_t lastBondDropMs = 0;

// Deletes this board's half of the bond so the next attempt pairs afresh, and
// forgets the peer with it: the phone that held the link is the one that left.
// Rate limited, because a failed attempt can disconnect in a loop and removing
// the bond on every pass would never let a pairing finish.
static void dropLinkBond(esp_bd_addr_t peer, const char *why) {
  if (bondDroppedOnce && millis() - lastBondDropMs < 5000) return;
  bondDroppedOnce = true;
  lastBondDropMs = millis();
  esp_ble_remove_bond_device(peer);
  linkPeerKnown = false;
  Serial.print("[link] bond dropped (");
  Serial.print(why);
  Serial.println(")");
}

// The app heals its own half of a bond and expects this deck to do the same, so
// a pairing that never completes is cleaned up here instead of leaving two ends
// disagreeing about keys that are gone.
class LinkSecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return 123456; }
  void onPassKeyNotify(uint32_t passKey) override { (void)passKey; }
  bool onSecurityRequest() override { return true; }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t cmpl) override {
    Serial.print("[link] pairing ");
    Serial.println(cmpl.success ? "ok" : "failed");
    if (!cmpl.success) dropLinkBond(cmpl.bd_addr, "pairing failed");
  }
  bool onConfirmPIN(uint32_t passKey) override {
    (void)passKey;
    return true;
  }
};

class LinkServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    linkConnected = true;
    linkChip = 0;
    linkChipMs = 0;
    // 7.5–15 ms with no slave latency, so a tap does not wait three connection
    // intervals to reach the phone. The phone may grant more; this is a request.
    server->updateConnParams(param->connect.remote_bda, 6, 12, 0, 400);
    memcpy(linkPeerBda, param->connect.remote_bda, 6);
    linkPeerKnown = true;
    Serial.println("[link] phone connected");
  }

  void onDisconnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    linkConnected = false;
    linkChip = 0;
    // Bluedroid stops advertising when a phone connects and never resumes it, so
    // without this the deck is invisible to the next scan once a phone walks off.
    BLEDevice::startAdvertising();
    Serial.println("[link] phone gone, advertising again");
    if (bondIsStale(param->disconnect.reason)) {
      dropLinkBond(param->disconnect.remote_bda, "keys gone");
    }
  }
};

// The same document a network deck answers on /deck.json, minus the fields that
// only mean something on a network. The app's DeckIdentity parses either.
static void buildLinkIdentity(char *out, size_t cap) {
  snprintf(out, cap,
           "{\"id\":\"%s\",\"widget\":\"%s\",\"tag\":\"%s\","
           "\"name\":\"%s\",\"label\":\"%s\",\"role\":\"%s\","
           "\"v\":\"%s\",\"link\":\"ble\"}",
           deckId().c_str(), DECK_WIDGET, deckTag, DECK_NAME, deckLabel, DECK_ROLE,
           DECK_VERSION);
}

static BLECharacteristic *linkIdentityChar = nullptr;

// The document quotes the label, so a rename rewrites what a reader gets rather
// than waiting for a reboot. 185 is this deck's own MTU, so one read carries 182
// bytes; a longer document would come back cut and name no widget.
static void refreshLinkIdentity() {
  char document[192];
  buildLinkIdentity(document, sizeof(document));
  if (linkIdentityChar != nullptr) {
    linkIdentityChar->setValue(reinterpret_cast<uint8_t *>(document), strlen(document));
  }
  const size_t length = strlen(document);
  if (length > 182) {
    Serial.print("[link] identity is ");
    Serial.print(static_cast<unsigned>(length));
    Serial.println(" bytes, over one 185-MTU read");
  }
}

class LinkLabelCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    const uint8_t *raw = c->getData();
    const size_t len = c->getLength();
    char incoming[32];
    const size_t n = (raw != nullptr && len < sizeof(incoming) - 1) ? len : 0;
    if (n > 0) memcpy(incoming, raw, n);
    incoming[n] = 0;
    char clean[17];
    sanitizeLabel(clean, sizeof(clean), incoming);
    if (strcmp(clean, deckLabel) == 0) return;
    snprintf(deckLabel, sizeof(deckLabel), "%s", clean);
    labelPrefs.begin("identity", false);
    if (clean[0] == 0) labelPrefs.remove("label");
    else labelPrefs.putString("label", clean);
    labelPrefs.end();
    refreshLinkIdentity();
    Serial.print("[link] label ");
    Serial.println(clean);
  }
};

static void startBluetooth() {
  // The advertised name is what the app's scan matches on, so it starts with the
  // deck's name; the tag on the end is the half of the id that cannot move.
  char name[28];
  snprintf(name, sizeof(name), "%s %s", DECK_NAME, deckTag);
  BLEDevice::init(name);
  BLEDevice::setMTU(185);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new LinkServerCallbacks());
  BLEDevice::setSecurityCallbacks(new LinkSecurityCallbacks());
  BLEService *service = server->createService(LINK_SERVICE);

  const uint32_t write = BLECharacteristic::PROPERTY_WRITE;
  BLECharacteristic *time = service->createCharacteristic(LINK_TIME, write);
  time->setCallbacks(new LinkTimeCallbacks());
  BLECharacteristic *battery = service->createCharacteristic(LINK_BATTERY, write);
  battery->setCallbacks(new LinkBatteryCallbacks());
  BLECharacteristic *notice = service->createCharacteristic(LINK_NOTICE, write);
  notice->setCallbacks(new LinkNoticeCallbacks());
  BLECharacteristic *now = service->createCharacteristic(LINK_NOW, write);
  now->setCallbacks(new LinkNowCallbacks());

  // The palette. Read-write because the app lights a chip from the board's own
  // answer; a deck flashed before this existed simply has no such characteristic.
  BLECharacteristic *theme = service->createCharacteristic(
      LINK_THEME,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  theme->setCallbacks(new LinkThemeCallbacks());
  uint8_t themeNow = themeIndex;
  theme->setValue(&themeNow, 1);

  // The owner's name, which is the one part of the identity a person can change.
  labelPrefs.begin("identity", true);
  sanitizeLabel(deckLabel, sizeof(deckLabel), labelPrefs.getString("label", "").c_str());
  labelPrefs.end();
  BLECharacteristic *label = service->createCharacteristic(
      LINK_LABEL, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  label->setCallbacks(new LinkLabelCallbacks());
  label->setValue(reinterpret_cast<uint8_t *>(deckLabel), strlen(deckLabel));

  linkIdentityChar = service->createCharacteristic(LINK_IDENTITY,
                                                  BLECharacteristic::PROPERTY_READ);
  refreshLinkIdentity();

  // Deck to phone, the only direction this board talks back on.
  linkCommandChar =
      service->createCharacteristic(LINK_CMD, BLECharacteristic::PROPERTY_NOTIFY);
  linkCommandChar->addDescriptor(new BLE2902());

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(LINK_SERVICE);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.printf("[link] advertising as %s\n", name);
}

// Hang up on the phone and forget every stored bond. Without this the deck has no way out
// of a link the phone re-opens on every advert: unpairing from the phone's own
// Bluetooth settings leaves the deck still bonded, so it will not take a new
// phone and the companion app cannot reconnect either. Held PLAY 3 s, or `unpair`
// at 115200. Pressed, PLAY is still play/pause.
static void unpairLink() {
  linkConnected = false;
  linkChip = 0;
  linkChipMs = 0;
  bool removed = false;
  if (linkPeerKnown) {
    esp_ble_gap_disconnect(linkPeerBda);
    esp_ble_remove_bond_device(linkPeerBda);
    linkPeerKnown = false;
    removed = true;
  }
  // The connected peer is not the whole set: bonds outlive a connection and a
  // reboot, and a deck that lost the app is bonded to a phone it is not talking
  // to -- which is what keeps a second phone out. Sweep the list.
  int count = esp_ble_get_bond_device_num();
  if (count > 0) {
    esp_ble_bond_dev_t *list = static_cast<esp_ble_bond_dev_t *>(
        malloc(sizeof(esp_ble_bond_dev_t) * count));
    if (list != nullptr) {
      if (esp_ble_get_bond_device_list(&count, list) == ESP_OK) {
        for (int i = 0; i < count; ++i) {
          esp_ble_remove_bond_device(list[i].bd_addr);
          removed = true;
        }
      }
      free(list);
    }
  }
  Serial.println(removed ? "[link] unpaired: bonds removed, advertising again"
                         : "[link] no phone bonded; advertising again");
  BLEDevice::startAdvertising();
  drawNow();
}

// Ask the phone to do something. False means no phone is listening, so a caller
// signed in to Spotify can fall back to the Web API instead.
static bool sendLinkCommand(const char *action) {
  if (!linkConnected || linkCommandChar == nullptr) return false;

  char line[20];
  snprintf(line, sizeof(line), "%lu|%s", static_cast<unsigned long>(++linkSerial),
           action);
  linkCommandChar->setValue(reinterpret_cast<uint8_t *>(line), strlen(line));
  linkCommandChar->notify();

  uint8_t chip = 0;
  if (strcmp(action, "prev") == 0) {
    chip = CMD_PREV;
  } else if (strcmp(action, "next") == 0) {
    chip = CMD_NEXT;
  }
  linkChip = chip;
  linkChipMs = millis();
  if (chip == CMD_PREV) drawChip(PREV_BTN, "|<<", 2, true);
  else if (chip == CMD_NEXT) drawChip(NEXT_BTN, ">>|", 2, true);
  Serial.printf("[cmd] %s\n", line);
  return true;
}

// ------------------------------------------------------------- calibration ---

static void drawCrosshair(int x, int y) {
  tft.fillCircle(x, y, 6, C_TEXT);
  tft.drawCircle(x, y, 12, C_EDGE);
  tft.drawCircle(x, y, 20, C_EDGE);
}

static void drawCalibrationScreen() {
  screen = SCR_CAL;
  tft.fillScreen(C_BG);
  const int tx = CAL_TARGET_X[calIndex];
  const int ty = CAL_TARGET_Y[calIndex];
  drawCrosshair(tx, ty);

  // Words at the corner the dot is not in: the heading used to sit under the
  // first target, whose 20 px ring covered CALIBRATE.
  const bool right = tx < SCREEN_W / 2;
  const bool lower = ty < SCREEN_H / 2;
  const int y = lower ? 168 : 10;
  char step[16];
  snprintf(step, sizeof(step), "%u / 3", static_cast<unsigned>(calIndex + 1));
  const int edge = SCREEN_W - 12;
  if (right) {
    drawTextRight("CALIBRATE", edge, y, 2, C_TEXT, C_BG);
    drawTextRight("tap the dot", edge, y + 22, 1, C_DIM, C_BG);
    drawTextRight(step, edge, y + 36, 1, C_ACCENT, C_BG);
    drawTextRight("hold 2 s to cancel", edge, y + 50, 1, C_DIM, C_BG);
  } else {
    drawTextAt("CALIBRATE", 12, y, 2, C_TEXT, C_BG);
    drawTextAt("tap the dot", 12, y + 22, 1, C_DIM, C_BG);
    drawTextAt(step, 12, y + 36, 1, C_ACCENT, C_BG);
    drawTextAt("hold 2 s to cancel", 12, y + 50, 1, C_DIM, C_BG);
  }
}

// ------------------------------------------------------------------ touch ---

// Four conversions per poll, median per axis. The XPT2046 answers a wild sample
// often enough that a single read lands a tap on the neighbouring chip, and the
// first conversions after touch-down are the worst. Pressure is used only to drop
// samples that did not convert at all: its scale differs per panel, and a
// threshold picked blind would kill touch outright.
static constexpr int TOUCH_SAMPLES = 4;
static constexpr int TOUCH_SPREAD_MAX = 300;  // raw units

static bool readRawTouch(int &rawX, int &rawY) {
  if (!touch.touched()) return false;

  int xs[TOUCH_SAMPLES];
  int ys[TOUCH_SAMPLES];
  int n = 0;
  for (int i = 0; i < TOUCH_SAMPLES; ++i) {
    uint16_t x = 0, y = 0;
    uint8_t z = 0;
    touch.readData(&x, &y, &z);
    if (z == 0) continue;
    xs[n] = static_cast<int>(x);
    ys[n] = static_cast<int>(y);
    ++n;
  }
  if (n < 2) return false;

  for (int i = 1; i < n; ++i) {  // insertion sort, n is at most four
    const int vx = xs[i];
    const int vy = ys[i];
    int j = i - 1;
    while (j >= 0 && xs[j] > vx) {
      xs[j + 1] = xs[j];
      --j;
    }
    xs[j + 1] = vx;
    j = i - 1;
    while (j >= 0 && ys[j] > vy) {
      ys[j + 1] = ys[j];
      --j;
    }
    ys[j + 1] = vy;
  }

  if (xs[n - 1] - xs[0] > TOUCH_SPREAD_MAX) return false;
  if (ys[n - 1] - ys[0] > TOUCH_SPREAD_MAX) return false;

  rawX = xs[n / 2];
  rawY = ys[n / 2];
  return true;
}

static bool readTouch(int &x, int &y) {
  int rawX = 0, rawY = 0;
  if (!readRawTouch(rawX, rawY)) return false;
  mapTouch(rawX, rawY, x, y);
  return true;
}

static bool inside(int x, int y, Rect r) {
  return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
}

static void startCalibration() {
  screen = SCR_CAL;
  calIndex = 0;
  calTapMs = 0;
  drawCalibrationScreen();
}

// Abandoning a run throws the candidate mapping away and keeps whatever was
// stored before, so a failed attempt can never leave the deck unusable.
static void cancelCalibration() {
  loadMapping();
  drawNow();
}

static void handleCalibrationTap(int rawX, int rawY) {
  // The old mapping is deliberately not consulted: when it is wrong it would
  // reject exactly the taps meant to replace it.
  if (calIndex > 0) {
    const int dx = rawX - calRaw[calIndex - 1].x;
    const int dy = rawY - calRaw[calIndex - 1].y;
    if (dx * dx + dy * dy < 300 * 300) return;  // bounce, not a second corner
  }
  calRaw[calIndex].x = rawX;
  calRaw[calIndex].y = rawY;
  ++calIndex;

  if (calIndex < 3) {
    drawCalibrationScreen();
    return;
  }

  TouchMapping fitted;
  if (fitMapping(calRaw, fitted)) {
    mapping = fitted;
    saveMapping(mapping);
    mappingStored = true;
    Serial.println("[touch] calibration saved");
    drawNow();
  } else {
    Serial.println("[touch] the three taps were too close; starting over");
    calIndex = 0;
    drawCalibrationScreen();
  }
}

// ------------------------------------------------------------------- loop ---

// Presses are latched on the way down and acted on when the finger lifts, so a
// redraw in between can never swallow the event.
static constexpr int TARGET_NONE = -1;
static constexpr int TARGET_PREV = 1;
static constexpr int TARGET_TOGGLE = 2;
static constexpr int TARGET_NEXT = 3;
static constexpr int TARGET_SETUP = 4;

bool wasTouched = false;
bool ignoreTouchUntilRelease = false;
uint32_t touchStartMs = 0;
uint32_t lastActionMs = 0;
int pressedTarget = TARGET_NONE;

static char cmdLine[16];
static size_t cmdLen = 0;
static bool cmdSawCR = false;

static void queueCommand(uint8_t cmd) {
  if (cmdQueue == nullptr) return;
  xQueueSend(cmdQueue, &cmd, 0);
}

// "setup" + Enter re-runs the wizard at any time. Only whole lines are checked,
// so a stray character from a monitor connecting cannot start it.
static void pumpSerialCommand() {
  while (Serial.available()) {
    const int raw = Serial.read();
    if (raw < 0) break;
    const char c = static_cast<char>(raw);
    // CRLF monitors send both characters for one Enter; the second one would
    // otherwise commit an empty line.
    if (c == '\n' && cmdSawCR) {
      cmdSawCR = false;
      continue;
    }
    cmdSawCR = (c == '\r');
    if (c == '\r' || c == '\n') {
      cmdLine[cmdLen] = '\0';
      if (strcasecmp(cmdLine, "setup") == 0) {
        Serial.println();
        startSetupWizard();
        return;
      }
      if (strcasecmp(cmdLine, "unpair") == 0) {
        Serial.println();
        unpairLink();
      }
      cmdLen = 0;
    } else if (c >= 32 && c < 127 && cmdLen + 1 < sizeof(cmdLine)) {
      cmdLine[cmdLen++] = c;
    }
  }
}

// The boot gesture: hold the panel for four seconds and the stored network and
// token are erased. Drawn as a countdown so it is never a surprise, and a release
// at any point leaves everything alone.
static bool bootHoldForgets() {
  const uint32_t startMs = millis();
  screen = SCR_SETUP;
  tft.fillScreen(C_BG);
  drawTextAt("FORGET CREDENTIALS", 12, 10, 2, C_TEXT, C_BG);
  drawTextAt("keep holding", 12, 40, 1, C_DIM, C_BG);
  drawTextAt("let go to cancel", 12, 54, 1, C_DIM, C_BG);

  tft.fillRoundRect(8, 80, 304, 44, CORNER_CARD, C_TILE);
  drawTextAt("WiFi password and", 18, 90, 1, C_DIM, C_TILE);
  drawTextAt("the Spotify token", 18, 104, 1, C_DIM, C_TILE);

  for (;;) {
    if (!touch.touched()) return false;  // released: nothing was changed
    const uint32_t held = millis() - startMs;
    if (held > 4000) break;
    const int filled = static_cast<int>(held * 284UL / 4000UL);
    tft.fillRect(18, 140, 284, 10, C_TILE);
    if (filled > 0) tft.fillRect(18, 140, filled, 10, C_TEXT);
    delay(20);
  }

  wipeCredentials();
  Serial.println("[setup] rebooting into setup");
  delay(200);
  ESP.restart();
  return true;  // not reached
}

static Preferences themePrefs;

// One palette into the twelve names, and the only place they all change at once.
static void applyTheme(uint8_t index) {
  if (index >= THEME_COUNT) index = THEME_DEFAULT;
  themeIndex = index;
  const Palette &theme = THEMES[index];
  C_BG         = theme.bg;
  C_TILE       = theme.tile;
  C_TILE_HI    = theme.tileHi;
  C_EDGE       = theme.edge;
  C_TEXT       = theme.text;
  C_DIM        = theme.dim;
  C_ACCENT     = theme.accent;
  C_ACCENT_DIM = theme.accentDim;
  C_PINK       = theme.pink;
  C_PURPLE     = theme.purple;
  C_YELLOW     = theme.yellow;
  C_GREEN      = theme.green;
}

// Clamped on the way in, so a corrupt byte cannot leave the deck with a palette
// that does not exist and no way back to one that does.
static void loadTheme() {
  themePrefs.begin("settings", true);
  const uint8_t saved = themePrefs.getUChar("theme", THEME_DEFAULT);
  themePrefs.end();
  applyTheme(saved);
}

static void saveTheme() {
  themePrefs.begin("settings", false);
  themePrefs.putUChar("theme", themeIndex);
  themePrefs.end();
}

// Runs on the loop task, where the panel is: apply, keep, and ask for a repaint.
static void applyPendingTheme() {
  const uint8_t wanted = themePending;
  themePending = THEME_PENDING_NONE;
  if (wanted >= THEME_COUNT) return;
  applyTheme(wanted);
  saveTheme();
  // The setup page and the calibration screen redraw themselves on their own
  // ticks; the player page is drawn by the loop, so ask it for a full repaint.
  if (!setupActive && screen != SCR_CAL) drawNow();
}

void setup() {
  Serial.begin(115200);
  disableOnboardLed();

  pinMode(PIN_TFT_BL, OUTPUT);
  digitalWrite(PIN_TFT_BL, HIGH);  // full brightness; there is no PWM control yet

  // Explicit SPI pins let the sketch work without editing library setup files.
  SPI.begin(PIN_SPI_SCK, PIN_SPI_MISO, PIN_SPI_MOSI);
  tft.begin();
  tft.setRotation(1);
  tft.setTextWrap(false);
  // Before the first fill: every draw below reads the twelve colours this sets.
  loadTheme();
  tft.fillScreen(C_BG);

  touch.begin(SPI);

  initDeckIdentity();
  loadMapping();
  loadCredentials();

  cmdQueue = xQueueCreate(4, sizeof(uint8_t));

  // Holding the panel while the board boots always opens calibration, even if
  // the stored mapping is wrong. A long hold forgets the credentials instead,
  // which is the way back when the WiFi password changed and no monitor is
  // around; the countdown makes it deliberate, and letting go cancels it.
  if (touch.touched()) {
    if (bootHoldForgets()) return;  // wiped and restarted; never returns
    startCalibration();
    return;
  }

  if (!mappingStored) {
    startCalibration();
    return;
  }

  // Bluetooth comes up either way, and before the credential test: a phone in the
  // room is a player this deck can show and drive with no client id at all, which
  // is the only path that works while Spotify's developer apps are closed.
  startBluetooth();

  Serial.printf("[id] Spotify Deck, esp32 // %08lx\n",
                static_cast<unsigned long>(ESP.getEfuseMac()));
  Serial.printf("[id] %s\n", deckId().c_str());

  if (!credsComplete()) {
    // No sign-in is a state this deck can live in now: the page is the phone's
    // player on better glass. Holding the footer chip still opens the wizard, and
    // so does `setup` on the monitor, which is how credentials are added later.
    Serial.println("[id] no credentials: running off the phone's session");
    drawNow();
    return;
  }

  drawNow();
  ensureNetTask();
}

void loop() {
  const uint32_t now = millis();

  // A palette written over the link is applied here, before anything draws.
  if (themePending != THEME_PENDING_NONE) applyPendingTheme();

  if (setupActive) {
    // DNS first: the phone blocks on the name before it ever opens the socket.
    pumpCaptiveDns();
    pumpWebSetup();
    pumpSetupWizard();
    drawSetupLink();
    delay(5);
    return;
  }
  pumpSerialCommand();
  if (setupActive) return;

  // Identity first: it is one accept() on a socket that is usually idle, and it
  // is what lets the app tell this deck from another one on the same network. A
  // deck running off the phone's session has no network, so there is nothing to
  // answer on and starting it would only take a socket from the stack.
  if (WiFi.status() == WL_CONNECTED) {
    ensureIdServer();
    pumpIdServer();
  }

  int x = 0, y = 0;
  const bool down = readTouch(x, y);

  if (ignoreTouchUntilRelease) {
    if (!down) ignoreTouchUntilRelease = false;
    wasTouched = down;
    delay(10);
    return;
  }

  // ------------------------------------------------------------- calibrate --
  if (screen == SCR_CAL) {
    int rawX = 0, rawY = 0;
    if (readRawTouch(rawX, rawY) && now - rawUiMs > 120) {
      rawUiMs = now;
      char line[28];
      snprintf(line, sizeof(line), "raw %4d %4d", rawX, rawY);
      drawTextAt(line, 34, 214, 1, C_DIM, C_BG);
    }
    if (down && !wasTouched) {
      touchStartMs = now;
      if (now - calTapMs > 250) {
        calTapMs = now;
        if (readRawTouch(rawX, rawY)) handleCalibrationTap(rawX, rawY);
      }
    }
    if (down && wasTouched && now - touchStartMs > 2000) {
      cancelCalibration();
      return;
    }
    wasTouched = down;
    delay(10);
    return;
  }

  // ------------------------------------------------------------------ setup --
  if (screen == SCR_SETUP) {
    // Nothing to do here: the wizard is driven by the phone's page and by the
    // serial monitor, both pumped above. The card is redrawn on a step change.
    wasTouched = down;
    delay(10);
    return;
  }

  // -------------------------------------------------------------------- now --
  // The bar and the clock move every second; the rest of the page is redrawn
  // only when Spotify actually says something different, because a full repaint
  // of a 320x240 panel every second is visible flicker.
  static NowPlaying last;
  static bool haveLast = false;
  static uint32_t lastTickMs = 0;
  static uint32_t lastFullMs = 0;

  NowPlaying current;
  bool ready = false;
  readNowPlaying(current, ready);

  const bool changed = !haveLast || current.playing != last.playing ||
                       strcmp(current.title, last.title) != 0 ||
                       strcmp(current.artist, last.artist) != 0 ||
                       strcmp(current.device, last.device) != 0 ||
                       current.status != last.status ||
                       current.durationMs != last.durationMs;

  if (changed || now - lastFullMs > 5000) {
    lastFullMs = now;
    last = current;
    haveLast = true;
    drawNow();
    lastTickMs = now;
  } else if (now - lastTickMs > 1000 && screen == SCR_NOW) {
    lastTickMs = now;
    drawProgress(current);
  }

  if (down && !wasTouched) {
    touchStartMs = now;
    if (inside(x, y, PREV_HIT)) {
      pressedTarget = TARGET_PREV;
    } else if (inside(x, y, PLAY_HIT)) {
      pressedTarget = TARGET_TOGGLE;
    } else if (inside(x, y, NEXT_HIT)) {
      pressedTarget = TARGET_NEXT;
    } else if (inside(x, y, SETUP_HIT)) {
      pressedTarget = TARGET_SETUP;
    } else {
      pressedTarget = TARGET_NONE;
    }
  }

  // Hold the wordmark for 1.5 s to calibrate, the deck's escape hatch.
  if (down && pressedTarget == TARGET_NONE && inside(x, y, WORDMARK) &&
      now - touchStartMs > 1500) {
    startCalibration();
    return;
  }

  // Hold the footer chip for the same 1.5 s to open setup: another network or
  // another Spotify account, without a serial monitor. A tap does nothing.
  if (down && pressedTarget == TARGET_SETUP) {
    if (!setupChipLit && now - touchStartMs > 500) {
      setupChipLit = true;
      drawSetupChip(true);
    }
    if (now - touchStartMs > 1500) {
      setupChipLit = false;
      pressedTarget = TARGET_NONE;
      startSetupWizard();
      return;
    }
  }

  // Hold PLAY for 3 s to drop the bonded phone. The label changes at 1.5 s so a
  // hold is visibly not a tap. Deliberately independent of linkConnected: the
  // case that needs it is a bond the deck cannot see a connection to.
  if (down && pressedTarget == TARGET_TOGGLE && now - touchStartMs > 1500 &&
      !unpairPromptShown) {
    unpairPromptShown = true;
    drawChip(PLAY_BTN, "UNPAIR", 1, false);
  }
  if (down && pressedTarget == TARGET_TOGGLE && now - touchStartMs > 3000) {
    unpairPromptShown = false;
    pressedTarget = TARGET_NONE;
    ignoreTouchUntilRelease = true;
    unpairLink();
    return;
  }

  // Release fires the command, so a hold is never mistaken for a tap.
  if (!down && wasTouched && pressedTarget != TARGET_NONE) {
    if (pressedTarget == TARGET_SETUP) {
      // Released too early: nothing started, so just put the chip back.
      setupChipLit = false;
      drawSetupChip(false);
    } else if (unpairPromptShown) {
      // Released before the hold finished: no command, just the label back.
      unpairPromptShown = false;
      drawNow();
    } else if (now - lastActionMs > 180) {
      lastActionMs = now;
      // Whichever source is live takes the tap. A linked phone is the player, so
      // ask it; a signed-in deck with no phone falls back to Spotify's Web API.
      if (pressedTarget == TARGET_PREV) {
        if (!sendLinkCommand("prev")) queueCommand(CMD_PREV);
      } else if (pressedTarget == TARGET_TOGGLE) {
        if (!sendLinkCommand("toggle")) queueCommand(CMD_TOGGLE);
      } else if (pressedTarget == TARGET_NEXT) {
        if (!sendLinkCommand("next")) queueCommand(CMD_NEXT);
      }
    }
    pressedTarget = TARGET_NONE;
  }

  wasTouched = down;
  delay(10);
}
