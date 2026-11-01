/*
 * Spotify Deck - ESP32-2432S024R (2.4" resistive touch CYD)
 *
 * The second deck: instead of listening to a phone over Bluetooth, this one is
 * on the network. It signs in to Spotify with a refresh token, polls what is
 * playing, and draws it; the three chips under the track send previous, play or
 * pause, and next back to the account's active device.
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
 * Do not install ArduinoBLE - it is not used here, and on the other deck its
 * BLEDevice.h shadows the core's BLE headers.
 *
 * Controls:
 *   - Tap |<< , PLAY/PAUSE or >>| to drive the account's active device.
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
// The same two monochrome themes the phone deck ships, because that is the house
// palette: 0 PAPER is black on white, 1 INK is white on black, and the six
// accent slots are a grey ramp rather than hues. A Spotify deck does not get to
// spend colour on green just because the service has one.
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
  // 0 PAPER - black on white: paper ground, black ink, greys in between
  { 0xFFFF /* #ffffff */, 0xF7BE /* #f4f4f4 */, 0xEF5D /* #e8e8e8 */,
    0xCE59 /* #c8c8c8 */, 0x0000 /* #000000 */, 0x6B6D /* #6f6f6f */,
    0x0000 /* #000000 */, 0xB596 /* #b0b0b0 */, 0x2945 /* #2b2b2b */,
    0x8C51 /* #8a8a8a */, 0x0000 /* #000000 */, 0x52AA /* #555555 */ },
  // 1 INK - white on black: the same ramp inverted, for a dark room
  { 0x0000 /* #000000 */, 0x0861 /* #0e0e0e */, 0x18E3 /* #1c1c1c */,
    0x3186 /* #333333 */, 0xFFFF /* #ffffff */, 0x8C71 /* #8c8c8c */,
    0xFFFF /* #ffffff */, 0x4A69 /* #4d4d4d */, 0xD6BA /* #d4d4d4 */,
    0x738E /* #707070 */, 0xFFFF /* #ffffff */, 0xA514 /* #a0a0a0 */ },
};

static constexpr uint8_t THEME_COUNT = sizeof(THEMES) / sizeof(THEMES[0]);
// Pick a palette here; a settings row can change it at runtime later.
static constexpr uint8_t THEME_INDEX = 1;  // INK
static_assert(THEME_INDEX < THEME_COUNT,
              "THEME_INDEX is past the end of THEMES");

// The twelve names the drawing code already uses, loaded from the chosen theme.
// Plain variables rather than constexpr, so a picker can reassign them later.
static uint16_t C_BG         = THEMES[THEME_INDEX].bg;
static uint16_t C_TILE       = THEMES[THEME_INDEX].tile;
static uint16_t C_TILE_HI    = THEMES[THEME_INDEX].tileHi;
static uint16_t C_EDGE       = THEMES[THEME_INDEX].edge;
static uint16_t C_TEXT       = THEMES[THEME_INDEX].text;
static uint16_t C_DIM        = THEMES[THEME_INDEX].dim;
static uint16_t C_ACCENT     = THEMES[THEME_INDEX].accent;
static uint16_t C_ACCENT_DIM = THEMES[THEME_INDEX].accentDim;
static uint16_t C_PINK       = THEMES[THEME_INDEX].pink;
static uint16_t C_PURPLE     = THEMES[THEME_INDEX].purple;
static uint16_t C_YELLOW     = THEMES[THEME_INDEX].yellow;
static uint16_t C_GREEN      = THEMES[THEME_INDEX].green;

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

static void drawTextAt(const char *text, int x, int y, uint8_t size,
                       uint16_t fg, uint16_t bg) {
  tft.setTextColor(fg, bg);
  tft.setTextSize(size);
  tft.setCursor(x, y);
  tft.print(text);
}

static void drawTextCentered(const char *text, Rect r, uint8_t size,
                             uint16_t fg, uint16_t bg) {
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setTextSize(size);
  tft.getTextBounds(text, 0, 0, &bx, &by, &bw, &bh);
  drawTextAt(text, r.x + (r.w - static_cast<int>(bw)) / 2,
             r.y + (r.h - static_cast<int>(bh)) / 2, size, fg, bg);
}

static void drawTextRight(const char *text, int right, int y, uint8_t size,
                          uint16_t fg, uint16_t bg) {
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setTextSize(size);
  tft.getTextBounds(text, 0, 0, &bx, &by, &bw, &bh);
  drawTextAt(text, right - static_cast<int>(bw), y, size, fg, bg);
}

// Greedy word wrap, the same one the deck uses. `maxLines` is a hard stop: a
// title longer than its card is cut, not run over the progress bar.
static void drawWrappedText(const char *text, int x, int y, int width,
                            uint8_t size, uint16_t fg, uint16_t bg,
                            int maxLines) {
  const int lineHeight = 8 * size + 3;
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
    tft.setTextSize(size);
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

static void publishNowPlaying(const NowPlaying &n) {
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
  tft.fillRect(18, 184, 284, 10, C_TILE);
  drawTextAt(typed, 18, 184, 1, C_TEXT, C_TILE);
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

  // The phone is the intended way in, so it leads. The serial wizard below is
  // the fallback, not the instruction.
  drawTextAt("on the phone, no laptop", 12, 40, 1, C_DIM, C_BG);
  drawTextAt("1  join WiFi \"Spotify Deck\"", 12, 56, 1, C_TEXT, C_BG);
  drawTextAt("2  open http://192.168.4.1", 12, 70, 1, C_TEXT, C_BG);
  drawTextAt("3  fill in the page", 12, 84, 1, C_TEXT, C_BG);

  tft.fillRoundRect(8, 102, 304, 96, CORNER_CARD, C_TILE);
  tft.drawRoundRect(8, 102, 304, 96, CORNER_CARD, C_EDGE);

  char step[28];
  snprintf(step, sizeof(step), "OR SERIAL  %u/%u",
           static_cast<unsigned>(setupStep + 1),
           static_cast<unsigned>(SETUP_STEPS));
  drawTextAt(step, 18, 110, 1, C_DIM, C_TILE);
  drawWrappedText(SETUP_SHORT[setupStep], 18, 128, 284, 1, C_TEXT, C_TILE, 2);

  drawTypedLine();

  // Both ways to reach the page: the access point's fixed address, and the home
  // network address the phone needs after it switches back to its own WiFi.
  drawTextAt("192.168.4.1", 12, 214, 1, C_DIM, C_BG);
  if (WiFi.status() == WL_CONNECTED) {
    const String ip = WiFi.localIP().toString();
    drawTextRight(ip.c_str(), SCREEN_W - 12, 214, 1, C_DIM, C_BG);
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
  Serial.println("\n[setup] Spotify Deck needs WiFi and a Spotify account.");
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
//   GET  /auth     302 to Spotify with a fresh S256 challenge (app=1: app scheme)
//   POST /code     exchange the pasted code, store the token, reboot
//   GET  /code     the same exchange for the app, which hands its code back
//                  through the phone's browser instead of opening a socket
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
static WiFiClientSecure webTls;  // not the poller's client: another task owns that
static bool webSetupActive = false;
static String pkceVerifier;
static uint32_t setupRebootMs = 0;

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

// `fields` is a form body or a query string; the two have the same shape, and
// the app path arrives as a query string because the phone's browser makes the
// request, not the app.
static void handleWebCode(WiFiClient &client, const String &fields,
                          bool appRedirect) {
  char pasted[1024];
  if (!formField(fields, "code", pasted, sizeof(pasted)) || pasted[0] == '\0') {
    sendMessagePage(client, "Nothing pasted",
                    "Paste the whole address the browser landed on, or just its "
                    "code= value.",
                    true);
    return;
  }
  if (WiFi.status() != WL_CONNECTED) {
    sendMessagePage(client, "Not on WiFi yet",
                    "The deck has to be on your network before it can exchange "
                    "the code. Wait for the sign-in page to say connected.",
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
    sendMessagePage(client, "That is not a code",
                    "It should look like AQ... and be hundreds of characters "
                    "long.",
                    true);
    return;
  }

  String detail;
  const char *redirectUri =
      appRedirect ? OAUTH_REDIRECT_APP : OAUTH_REDIRECT_PASTE;
  if (!exchangeCode(code, redirectUri, detail)) {
    sendMessagePage(client, "Spotify said no", detail, true);
    return;
  }
  // One code, one verifier: a second paste must start a fresh challenge rather
  // than reuse the pair that already worked.
  pkceVerifier = "";
  clearVerifier();
  sendDonePage(client);
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

static void handleWebAuth(WiFiClient &client, bool appRedirect) {
  if (creds.clientId[0] == '\0') {
    sendMessagePage(client, "No client id",
                    "Save the network and the client id on the first page first.",
                    true);
    return;
  }
  pkceVerifier = makeVerifier();
  // Stored before the redirect: everything after this point happens on the
  // phone, out of the deck's control.
  saveVerifier(pkceVerifier);
  const String challenge = pkceChallenge(pkceVerifier);
  if (challenge.length() == 0) {
    sendMessagePage(client, "No challenge",
                    "The SHA-256 behind the PKCE challenge could not be built.",
                    true);
    return;
  }

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

  Serial.println("[setup] handing the phone to Spotify");
  client.print(F("HTTP/1.1 302 Found\r\nLocation: "));
  client.print(url);
  client.print(F("\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
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

  if (method == "POST" && path == "/save") {
    handleWebSave(client, body);
  } else if (method == "POST" && path == "/code") {
    handleWebCode(client, body, false);
  } else if (method == "GET" && path == "/code") {
    // A GET that changes state, deliberately: the app opens this in the phone's
    // browser rather than opening a socket of its own, which is what keeps the
    // APK free of the INTERNET permission. The code is single-use, so a replayed
    // load only earns a second `invalid_grant`.
    handleWebCode(client, query, appCode);
  } else if (method == "POST" && path == "/wipe") {
    handleWebWipe(client);
  } else if (method == "GET" && path == "/auth") {
    handleWebAuth(client, appRedirect);
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

// --------------------------------------------------------------- now page ---

static const Rect PREV_BTN = {8, 152, 96, 52};
static const Rect PLAY_BTN = {112, 152, 96, 52};
static const Rect NEXT_BTN = {216, 152, 96, 52};
static const Rect WORDMARK = {0, 0, 220, 32};
// Footer chip: hold it to open setup. 30 px tall and 112 wide, because a
// resistive panel needs a target a finger cannot miss at the screen's edge.
static const Rect SETUP_BTN = {200, 206, 112, 30};
static bool setupChipLit = false;

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
  const bool up = WiFi.status() == WL_CONNECTED;
  drawTextRight(up ? "WIFI" : "NO WIFI", SCREEN_W - 12, 13, 1,
                up ? C_DIM : C_PINK, C_BG);
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

  tft.fillRect(18, 112, 284, 6, C_TILE);
  tft.fillRoundRect(18, 112, 284, 6, 3, C_TILE_HI);
  if (dur > 0) {
    const int filled =
        static_cast<int>((static_cast<uint64_t>(pos) * 284ULL) / dur);
    if (filled > 0) tft.fillRoundRect(18, 112, filled, 6, 3, C_TEXT);
  }

  char elapsed[10];
  char total[10];
  formatMs(pos, elapsed, sizeof(elapsed));
  formatMs(dur, total, sizeof(total));
  drawTextAt(elapsed, 18, 121, 1, C_DIM, C_TILE);
  drawTextRight(total, 302, 121, 1, C_DIM, C_TILE);
}

static void drawNow() {
  screen = SCR_NOW;
  tft.fillScreen(C_BG);
  drawHeader();

  NowPlaying n;
  bool ready = false;
  readNowPlaying(n, ready);

  tft.fillRoundRect(8, 40, 304, 104, CORNER_CARD, C_TILE);
  tft.drawRoundRect(8, 40, 304, 104, CORNER_CARD, C_EDGE);

  const char *title = n.title[0] ? n.title
                                 : (ready ? "NOTHING PLAYING" : "CONNECTING...");
  drawWrappedText(title, 18, 50, 284, 2, C_TEXT, C_TILE, 2);
  drawTextAt(n.artist[0] ? n.artist : "waiting for Spotify", 18, 94, 1, C_DIM,
             C_TILE);
  drawProgress(n);

  drawChip(PREV_BTN, "|<<", 2, false);
  drawChip(PLAY_BTN, n.playing ? "PAUSE" : "PLAY", 2, true);
  drawChip(NEXT_BTN, ">>|", 2, false);

  tft.fillRect(0, 204, SCREEN_W, 36, C_BG);
  drawTextAt(n.device[0] ? n.device : "no output device", 12, 210, 1, C_DIM,
             C_BG);
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

// ------------------------------------------------------------- calibration ---

static void drawCrosshair(int x, int y) {
  tft.fillCircle(x, y, 6, C_TEXT);
  tft.drawCircle(x, y, 12, C_EDGE);
  tft.drawCircle(x, y, 20, C_EDGE);
}

static void drawCalibrationScreen() {
  screen = SCR_CAL;
  tft.fillScreen(C_BG);
  drawTextAt("CALIBRATE", 12, 10, 2, C_TEXT, C_BG);
  drawTextRight("TOUCH", SCREEN_W - 12, 13, 1, C_DIM, C_BG);
  tft.drawFastHLine(0, 31, SCREEN_W, C_EDGE);
  drawTextAt("tap the dot", 12, 40, 1, C_DIM, C_BG);
  drawTextAt("hold 2 s to cancel", 12, 54, 1, C_DIM, C_BG);

  drawCrosshair(CAL_TARGET_X[calIndex], CAL_TARGET_Y[calIndex]);
  char step[16];
  snprintf(step, sizeof(step), "%u / 3", static_cast<unsigned>(calIndex + 1));
  drawTextAt(step, 12, 214, 1, C_DIM, C_BG);
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
  tft.fillScreen(C_BG);

  touch.begin(SPI);

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

  if (!credsComplete()) {
    startSetupWizard();
    return;
  }

  Serial.printf("[id] Spotify Deck, esp32 // %08lx\n",
                static_cast<unsigned long>(ESP.getEfuseMac()));
  drawNow();
  ensureNetTask();
}

void loop() {
  const uint32_t now = millis();

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
    if (inside(x, y, PREV_BTN)) {
      pressedTarget = TARGET_PREV;
    } else if (inside(x, y, PLAY_BTN)) {
      pressedTarget = TARGET_TOGGLE;
    } else if (inside(x, y, NEXT_BTN)) {
      pressedTarget = TARGET_NEXT;
    } else if (inside(x, y, SETUP_BTN)) {
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

  // Release fires the command, so a hold is never mistaken for a tap.
  if (!down && wasTouched && pressedTarget != TARGET_NONE) {
    if (pressedTarget == TARGET_SETUP) {
      // Released too early: nothing started, so just put the chip back.
      setupChipLit = false;
      drawSetupChip(false);
    } else if (now - lastActionMs > 180) {
      lastActionMs = now;
      if (pressedTarget == TARGET_PREV) {
        queueCommand(CMD_PREV);
      } else if (pressedTarget == TARGET_TOGGLE) {
        queueCommand(CMD_TOGGLE);
      } else if (pressedTarget == TARGET_NEXT) {
        queueCommand(CMD_NEXT);
      }
    }
    pressedTarget = TARGET_NONE;
  }

  wasTouched = down;
  delay(10);
}
