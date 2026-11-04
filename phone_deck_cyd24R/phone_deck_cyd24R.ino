/*
 * Phone Deck - ESP32-2432S024R (2.4" resistive touch CYD)
 *
 * A desk dashboard for your phone: clock, battery and notifications, drawn from
 * the fixed colour codes in the palette block below.
 *
 * Where the data comes from:
 *   - Notifications, time and battery: the "Phone Link" service below, which
 *     the companion app writes. Android has no equivalent of Apple's
 *     Notification Center Service, and a BLE peripheral cannot read a phone's
 *     clock, battery or notifications by itself, so the phone pushes all three
 *     and the deck draws what it receives.
 *   - ANCS is the iOS path and it is off: ENABLE_ANCS = false. With it true,
 *     the deck advertises Apple's ANCS solicitation instead, the iPhone pairs
 *     and pushes notifications, and the deck asks for the app / title / message
 *     / date of each one; battery then comes from the phone's Battery Service
 *     when it exposes one (iOS does not), and time from the Current Time
 *     Service or the notification's own date. Android ignores the solicitation.
 *
 * Libraries needed (only these three):
 *   - Adafruit GFX Library
 *   - Adafruit ILI9341
 *   - XPT2046_Touchscreen (Paul Stoffregen)
 *
 * ESP32 Arduino core 3.x is required: this sketch uses the core's own
 * Bluedroid BLE library (BLEDevice / BLEServer / BLEClient), not ArduinoBLE.
 * Do not install ArduinoBLE - its BLEDevice.h shadows the core header and the
 * build dies with a wall of errors.
 *
 * Controls:
 *   - Tap a notification for the full text, then DISMISS or BACK.
 *   - Hold a notification to dismiss it on the phone.
 *   - Tap the gear (top right) for brightness and calibration.
 *   - Hold the gear for 1.2 s to forget the phone this board is bound to.
 *   - Hold the title for 1.5 s to calibrate. Booting never opens it.
 *
 * Every board is its own widget: the advertised name carries a tag read from
 * the chip's efuse MAC, and the first phone to connect claims the board. Other
 * phones are refused until the gear is held, so one deck answers to one phone.
 */

#include <Arduino.h>
#include <SPI.h>
#include <Preferences.h>
#include <ctype.h>
#include <math.h>
#include <string.h>
#include <time.h>

#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include <XPT2046_Touchscreen.h>

// Fail with one readable error instead of hundreds of BLE header errors.
#if !__has_include(<BLEDevice.h>)
#error "This sketch needs the ESP32 Arduino core 3.x BLE library (BLEDevice.h). Install the esp32 board package from Espressif."
#endif

#include <BLEDevice.h>
#if defined(_BLE_DEVICE_H_)  // include guard of ArduinoBLE's BLEDevice.h
#error "The ArduinoBLE library is installed and is hiding the ESP32 core BLE headers. Remove ArduinoBLE in the Library Manager, then compile again."
#endif

#include <BLEAddress.h>
#include <BLEClient.h>
#include <BLEUtils.h>
#include <BLESecurity.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <esp_gap_ble_api.h>
#include <esp_gatts_api.h>

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
// Two themes, and nothing in between: 0 PAPER is black ink on an off-white page,
// 1 INK is the same page inverted. One ink, one ground, and the last six slots
// step that ink so the same draw calls still say "this one, not that one" without
// hue. A new widget starts from these two; add a theme only when a screen cannot
// be read in mono at all.
//
// The first six are ground, panel, lifted panel, hairline, ink and dim ink. The
// last six keep their jobs and lose their colours: accent for the link, the
// status line and a charging battery, accentDim for that colour in shadow, pink
// for the one action and a battery under 30%, purple for the section dot, yellow
// for the unread count, green for a battery at 30% or more. With no hue to spend,
// the three battery states separate by luminance instead: charging at the
// strongest contrast against the ground, a battery under 30% next, 30% and up
// quieter. batteryColor() therefore still reads as three states in greyscale.
//
// Each entry is written 0x5655 /* #rrggbb */: the panel takes the RGB565 value
// and the comment keeps the same colour in hex, so a palette can be read,
// compared against a design or carried into another sketch without decoding
// 565 by hand. The value is what the panel shows; the two must agree.

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
// The palette is a setting now, not a build flag. The companion app writes it
// over the link and the board keeps it in NVS, so a deck follows its phone
// without a re-flash; 1 INK is the default because a dark room is the usual one.
static constexpr uint8_t THEME_DEFAULT = 1;  // INK
static uint8_t themeIndex = THEME_DEFAULT;

// The twelve names the drawing code already uses. Plain variables rather than
// constexpr, so a write from the phone can reassign them without touching a
// draw call; applyTheme() is the only place that does.
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

// One palette into the twelve names, and the only place they all change at
// once: a half-applied theme is a screen drawn in two greys. Defined beside the
// settings below, because the generated prototypes land before the first
// function in this file and every type they mention must already be above that.
static void applyTheme(uint8_t index);

static constexpr uint8_t CORNER_CARD = 10;
static constexpr uint8_t CORNER_BUTTON = 8;
static constexpr uint8_t CORNER_PANEL = 10;

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

// The front page comes first: everything else is opened from it.
enum Screen : uint8_t { SCR_HOME, SCR_BOARD, SCR_DETAIL, SCR_NOW, SCR_SETTINGS,
                       SCR_CAL };

// Where the wall clock came from. A phone-supplied time beats a notification
// date, because the date is only as fresh as the last notification.
enum ClockSource : uint8_t { CLOCK_NONE, CLOCK_NOTICE, CLOCK_PHONE };

static constexpr int NOTICE_MAX = 6;
static constexpr size_t NOTICE_APP = 20;
static constexpr size_t NOTICE_TITLE = 44;
static constexpr size_t NOTICE_BODY = 84;

struct Notice {
  uint32_t uid;
  uint8_t category;
  bool unread;
  char app[NOTICE_APP];
  char title[NOTICE_TITLE];
  char body[NOTICE_BODY];
  time_t at;  // 0 when the phone did not say
};

// What the phone is playing, mirrored. The deck never talks to Spotify: the
// companion app reads the phone's own media session and writes one line down the
// link, so there is no client id, no browser and no token anywhere here. The
// buffers match what NowPacket.java caps its fields to.
static constexpr size_t NOW_TITLE = 48;
static constexpr size_t NOW_ARTIST = 32;
static constexpr size_t NOW_ALBUM = 32;

struct NowTrack {
  bool valid;         // a line has arrived since the link came up
  bool playing;
  uint32_t position;  // seconds, at the moment the line arrived
  uint32_t duration;  // seconds, 0 when the phone did not say
  uint32_t at;        // millis() when it arrived
  char title[NOW_TITLE];
  char artist[NOW_ARTIST];
  char album[NOW_ALBUM];
};

enum EventType : uint8_t {
  EVT_LINK_ON,
  EVT_LINK_OFF,
  EVT_NOTICE,
  EVT_NOTICE_GONE,
  EVT_BATTERY,
  EVT_TIME,
  EVT_NOW,
  EVT_THEME,
};

struct UiEvent {
  uint8_t type;
  Notice notice;
  NowTrack now;
  uint32_t uid;
  int16_t battery;
  uint8_t charging;
  time_t epoch;
  uint8_t theme;
};

// ----------------------------------------------------------- calibration ---

TouchMapping mapping;
Preferences calPrefs;

// Bumped whenever a stored touch fit should stop being trusted. A mismatch
// makes loadMapping() fall back to the factory default and re-opens the
// calibration screen, which a panel whose taps land wrong needs.
static constexpr uint8_t CAL_MAP_VERSION = 2;
bool mappingStored = false;

Screen screen = SCR_BOARD;
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

// --------------------------------------------------------------- settings ---

static constexpr int BL_CHANNEL = 7;
static constexpr uint8_t BRI_MIN = 10;
static constexpr uint8_t BRI_MAX = 100;
static constexpr uint8_t BRI_STEP = 10;

uint8_t brightness = 80;
Preferences settingsPrefs;

// PWM on the backlight pin. Cores 3.x use the pin-based LEDC API.
static void writeBacklightDuty(uint32_t duty) {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(PIN_TFT_BL, duty);
#else
  ledcWrite(BL_CHANNEL, duty);
#endif
}

// The only place a duty value is computed. "The number moves but the panel does
// not" is either a write that never reached the channel or a value that never
// moved, so both the duty and the channel's read-back are logged.
static uint32_t brightnessDuty() {
  return static_cast<uint32_t>(brightness) * 255 / 100;
}

static void applyBrightness() {
  const uint32_t duty = brightnessDuty();
  writeBacklightDuty(duty);
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  Serial.printf("[bl] %u%% -> duty %u, channel reads %u\n",
                static_cast<unsigned>(brightness), static_cast<unsigned>(duty),
                static_cast<unsigned>(ledcRead(PIN_TFT_BL)));
#else
  Serial.printf("[bl] %u%% -> duty %u\n", static_cast<unsigned>(brightness),
                static_cast<unsigned>(duty));
#endif
}

static void initBacklight() {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  // A silent failure here leaves the pin wherever the last boot put it, and the
  // panel then answers the setting with nothing at all.
  bool attached = ledcAttach(PIN_TFT_BL, 5000, 8);
  if (!attached) {
    ledcDetach(PIN_TFT_BL);
    attached = ledcAttach(PIN_TFT_BL, 5000, 8);
    Serial.printf("[bl] re-attached PWM on gpio %d: %s\n", PIN_TFT_BL,
                  attached ? "ok" : "FAILED");
  }
  if (!attached) Serial.println("[bl] PWM attach failed: backlight cannot dim");
#else
  ledcSetup(BL_CHANNEL, 5000, 8);
  ledcAttachPin(PIN_TFT_BL, BL_CHANNEL);
#endif
  applyBrightness();
}

// Drive every channel HIGH: same potential as the 3.3 V side of the LED, so no
// current flows. Channels left floating are what makes the board glow cyan.
static void disableOnboardLed() {
  const int channels[3] = {PIN_LED_R, PIN_LED_G, PIN_LED_B};
  for (int i = 0; i < 3; ++i) {
    pinMode(channels[i], OUTPUT);
    digitalWrite(channels[i], HIGH);
  }
}

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

static void loadSettings() {
  settingsPrefs.begin("settings", true);
  brightness = settingsPrefs.getUChar("bri", 80);
  const uint8_t saved = settingsPrefs.getUChar("theme", THEME_DEFAULT);
  settingsPrefs.end();
  brightness = constrain(brightness, BRI_MIN, BRI_MAX);
  // Clamped on the way in, so a corrupt byte cannot leave the deck with a
  // palette that does not exist and no way back to one that does.
  applyTheme(saved);
}

static void saveSettings() {
  settingsPrefs.begin("settings", false);
  settingsPrefs.putUChar("bri", brightness);
  settingsPrefs.putUChar("theme", themeIndex);
  settingsPrefs.end();
}

// ------------------------------------------------------------------ clock ---

// Wall-clock seconds, no timezone: the phone sends local time and we simply
// count forward from it. Sources, best last: nothing, a notification date, a
// phone-supplied time (Current Time Service or the Phone Link characteristic).
static time_t clockBase = 0;      // wall clock at clockSetMs
static uint32_t clockSetMs = 0;
static bool clockValid = false;
static ClockSource clockSource = CLOCK_NONE;

// Days-from-civil (Howard Hinnant): converts Y/M/D to a day count without a TZ.
static long daysFromCivil(int y, int mo, int d) {
  y -= mo <= 2;
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(y - era * 400);
  const unsigned doy =
      static_cast<unsigned>((153 * (mo + (mo > 2 ? -3 : 9)) + 2) / 5 + d - 1);
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return static_cast<long>(era) * 146097L + static_cast<long>(doe) - 719468L;
}

static time_t epochFromParts(int year, int month, int day, int hour, int minute,
                             int second) {
  if (year < 2000 || year > 2099 || month < 1 || month > 12 || day < 1 ||
      day > 31) {
    return 0;
  }
  const long days = daysFromCivil(year, month, day);
  return static_cast<time_t>(days) * 86400 + hour * 3600 + minute * 60 + second;
}

static time_t clockNow() {
  if (!clockValid) return 0;
  return clockBase + static_cast<time_t>((millis() - clockSetMs) / 1000);
}

static void setClock(time_t epoch, ClockSource source) {
  if (epoch <= 0) return;
  // A notification date is local wall-clock time and can be slightly stale;
  // it never overrides a phone-supplied clock.
  if (clockValid && clockSource == CLOCK_PHONE && source == CLOCK_NOTICE) return;
  clockBase = epoch;
  clockSetMs = millis();
  clockValid = true;
  clockSource = source;
}

// ANCS sends the notification date as "yyyyMMdd'T'HHmmSS" in local time.
static time_t parseAncsDate(const char *text, size_t len) {
  if (text == nullptr || len < 15) return 0;
  char buf[16];
  memcpy(buf, text, 15);
  buf[15] = '\0';
  if (buf[8] != 'T') return 0;
  const int year = atoi(buf);
  const int month = atoi(buf + 4);
  const int day = atoi(buf + 6);
  const int hour = atoi(buf + 9);
  const int minute = atoi(buf + 11);
  const int second = atoi(buf + 13);
  return epochFromParts(year, month, day, hour, minute, second);
}

// ----------------------------------------------------------- notifications ---

Notice notices[NOTICE_MAX];
int noticeCount = 0;

// One track, not a history: the phone rewrites this whenever the song or the
// state changes, and a stop clears it. Millis are used rather than a clock so the
// progress bar keeps moving between writes without asking the phone again.
static NowTrack nowTrack;

static uint32_t nowPosition() {
  if (!nowTrack.valid) return 0;
  uint32_t seconds = nowTrack.position;
  if (nowTrack.playing) seconds += (millis() - nowTrack.at) / 1000;
  if (nowTrack.duration > 0 && seconds > nowTrack.duration) seconds = nowTrack.duration;
  return seconds;
}

// m:ss, which is what every player prints. No hours: a mirrored track will not
// reach one, and the deck has the room for a time, not a lecture.
static void formatClock(uint32_t seconds, char *out, size_t outLen) {
  snprintf(out, outLen, "%u:%02u", static_cast<unsigned>(seconds / 60),
           static_cast<unsigned>(seconds % 60));
}

static const char *CATEGORY_LABEL[13] = {
    "OTHER", "CALL",   "MISSED", "VOICEMAIL", "SOCIAL", "SCHEDULE", "EMAIL",
    "NEWS",  "HEALTH", "FINANCE", "LOCATION", "MEDIA", "OTHER"};

static bool containsCI(const char *haystack, const char *needle) {
  if (*needle == '\0') return true;
  for (const char *h = haystack; *h; ++h) {
    const char *a = h;
    const char *b = needle;
    while (*a && *b && tolower(static_cast<unsigned char>(*a)) ==
                            tolower(static_cast<unsigned char>(*b))) {
      ++a;
      ++b;
    }
    if (*b == '\0') return true;
  }
  return false;
}

// ANCS reports a bundle id ("com.apple.MobileSMS"). Turn the common ones into
// something readable, and fall back to the last dotted component.
static void friendlyApp(const char *bundle, char *out, size_t outLen) {
  struct Alias {
    const char *needle;
    const char *label;
  };
  static const Alias ALIASES[] = {
      {"mobilesms", "MESSAGES"},   {"mobilemail", "MAIL"},
      {"gmail", "GMAIL"},          {"whatsapp", "WHATSAPP"},
      {"instagram", "INSTAGRAM"},  {"messenger", "MESSENGER"},
      {"slack", "SLACK"},          {"discord", "DISCORD"},
      {"spotify", "SPOTIFY"},      {"telegram", "TELEGRAM"},
      {"calendar", "CALENDAR"},    {"phone", "PHONE"},
      {"facetime", "FACETIME"},    {"teams", "TEAMS"},
      {"outlook", "OUTLOOK"},      {"youtube", "YOUTUBE"},
      {"tiktok", "TIKTOK"},        {"signal", "SIGNAL"},
  };
  if (bundle == nullptr || bundle[0] == '\0') {
    snprintf(out, outLen, "PHONE");
    return;
  }
  for (size_t i = 0; i < sizeof(ALIASES) / sizeof(ALIASES[0]); ++i) {
    if (containsCI(bundle, ALIASES[i].needle)) {
      snprintf(out, outLen, "%s", ALIASES[i].label);
      return;
    }
  }
  const char *dot = strrchr(bundle, '.');
  const char *base = dot != nullptr ? dot + 1 : bundle;
  snprintf(out, outLen, "%s", base);
  for (char *p = out; *p; ++p) *p = static_cast<char>(toupper(*p));
}

static int countUnread() {
  int n = 0;
  for (int i = 0; i < noticeCount; ++i) {
    if (notices[i].unread) ++n;
  }
  return n;
}

static int findNotice(uint32_t uid) {
  for (int i = 0; i < noticeCount; ++i) {
    if (notices[i].uid == uid && uid != 0) return i;
  }
  return -1;
}

static void dropNotice(int index) {
  if (index < 0 || index >= noticeCount) return;
  for (int i = index; i < noticeCount - 1; ++i) notices[i] = notices[i + 1];
  --noticeCount;
}

static void insertNotice(const Notice &n) {
  const int existing = findNotice(n.uid);
  if (existing >= 0) {
    notices[existing] = n;
    // Move it back to the top: the newest activity is the first row.
    Notice copy = notices[existing];
    for (int i = existing; i > 0; --i) notices[i] = notices[i - 1];
    notices[0] = copy;
    return;
  }
  const int last = noticeCount < NOTICE_MAX ? noticeCount : NOTICE_MAX - 1;
  for (int i = last; i > 0; --i) notices[i] = notices[i - 1];
  notices[0] = n;
  if (noticeCount < NOTICE_MAX) ++noticeCount;
}

// ---------------------------------------------------------- UI event queue ---

// BLE callbacks run on the Bluedroid task, drawing runs in loop(). Everything
// the radio produces is handed over through this queue so the display is only
// ever touched from one task.
static QueueHandle_t uiQueue = nullptr;
static QueueHandle_t ancsCmdQueue = nullptr;

static void pushEvent(const UiEvent &ev) {
  if (uiQueue == nullptr) return;
  xQueueSend(uiQueue, &ev, 0);
}

static void pushLinkEvent(bool linked) {
  UiEvent ev = {};
  ev.type = linked ? EVT_LINK_ON : EVT_LINK_OFF;
  pushEvent(ev);
}

static void pushBattery(int level, bool charging) {
  UiEvent ev = {};
  ev.type = EVT_BATTERY;
  ev.battery = static_cast<int16_t>(level);
  ev.charging = charging ? 1 : 0;
  pushEvent(ev);
}

static void pushTime(time_t epoch) {
  UiEvent ev = {};
  ev.type = EVT_TIME;
  ev.epoch = epoch;
  pushEvent(ev);
}

// -------------------------------------------------------------- BLE state ---

// --------------------------------------------------------------- identity ---
// Each board is a distinct widget. The tag is the low 24 bits of the chip's
// efuse MAC, which is unique per module and cannot be cloned by re-flashing, so
// two decks running this sketch never advertise the same name.
//
// The first phone to connect claims the board: its address is written to NVS
// and the gear, held for 1.2 s on the board screen, clears that record. It is a
// record, not a gate - see rememberPeer().

Preferences identityPrefs;
char DEVICE_TAG[7] = "000000";
char DEVICE_NAME[32] = "Phone Deck";
bool ownerKnown = false;
uint8_t ownerBda[6] = {0, 0, 0, 0, 0, 0};
char ownerLabel[18] = "unbound";

// True while a phone holds the link and may write. One central connects at a
// time, so this follows the connection rather than a stored address.
static volatile bool peerAllowed = false;

static void bdaToText(const uint8_t bda[6], char *out, size_t outLen) {
  snprintf(out, outLen, "%02X:%02X:%02X:%02X:%02X:%02X", bda[5], bda[4], bda[3],
           bda[2], bda[1], bda[0]);
}

static void initIdentity() {
  const uint64_t mac = ESP.getEfuseMac();
  snprintf(DEVICE_TAG, sizeof(DEVICE_TAG), "%06X",
           static_cast<unsigned>(mac & 0xFFFFFF));
  snprintf(DEVICE_NAME, sizeof(DEVICE_NAME), "Phone Deck %s", DEVICE_TAG);

  identityPrefs.begin("identity", true);
  if (identityPrefs.getBytesLength("owner") == sizeof(ownerBda) &&
      identityPrefs.getBytes("owner", ownerBda, sizeof(ownerBda)) == sizeof(ownerBda)) {
    ownerKnown = true;
    bdaToText(ownerBda, ownerLabel, sizeof(ownerLabel));
  }
  identityPrefs.end();

  Serial.printf("[id] %s  owner %s\n", DEVICE_NAME, ownerKnown ? ownerLabel : "unbound");
}

// Records who is driving the deck. Deliberately not a gate: one central can hold
// the link at a time, and an Android central's resolvable private address rotates,
// so a stored address must never be able to lock the owner's own phone out. That
// is what left a real board saying OFFLINE while a phone was connected.
// The identity document: the same keys a network deck answers /deck.json with,
// minus the network fields a Bluetooth deck has none of (ip, setup, signed). It
// is written once at boot into a read-only characteristic. Nothing here is a
// secret - no token, no password - so a phone can ask any nearby board who it is
// before it links, and the app keys its records by `id` instead of by address:
// a re-flash keeps the row, a second board of the same widget is a second row,
// and the MAC stays what it is, the address that connection happens over.
//
// It is longer than the 20 bytes a default ATT MTU carries, so a reader has to
// ask for a larger MTU first; a truncated read names no widget, and the app then
// falls back to the address rather than inventing an id.
static void buildDeckIdentity(char *out, size_t outLen) {
  snprintf(out, outLen,
           "{\"id\":\"phone_deck_cyd24R-%s\",\"widget\":\"phone_deck_cyd24R\","
           "\"tag\":\"%s\",\"name\":\"Phone Deck\",\"role\":\"phone\","
           "\"board\":\"2432S024R\",\"link\":\"ble\"}",
           DEVICE_TAG, DEVICE_TAG);
}

static void rememberPeer(const uint8_t bda[6]) {
  if (ownerKnown && memcmp(ownerBda, bda, sizeof(ownerBda)) == 0) return;
  memcpy(ownerBda, bda, sizeof(ownerBda));
  ownerKnown = true;
  bdaToText(ownerBda, ownerLabel, sizeof(ownerLabel));
  identityPrefs.begin("identity", false);
  identityPrefs.putBytes("owner", ownerBda, sizeof(ownerBda));
  identityPrefs.end();
  Serial.printf("[ble] link held by %s\n", ownerLabel);
}

static void releaseOwner() {
  ownerKnown = false;
  memset(ownerBda, 0, sizeof(ownerBda));
  snprintf(ownerLabel, sizeof(ownerLabel), "unbound");
  identityPrefs.begin("identity", false);
  identityPrefs.remove("owner");
  identityPrefs.end();
  Serial.println("[id] binding cleared - the next phone to connect wins");
}

// ANCS only exists on iOS. Android has no equivalent service, so the deck gets
// battery, clock and notifications from the Phone Link GATT service instead,
// written by a companion app. With this true the deck advertises Apple's ANCS
// solicitation and dials back to the phone, which Android ignores. The HID
// advertising that once lived here made Android try to use the deck as a
// keyboard and drop the link, so do not revive it. Set true only to test the
// iPhone path.
static constexpr bool ENABLE_ANCS = false;

static BLEUUID UUID_ANCS_SERVICE("7905f431-b5ce-4e99-a40f-4b1e122d00d0");
static BLEUUID UUID_ANCS_NOTIFY("9fbf120d-6301-42d9-8c58-25e699a21dbd");
static BLEUUID UUID_ANCS_CONTROL("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9");
static BLEUUID UUID_ANCS_DATA("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb");

// "Phone Link": anything on the phone can push battery, clock and notification
// lines without speaking ANCS. iOS has no such hook, but Android helpers,
// laptops and test scripts do.
static BLEUUID UUID_LINK_SERVICE("6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10");
static BLEUUID UUID_LINK_TIME("6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10");
static BLEUUID UUID_LINK_BATTERY("6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10");
static BLEUUID UUID_LINK_NOTICE("6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10");

// The identity document, read-only. A network deck serves the same keys at
// GET /deck.json, so one reader in the app understands either kind of deck.
static BLEUUID UUID_LINK_IDENTITY("6f2d0005-9a3c-4b57-9d21-6a1e0c4f8b10");

// What the phone is playing. One write, one line, display only: the deck has no
// way to ask Spotify anything by itself, so nothing here is a transport button.
static BLEUUID UUID_LINK_NOW("6f2d0006-9a3c-4b57-9d21-6a1e0c4f8b10");
// The palette, one byte, read and written by the companion app.
static BLEUUID UUID_LINK_THEME("6f2d0008-9a3c-4b57-9d21-6a1e0c4f8b10");

// Apple's ANCS UUID in little-endian byte order, used as the 128-bit service
// solicitation in the advertising packet. This is what makes an iPhone offer
// to pair with the deck.
static const uint8_t ANCS_SOLICITATION_LE[16] = {
    0xD0, 0x00, 0x2D, 0x12, 0x1E, 0x4B, 0x0F, 0xA4,
    0x99, 0x4E, 0xCE, 0xB5, 0x31, 0xF4, 0x05, 0x79};

static volatile bool ancsReady = false;       // ANCS discovered and subscribed
static volatile bool serverConnected = false; // a phone holds the server link
static volatile bool ancsTaskRunning = false;
static volatile bool linkDirty = false;

static bool phoneLinked() {
  return ENABLE_ANCS ? ancsReady : serverConnected;
}

static BLERemoteCharacteristic *g_controlPoint = nullptr;  // valid while ancsReady

// Attribute fetch, all touched from the Bluedroid callback task only.
static constexpr int REQUEST_QUEUE = 6;
struct PendingRequest {
  uint32_t uid;
  uint8_t flags;
  uint8_t category;
};

static PendingRequest reqQueue[REQUEST_QUEUE];
static uint8_t reqHead = 0;
static uint8_t reqTail = 0;
static uint8_t reqCount = 0;

struct PendingNotice {
  uint32_t uid;
  uint8_t category;
  char app[NOTICE_APP];
  char title[NOTICE_TITLE];
  char body[NOTICE_BODY];
  char date[16];
  bool haveDate;
};

static PendingNotice pending;
static volatile bool requestInFlight = false;
static volatile uint32_t requestStartMs = 0;
static uint8_t dsBuffer[600];
static size_t dsLen = 0;

static void requestNext();

static void enqueueRequest(uint32_t uid, uint8_t flags, uint8_t category) {
  if (reqCount >= REQUEST_QUEUE) {
    // Nothing sane to do with a burst this large; keep the newest events.
    reqHead = static_cast<uint8_t>((reqHead + 1) % REQUEST_QUEUE);
    --reqCount;
  }
  reqQueue[reqTail].uid = uid;
  reqQueue[reqTail].flags = flags;
  reqQueue[reqTail].category = category;
  reqTail = static_cast<uint8_t>((reqTail + 1) % REQUEST_QUEUE);
  ++reqCount;
  requestNext();
}

static void completePending() {
  if (pending.uid == 0) return;
  UiEvent ev = {};
  ev.type = EVT_NOTICE;
  ev.notice.uid = pending.uid;
  ev.notice.category = pending.category;
  ev.notice.unread = true;
  friendlyApp(pending.app, ev.notice.app, sizeof(ev.notice.app));
  const char *title = pending.title[0] != '\0'
                          ? pending.title
                          : (pending.category < 13 ? CATEGORY_LABEL[pending.category]
                                                   : "NOTIFICATION");
  snprintf(ev.notice.title, sizeof(ev.notice.title), "%s", title);
  snprintf(ev.notice.body, sizeof(ev.notice.body), "%s", pending.body);
  ev.notice.at = pending.haveDate ? parseAncsDate(pending.date, strlen(pending.date)) : 0;
  pushEvent(ev);

  pending = PendingNotice();
  requestInFlight = false;
  requestNext();
}

static void storeAttribute(uint32_t uid, uint8_t attribute, const char *data,
                           size_t len) {
  if (pending.uid != uid) return;
  switch (attribute) {
    case 0x00:  // AppIdentifier
      snprintf(pending.app, sizeof(pending.app), "%.*s", static_cast<int>(len), data);
      break;
    case 0x01:  // Title
      snprintf(pending.title, sizeof(pending.title), "%.*s", static_cast<int>(len), data);
      break;
    case 0x03:  // Message
      snprintf(pending.body, sizeof(pending.body), "%.*s", static_cast<int>(len), data);
      break;
    case 0x05: {  // Date
      const size_t copy = len < sizeof(pending.date) - 1 ? len : sizeof(pending.date) - 1;
      memcpy(pending.date, data, copy);
      pending.date[copy] = '\0';
      pending.haveDate = true;
      break;
    }
    default:
      break;
  }
}

// A data-source response can be split across notifications; parse whole
// attribute tuples and keep the header plus any partial tail for the next one.
static void parseDataSource() {
  if (dsLen < 5) return;
  if (dsBuffer[0] != 0x00) {  // status reply to a control point command
    dsLen = 0;
    return;
  }
  const uint32_t uid = static_cast<uint32_t>(dsBuffer[1]) |
                       (static_cast<uint32_t>(dsBuffer[2]) << 8) |
                       (static_cast<uint32_t>(dsBuffer[3]) << 16) |
                       (static_cast<uint32_t>(dsBuffer[4]) << 24);
  size_t pos = 5;
  bool complete = false;
  while (pos + 3 <= dsLen) {
    const uint8_t attribute = dsBuffer[pos];
    const uint16_t len = static_cast<uint16_t>(dsBuffer[pos + 1]) |
                         (static_cast<uint16_t>(dsBuffer[pos + 2]) << 8);
    if (pos + 3 + len > dsLen) break;  // wait for the next fragment
    storeAttribute(uid, attribute, reinterpret_cast<const char *>(dsBuffer + pos + 3), len);
    pos += 3 + len;
    if (attribute == 0x05) complete = true;  // date is the last attribute asked for
  }

  if (complete) {
    completePending();
    dsLen = 0;
    return;
  }
  if (pos > 5) {
    memmove(dsBuffer + 5, dsBuffer + pos, dsLen - pos);
    dsLen = 5 + (dsLen - pos);
  }
}

static void onDataSourceNotify(BLERemoteCharacteristic *, uint8_t *data,
                               size_t length, bool) {
  if (length == 0 || length > sizeof(dsBuffer)) return;
  if (dsLen + length > sizeof(dsBuffer)) dsLen = 0;
  memcpy(dsBuffer + dsLen, data, length);
  dsLen += length;
  parseDataSource();
}

static void onNotificationSourceNotify(BLERemoteCharacteristic *, uint8_t *data,
                                       size_t length, bool) {
  if (length < 8) return;
  const uint8_t event = data[0];
  const uint8_t flags = data[1];
  const uint8_t category = data[2];
  const uint32_t uid = static_cast<uint32_t>(data[4]) |
                       (static_cast<uint32_t>(data[5]) << 8) |
                       (static_cast<uint32_t>(data[6]) << 16) |
                       (static_cast<uint32_t>(data[7]) << 24);

  if (event == 0 || event == 1) {  // added / modified
    enqueueRequest(uid, flags, category);
    return;
  }
  if (event == 2) {  // removed
    UiEvent ev = {};
    ev.type = EVT_NOTICE_GONE;
    ev.uid = uid;
    pushEvent(ev);
  }
}

static void requestNext() {
  if (requestInFlight || reqCount == 0 || g_controlPoint == nullptr || !ancsReady) {
    return;
  }
  const PendingRequest &req = reqQueue[reqHead];
  const uint32_t uid = req.uid;
  pending = PendingNotice();
  pending.uid = uid;
  dsLen = 0;  // never splice a new response onto an abandoned one
  pending.category = req.category;
  reqHead = static_cast<uint8_t>((reqHead + 1) % REQUEST_QUEUE);
  --reqCount;

  uint8_t cmd[20];
  size_t n = 0;
  cmd[n++] = 0x00;  // CommandIDGetNotificationAttributes
  cmd[n++] = static_cast<uint8_t>(uid & 0xFF);
  cmd[n++] = static_cast<uint8_t>((uid >> 8) & 0xFF);
  cmd[n++] = static_cast<uint8_t>((uid >> 16) & 0xFF);
  cmd[n++] = static_cast<uint8_t>((uid >> 24) & 0xFF);
  cmd[n++] = 0x00;  // AppIdentifier
  cmd[n++] = 0x01;  // Title, max 24 bytes
  cmd[n++] = 0x18;
  cmd[n++] = 0x00;
  cmd[n++] = 0x03;  // Message, max 64 bytes
  cmd[n++] = 0x40;
  cmd[n++] = 0x00;
  cmd[n++] = 0x05;  // Date

  requestInFlight = true;
  requestStartMs = millis();
  // writeValue returns bool on core 3.x and void on 2.x, so the result is not
  // inspected; a write that never answers is caught by the 3 s timeout.
  g_controlPoint->writeValue(cmd, n, true);
}

// Dismiss on the phone: ANCS "perform action", negative action id.
static void requestDismissOnPhone(uint32_t uid) {
  if (g_controlPoint == nullptr || !ancsReady) return;
  uint8_t cmd[6] = {0x02,
                    static_cast<uint8_t>(uid & 0xFF),
                    static_cast<uint8_t>((uid >> 8) & 0xFF),
                    static_cast<uint8_t>((uid >> 16) & 0xFF),
                    static_cast<uint8_t>((uid >> 24) & 0xFF),
                    0x01};
  g_controlPoint->writeValue(cmd, sizeof(cmd), true);
}

static void subscribe(BLERemoteCharacteristic *characteristic,
                      void (*callback)(BLERemoteCharacteristic *, uint8_t *,
                                       size_t, bool)) {
  if (characteristic == nullptr) return;
  characteristic->registerForNotify(callback);
  BLERemoteDescriptor *descriptor = characteristic->getDescriptor(BLEUUID((uint16_t)0x2902));
  if (descriptor != nullptr) {
    uint8_t notifyOn[2] = {0x01, 0x00};
    descriptor->writeValue(notifyOn, sizeof(notifyOn), true);
  }
}

static void readPhoneBattery(BLEClient *client, bool *found) {
  BLERemoteService *service = client->getService(BLEUUID((uint16_t)0x180F));
  if (service == nullptr) return;
  BLERemoteCharacteristic *level =
      service->getCharacteristic(BLEUUID((uint16_t)0x2A19));
  if (level == nullptr) return;
  // readValue() is a String on core 3.x and a std::string on 2.x; both offer
  // length(), c_str() and operator[], so let the compiler pick the type.
  const auto value = level->readValue();
  if (value.length() < 1) return;
  if (found != nullptr) *found = true;
  pushBattery(static_cast<uint8_t>(value[0]), false);
}

// Some phones (and all Android companions) expose the standard Current Time
// Service. iOS usually does not, which is why notification dates seed the
// clock as well.
static void readPhoneTime(BLEClient *client) {
  BLERemoteService *service = client->getService(BLEUUID((uint16_t)0x1805));
  if (service == nullptr) return;
  BLERemoteCharacteristic *exact =
      service->getCharacteristic(BLEUUID((uint16_t)0x2A2B));
  if (exact == nullptr) return;
  const auto value = exact->readValue();
  if (value.length() < 7) return;
  const unsigned char *b = reinterpret_cast<const unsigned char *>(value.c_str());
  const int year = b[0] | (b[1] << 8);
  const time_t epoch = epochFromParts(year, b[2], b[3], b[4], b[5], b[6]);
  if (epoch > 0) pushTime(epoch);
}

// Connects back to the phone that holds the server link and subscribes to
// ANCS. This mirrors how Apple accessories use the service: they advertise
// ANCS, the phone connects, and the accessory becomes the GATT client.
static void ancsClientTask(void *rawAddress) {
  BLEAddress peer = *static_cast<BLEAddress *>(rawAddress);
  delete static_cast<BLEAddress *>(rawAddress);

  // iOS publishes ANCS only once the bond exists and the link is encrypted,
  // which can happen a moment after the phone first connects, so the whole
  // discovery is retried instead of giving up on the first visit.
  BLEClient *client = nullptr;
  BLERemoteCharacteristic *source = nullptr;
  BLERemoteCharacteristic *control = nullptr;
  BLERemoteCharacteristic *data = nullptr;
  for (int attempt = 0; attempt < 3 && client == nullptr; ++attempt) {
    if (attempt > 0) vTaskDelay(pdMS_TO_TICKS(2000));
    BLEClient *candidate = BLEDevice::createClient();
    if (!candidate->connect(peer)) {
      Serial.println("[ancs] could not connect back to the phone");
      delete candidate;
      continue;
    }
    // getNative() hands back a pointer to a 6-byte array, and its type differs
  // between cores, so copy the address into a plain array first.
  uint8_t peerAddress[6];
  memcpy(peerAddress, reinterpret_cast<const void *>(peer.getNative()), sizeof(peerAddress));
  esp_ble_set_encryption(peerAddress, ESP_BLE_SEC_ENCRYPT);

    BLERemoteService *service = candidate->getService(UUID_ANCS_SERVICE);
    source = service != nullptr ? service->getCharacteristic(UUID_ANCS_NOTIFY) : nullptr;
    control = service != nullptr ? service->getCharacteristic(UUID_ANCS_CONTROL) : nullptr;
    data = service != nullptr ? service->getCharacteristic(UUID_ANCS_DATA) : nullptr;
    if (source != nullptr && control != nullptr && data != nullptr) {
      client = candidate;
      break;
    }
    Serial.println("[ancs] ANCS not exposed yet - is the deck paired?");
    candidate->disconnect();
    delete candidate;
    source = nullptr;
    control = nullptr;
    data = nullptr;
  }
  if (client == nullptr) {
    Serial.println("[ancs] phone never exposed ANCS - pair the deck in Settings > Bluetooth");
    ancsTaskRunning = false;
    pushLinkEvent(false);
    vTaskDelete(nullptr);
    return;
  }

  g_controlPoint = control;
  subscribe(data, onDataSourceNotify);
  subscribe(source, onNotificationSourceNotify);
  ancsReady = true;
  linkDirty = true;
  pushLinkEvent(true);

  bool batteryFound = false;
  readPhoneBattery(client, &batteryFound);
  readPhoneTime(client);

  uint32_t lastBatteryMs = millis();
  uint32_t lastTimeMs = millis();

  while (client->isConnected()) {
    uint32_t dismissUid = 0;
    if (xQueueReceive(ancsCmdQueue, &dismissUid, pdMS_TO_TICKS(200)) == pdTRUE) {
      requestDismissOnPhone(dismissUid);
    }
    if (millis() - lastBatteryMs > 60000) {
      lastBatteryMs = millis();
      readPhoneBattery(client, &batteryFound);
    }
    if (millis() - lastTimeMs > 300000) {
      lastTimeMs = millis();
      readPhoneTime(client);
    }
  }

  Serial.println("[ancs] phone link lost");
  ancsReady = false;
  g_controlPoint = nullptr;
  requestInFlight = false;
  linkDirty = true;
  pushLinkEvent(false);
  client->disconnect();
  delete client;
  ancsTaskRunning = false;
  vTaskDelete(nullptr);
}

class DeckServerCallbacks : public BLEServerCallbacks {
 public:
  void onConnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    (void)server;
    serverConnected = true;
    peerAllowed = true;
    rememberPeer(param->connect.remote_bda);
    linkDirty = true;
    Serial.println("[ble] phone connected");
    if (!ENABLE_ANCS) return;  // Android companions write Phone Link directly
    if (ancsTaskRunning) return;
    ancsTaskRunning = true;
    BLEAddress *address = new BLEAddress(param->connect.remote_bda);
    xTaskCreatePinnedToCore(ancsClientTask, "ancs", 10240, address, 5, nullptr, 1);
  }

  void onDisconnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    (void)param;
    peerAllowed = false;
    serverConnected = false;
    linkDirty = true;
    ancsReady = false;
    g_controlPoint = nullptr;
    Serial.println("[ble] phone disconnected");
    server->startAdvertising();  // the phone may reconnect at any time
  }
};

class DeckSecurityCallbacks : public BLESecurityCallbacks {
 public:
  uint32_t onPassKeyRequest() override { return 123456; }
  void onPassKeyNotify(uint32_t passKey) override { (void)passKey; }
  bool onSecurityRequest() override { return true; }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t cmpl) override {
    Serial.printf("[ble] pairing %s\n", cmpl.success ? "ok" : "failed");
    linkDirty = true;
  }
  bool onConfirmPIN(uint32_t passKey) override {
    (void)passKey;
    return true;
  }
};

class LinkTimeCallbacks : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic *characteristic) override {
    if (!peerAllowed) return;  // a phone that does not own this board
    const auto value = characteristic->getValue();
    if (value.length() < 7) return;
    const unsigned char *b = reinterpret_cast<const unsigned char *>(value.c_str());
    const time_t epoch =
        epochFromParts(2000 + b[0], b[1], b[2], b[3], b[4], b[5]);
    if (epoch > 0) pushTime(epoch);
  }
};

class LinkBatteryCallbacks : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic *characteristic) override {
    if (!peerAllowed) return;
    const auto value = characteristic->getValue();
    if (value.length() < 1) return;
    const int level = static_cast<unsigned char>(value[0]);
    if (level > 100) return;
    const bool charging = value.length() >= 2 && static_cast<unsigned char>(value[1]) != 0;
    pushBattery(level, charging);
  }
};

// Phone Link entries have no ANCS UID, but the UI addresses notices by id, so
// give each one a synthetic id with the top bit set. ANCS ids never use it.
static uint32_t linkNoticeSerial = 0;

class LinkNoticeCallbacks : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic *characteristic) override {
    if (!peerAllowed) return;
    const auto value = characteristic->getValue();
    if (value.length() == 0) return;
    char buf[160];
    snprintf(buf, sizeof(buf), "%s", value.c_str());

    // "app|title|body", any part optional.
    char *app = buf;
    char *title = strchr(buf, '|');
    char *body = nullptr;
    if (title != nullptr) {
      *title++ = '\0';
      body = strchr(title, '|');
      if (body != nullptr) *body++ = '\0';
    } else {
      title = app;
      app = const_cast<char *>("");
    }

    UiEvent ev = {};
    ev.type = EVT_NOTICE;
    ev.notice.uid = 0x80000000u | (++linkNoticeSerial & 0x7FFFFFFFu);
    ev.notice.category = 0;
    ev.notice.unread = true;
    friendlyApp(app, ev.notice.app, sizeof(ev.notice.app));
    snprintf(ev.notice.title, sizeof(ev.notice.title), "%s", title);
    snprintf(ev.notice.body, sizeof(ev.notice.body), "%s", body != nullptr ? body : "");
    ev.notice.at = clockNow();
    pushEvent(ev);
  }
};

// "<state>|<position>|<duration>|<title>|<artist>|<album>", seconds without a
// unit and `play` / `pause` / `stop`. The app replaces any '|' inside the text
// with '/', so splitting on it here is safe; a missing tail is empty, and a line
// this parser cannot read at all is dropped rather than guessed at.
class LinkNowCallbacks : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic *characteristic) override {
    if (!peerAllowed) return;
    const auto value = characteristic->getValue();
    if (value.length() == 0) return;
    char buf[192];
    snprintf(buf, sizeof(buf), "%s", value.c_str());

    char *field[6] = {buf, nullptr, nullptr, nullptr, nullptr, nullptr};
    for (int i = 1; i < 6; ++i) {
      field[i] = strchr(field[i - 1], '|');
      if (field[i] == nullptr) break;
      *field[i]++ = '\0';
    }
    if (field[1] == nullptr) return;  // no position: not this shape at all

    // `stop` is what the phone sends when nothing is playing, and it clears the
    // mirror instead of leaving the last song on screen forever.
    const bool stop = strncmp(field[0], "stop", 4) == 0;
    UiEvent ev = {};
    ev.type = EVT_NOW;
    ev.now.valid = !stop;
    ev.now.playing = strncmp(field[0], "play", 4) == 0;
    ev.now.position = nowSeconds(field[1]);
    ev.now.duration = field[2] != nullptr ? nowSeconds(field[2]) : 0;
    snprintf(ev.now.title, sizeof(ev.now.title), "%s", field[3] != nullptr ? field[3] : "");
    snprintf(ev.now.artist, sizeof(ev.now.artist), "%s", field[4] != nullptr ? field[4] : "");
    snprintf(ev.now.album, sizeof(ev.now.album), "%s", field[5] != nullptr ? field[5] : "");
    ev.now.at = millis();
    pushEvent(ev);
  }

 private:
  // A day is longer than any track, so anything past it is a broken line rather
  // than a number worth keeping.
  static uint32_t nowSeconds(const char *text) {
    if (text == nullptr) return 0;
    const unsigned long value = strtoul(text, nullptr, 10);
    return static_cast<uint32_t>(value > 86400UL ? 86400UL : value);
  }
};

// 0 PAPER or 1 INK, one byte. The write is handed to the UI queue rather than
// applied here: the panel belongs to the loop task, and a redraw from the BLE
// task is two tasks drawing at once. The read answers where the board actually
// is, so the app can light the right chip without guessing.
class LinkThemeCallbacks : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic *characteristic) override {
    if (!peerAllowed) return;
    const auto value = characteristic->getValue();
    if (value.length() < 1) return;
    const uint8_t index = static_cast<uint8_t>(value[0]);
    if (index >= THEME_COUNT) return;
    UiEvent ev = {};
    ev.type = EVT_THEME;
    ev.theme = index;
    pushEvent(ev);
  }

  void onRead(BLECharacteristic *characteristic) override {
    // Not const: core 2.0.17's setValue() takes a uint8_t*, and this sketch has
    // to link on both cores.
    uint8_t index = themeIndex;
    characteristic->setValue(&index, 1);
  }
};

static void startPhoneLink(BLEServer *server) {
  BLEService *service = server->createService(UUID_LINK_SERVICE);

  BLECharacteristic *time =
      service->createCharacteristic(UUID_LINK_TIME, BLECharacteristic::PROPERTY_WRITE);
  time->setCallbacks(new LinkTimeCallbacks());

  BLECharacteristic *battery = service->createCharacteristic(
      UUID_LINK_BATTERY, BLECharacteristic::PROPERTY_WRITE);
  battery->setCallbacks(new LinkBatteryCallbacks());

  BLECharacteristic *notice = service->createCharacteristic(
      UUID_LINK_NOTICE, BLECharacteristic::PROPERTY_WRITE);
  notice->setCallbacks(new LinkNoticeCallbacks());

  // What is playing on the phone, mirrored. Missing from a deck flashed before
  // it existed, so the app treats it as optional.
  BLECharacteristic *nowPlaying = service->createCharacteristic(
      UUID_LINK_NOW, BLECharacteristic::PROPERTY_WRITE);
  nowPlaying->setCallbacks(new LinkNowCallbacks());

  // The palette. Read-write because the app lights a chip from the board's own
  // answer, and a deck flashed before this existed simply has no such
  // characteristic: the app treats the switch as absent rather than broken.
  BLECharacteristic *theme = service->createCharacteristic(
      UUID_LINK_THEME,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  theme->setCallbacks(new LinkThemeCallbacks());
  uint8_t themeNow = themeIndex;
  theme->setValue(&themeNow, 1);

  // Who this board is. Written once: the tag cannot change without a re-flash,
  // and a read-only characteristic is all a reader needs.
  char document[192];
  buildDeckIdentity(document, sizeof(document));
  BLECharacteristic *identity = service->createCharacteristic(
      UUID_LINK_IDENTITY, BLECharacteristic::PROPERTY_READ);
  identity->setValue(reinterpret_cast<uint8_t *>(document), strlen(document));

  service->start();
}

// BLEAdvertisementData::addData takes a String on core 3.x and a std::string on
// 2.x; both accept a NUL-containing buffer, so route through one helper.
static void addAdvData(BLEAdvertisementData &data, const String &bytes) {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  data.addData(bytes);
#else
  data.addData(bytes.c_str());
#endif
}

static void startBluetooth() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(517);
  BLEDevice::setSecurityCallbacks(new DeckSecurityCallbacks());

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new DeckServerCallbacks());
  startPhoneLink(server);

  BLESecurity *security = new BLESecurity();
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  security->setCapability(ESP_IO_CAP_NONE);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

  BLEAdvertising *advertising = BLEDevice::getAdvertising();

  // Advertising packet (28 of 31 bytes): flags, the Phone Link service UUID and
  // a shortened name. The complete name goes in the scan response.
  BLEAdvertisementData advertisement;
  advertisement.setFlags(0x06);

  if (ENABLE_ANCS) {
    // iPhone path: the ANCS service solicitation is what lets iOS pair.
    String solicitation;
    solicitation.concat(static_cast<char>(17));
    solicitation.concat(static_cast<char>(0x15));
    for (int i = 0; i < 16; ++i) {
      solicitation.concat(static_cast<char>(ANCS_SOLICITATION_LE[i]));
    }
    addAdvData(advertisement, solicitation);
  } else {
    // Android path: advertise the Phone Link service so a companion app can
    // filter for the deck instead of matching on the name.
    advertisement.setCompleteServices(UUID_LINK_SERVICE);

    String shortName;
    shortName.concat(static_cast<char>(7));
    shortName.concat(static_cast<char>(0x08));  // shortened local name
    shortName.concat("Phone");
    addAdvData(advertisement, shortName);
  }
  advertising->setAdvertisementData(advertisement);

  BLEAdvertisementData scanResponse;
  scanResponse.setName(DEVICE_NAME);
  advertising->setScanResponseData(scanResponse);
  advertising->setScanResponse(true);

  // Both payloads are configured asynchronously by the stack. Calling start()
  // in the same breath raced that configuration and left the deck invisible on
  // phones, so let the configuration land first.
  delay(200);
  // start() returns bool on core 3.x and void on 2.x, so the call is not captured.
  advertising->start();
  Serial.printf("[ble] advertising as \"%s\"\n", DEVICE_NAME);
}

// ------------------------------------------------------------------ layout ---

// A control surface rather than a watch face: one wide status card, a numbered
// section marker, then equal-width notification rows.
static constexpr int HEADER_H = 34;

// One margin for every page: the status line, the wordmark, the tabs and the
// rules all start on this edge.
static constexpr int MARGIN = 12;
// Everything inside a card sits this far from its edge, and cards run from MARGIN
// to SCREEN_W - MARGIN. Before this, cards started at 8 and text at 12, 18 or 20
// depending on the page, so no two edges lined up.
static constexpr int CARD_PAD = 10;

// 24x24 was under a fingertip on a resistive panel; 34x28 plus slop clears it.
static const Rect GEAR_BTN = {276, 5, 32, 24};
static const Rect LINK_PILL = {196, 6, 76, 22};
// The same pill, right-aligned in a header that has no gear.
static const Rect SET_LINK_PILL = {232, 6, 76, 22};
static const Rect BACK_BTN = {6, 3, 34, 28};

static const Rect STATUS_CARD = {12, 40, 296, 76};
static const Rect NOTIF_ROW[3] = {{12, 134, 296, 32}, {12, 168, 296, 32},
                                  {12, 202, 296, 32}};

static const Rect DETAIL_DISMISS = {12, 202, 148, 30};
static const Rect DETAIL_BACK = {168, 202, 140, 30};

// The date line inside the status card while a track is live, and the button that
// leaves the now-playing page. The strip is 196 px wide because the phone's
// battery block starts at 244.
static const Rect NOW_STRIP = {22, 96, 196, 16};
static const Rect NOW_BACK = {12, 202, 296, 30};

// Half the brightness card each. Two 54px chips were pushed to the card's outer
// edges, where the panel's x mapping is least linear: "-" landed, "+" did not.
// The notifications card that sat between brightness and calibration is gone, so
// those two and CLOSE moved up into the space it held.
static const Rect SET_BRI_DOWN = {22, 64, 134, 28};
static const Rect SET_BRI_UP = {164, 64, 134, 28};
static const Rect SET_CAL = {12, 104, 296, 32};
static const Rect SET_CLOSE = {12, 144, 296, 34};

// Front page: a status line, the wordmark, two pills, three numbered tabs. The
// clock and battery moved up out of the bottom strip, so the page now ends in a
// closing rule. A tab is 300x30, the widest target the panel can give. The
// fourth row was THEME, a contact sheet; the switch lives in the app now, so the
// three rows keep their places and the page ends higher.
static const Rect HEADER_BACK = {6, 3, 34, 28};
static const Rect HOME_TAB[3] = {{12, 72, 296, 34}, {12, 110, 296, 34},
                                 {12, 148, 296, 34}};

// ------------------------------------------------------------- typeface ---
// Two faces cut from DejaVu Sans Mono by artifacts/deck_font.py, pasted in rather
// than included: a pasted sketch cannot see a header beside it. Both are aligned
// by cap top, so text at y starts its capitals at y exactly as the 5x7 font did.
// Body is 11 px (7 px advance, 8 px caps), display the bold at 20 px (12 px
// advance, 15 px caps), and clock the same bold at 30 px for the one number the
// board is for. Nothing scales a face: a heading is a different size, not the body
// doubled.

static const uint8_t DeckFontBodyBits[] PROGMEM = {
    0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x00, 0x40, 0x50, 0x50, 0x50, 0x14,
    0x20, 0x7E, 0x28, 0x28, 0xFC, 0x50, 0x50, 0x20, 0x70, 0xA0, 0xA0, 0x70,
    0x28, 0x28, 0xF0, 0x20, 0x20, 0x60, 0x90, 0x64, 0x00, 0x20, 0x4C, 0x14,
    0x0C, 0x38, 0x40, 0x40, 0x60, 0x50, 0x8C, 0x4C, 0x74, 0x40, 0x40, 0x40,
    0x00, 0x40, 0x40, 0x80, 0x80, 0x80, 0x80, 0x40, 0x40, 0x00, 0x40, 0x20,
    0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x40, 0x10, 0x50, 0x38, 0x38,
    0x50, 0x10, 0x10, 0x10, 0xFC, 0x10, 0x10, 0x40, 0x40, 0x80, 0x70, 0x40,
    0x40, 0x04, 0x08, 0x08, 0x10, 0x10, 0x20, 0x20, 0x40, 0x40, 0x38, 0x48,
    0x44, 0x44, 0x54, 0x44, 0x48, 0x38, 0xE0, 0x20, 0x20, 0x20, 0x20, 0x20,
    0x20, 0xF8, 0x38, 0x48, 0x0C, 0x08, 0x10, 0x20, 0x40, 0x7C, 0x38, 0x4C,
    0x08, 0x38, 0x08, 0x04, 0x0C, 0x78, 0x18, 0x18, 0x28, 0x08, 0x48, 0x7C,
    0x08, 0x08, 0x78, 0x40, 0x40, 0x78, 0x0C, 0x04, 0x08, 0x78, 0x38, 0x60,
    0x40, 0x78, 0x4C, 0x44, 0x4C, 0x38, 0x7C, 0x08, 0x08, 0x08, 0x10, 0x10,
    0x20, 0x20, 0x38, 0x4C, 0x4C, 0x38, 0x4C, 0x44, 0x4C, 0x38, 0x38, 0x48,
    0x44, 0x4C, 0x3C, 0x04, 0x08, 0x70, 0x40, 0x40, 0x00, 0x00, 0x40, 0x40,
    0x40, 0x40, 0x00, 0x00, 0x40, 0x40, 0x80, 0x04, 0x30, 0xC0, 0x30, 0x04,
    0xFC, 0x00, 0xFC, 0x40, 0x38, 0x0C, 0x38, 0x40, 0xF0, 0x18, 0x10, 0x20,
    0x20, 0x20, 0x00, 0x20, 0x38, 0x44, 0x44, 0x9C, 0xA4, 0xA4, 0x9C, 0x40,
    0x60, 0x38, 0x30, 0x30, 0x28, 0x28, 0x48, 0x7C, 0x44, 0x84, 0x78, 0x44,
    0x4C, 0x78, 0x44, 0x44, 0x44, 0x78, 0x38, 0x64, 0x40, 0x40, 0x40, 0x40,
    0x64, 0x38, 0x70, 0x48, 0x44, 0x44, 0x44, 0x44, 0x48, 0x70, 0xF8, 0x80,
    0x80, 0xF8, 0x80, 0x80, 0x80, 0xF8, 0xF8, 0x80, 0x80, 0xF8, 0x80, 0x80,
    0x80, 0x80, 0x38, 0x60, 0x40, 0x40, 0x4C, 0x44, 0x44, 0x38, 0x44, 0x44,
    0x44, 0x7C, 0x44, 0x44, 0x44, 0x44, 0xF8, 0x20, 0x20, 0x20, 0x20, 0x20,
    0x20, 0xF8, 0x38, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x70, 0x44, 0x48,
    0x50, 0x70, 0x50, 0x48, 0x4C, 0x44, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
    0x80, 0xF8, 0xC4, 0xCC, 0xEC, 0xB4, 0x94, 0x84, 0x84, 0x84, 0x44, 0x64,
    0x64, 0x54, 0x54, 0x5C, 0x4C, 0x4C, 0x38, 0x4C, 0x44, 0x44, 0x44, 0x44,
    0x4C, 0x38, 0xF0, 0x88, 0x88, 0x88, 0xF0, 0x80, 0x80, 0x80, 0x38, 0x4C,
    0x44, 0x44, 0x44, 0x44, 0x4C, 0x38, 0x08, 0x78, 0x4C, 0x44, 0x48, 0x78,
    0x48, 0x44, 0x44, 0x38, 0x40, 0x40, 0x60, 0x08, 0x04, 0x4C, 0x38, 0xFC,
    0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x44, 0x44, 0x44, 0x44, 0x44,
    0x44, 0x4C, 0x38, 0xC4, 0x44, 0x44, 0x48, 0x28, 0x28, 0x30, 0x10, 0x86,
    0x84, 0x94, 0x34, 0x64, 0x6C, 0x6C, 0x48, 0x44, 0x68, 0x28, 0x10, 0x30,
    0x28, 0x44, 0xC4, 0xC4, 0x4C, 0x28, 0x30, 0x10, 0x10, 0x10, 0x10, 0x7C,
    0x04, 0x08, 0x10, 0x10, 0x20, 0x40, 0x7C, 0xE0, 0x80, 0x80, 0x80, 0x80,
    0x80, 0x80, 0x80, 0x80, 0xE0, 0x40, 0x40, 0x20, 0x20, 0x10, 0x10, 0x08,
    0x08, 0x04, 0x60, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x60,
    0x30, 0x28, 0x44, 0xFE, 0x40, 0x20, 0x78, 0x0C, 0x3C, 0x44, 0x4C, 0x7C,
    0x80, 0x80, 0x80, 0xF0, 0xD8, 0x88, 0x88, 0x98, 0xF0, 0x78, 0xC0, 0x80,
    0x80, 0xC0, 0x78, 0x04, 0x04, 0x04, 0x3C, 0x4C, 0x44, 0x44, 0x4C, 0x3C,
    0x38, 0x44, 0x7C, 0x40, 0x40, 0x3C, 0x38, 0x20, 0x20, 0xF8, 0x20, 0x20,
    0x20, 0x20, 0x20, 0x3C, 0x4C, 0x44, 0x44, 0x4C, 0x3C, 0x08, 0x78, 0x80,
    0x80, 0x80, 0xB0, 0x98, 0x88, 0x88, 0x88, 0x88, 0x10, 0x00, 0x00, 0x70,
    0x10, 0x10, 0x10, 0x10, 0x7C, 0x20, 0x00, 0x00, 0x60, 0x20, 0x20, 0x20,
    0x20, 0x20, 0x20, 0xE0, 0x80, 0x80, 0x80, 0x98, 0xB0, 0xE0, 0xB0, 0x90,
    0x88, 0x60, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x10, 0x1C, 0x6C, 0x54,
    0x14, 0x14, 0x14, 0x14, 0xB0, 0x98, 0x88, 0x88, 0x88, 0x88, 0x38, 0x4C,
    0x44, 0x44, 0x4C, 0x38, 0xB0, 0xD8, 0x88, 0x88, 0x98, 0xF0, 0x80, 0x80,
    0x3C, 0x4C, 0x44, 0x44, 0x4C, 0x3C, 0x04, 0x04, 0x78, 0x60, 0x40, 0x40,
    0x40, 0x40, 0x70, 0x80, 0xC0, 0x30, 0x10, 0xF0, 0x20, 0x20, 0x7C, 0x20,
    0x20, 0x20, 0x20, 0x1C, 0x88, 0x88, 0x88, 0x88, 0x98, 0x78, 0x44, 0x44,
    0x48, 0x28, 0x30, 0x30, 0x82, 0x84, 0x54, 0x74, 0x6C, 0x68, 0x44, 0x28,
    0x30, 0x30, 0x28, 0x44, 0x44, 0x44, 0x28, 0x28, 0x30, 0x10, 0x30, 0x60,
    0xF8, 0x10, 0x20, 0x40, 0x40, 0xF8, 0x30, 0x20, 0x20, 0x20, 0xC0, 0x60,
    0x20, 0x20, 0x20, 0x30, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40,
    0x40, 0x40, 0x40, 0xC0, 0x20, 0x20, 0x20, 0x30, 0x20, 0x20, 0x20, 0x20,
    0xC0, 0x60, 0x1C,
};

static const GFXglyph DeckFontBodyGlyphs[] PROGMEM = {
    {0, 0, 0, 7, 0, 0},
    {0, 2, 8, 7, 2, 0},
    {8, 4, 3, 7, 1, 0},
    {11, 7, 8, 7, 0, 0},
    {19, 5, 10, 7, 1, 0},
    {29, 7, 8, 7, 0, 0},
    {37, 7, 8, 7, 0, 0},
    {45, 2, 3, 7, 2, 0},
    {48, 3, 10, 7, 2, -1},
    {58, 4, 10, 7, 1, -1},
    {68, 6, 6, 7, 0, 0},
    {74, 7, 5, 7, 0, 2},
    {79, 3, 3, 7, 2, 6},
    {82, 4, 1, 7, 1, 4},
    {83, 2, 2, 7, 2, 6},
    {85, 6, 9, 7, 0, 0},
    {94, 6, 8, 7, 0, 0},
    {102, 5, 8, 7, 1, 0},
    {110, 6, 8, 7, 0, 0},
    {118, 6, 8, 7, 0, 0},
    {126, 7, 8, 7, 0, 0},
    {134, 6, 8, 7, 0, 0},
    {142, 6, 8, 7, 0, 0},
    {150, 6, 8, 7, 0, 0},
    {158, 6, 8, 7, 0, 0},
    {166, 6, 8, 7, 0, 0},
    {174, 2, 6, 7, 2, 2},
    {180, 3, 7, 7, 2, 2},
    {187, 7, 5, 7, 0, 2},
    {192, 7, 3, 7, 0, 3},
    {195, 7, 5, 7, 0, 2},
    {200, 5, 8, 7, 1, 0},
    {208, 7, 10, 7, 0, 0},
    {218, 7, 8, 7, 0, 0},
    {226, 7, 8, 7, 0, 0},
    {234, 6, 8, 7, 0, 0},
    {242, 6, 8, 7, 0, 0},
    {250, 5, 8, 7, 1, 0},
    {258, 5, 8, 7, 1, 0},
    {266, 6, 8, 7, 0, 0},
    {274, 6, 8, 7, 0, 0},
    {282, 5, 8, 7, 1, 0},
    {290, 6, 8, 7, 0, 0},
    {298, 7, 8, 7, 0, 0},
    {306, 6, 8, 7, 1, 0},
    {314, 7, 8, 7, 0, 0},
    {322, 6, 8, 7, 0, 0},
    {330, 6, 8, 7, 0, 0},
    {338, 6, 8, 7, 1, 0},
    {346, 6, 9, 7, 0, 0},
    {355, 7, 8, 7, 0, 0},
    {363, 6, 8, 7, 0, 0},
    {371, 7, 8, 7, 0, 0},
    {379, 6, 8, 7, 0, 0},
    {387, 7, 8, 7, 0, 0},
    {395, 7, 8, 7, 0, 0},
    {403, 7, 8, 7, 0, 0},
    {411, 7, 8, 7, 0, 0},
    {419, 7, 8, 7, 0, 0},
    {427, 3, 10, 7, 2, -1},
    {437, 6, 9, 7, 0, 0},
    {446, 4, 10, 7, 1, -1},
    {456, 7, 3, 7, 0, 0},
    {459, 7, 1, 7, 0, 10},
    {460, 4, 2, 7, 1, -1},
    {462, 6, 6, 7, 0, 2},
    {468, 5, 9, 7, 1, -1},
    {477, 5, 6, 7, 1, 2},
    {483, 6, 9, 7, 0, -1},
    {492, 6, 6, 7, 0, 2},
    {498, 5, 9, 7, 1, -1},
    {507, 6, 8, 7, 0, 2},
    {515, 5, 9, 7, 1, -1},
    {524, 6, 9, 7, 0, -1},
    {533, 4, 11, 7, 1, -1},
    {544, 6, 9, 7, 1, -1},
    {553, 6, 9, 7, 0, -1},
    {562, 7, 6, 7, 0, 2},
    {568, 5, 6, 7, 1, 2},
    {574, 6, 6, 7, 0, 2},
    {580, 5, 8, 7, 1, 2},
    {588, 6, 8, 7, 0, 2},
    {596, 6, 6, 7, 1, 2},
    {602, 5, 6, 7, 1, 2},
    {608, 6, 8, 7, 0, 0},
    {616, 5, 6, 7, 1, 2},
    {622, 7, 6, 7, 0, 2},
    {628, 7, 6, 7, 0, 2},
    {634, 7, 6, 7, 0, 2},
    {640, 7, 8, 7, 0, 2},
    {648, 5, 6, 7, 1, 2},
    {654, 5, 10, 7, 1, -1},
    {664, 2, 11, 7, 2, -1},
    {675, 5, 10, 7, 1, -1},
    {685, 7, 2, 7, 0, 4},
};

const GFXfont DeckFontBody PROGMEM = {(uint8_t *)DeckFontBodyBits, (GFXglyph *)DeckFontBodyGlyphs, 32, 126, 14};

static const uint8_t DeckFontDisplayBits[] PROGMEM = {
    0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x00, 0x00,
    0x60, 0x60, 0x60, 0xE7, 0xE7, 0xE7, 0xE7, 0xE7, 0x06, 0x60, 0x0E, 0x60,
    0x0C, 0xC0, 0x0C, 0xC0, 0x7F, 0xF0, 0x7F, 0xF0, 0x19, 0x80, 0x19, 0x80,
    0x19, 0x80, 0xFF, 0xE0, 0xFF, 0xE0, 0x33, 0x00, 0x33, 0x00, 0x73, 0x00,
    0x67, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x1F, 0x00, 0x7F, 0x80, 0x7D, 0x80,
    0x6C, 0x00, 0x6C, 0x00, 0x7C, 0x00, 0x3F, 0x00, 0x0F, 0x80, 0x0D, 0xC0,
    0x0D, 0xC0, 0x6D, 0xC0, 0x7F, 0x80, 0x3F, 0x00, 0x0C, 0x00, 0x0C, 0x00,
    0x0C, 0x00, 0x38, 0x00, 0x7C, 0x00, 0xCC, 0x00, 0xCC, 0x00, 0x7C, 0x20,
    0x38, 0xE0, 0x01, 0x80, 0x06, 0x00, 0x18, 0x00, 0x71, 0xC0, 0x43, 0xE0,
    0x03, 0x30, 0x03, 0x30, 0x03, 0xE0, 0x01, 0xC0, 0x0F, 0x00, 0x1F, 0x80,
    0x38, 0x80, 0x38, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x3E, 0x00, 0x7E, 0x30,
    0x67, 0x30, 0xE3, 0xF0, 0xE3, 0xE0, 0x61, 0xE0, 0x71, 0xE0, 0x3F, 0xE0,
    0x1F, 0xF0, 0x60, 0x60, 0x60, 0x60, 0x60, 0x1C, 0x18, 0x38, 0x30, 0x30,
    0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x70, 0x30, 0x30, 0x38, 0x18,
    0x1C, 0xE0, 0x60, 0x70, 0x30, 0x38, 0x38, 0x38, 0x38, 0x18, 0x18, 0x38,
    0x38, 0x38, 0x38, 0x30, 0x70, 0x60, 0xE0, 0x0C, 0x00, 0x0C, 0x00, 0x4C,
    0x80, 0x7F, 0x80, 0x3F, 0x00, 0x3F, 0x00, 0x7F, 0x80, 0x4C, 0x80, 0x0C,
    0x00, 0x0C, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x7F,
    0xE0, 0x7F, 0xE0, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x78,
    0x78, 0x70, 0x70, 0x60, 0x60, 0x7E, 0x7E, 0x7E, 0xF0, 0xF0, 0xF0, 0xF0,
    0x00, 0xC0, 0x01, 0x80, 0x01, 0x80, 0x03, 0x00, 0x03, 0x00, 0x06, 0x00,
    0x06, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x18, 0x00, 0x18, 0x00, 0x30, 0x00,
    0x30, 0x00, 0x60, 0x00, 0x60, 0x00, 0xE0, 0x00, 0x1E, 0x00, 0x3F, 0x00,
    0x73, 0x80, 0x71, 0x80, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xED, 0xC0,
    0xED, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0x71, 0x80, 0x73, 0x80, 0x3F, 0x00,
    0x1E, 0x00, 0x1E, 0x00, 0x7E, 0x00, 0x6E, 0x00, 0x0E, 0x00, 0x0E, 0x00,
    0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00,
    0x0E, 0x00, 0x0E, 0x00, 0x7F, 0xC0, 0x7F, 0xC0, 0x3E, 0x00, 0xFF, 0x00,
    0xE3, 0x80, 0x03, 0x80, 0x03, 0x80, 0x03, 0x80, 0x03, 0x80, 0x07, 0x00,
    0x0E, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x78, 0x00, 0x70, 0x00, 0xFF, 0x80,
    0xFF, 0x80, 0x3E, 0x00, 0x7F, 0x80, 0x63, 0x80, 0x01, 0x80, 0x01, 0x80,
    0x03, 0x80, 0x1F, 0x00, 0x1F, 0x00, 0x03, 0x80, 0x01, 0xC0, 0x01, 0xC0,
    0x81, 0xC0, 0xC3, 0x80, 0xFF, 0x80, 0x3E, 0x00, 0x07, 0x80, 0x07, 0x80,
    0x0F, 0x80, 0x1F, 0x80, 0x1B, 0x80, 0x33, 0x80, 0x73, 0x80, 0x63, 0x80,
    0xC3, 0x80, 0xFF, 0xC0, 0xFF, 0xC0, 0x03, 0x80, 0x03, 0x80, 0x03, 0x80,
    0x03, 0x80, 0x7F, 0x80, 0x7F, 0x80, 0x60, 0x00, 0x60, 0x00, 0x60, 0x00,
    0x7E, 0x00, 0x7F, 0x00, 0x43, 0x80, 0x03, 0x80, 0x01, 0xC0, 0x01, 0xC0,
    0x01, 0x80, 0xC3, 0x80, 0xFF, 0x00, 0x3E, 0x00, 0x0F, 0x00, 0x3F, 0x80,
    0x70, 0x80, 0x70, 0x00, 0x60, 0x00, 0xEF, 0x00, 0xFF, 0x80, 0xF3, 0xC0,
    0xF1, 0xC0, 0xE1, 0xC0, 0x61, 0xC0, 0x71, 0xC0, 0x73, 0x80, 0x3F, 0x80,
    0x1F, 0x00, 0xFF, 0xC0, 0xFF, 0xC0, 0x03, 0x80, 0x03, 0x80, 0x07, 0x80,
    0x07, 0x00, 0x07, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x1C, 0x00,
    0x1C, 0x00, 0x1C, 0x00, 0x38, 0x00, 0x38, 0x00, 0x1E, 0x00, 0x7F, 0x80,
    0x73, 0x80, 0x61, 0x80, 0x61, 0x80, 0x73, 0x80, 0x3F, 0x00, 0x3F, 0x00,
    0x73, 0x80, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0x73, 0x80, 0x7F, 0x80,
    0x1F, 0x00, 0x3E, 0x00, 0x7F, 0x00, 0x73, 0x80, 0xE3, 0x80, 0xE1, 0xC0,
    0xE1, 0xC0, 0xE3, 0xC0, 0xF3, 0xC0, 0x7F, 0xC0, 0x3D, 0xC0, 0x01, 0x80,
    0x03, 0x80, 0x43, 0x80, 0x7F, 0x00, 0x3C, 0x00, 0xF0, 0xF0, 0xF0, 0xF0,
    0x00, 0x00, 0xF0, 0xF0, 0xF0, 0xF0, 0x78, 0x78, 0x78, 0x78, 0x00, 0x00,
    0x78, 0x78, 0x78, 0x70, 0x70, 0x60, 0x00, 0x20, 0x01, 0xE0, 0x07, 0xE0,
    0x3F, 0x00, 0x78, 0x00, 0x78, 0x00, 0x3F, 0x00, 0x07, 0xE0, 0x01, 0xE0,
    0x00, 0x20, 0x7F, 0xE0, 0x7F, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x7F, 0xE0,
    0x7F, 0xE0, 0x40, 0x00, 0x78, 0x00, 0x7E, 0x00, 0x0F, 0xC0, 0x01, 0xE0,
    0x01, 0xE0, 0x0F, 0xC0, 0x7F, 0x00, 0x78, 0x00, 0x40, 0x00, 0x3E, 0x00,
    0xFF, 0x00, 0xC7, 0x00, 0x03, 0x00, 0x07, 0x00, 0x0E, 0x00, 0x1C, 0x00,
    0x18, 0x00, 0x38, 0x00, 0x38, 0x00, 0x38, 0x00, 0x00, 0x00, 0x38, 0x00,
    0x38, 0x00, 0x38, 0x00, 0x0F, 0x80, 0x1F, 0xC0, 0x38, 0xE0, 0x70, 0x60,
    0x63, 0xE0, 0xC7, 0xE0, 0xCE, 0x60, 0xCC, 0x60, 0xCC, 0x60, 0xCC, 0x60,
    0xCE, 0x60, 0xE7, 0xE0, 0x63, 0xE0, 0x70, 0x00, 0x38, 0x60, 0x1F, 0xE0,
    0x07, 0xC0, 0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x1F, 0x80, 0x1F, 0x80,
    0x19, 0x80, 0x19, 0x80, 0x39, 0xC0, 0x39, 0xC0, 0x3F, 0xC0, 0x3F, 0xE0,
    0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0xE0, 0x70, 0xFF, 0x00, 0xFF, 0x80,
    0xE3, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE3, 0x80, 0xFF, 0x00, 0xFF, 0x80,
    0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xFF, 0xC0,
    0xFF, 0x00, 0x0F, 0x80, 0x3F, 0xC0, 0x38, 0xC0, 0x70, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x70, 0x00, 0xE0, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x38, 0xC0, 0x3F, 0xC0, 0x0F, 0x80, 0xFE, 0x00, 0xFF, 0x00,
    0xE3, 0x80, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0,
    0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE3, 0x80, 0xFF, 0x00,
    0xFE, 0x00, 0x7F, 0xC0, 0x7F, 0xC0, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x7F, 0x80, 0x7F, 0x80, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x70, 0x00, 0x7F, 0xC0, 0x7F, 0xC0, 0x7F, 0xC0, 0x7F, 0xC0,
    0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x7F, 0x80, 0x7F, 0x80,
    0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x0F, 0x00, 0x3F, 0xC0, 0x78, 0xC0, 0x70, 0x00, 0xF0, 0x00,
    0xE0, 0x00, 0xE0, 0x00, 0xE3, 0xC0, 0xE3, 0xC0, 0xE0, 0xC0, 0xF0, 0xC0,
    0x70, 0xC0, 0x79, 0xC0, 0x3F, 0xC0, 0x0F, 0x80, 0xE1, 0xC0, 0xE1, 0xC0,
    0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xFF, 0xC0, 0xFF, 0xC0,
    0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0,
    0xE1, 0xC0, 0x7F, 0x80, 0x7F, 0x80, 0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00,
    0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00,
    0x0C, 0x00, 0x0C, 0x00, 0x7F, 0x80, 0x7F, 0x80, 0x3F, 0x80, 0x3F, 0x80,
    0x03, 0x80, 0x03, 0x80, 0x03, 0x80, 0x03, 0x80, 0x03, 0x80, 0x03, 0x80,
    0x03, 0x80, 0x03, 0x80, 0x03, 0x80, 0x03, 0x80, 0xC7, 0x80, 0xFF, 0x00,
    0x7E, 0x00, 0xE1, 0xC0, 0xE3, 0xC0, 0xE3, 0x80, 0xE7, 0x00, 0xEE, 0x00,
    0xFE, 0x00, 0xFE, 0x00, 0xFE, 0x00, 0xFF, 0x00, 0xE7, 0x00, 0xE7, 0x80,
    0xE3, 0x80, 0xE3, 0xC0, 0xE1, 0xC0, 0xE0, 0xE0, 0xE0, 0x00, 0xE0, 0x00,
    0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00,
    0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xFF, 0x80,
    0xFF, 0x80, 0x70, 0xE0, 0x79, 0xE0, 0x79, 0xE0, 0x79, 0xE0, 0x79, 0xE0,
    0x6F, 0x60, 0x6F, 0x60, 0x6F, 0x60, 0x67, 0x60, 0x66, 0x60, 0x60, 0x60,
    0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0xE1, 0xC0, 0xF1, 0xC0,
    0xF1, 0xC0, 0xF9, 0xC0, 0xF9, 0xC0, 0xF9, 0xC0, 0xED, 0xC0, 0xED, 0xC0,
    0xED, 0xC0, 0xE7, 0xC0, 0xE7, 0xC0, 0xE7, 0xC0, 0xE3, 0xC0, 0xE3, 0xC0,
    0xE1, 0xC0, 0x0F, 0x00, 0x1F, 0x80, 0x39, 0xC0, 0x70, 0xE0, 0x70, 0xE0,
    0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0,
    0x70, 0xE0, 0x39, 0xC0, 0x1F, 0x80, 0x0F, 0x00, 0x7F, 0x00, 0x7F, 0x80,
    0x63, 0xC0, 0x61, 0xC0, 0x61, 0xC0, 0x61, 0xC0, 0x63, 0xC0, 0x7F, 0x80,
    0x7F, 0x00, 0x60, 0x00, 0x60, 0x00, 0x60, 0x00, 0x60, 0x00, 0x60, 0x00,
    0x60, 0x00, 0x0F, 0x00, 0x1F, 0x80, 0x39, 0xC0, 0x70, 0xE0, 0x70, 0xE0,
    0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0,
    0x70, 0xE0, 0x39, 0xC0, 0x1F, 0xC0, 0x0F, 0x80, 0x01, 0xC0, 0x00, 0x80,
    0xFF, 0x00, 0xFF, 0x80, 0xE3, 0x80, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0,
    0xE3, 0x80, 0xFF, 0x00, 0xFF, 0x00, 0xE7, 0x00, 0xE3, 0x80, 0xE3, 0x80,
    0xE1, 0xC0, 0xE1, 0xC0, 0xE0, 0xE0, 0x1F, 0x00, 0x7F, 0x80, 0x71, 0x80,
    0xE0, 0x80, 0xE0, 0x00, 0xF0, 0x00, 0x7C, 0x00, 0x3F, 0x00, 0x0F, 0x80,
    0x03, 0xC0, 0x01, 0xC0, 0x81, 0xC0, 0xE3, 0xC0, 0xFF, 0x80, 0x3F, 0x00,
    0x7F, 0xE0, 0x7F, 0xE0, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00,
    0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00,
    0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0,
    0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0,
    0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xF3, 0xC0, 0x7F, 0x80, 0x3F, 0x00,
    0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x30, 0xC0, 0x39, 0xC0,
    0x39, 0xC0, 0x39, 0xC0, 0x19, 0xC0, 0x19, 0x80, 0x1F, 0x80, 0x1F, 0x80,
    0x1F, 0x80, 0x0F, 0x00, 0x0F, 0x00, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70,
    0xE0, 0x70, 0x66, 0x70, 0x67, 0x60, 0x6F, 0x60, 0x6F, 0x60, 0x6F, 0x60,
    0x7D, 0xE0, 0x79, 0xE0, 0x79, 0xE0, 0x79, 0xE0, 0x39, 0xE0, 0x38, 0xC0,
    0x70, 0xE0, 0x70, 0xE0, 0x39, 0xC0, 0x39, 0xC0, 0x1F, 0x80, 0x1F, 0x80,
    0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x1F, 0x80, 0x1F, 0x80, 0x39, 0xC0,
    0x39, 0xC0, 0x70, 0xE0, 0x70, 0x70, 0xE0, 0x70, 0x70, 0xE0, 0x70, 0xE0,
    0x39, 0xC0, 0x39, 0xC0, 0x1F, 0x80, 0x1F, 0x80, 0x0F, 0x00, 0x0F, 0x00,
    0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00, 0x06, 0x00,
    0xFF, 0xC0, 0xFF, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x07, 0x80, 0x0F, 0x00,
    0x0F, 0x00, 0x1E, 0x00, 0x1C, 0x00, 0x3C, 0x00, 0x78, 0x00, 0x70, 0x00,
    0xF0, 0x00, 0xFF, 0xC0, 0xFF, 0xC0, 0xF8, 0xF8, 0xE0, 0xE0, 0xE0, 0xE0,
    0xE0, 0xE0, 0xE0, 0xE0, 0xE0, 0xE0, 0xE0, 0xE0, 0xE0, 0xE0, 0xF8, 0xF8,
    0xE0, 0x00, 0x60, 0x00, 0x60, 0x00, 0x30, 0x00, 0x30, 0x00, 0x18, 0x00,
    0x18, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x06, 0x00, 0x06, 0x00, 0x03, 0x00,
    0x03, 0x00, 0x01, 0x80, 0x01, 0x80, 0x00, 0xC0, 0x7C, 0x7C, 0x1C, 0x1C,
    0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C,
    0x7C, 0x7C, 0x0F, 0x00, 0x0F, 0x00, 0x1F, 0x80, 0x39, 0xC0, 0x60, 0x60,
    0xFF, 0xF0, 0xFF, 0xF0, 0xE0, 0x70, 0x30, 0x18, 0x1F, 0x80, 0x3F, 0xC0,
    0x20, 0xE0, 0x00, 0xE0, 0x1F, 0xE0, 0x3F, 0xE0, 0x78, 0xE0, 0x70, 0xE0,
    0x71, 0xE0, 0x7F, 0xE0, 0x1E, 0xE0, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00,
    0xE0, 0x00, 0xEF, 0x00, 0xFF, 0x80, 0xF3, 0xC0, 0xF1, 0xC0, 0xE1, 0xC0,
    0xE1, 0xC0, 0xE1, 0xC0, 0xF1, 0xC0, 0xF3, 0xC0, 0xFF, 0x80, 0xEF, 0x00,
    0x0F, 0x00, 0x3F, 0x80, 0x78, 0x80, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x70, 0x00, 0x78, 0x80, 0x3F, 0x80, 0x1F, 0x00, 0x00, 0xE0,
    0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x1E, 0xE0, 0x3F, 0xE0, 0x79, 0xE0,
    0x71, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x71, 0xE0, 0x79, 0xE0,
    0x3F, 0xE0, 0x1E, 0xE0, 0x0F, 0x80, 0x3F, 0xC0, 0x38, 0xE0, 0x70, 0xE0,
    0x7F, 0xE0, 0x7F, 0xE0, 0x70, 0x00, 0x70, 0x00, 0x38, 0x20, 0x3F, 0xE0,
    0x0F, 0x80, 0x07, 0xC0, 0x0F, 0xC0, 0x1C, 0x00, 0x1C, 0x00, 0x7F, 0xC0,
    0x7F, 0xC0, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00,
    0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1E, 0xE0, 0x3F, 0xE0,
    0x39, 0xE0, 0x71, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x71, 0xE0,
    0x79, 0xE0, 0x3F, 0xE0, 0x1E, 0xE0, 0x00, 0xE0, 0x21, 0xC0, 0x3F, 0xC0,
    0x1F, 0x80, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x77, 0x00,
    0x7F, 0x80, 0x73, 0x80, 0x71, 0x80, 0x71, 0x80, 0x71, 0x80, 0x71, 0x80,
    0x71, 0x80, 0x71, 0x80, 0x71, 0x80, 0x71, 0x80, 0x0E, 0x00, 0x0E, 0x00,
    0x0E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E, 0x00, 0x7E, 0x00, 0x0E, 0x00,
    0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00,
    0xFF, 0xC0, 0xFF, 0xC0, 0x0E, 0x0E, 0x0E, 0x00, 0x00, 0x3E, 0x3E, 0x0E,
    0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0xFE, 0xFC,
    0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x71, 0xC0, 0x73, 0x80,
    0x77, 0x00, 0x7E, 0x00, 0x7E, 0x00, 0x7E, 0x00, 0x77, 0x00, 0x77, 0x00,
    0x73, 0x80, 0x71, 0xC0, 0x71, 0xC0, 0x7E, 0x00, 0x7E, 0x00, 0x0E, 0x00,
    0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00,
    0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0E, 0x00, 0x0F, 0xE0, 0x07, 0xE0,
    0x7D, 0xC0, 0x7F, 0xE0, 0x66, 0x60, 0x66, 0x60, 0x66, 0x60, 0x66, 0x60,
    0x66, 0x60, 0x66, 0x60, 0x66, 0x60, 0x66, 0x60, 0x66, 0x60, 0x77, 0x00,
    0x7F, 0x80, 0x73, 0x80, 0x71, 0x80, 0x71, 0x80, 0x71, 0x80, 0x71, 0x80,
    0x71, 0x80, 0x71, 0x80, 0x71, 0x80, 0x71, 0x80, 0x0F, 0x00, 0x3F, 0xC0,
    0x39, 0xC0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0, 0x70, 0xE0,
    0x39, 0xC0, 0x3F, 0xC0, 0x0F, 0x00, 0xEF, 0x00, 0xFF, 0x80, 0xF3, 0xC0,
    0xF1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xE1, 0xC0, 0xF1, 0xC0, 0xF3, 0xC0,
    0xFF, 0x80, 0xEF, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00,
    0x1E, 0xE0, 0x3F, 0xE0, 0x79, 0xE0, 0x71, 0xE0, 0x70, 0xE0, 0x70, 0xE0,
    0x70, 0xE0, 0x71, 0xE0, 0x79, 0xE0, 0x3F, 0xE0, 0x1E, 0xE0, 0x00, 0xE0,
    0x00, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x77, 0x80, 0x7F, 0x80, 0x78, 0x80,
    0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x70, 0x00, 0x1E, 0x00, 0x7F, 0x80, 0x70, 0x80, 0x70, 0x00,
    0x7C, 0x00, 0x3F, 0x00, 0x0F, 0x80, 0x01, 0x80, 0x43, 0x80, 0x7F, 0x80,
    0x3F, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0xFF, 0x80, 0xFF, 0x80,
    0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1C, 0x00,
    0x1C, 0x00, 0x1F, 0x80, 0x0F, 0x80, 0x63, 0x80, 0x63, 0x80, 0x63, 0x80,
    0x63, 0x80, 0x63, 0x80, 0x63, 0x80, 0x63, 0x80, 0x63, 0x80, 0x73, 0x80,
    0x7F, 0x80, 0x3F, 0x80, 0x70, 0xE0, 0x70, 0xE0, 0x30, 0xC0, 0x39, 0xC0,
    0x39, 0xC0, 0x19, 0xC0, 0x19, 0x80, 0x1F, 0x80, 0x1F, 0x80, 0x0F, 0x00,
    0x0F, 0x00, 0xC0, 0x30, 0xE0, 0x70, 0xE0, 0x70, 0x66, 0x60, 0x66, 0x60,
    0x6F, 0x60, 0x6F, 0x60, 0x7D, 0xE0, 0x39, 0xE0, 0x39, 0xC0, 0x39, 0xC0,
    0x70, 0xE0, 0x39, 0xC0, 0x1F, 0x80, 0x1F, 0x80, 0x0F, 0x00, 0x0F, 0x00,
    0x0F, 0x00, 0x1F, 0x80, 0x39, 0xC0, 0x39, 0xC0, 0x70, 0xE0, 0x70, 0xE0,
    0x70, 0xE0, 0x30, 0xE0, 0x39, 0xC0, 0x39, 0xC0, 0x1D, 0x80, 0x1F, 0x80,
    0x1F, 0x80, 0x0F, 0x00, 0x0F, 0x00, 0x07, 0x00, 0x0E, 0x00, 0x0E, 0x00,
    0x7C, 0x00, 0x78, 0x00, 0x7F, 0xC0, 0x7F, 0xC0, 0x03, 0x80, 0x07, 0x80,
    0x0F, 0x00, 0x1E, 0x00, 0x3C, 0x00, 0x38, 0x00, 0x70, 0x00, 0x7F, 0xC0,
    0x7F, 0xC0, 0x07, 0x80, 0x0F, 0x80, 0x0E, 0x00, 0x0C, 0x00, 0x0C, 0x00,
    0x0C, 0x00, 0x0C, 0x00, 0x1C, 0x00, 0x78, 0x00, 0x78, 0x00, 0x1C, 0x00,
    0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x0E, 0x00, 0x0F, 0x80,
    0x07, 0x80, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60,
    0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x78, 0x00,
    0x7C, 0x00, 0x1C, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00, 0x0C, 0x00,
    0x0E, 0x00, 0x07, 0x80, 0x07, 0x80, 0x0E, 0x00, 0x0C, 0x00, 0x0C, 0x00,
    0x0C, 0x00, 0x0C, 0x00, 0x1C, 0x00, 0x7C, 0x00, 0x78, 0x00, 0x3C, 0x20,
    0x7F, 0xE0, 0x43, 0xC0,
};

static const GFXglyph DeckFontDisplayGlyphs[] PROGMEM = {
    {0, 0, 0, 12, 0, 0},
    {0, 4, 15, 12, 4, 0},
    {15, 8, 5, 12, 2, 0},
    {20, 12, 15, 12, 0, 0},
    {50, 10, 18, 12, 1, 0},
    {86, 12, 15, 12, 0, 0},
    {116, 12, 15, 12, 0, 0},
    {146, 4, 5, 12, 4, 0},
    {151, 6, 18, 12, 3, 0},
    {169, 6, 18, 12, 3, 0},
    {187, 10, 10, 12, 1, 0},
    {207, 12, 10, 12, 0, 4},
    {227, 5, 6, 12, 3, 11},
    {233, 8, 3, 12, 2, 8},
    {236, 4, 4, 12, 4, 11},
    {240, 10, 16, 12, 1, 0},
    {272, 10, 15, 12, 1, 0},
    {302, 11, 15, 12, 1, 0},
    {332, 10, 15, 12, 1, 0},
    {362, 10, 15, 12, 1, 0},
    {392, 11, 15, 12, 1, 0},
    {422, 10, 15, 12, 1, 0},
    {452, 10, 15, 12, 1, 0},
    {482, 10, 15, 12, 1, 0},
    {512, 10, 15, 12, 1, 0},
    {542, 10, 15, 12, 1, 0},
    {572, 4, 10, 12, 4, 5},
    {582, 5, 12, 12, 3, 5},
    {594, 12, 10, 12, 0, 4},
    {614, 12, 6, 12, 0, 6},
    {626, 12, 10, 12, 0, 4},
    {646, 9, 15, 12, 2, 0},
    {676, 12, 17, 12, 0, 1},
    {710, 12, 15, 12, 0, 0},
    {740, 11, 15, 12, 1, 0},
    {770, 10, 15, 12, 1, 0},
    {800, 11, 15, 12, 1, 0},
    {830, 10, 15, 12, 1, 0},
    {860, 10, 15, 12, 1, 0},
    {890, 11, 15, 12, 1, 0},
    {920, 10, 15, 12, 1, 0},
    {950, 10, 15, 12, 1, 0},
    {980, 9, 15, 12, 1, 0},
    {1010, 11, 15, 12, 1, 0},
    {1040, 10, 15, 12, 2, 0},
    {1070, 12, 15, 12, 0, 0},
    {1100, 10, 15, 12, 1, 0},
    {1130, 12, 15, 12, 0, 0},
    {1160, 11, 15, 12, 1, 0},
    {1190, 12, 17, 12, 0, 0},
    {1224, 11, 15, 12, 1, 0},
    {1254, 10, 15, 12, 1, 0},
    {1284, 12, 15, 12, 0, 0},
    {1314, 10, 15, 12, 1, 0},
    {1344, 12, 15, 12, 0, 0},
    {1374, 13, 15, 12, 0, 0},
    {1404, 12, 15, 12, 0, 0},
    {1434, 12, 15, 12, 0, 0},
    {1464, 11, 15, 12, 1, 0},
    {1494, 6, 18, 12, 4, 0},
    {1512, 10, 16, 12, 1, 0},
    {1544, 6, 18, 12, 2, 0},
    {1562, 12, 5, 12, 0, 0},
    {1572, 13, 2, 12, 0, 18},
    {1576, 6, 4, 12, 2, -1},
    {1580, 11, 11, 12, 0, 4},
    {1602, 11, 15, 12, 1, 0},
    {1632, 10, 11, 12, 1, 4},
    {1654, 11, 15, 12, 0, 0},
    {1684, 12, 11, 12, 0, 4},
    {1706, 10, 15, 12, 1, 0},
    {1736, 11, 15, 12, 0, 4},
    {1766, 10, 15, 12, 1, 0},
    {1796, 11, 16, 12, 1, -1},
    {1828, 8, 20, 12, 1, -1},
    {1848, 11, 15, 12, 1, 0},
    {1878, 11, 15, 12, 0, 0},
    {1908, 12, 11, 12, 0, 4},
    {1930, 10, 11, 12, 1, 4},
    {1952, 12, 11, 12, 0, 4},
    {1974, 11, 15, 12, 1, 4},
    {2004, 11, 15, 12, 0, 4},
    {2034, 10, 11, 12, 2, 4},
    {2056, 10, 11, 12, 1, 4},
    {2078, 10, 14, 12, 1, 1},
    {2106, 10, 11, 12, 1, 4},
    {2128, 12, 11, 12, 0, 4},
    {2150, 13, 11, 12, 0, 4},
    {2172, 12, 11, 12, 0, 4},
    {2194, 12, 15, 12, 0, 4},
    {2224, 10, 11, 12, 1, 4},
    {2246, 10, 18, 12, 1, 0},
    {2282, 4, 20, 12, 4, 0},
    {2302, 10, 18, 12, 1, 0},
    {2338, 12, 3, 12, 0, 7},
};

const GFXfont DeckFontDisplay PROGMEM = {(uint8_t *)DeckFontDisplayBits, (GFXglyph *)DeckFontDisplayGlyphs, 32, 126, 24};

static const uint8_t DeckFontClockBits[] PROGMEM = {
    0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0x70, 0x00, 0x00, 0x00, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0x01, 0xC7, 0x00, 0x01, 0xC7, 0x00, 0x03, 0xC7, 0x00, 0x03,
    0x8F, 0x00, 0x03, 0x8E, 0x00, 0x3F, 0xFF, 0xC0, 0x3F, 0xFF, 0xC0, 0x3F,
    0xFF, 0xC0, 0x07, 0x1C, 0x00, 0x07, 0x1C, 0x00, 0x0E, 0x3C, 0x00, 0x0E,
    0x38, 0x00, 0xFF, 0xFF, 0x00, 0xFF, 0xFF, 0x00, 0xFF, 0xFF, 0x00, 0x1C,
    0x70, 0x00, 0x1C, 0x70, 0x00, 0x3C, 0x70, 0x00, 0x38, 0xF0, 0x00, 0x38,
    0xE0, 0x00, 0x03, 0x00, 0x03, 0x00, 0x03, 0x00, 0x0F, 0xE0, 0x3F, 0xF8,
    0x7F, 0xF8, 0x7B, 0x38, 0xF3, 0x00, 0xF3, 0x00, 0xFB, 0x00, 0x7F, 0x00,
    0x7F, 0xC0, 0x3F, 0xF0, 0x0F, 0xF8, 0x03, 0xFC, 0x03, 0x3C, 0x03, 0x3C,
    0x03, 0x3C, 0x73, 0x7C, 0x7F, 0xF8, 0x7F, 0xF0, 0x1F, 0xE0, 0x03, 0x00,
    0x03, 0x00, 0x03, 0x00, 0x03, 0x00, 0x1E, 0x00, 0x00, 0x3F, 0x80, 0x00,
    0x73, 0x80, 0x00, 0x61, 0xC0, 0x00, 0x61, 0xC0, 0x00, 0x61, 0xC0, 0x00,
    0x73, 0x80, 0x00, 0x3F, 0x81, 0x80, 0x1E, 0x07, 0x00, 0x00, 0x1C, 0x00,
    0x00, 0x70, 0x00, 0x03, 0x80, 0x00, 0x0E, 0x00, 0x00, 0x38, 0x1E, 0x00,
    0x20, 0x3F, 0x80, 0x00, 0x73, 0x80, 0x00, 0x61, 0xC0, 0x00, 0x61, 0xC0,
    0x00, 0x61, 0xC0, 0x00, 0x73, 0x80, 0x00, 0x3F, 0x80, 0x00, 0x1E, 0x00,
    0x03, 0xF0, 0x00, 0x07, 0xFC, 0x00, 0x0F, 0xFC, 0x00, 0x1F, 0x08, 0x00,
    0x1E, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x0F, 0x80, 0x00,
    0x0F, 0x80, 0x00, 0x1F, 0xC0, 0x00, 0x3F, 0xE0, 0x00, 0x3F, 0xE3, 0xC0,
    0x79, 0xF3, 0xC0, 0x78, 0xFB, 0xC0, 0x78, 0x7B, 0xC0, 0x78, 0x7F, 0x80,
    0x78, 0x3F, 0x80, 0x78, 0x1F, 0x00, 0x7E, 0x3F, 0x00, 0x3F, 0xFF, 0x00,
    0x1F, 0xFF, 0x80, 0x07, 0xE7, 0xC0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0x07, 0x00, 0x0F, 0x00, 0x0E, 0x00, 0x1E, 0x00, 0x1C, 0x00,
    0x3C, 0x00, 0x3C, 0x00, 0x3C, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00,
    0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00,
    0x78, 0x00, 0x78, 0x00, 0x3C, 0x00, 0x3C, 0x00, 0x3C, 0x00, 0x1E, 0x00,
    0x1E, 0x00, 0x0E, 0x00, 0x0F, 0x00, 0x07, 0x00, 0x70, 0x00, 0x78, 0x00,
    0x38, 0x00, 0x3C, 0x00, 0x1C, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x0F, 0x00,
    0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00,
    0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00, 0x0F, 0x00,
    0x1E, 0x00, 0x1E, 0x00, 0x1C, 0x00, 0x3C, 0x00, 0x38, 0x00, 0x78, 0x00,
    0x70, 0x00, 0x01, 0x80, 0x01, 0x80, 0x21, 0x84, 0x71, 0x8E, 0x7F, 0xBE,
    0x1F, 0xF8, 0x07, 0xE0, 0x07, 0xE0, 0x1F, 0xF8, 0x7D, 0xBE, 0x71, 0x8E,
    0x21, 0x84, 0x01, 0x80, 0x01, 0x80, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00,
    0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00,
    0x7F, 0xFF, 0x80, 0x7F, 0xFF, 0x80, 0x7F, 0xFF, 0x80, 0x01, 0xE0, 0x00,
    0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00,
    0x01, 0xE0, 0x00, 0x3C, 0x3C, 0x3C, 0x3C, 0x3C, 0x7C, 0x78, 0x78, 0x70,
    0xF0, 0xFF, 0xC0, 0xFF, 0xC0, 0xFF, 0xC0, 0xFF, 0xC0, 0x78, 0x78, 0x78,
    0x78, 0x78, 0x00, 0x0E, 0x00, 0x1E, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x38,
    0x00, 0x38, 0x00, 0x70, 0x00, 0x70, 0x00, 0xE0, 0x00, 0xE0, 0x01, 0xE0,
    0x01, 0xC0, 0x03, 0xC0, 0x03, 0x80, 0x07, 0x80, 0x07, 0x00, 0x07, 0x00,
    0x0E, 0x00, 0x0E, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x3C, 0x00, 0x38, 0x00,
    0x78, 0x00, 0x70, 0x00, 0x07, 0xE0, 0x0F, 0xF0, 0x1F, 0xF8, 0x3E, 0x7C,
    0x3C, 0x3C, 0x7C, 0x3E, 0x78, 0x1E, 0x78, 0x1E, 0x79, 0x9E, 0x7B, 0xDE,
    0x7B, 0xDE, 0x79, 0x9E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E,
    0x7C, 0x3E, 0x3C, 0x3C, 0x3E, 0x7C, 0x1F, 0xF8, 0x0F, 0xF0, 0x07, 0xE0,
    0x1F, 0xC0, 0x7F, 0xC0, 0x7F, 0xC0, 0x63, 0xC0, 0x03, 0xC0, 0x03, 0xC0,
    0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0,
    0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0,
    0x03, 0xC0, 0x7F, 0xFE, 0x7F, 0xFE, 0x7F, 0xFE, 0x1F, 0xC0, 0x7F, 0xF0,
    0x7F, 0xF8, 0x70, 0x7C, 0x40, 0x3C, 0x00, 0x3C, 0x00, 0x3E, 0x00, 0x3C,
    0x00, 0x3C, 0x00, 0x7C, 0x00, 0xF8, 0x01, 0xF0, 0x01, 0xF0, 0x03, 0xE0,
    0x07, 0xC0, 0x0F, 0x80, 0x1F, 0x00, 0x3E, 0x00, 0x7C, 0x00, 0x7F, 0xFE,
    0x7F, 0xFE, 0x7F, 0xFE, 0x0F, 0xE0, 0x7F, 0xF8, 0x7F, 0xFC, 0x78, 0x7C,
    0x00, 0x3E, 0x00, 0x3E, 0x00, 0x3C, 0x00, 0x7C, 0x07, 0xF8, 0x07, 0xE0,
    0x07, 0xF8, 0x00, 0x7C, 0x00, 0x3E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E,
    0x00, 0x1E, 0x40, 0x3E, 0x70, 0x7C, 0x7F, 0xFC, 0x7F, 0xF8, 0x1F, 0xE0,
    0x00, 0xF8, 0x00, 0xF8, 0x01, 0xF8, 0x01, 0xF8, 0x03, 0xF8, 0x07, 0xF8,
    0x07, 0x78, 0x0F, 0x78, 0x1E, 0x78, 0x1C, 0x78, 0x3C, 0x78, 0x78, 0x78,
    0x78, 0x78, 0xF0, 0x78, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x78,
    0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x7F, 0xF8, 0x7F, 0xF8,
    0x7F, 0xF8, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00, 0x70, 0x00,
    0x7F, 0xC0, 0x7F, 0xE0, 0x7F, 0xF0, 0x40, 0xF8, 0x00, 0x7C, 0x00, 0x3C,
    0x00, 0x3C, 0x00, 0x3C, 0x00, 0x3C, 0x80, 0x7C, 0xE0, 0xF8, 0xFF, 0xF0,
    0xFF, 0xE0, 0x3F, 0x80, 0x01, 0xF8, 0x07, 0xFC, 0x1F, 0xFC, 0x1F, 0x0C,
    0x3C, 0x04, 0x3C, 0x00, 0x78, 0x00, 0x79, 0xF0, 0x7F, 0xF8, 0x7F, 0xFC,
    0x7E, 0x3E, 0x7C, 0x1E, 0x7C, 0x1E, 0x7C, 0x1E, 0x7C, 0x1E, 0x7C, 0x1E,
    0x3C, 0x1E, 0x3C, 0x1E, 0x3E, 0x3E, 0x1F, 0xFC, 0x0F, 0xF8, 0x03, 0xE0,
    0x7F, 0xFE, 0x7F, 0xFE, 0x7F, 0xFE, 0x00, 0x3E, 0x00, 0x3C, 0x00, 0x7C,
    0x00, 0x78, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF0, 0x01, 0xF0, 0x01, 0xF0,
    0x01, 0xE0, 0x03, 0xE0, 0x03, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0x80,
    0x0F, 0x80, 0x0F, 0x00, 0x0F, 0x00, 0x1F, 0x00, 0x07, 0xE0, 0x1F, 0xF8,
    0x3F, 0xFC, 0x3C, 0x3C, 0x3C, 0x3C, 0x78, 0x1E, 0x38, 0x1E, 0x3C, 0x3C,
    0x3C, 0x3C, 0x1F, 0xF8, 0x0F, 0xF0, 0x1F, 0xF8, 0x3C, 0x3C, 0x78, 0x1E,
    0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x7C, 0x3E, 0x3F, 0xFC,
    0x1F, 0xF8, 0x07, 0xE0, 0x07, 0xC0, 0x1F, 0xF0, 0x3F, 0xF8, 0x7C, 0x7C,
    0x78, 0x3C, 0x78, 0x3E, 0x78, 0x3E, 0x78, 0x1E, 0x78, 0x3E, 0x78, 0x3E,
    0x78, 0x3E, 0x7C, 0x7E, 0x3F, 0xFE, 0x1F, 0xFE, 0x0F, 0xDE, 0x00, 0x1E,
    0x00, 0x3C, 0x20, 0x3C, 0x30, 0x78, 0x3F, 0xF8, 0x3F, 0xF0, 0x1F, 0xC0,
    0x78, 0x78, 0x78, 0x78, 0x78, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x78,
    0x78, 0x78, 0x78, 0x78, 0x3C, 0x3C, 0x3C, 0x3C, 0x3C, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x3C, 0x3C, 0x3C, 0x3C, 0x3C, 0x7C, 0x78, 0x78, 0x70,
    0x70, 0x00, 0x01, 0x00, 0x0F, 0x00, 0x3F, 0x01, 0xFF, 0x07, 0xFC, 0x3F,
    0xE0, 0xFF, 0x00, 0xFC, 0x00, 0xF8, 0x00, 0xFF, 0x00, 0x3F, 0xE0, 0x07,
    0xFC, 0x01, 0xFF, 0x00, 0x3F, 0x00, 0x0F, 0x00, 0x01, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xFF, 0xC0, 0x00, 0xF0, 0x00, 0xFC, 0x00, 0xFF, 0x80, 0x3F,
    0xE0, 0x07, 0xFC, 0x00, 0xFF, 0x00, 0x1F, 0x00, 0x1F, 0x00, 0xFF, 0x07,
    0xFC, 0x3F, 0xE0, 0xFF, 0x80, 0xFE, 0x00, 0xF0, 0x00, 0xC0, 0x00, 0x1F,
    0x80, 0x7F, 0xE0, 0xFF, 0xF0, 0xE0, 0xF0, 0x00, 0xF8, 0x00, 0xF8, 0x00,
    0xF0, 0x01, 0xF0, 0x03, 0xE0, 0x07, 0xC0, 0x0F, 0x80, 0x0F, 0x00, 0x0F,
    0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1E,
    0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x01, 0xF8, 0x00, 0x07, 0xFE,
    0x00, 0x0F, 0xFF, 0x00, 0x1F, 0x0F, 0x00, 0x3C, 0x07, 0x80, 0x38, 0x03,
    0x80, 0x70, 0x7B, 0x80, 0x71, 0xFF, 0x80, 0x71, 0xFF, 0x80, 0xE3, 0xC7,
    0x80, 0xE3, 0x83, 0x80, 0xE3, 0x83, 0x80, 0xE3, 0x83, 0x80, 0xE3, 0x83,
    0x80, 0xE3, 0x83, 0x80, 0xE3, 0xC7, 0x80, 0x71, 0xFF, 0x80, 0x71, 0xFF,
    0x80, 0x78, 0x7B, 0x80, 0x38, 0x00, 0x00, 0x3C, 0x00, 0x00, 0x1F, 0x03,
    0x00, 0x0F, 0xFF, 0x00, 0x03, 0xFF, 0x00, 0x00, 0xFC, 0x00, 0x03, 0xF0,
    0x00, 0x03, 0xF0, 0x00, 0x03, 0xF0, 0x00, 0x07, 0xF8, 0x00, 0x07, 0xF8,
    0x00, 0x07, 0xF8, 0x00, 0x07, 0x38, 0x00, 0x0F, 0x3C, 0x00, 0x0F, 0x3C,
    0x00, 0x0F, 0x3C, 0x00, 0x0F, 0x1C, 0x00, 0x1E, 0x1E, 0x00, 0x1E, 0x1E,
    0x00, 0x1F, 0xFE, 0x00, 0x3F, 0xFF, 0x00, 0x3F, 0xFF, 0x00, 0x3C, 0x0F,
    0x00, 0x3C, 0x0F, 0x00, 0x7C, 0x0F, 0x80, 0x78, 0x07, 0x80, 0x78, 0x07,
    0x80, 0x78, 0x07, 0x80, 0x7F, 0xF0, 0x7F, 0xFC, 0x7F, 0xFC, 0x78, 0x3E,
    0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x3E, 0x7F, 0xFC, 0x7F, 0xF0,
    0x7F, 0xFC, 0x78, 0x3E, 0x78, 0x1E, 0x78, 0x1F, 0x78, 0x0F, 0x78, 0x0F,
    0x78, 0x1F, 0x78, 0x1F, 0x78, 0x3E, 0x7F, 0xFE, 0x7F, 0xFC, 0x7F, 0xF0,
    0x03, 0xF0, 0x0F, 0xFC, 0x1F, 0xFC, 0x3F, 0x1C, 0x3C, 0x04, 0x7C, 0x00,
    0x78, 0x00, 0x78, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00,
    0xF8, 0x00, 0xF8, 0x00, 0x78, 0x00, 0x78, 0x00, 0x7C, 0x00, 0x3C, 0x04,
    0x3F, 0x1C, 0x1F, 0xFC, 0x0F, 0xFC, 0x03, 0xF0, 0xFF, 0x80, 0xFF, 0xE0,
    0xFF, 0xF0, 0xF0, 0xF8, 0xF0, 0x7C, 0xF0, 0x7C, 0xF0, 0x3C, 0xF0, 0x3C,
    0xF0, 0x3E, 0xF0, 0x3E, 0xF0, 0x3E, 0xF0, 0x3E, 0xF0, 0x3E, 0xF0, 0x3E,
    0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x7C, 0xF0, 0x7C, 0xF0, 0xF8, 0xFF, 0xF0,
    0xFF, 0xE0, 0xFF, 0x80, 0xFF, 0xFC, 0xFF, 0xFC, 0xFF, 0xFC, 0xF8, 0x00,
    0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xFF, 0xF8, 0xFF, 0xF8,
    0xFF, 0xF8, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00,
    0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xFF, 0xFC, 0xFF, 0xFC, 0xFF, 0xFC,
    0x7F, 0xFC, 0x7F, 0xFC, 0x7F, 0xFC, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00,
    0x78, 0x00, 0x78, 0x00, 0x7F, 0xF8, 0x7F, 0xF8, 0x7F, 0xF8, 0x78, 0x00,
    0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00,
    0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x01, 0xF8, 0x07, 0xFE,
    0x1F, 0xFE, 0x1F, 0x0E, 0x3E, 0x02, 0x3C, 0x00, 0x7C, 0x00, 0x7C, 0x00,
    0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x7F, 0x78, 0x7F, 0x78, 0x7F,
    0x7C, 0x0F, 0x7C, 0x0F, 0x3C, 0x0F, 0x3E, 0x0F, 0x1F, 0x0F, 0x1F, 0xFF,
    0x07, 0xFE, 0x01, 0xF8, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C,
    0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xFF, 0xFC, 0xFF, 0xFC,
    0xFF, 0xFC, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C,
    0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C, 0xF0, 0x3C,
    0x7F, 0xFC, 0x7F, 0xFC, 0x7F, 0xFC, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80,
    0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80,
    0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80,
    0x07, 0x80, 0x7F, 0xFC, 0x7F, 0xFC, 0x7F, 0xFC, 0x0F, 0xFC, 0x0F, 0xFC,
    0x0F, 0xFC, 0x00, 0x7C, 0x00, 0x7C, 0x00, 0x7C, 0x00, 0x7C, 0x00, 0x7C,
    0x00, 0x7C, 0x00, 0x7C, 0x00, 0x7C, 0x00, 0x7C, 0x00, 0x7C, 0x00, 0x7C,
    0x00, 0x7C, 0x00, 0x7C, 0x40, 0x7C, 0x60, 0x78, 0x70, 0xF8, 0x7F, 0xF8,
    0x7F, 0xF0, 0x0F, 0xC0, 0x78, 0x1F, 0x00, 0x78, 0x1F, 0x00, 0x78, 0x3E,
    0x00, 0x78, 0x7C, 0x00, 0x78, 0xF8, 0x00, 0x78, 0xF0, 0x00, 0x79, 0xF0,
    0x00, 0x7B, 0xE0, 0x00, 0x7F, 0xC0, 0x00, 0x7F, 0xC0, 0x00, 0x7F, 0xE0,
    0x00, 0x7F, 0xE0, 0x00, 0x7F, 0xF0, 0x00, 0x7C, 0xF0, 0x00, 0x78, 0xF8,
    0x00, 0x78, 0x78, 0x00, 0x78, 0x7C, 0x00, 0x78, 0x3E, 0x00, 0x78, 0x3E,
    0x00, 0x78, 0x1F, 0x00, 0x78, 0x1F, 0x00, 0x78, 0x0F, 0x80, 0xF8, 0x00,
    0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00,
    0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00,
    0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00,
    0xFF, 0xFC, 0xFF, 0xFC, 0xFF, 0xFC, 0xFC, 0x3F, 0xFC, 0x3F, 0xFC, 0x3F,
    0xFC, 0x3F, 0xFE, 0x7F, 0xFE, 0x7F, 0xFE, 0x7F, 0xF6, 0x6F, 0xF7, 0xEF,
    0xF7, 0xEF, 0xF7, 0xEF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF0, 0x0F,
    0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F,
    0xF0, 0x0F, 0x7C, 0x1E, 0x7C, 0x1E, 0x7C, 0x1E, 0x7E, 0x1E, 0x7E, 0x1E,
    0x7E, 0x1E, 0x7F, 0x1E, 0x7F, 0x1E, 0x7F, 0x9E, 0x7B, 0x9E, 0x7B, 0x9E,
    0x79, 0xDE, 0x79, 0xDE, 0x79, 0xFE, 0x78, 0xFE, 0x78, 0xFE, 0x78, 0x7E,
    0x78, 0x7E, 0x78, 0x7E, 0x78, 0x3E, 0x78, 0x3E, 0x78, 0x3E, 0x07, 0xE0,
    0x0F, 0xF8, 0x1F, 0xFC, 0x3E, 0x7C, 0x7C, 0x3E, 0x78, 0x1E, 0x78, 0x1E,
    0x78, 0x1E, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F,
    0xF8, 0x1F, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0x7C, 0x3E, 0x3E, 0x7C,
    0x1F, 0xFC, 0x0F, 0xF8, 0x07, 0xE0, 0xFF, 0xC0, 0xFF, 0xF8, 0xFF, 0xFC,
    0xF8, 0x7C, 0xF8, 0x3C, 0xF8, 0x3E, 0xF8, 0x3E, 0xF8, 0x3E, 0xF8, 0x3E,
    0xF8, 0x3C, 0xF8, 0x7C, 0xFF, 0xFC, 0xFF, 0xF8, 0xFF, 0xC0, 0xF8, 0x00,
    0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00,
    0xF8, 0x00, 0x07, 0xE0, 0x0F, 0xF8, 0x1F, 0xF8, 0x3E, 0x7C, 0x7C, 0x3E,
    0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E, 0xF8, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F,
    0xF8, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0x78, 0x1E, 0x78, 0x1E, 0x78, 0x1E,
    0x7C, 0x3E, 0x3E, 0x7C, 0x1F, 0xFC, 0x0F, 0xF8, 0x07, 0xF8, 0x00, 0x7C,
    0x00, 0x3E, 0x00, 0x1C, 0x00, 0x18, 0x7F, 0xE0, 0x00, 0x7F, 0xF8, 0x00,
    0x7F, 0xFC, 0x00, 0x78, 0x7E, 0x00, 0x78, 0x3E, 0x00, 0x78, 0x1E, 0x00,
    0x78, 0x1E, 0x00, 0x78, 0x1E, 0x00, 0x78, 0x3E, 0x00, 0x78, 0x7C, 0x00,
    0x7F, 0xF8, 0x00, 0x7F, 0xF0, 0x00, 0x7F, 0xF0, 0x00, 0x78, 0xF8, 0x00,
    0x78, 0x78, 0x00, 0x78, 0x7C, 0x00, 0x78, 0x3C, 0x00, 0x78, 0x3E, 0x00,
    0x78, 0x1E, 0x00, 0x78, 0x1F, 0x00, 0x78, 0x0F, 0x00, 0x78, 0x0F, 0x80,
    0x07, 0xF0, 0x1F, 0xFC, 0x3F, 0xFC, 0x7C, 0x1C, 0x78, 0x0C, 0x78, 0x04,
    0x78, 0x00, 0x7C, 0x00, 0x7F, 0x00, 0x3F, 0xE0, 0x0F, 0xF8, 0x03, 0xFC,
    0x00, 0x7E, 0x00, 0x3E, 0x00, 0x1E, 0x00, 0x1E, 0x40, 0x1E, 0x70, 0x3E,
    0x78, 0x7E, 0x7F, 0xFC, 0x3F, 0xF8, 0x0F, 0xE0, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0,
    0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0,
    0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0,
    0x03, 0xC0, 0x03, 0xC0, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F,
    0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F,
    0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1F, 0x78, 0x1E, 0x78, 0x1E,
    0x78, 0x1E, 0x78, 0x1E, 0x7C, 0x3E, 0x3F, 0xFC, 0x1F, 0xF8, 0x07, 0xE0,
    0x78, 0x07, 0x80, 0x7C, 0x07, 0x80, 0x7C, 0x0F, 0x80, 0x3C, 0x0F, 0x00,
    0x3C, 0x0F, 0x00, 0x3C, 0x0F, 0x00, 0x3E, 0x0F, 0x00, 0x1E, 0x1E, 0x00,
    0x1E, 0x1E, 0x00, 0x1E, 0x1E, 0x00, 0x1E, 0x1E, 0x00, 0x0F, 0x3E, 0x00,
    0x0F, 0x3C, 0x00, 0x0F, 0x3C, 0x00, 0x0F, 0x3C, 0x00, 0x0F, 0x3C, 0x00,
    0x07, 0xF8, 0x00, 0x07, 0xF8, 0x00, 0x07, 0xF8, 0x00, 0x07, 0xF8, 0x00,
    0x03, 0xF0, 0x00, 0x03, 0xF0, 0x00, 0xF0, 0x03, 0xC0, 0xF0, 0x03, 0xC0,
    0xF0, 0x03, 0xC0, 0xF0, 0x03, 0xC0, 0x70, 0x03, 0xC0, 0x70, 0x03, 0x80,
    0x71, 0xE3, 0x80, 0x79, 0xE3, 0x80, 0x79, 0xE3, 0x80, 0x79, 0xF3, 0x80,
    0x7B, 0xF7, 0x80, 0x7B, 0xF7, 0x80, 0x3B, 0xF7, 0x80, 0x3B, 0x37, 0x80,
    0x3F, 0x3F, 0x00, 0x3F, 0x3F, 0x00, 0x3F, 0x3F, 0x00, 0x3F, 0x1F, 0x00,
    0x3E, 0x1F, 0x00, 0x3E, 0x1F, 0x00, 0x1E, 0x1F, 0x00, 0x1E, 0x1F, 0x00,
    0x78, 0x07, 0x80, 0x7C, 0x0F, 0x80, 0x3C, 0x0F, 0x00, 0x1E, 0x1E, 0x00,
    0x1F, 0x3E, 0x00, 0x0F, 0x3C, 0x00, 0x0F, 0xFC, 0x00, 0x07, 0xF8, 0x00,
    0x07, 0xF8, 0x00, 0x03, 0xF0, 0x00, 0x01, 0xE0, 0x00, 0x03, 0xF0, 0x00,
    0x03, 0xF0, 0x00, 0x07, 0xF8, 0x00, 0x07, 0xF8, 0x00, 0x0F, 0xFC, 0x00,
    0x0F, 0x3E, 0x00, 0x1E, 0x1E, 0x00, 0x3E, 0x1F, 0x00, 0x3C, 0x0F, 0x00,
    0x7C, 0x0F, 0x80, 0x78, 0x07, 0x80, 0xF8, 0x07, 0xC0, 0x7C, 0x07, 0x80,
    0x7C, 0x0F, 0x80, 0x3C, 0x0F, 0x00, 0x3E, 0x1F, 0x00, 0x1E, 0x1E, 0x00,
    0x1F, 0x3E, 0x00, 0x0F, 0x3C, 0x00, 0x0F, 0xFC, 0x00, 0x07, 0xF8, 0x00,
    0x07, 0xF8, 0x00, 0x03, 0xF0, 0x00, 0x03, 0xF0, 0x00, 0x01, 0xE0, 0x00,
    0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00,
    0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00,
    0x7F, 0xFF, 0x7F, 0xFF, 0x7F, 0xFF, 0x00, 0x3F, 0x00, 0x3E, 0x00, 0x7E,
    0x00, 0xFC, 0x00, 0xF8, 0x01, 0xF8, 0x03, 0xF0, 0x03, 0xE0, 0x07, 0xE0,
    0x0F, 0xC0, 0x0F, 0x80, 0x1F, 0x80, 0x3F, 0x00, 0x3E, 0x00, 0x7E, 0x00,
    0x7C, 0x00, 0x7F, 0xFF, 0x7F, 0xFF, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xF0,
    0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xFF, 0xFF, 0xFF, 0x70,
    0x00, 0x78, 0x00, 0x38, 0x00, 0x3C, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x0E,
    0x00, 0x0E, 0x00, 0x07, 0x00, 0x07, 0x00, 0x07, 0x80, 0x03, 0x80, 0x03,
    0xC0, 0x01, 0xC0, 0x01, 0xE0, 0x00, 0xE0, 0x00, 0xE0, 0x00, 0x70, 0x00,
    0x70, 0x00, 0x38, 0x00, 0x38, 0x00, 0x1C, 0x00, 0x1C, 0x00, 0x1E, 0x00,
    0x0E, 0xFF, 0xFF, 0xFF, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F,
    0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F,
    0x0F, 0xFF, 0xFF, 0xFF, 0x01, 0xE0, 0x00, 0x03, 0xF0, 0x00, 0x07, 0xF8,
    0x00, 0x0F, 0xFC, 0x00, 0x0F, 0x3C, 0x00, 0x1E, 0x1E, 0x00, 0x3C, 0x0F,
    0x00, 0x78, 0x07, 0x80, 0xFF, 0xFF, 0xC0, 0xFF, 0xFF, 0xC0, 0xFF, 0xFF,
    0xC0, 0xF0, 0x00, 0x78, 0x00, 0x3C, 0x00, 0x1C, 0x00, 0x0E, 0x00, 0x07,
    0x00, 0x0F, 0xF0, 0x3F, 0xFC, 0x3F, 0xFC, 0x30, 0x3E, 0x00, 0x1E, 0x0F,
    0xFE, 0x3F, 0xFE, 0x7F, 0xFE, 0x7C, 0x1E, 0xF8, 0x1E, 0xF8, 0x1E, 0xF8,
    0x3E, 0x7C, 0x7E, 0x7F, 0xFE, 0x3F, 0xDE, 0x0F, 0x9E, 0xF0, 0x00, 0xF0,
    0x00, 0xF0, 0x00, 0xF0, 0x00, 0xF0, 0x00, 0xF0, 0x00, 0xF0, 0x00, 0xF1,
    0xE0, 0xFF, 0xF8, 0xFF, 0xF8, 0xFC, 0x7C, 0xF8, 0x3C, 0xF8, 0x3E, 0xF8,
    0x3E, 0xF0, 0x3E, 0xF0, 0x3E, 0xF8, 0x3E, 0xF8, 0x3E, 0xF8, 0x3C, 0xFC,
    0x7C, 0xFF, 0xF8, 0xFF, 0xF8, 0xF1, 0xE0, 0x07, 0xF0, 0x1F, 0xFC, 0x3F,
    0xFC, 0x3E, 0x08, 0x7C, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78,
    0x00, 0x78, 0x00, 0x78, 0x00, 0x7C, 0x00, 0x3E, 0x08, 0x3F, 0xFC, 0x1F,
    0xFC, 0x07, 0xF0, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00,
    0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x0F, 0x9E, 0x3F, 0xDE, 0x3F, 0xFE, 0x7C,
    0x7E, 0x78, 0x3E, 0x78, 0x3E, 0xF8, 0x3E, 0xF8, 0x1E, 0xF8, 0x1E, 0xF8,
    0x3E, 0x78, 0x3E, 0x78, 0x3E, 0x7C, 0x7E, 0x3F, 0xFE, 0x3F, 0xDE, 0x0F,
    0x9E, 0x07, 0xE0, 0x1F, 0xF8, 0x3F, 0xFC, 0x7C, 0x3E, 0x7C, 0x1E, 0x78,
    0x1E, 0xF8, 0x1F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x78, 0x00, 0x78,
    0x00, 0x7C, 0x06, 0x3F, 0xFE, 0x1F, 0xFE, 0x07, 0xF8, 0x01, 0xFC, 0x03,
    0xFC, 0x07, 0xFC, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x7F,
    0xFC, 0x7F, 0xFC, 0x7F, 0xFC, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x0F, 0x9E, 0x1F, 0xFE, 0x3F,
    0xFE, 0x7C, 0x7E, 0x7C, 0x3E, 0x78, 0x3E, 0x78, 0x1E, 0xF8, 0x1E, 0xF8,
    0x1E, 0x78, 0x1E, 0x78, 0x3E, 0x7C, 0x3E, 0x7C, 0x7E, 0x3F, 0xFE, 0x1F,
    0xFE, 0x0F, 0x9E, 0x00, 0x1E, 0x00, 0x3E, 0x30, 0x7C, 0x3F, 0xFC, 0x3F,
    0xF8, 0x0F, 0xE0, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78,
    0x00, 0x78, 0x00, 0x78, 0x00, 0x79, 0xE0, 0x7F, 0xF8, 0x7F, 0xF8, 0x7C,
    0x78, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78,
    0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78,
    0x7C, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x7F, 0xC0, 0x7F, 0xC0, 0x7F, 0xC0, 0x07,
    0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07,
    0xC0, 0x07, 0xC0, 0x07, 0xC0, 0x07, 0xC0, 0xFF, 0xFE, 0xFF, 0xFE, 0xFF,
    0xFE, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x3F, 0xC0, 0x3F, 0xC0, 0x3F, 0xC0, 0x03,
    0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03,
    0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x03,
    0xC0, 0x03, 0xC0, 0x03, 0xC0, 0x07, 0xC0, 0xFF, 0xC0, 0xFF, 0x80, 0xFF,
    0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78, 0x00, 0x78,
    0x00, 0x78, 0x00, 0x78, 0x3E, 0x78, 0x7C, 0x78, 0xF8, 0x79, 0xF0, 0x7B,
    0xE0, 0x7F, 0x80, 0x7F, 0x80, 0x7F, 0xC0, 0x7F, 0xE0, 0x79, 0xE0, 0x78,
    0xF0, 0x78, 0xF8, 0x78, 0x78, 0x78, 0x7C, 0x78, 0x3E, 0x78, 0x1E, 0xFF,
    0x80, 0xFF, 0x80, 0xFF, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0xC0, 0x07, 0xFE, 0x03, 0xFE, 0x00, 0xFE, 0xE7, 0x3C, 0xFF,
    0xFE, 0xFF, 0xFF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3,
    0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0xF3,
    0xCF, 0xF3, 0xCF, 0xF3, 0xCF, 0x79, 0xE0, 0x7F, 0xF8, 0x7F, 0xF8, 0x7C,
    0x78, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78,
    0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78, 0x7C, 0x78,
    0x7C, 0x07, 0xE0, 0x1F, 0xF8, 0x3F, 0xFC, 0x3C, 0x3E, 0x7C, 0x1E, 0x78,
    0x1E, 0x78, 0x1F, 0xF8, 0x1F, 0xF8, 0x1F, 0x78, 0x1F, 0x78, 0x1E, 0x7C,
    0x1E, 0x3C, 0x3E, 0x3F, 0xFC, 0x1F, 0xF8, 0x07, 0xE0, 0xF1, 0xE0, 0xFF,
    0xF8, 0xFF, 0xF8, 0xFC, 0x7C, 0xF8, 0x3C, 0xF8, 0x3E, 0xF8, 0x3E, 0xF0,
    0x3E, 0xF0, 0x3E, 0xF8, 0x3E, 0xF8, 0x3E, 0xF8, 0x3C, 0xFC, 0x7C, 0xFF,
    0xF8, 0xFF, 0xF8, 0xF1, 0xE0, 0xF0, 0x00, 0xF0, 0x00, 0xF0, 0x00, 0xF0,
    0x00, 0xF0, 0x00, 0xF0, 0x00, 0x0F, 0x9E, 0x3F, 0xDE, 0x3F, 0xFE, 0x7C,
    0x7E, 0x78, 0x3E, 0x78, 0x3E, 0xF8, 0x3E, 0xF8, 0x1E, 0xF8, 0x1E, 0xF8,
    0x3E, 0x78, 0x3E, 0x78, 0x3E, 0x7C, 0x7E, 0x3F, 0xFE, 0x3F, 0xDE, 0x0F,
    0x9E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00, 0x1E, 0x00,
    0x1E, 0xF9, 0xF0, 0xFF, 0xF8, 0xFF, 0xF8, 0xFE, 0x18, 0xFC, 0x00, 0xF8,
    0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8,
    0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0xF8, 0x00, 0x0F, 0xE0, 0x3F,
    0xF8, 0x7F, 0xF8, 0x78, 0x10, 0x78, 0x00, 0x7C, 0x00, 0x7F, 0x80, 0x7F,
    0xF0, 0x1F, 0xF8, 0x03, 0xF8, 0x00, 0x7C, 0x00, 0x3C, 0x60, 0x78, 0x7F,
    0xF8, 0x7F, 0xF0, 0x1F, 0xE0, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x7F, 0xFE, 0x7F, 0xFE, 0x7F, 0xFE, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0xC0, 0x07, 0xFE, 0x03, 0xFE, 0x01, 0xFE, 0xF8,
    0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0xF8,
    0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0xF8, 0x7C, 0x78,
    0xFC, 0x7F, 0xFC, 0x3F, 0xFC, 0x1E, 0x7C, 0xF8, 0x1F, 0x78, 0x1E, 0x78,
    0x1E, 0x7C, 0x1E, 0x3C, 0x3C, 0x3C, 0x3C, 0x3C, 0x3C, 0x1E, 0x78, 0x1E,
    0x78, 0x1E, 0x78, 0x0E, 0x70, 0x0F, 0xF0, 0x0F, 0xF0, 0x07, 0xE0, 0x07,
    0xE0, 0x07, 0xE0, 0xF0, 0x03, 0xC0, 0xF0, 0x03, 0xC0, 0xF0, 0x03, 0xC0,
    0x70, 0x03, 0x80, 0x71, 0xE3, 0x80, 0x71, 0xE3, 0x80, 0x79, 0xE7, 0x80,
    0x79, 0xF7, 0x80, 0x3B, 0xF7, 0x00, 0x3B, 0xF7, 0x00, 0x3B, 0x37, 0x00,
    0x3F, 0x3F, 0x00, 0x3F, 0x3F, 0x00, 0x1F, 0x3F, 0x00, 0x1E, 0x1E, 0x00,
    0x1E, 0x1E, 0x00, 0x3E, 0x1F, 0x00, 0x3E, 0x1F, 0x00, 0x1F, 0x3E, 0x00,
    0x0F, 0x3C, 0x00, 0x07, 0xFC, 0x00, 0x07, 0xF8, 0x00, 0x03, 0xF0, 0x00,
    0x01, 0xE0, 0x00, 0x03, 0xF0, 0x00, 0x03, 0xF8, 0x00, 0x07, 0xF8, 0x00,
    0x0F, 0xBC, 0x00, 0x1F, 0x3E, 0x00, 0x1E, 0x1E, 0x00, 0x3E, 0x1F, 0x00,
    0x7C, 0x0F, 0x80, 0x7C, 0x07, 0x80, 0x7C, 0x0F, 0x80, 0x3C, 0x0F, 0x00,
    0x3E, 0x0F, 0x00, 0x1E, 0x1F, 0x00, 0x1E, 0x1E, 0x00, 0x1F, 0x3E, 0x00,
    0x0F, 0x3C, 0x00, 0x0F, 0x3C, 0x00, 0x07, 0xFC, 0x00, 0x07, 0xF8, 0x00,
    0x07, 0xF8, 0x00, 0x03, 0xF0, 0x00, 0x03, 0xF0, 0x00, 0x01, 0xF0, 0x00,
    0x01, 0xE0, 0x00, 0x01, 0xE0, 0x00, 0x03, 0xE0, 0x00, 0x07, 0xC0, 0x00,
    0x3F, 0xC0, 0x00, 0x3F, 0x80, 0x00, 0x3E, 0x00, 0x00, 0x7F, 0xFC, 0x7F,
    0xFC, 0x7F, 0xFC, 0x00, 0x7C, 0x00, 0xF8, 0x01, 0xF0, 0x03, 0xE0, 0x07,
    0xC0, 0x0F, 0x80, 0x1F, 0x00, 0x3E, 0x00, 0x7E, 0x00, 0x7C, 0x00, 0xFF,
    0xFC, 0xFF, 0xFC, 0xFF, 0xFC, 0x01, 0xF8, 0x03, 0xF8, 0x03, 0xF8, 0x07,
    0xC0, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x0F, 0x80, 0x7F, 0x00, 0x7E, 0x00, 0x7F, 0x00, 0x0F,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0xC0, 0x03, 0xF8, 0x03, 0xF8, 0x00,
    0xF8, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0,
    0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0x7E, 0x00, 0x7F, 0x00, 0x7F,
    0x00, 0x0F, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0xC0, 0x03, 0xF8, 0x01, 0xF8, 0x03,
    0xF8, 0x07, 0xC0, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x07,
    0x80, 0x07, 0x80, 0x07, 0x80, 0x07, 0x80, 0x0F, 0x80, 0x7F, 0x00, 0x7F,
    0x00, 0x7C, 0x00, 0x3E, 0x01, 0x7F, 0xC3, 0xFF, 0xFF, 0xC1, 0xFE, 0x00,
    0x7C,
};

static const GFXglyph DeckFontClockGlyphs[] PROGMEM = {
    {0, 0, 0, 18, 0, 0},
    {0, 4, 22, 18, 7, 0},
    {22, 12, 8, 18, 3, 0},
    {38, 18, 20, 18, 0, 2},
    {98, 14, 26, 18, 2, 0},
    {150, 18, 22, 18, 0, 0},
    {216, 19, 22, 18, 0, 0},
    {282, 4, 8, 18, 7, 0},
    {290, 9, 27, 18, 5, -1},
    {344, 9, 27, 18, 4, -1},
    {398, 16, 14, 18, 1, 0},
    {426, 18, 15, 18, 0, 5},
    {471, 7, 10, 18, 5, 17},
    {481, 10, 4, 18, 4, 11},
    {489, 6, 5, 18, 6, 17},
    {494, 16, 25, 18, 1, 0},
    {544, 16, 22, 18, 1, 0},
    {588, 15, 22, 18, 2, 0},
    {632, 15, 22, 18, 1, 0},
    {676, 16, 22, 18, 1, 0},
    {720, 16, 22, 18, 1, 0},
    {764, 15, 22, 18, 2, 0},
    {808, 16, 22, 18, 1, 0},
    {852, 15, 22, 18, 1, 0},
    {896, 16, 22, 18, 1, 0},
    {940, 16, 22, 18, 1, 0},
    {984, 6, 16, 18, 6, 6},
    {1000, 7, 21, 18, 5, 6},
    {1021, 16, 16, 18, 1, 5},
    {1053, 16, 9, 18, 1, 8},
    {1071, 16, 16, 18, 1, 5},
    {1103, 13, 22, 18, 3, 0},
    {1147, 17, 25, 18, 0, 2},
    {1222, 18, 22, 18, 0, 0},
    {1288, 16, 22, 18, 1, 0},
    {1332, 14, 22, 18, 2, 0},
    {1376, 15, 22, 18, 2, 0},
    {1420, 15, 22, 18, 2, 0},
    {1464, 15, 22, 18, 2, 0},
    {1508, 16, 22, 18, 1, 0},
    {1552, 15, 22, 18, 2, 0},
    {1596, 14, 22, 18, 2, 0},
    {1640, 14, 22, 18, 1, 0},
    {1684, 17, 22, 18, 1, 0},
    {1750, 14, 22, 18, 3, 0},
    {1794, 16, 22, 18, 1, 0},
    {1838, 16, 22, 18, 1, 0},
    {1882, 16, 22, 18, 1, 0},
    {1926, 15, 22, 18, 2, 0},
    {1970, 16, 26, 18, 1, 0},
    {2022, 18, 22, 18, 1, 0},
    {2088, 16, 22, 18, 1, 0},
    {2132, 16, 22, 18, 1, 0},
    {2176, 16, 22, 18, 1, 0},
    {2220, 18, 22, 18, 0, 0},
    {2286, 19, 22, 18, 0, 0},
    {2352, 18, 22, 18, 0, 0},
    {2418, 18, 22, 18, 0, 0},
    {2484, 16, 22, 18, 1, 0},
    {2528, 8, 27, 18, 6, -1},
    {2555, 16, 25, 18, 1, 0},
    {2605, 8, 27, 18, 4, -1},
    {2632, 18, 8, 18, 0, 0},
    {2656, 19, 3, 18, 0, 26},
    {2665, 9, 6, 18, 3, -2},
    {2677, 16, 16, 18, 1, 6},
    {2709, 15, 23, 18, 2, -1},
    {2755, 14, 16, 18, 2, 6},
    {2787, 15, 23, 18, 1, -1},
    {2833, 16, 16, 18, 1, 6},
    {2865, 14, 23, 18, 2, -1},
    {2911, 16, 22, 18, 1, 6},
    {2955, 14, 23, 18, 2, -1},
    {3001, 16, 24, 18, 2, -2},
    {3049, 11, 30, 18, 2, -2},
    {3109, 16, 23, 18, 2, -1},
    {3155, 16, 23, 18, 1, -1},
    {3201, 16, 16, 18, 1, 6},
    {3233, 14, 16, 18, 2, 6},
    {3265, 16, 16, 18, 1, 6},
    {3297, 15, 22, 18, 2, 6},
    {3341, 15, 22, 18, 1, 6},
    {3385, 13, 16, 18, 4, 6},
    {3417, 14, 16, 18, 2, 6},
    {3449, 15, 21, 18, 1, 1},
    {3491, 14, 16, 18, 2, 6},
    {3523, 16, 16, 18, 1, 6},
    {3555, 19, 16, 18, 0, 6},
    {3603, 18, 16, 18, 0, 6},
    {3651, 18, 22, 18, 0, 6},
    {3717, 14, 16, 18, 2, 6},
    {3749, 14, 28, 18, 2, -1},
    {3805, 4, 30, 18, 7, -1},
    {3835, 14, 28, 18, 2, -1},
    {3891, 16, 5, 18, 1, 10},
};

const GFXfont DeckFontClock PROGMEM = {(uint8_t *)DeckFontClockBits, (GFXglyph *)DeckFontClockGlyphs, 32, 126, 36};

static const GFXfont *fontFor(uint8_t size) {
  if (size >= 4) return &DeckFontClock;
  return size >= 2 ? &DeckFontDisplay : &DeckFontBody;
}

// Body lines advance 13 px and display lines 26: the descenders hang below the
// cap box, so both are taller than the 8 * size the 5x7 font needed.
static uint8_t lineHeightFor(uint8_t size) { return size >= 2 ? 26 : 13; }

// -------------------------------------------------------------- draw utils ---

static void drawTextAt(const char *text, int x, int y, uint8_t size, uint16_t fg,
                       uint16_t bg) {
  tft.setFont(fontFor(size));
  tft.setTextColor(fg, bg);
  tft.setCursor(x, y);
  tft.print(text);
}

static void drawTextCentered(const char *text, Rect r, uint8_t size, uint16_t fg,
                             uint16_t bg) {
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setFont(fontFor(size));
  tft.getTextBounds(text, 0, 0, &bx, &by, &bw, &bh);
  tft.setTextColor(fg, bg);
  tft.setCursor(r.x + (r.w - static_cast<int>(bw)) / 2 - bx,
                r.y + (r.h - static_cast<int>(bh)) / 2 - by);
  tft.print(text);
}

static void drawTextRight(const char *text, int right, int y, uint8_t size,
                          uint16_t fg, uint16_t bg) {
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setFont(fontFor(size));
  tft.getTextBounds(text, 0, 0, &bx, &by, &bw, &bh);
  tft.setTextColor(fg, bg);
  tft.setCursor(right - static_cast<int>(bw) - bx, y);
  tft.print(text);
}

// accent = the one strong call to action on a screen: pink fill, dark label.
static void drawButton(Rect r, const char *label, uint8_t size, bool accent) {
  const uint16_t bg = accent ? C_PINK : C_TILE;
  tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_BUTTON, bg);
  tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_BUTTON, accent ? C_PINK : C_EDGE);
  drawTextCentered(label, r, size, accent ? C_BG : C_TEXT, bg);
}

static void fitText(char *out, size_t outLen, const char *source, size_t maxChars) {
  const size_t length = strlen(source);
  if (length <= maxChars) {
    snprintf(out, outLen, "%s", source);
  } else if (maxChars > 3) {
    snprintf(out, outLen, "%.*s...", static_cast<int>(maxChars - 3), source);
  } else {
    snprintf(out, outLen, "%.*s", static_cast<int>(maxChars), source);
  }
}

static bool inside(int x, int y, Rect r) {
  return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
}

// A resistive panel reports a tap a few pixels from where the finger landed. Six
// pixels of slop on the way back costs nothing: nothing else lives that close.
static bool insideBack(int x, int y) {
  const Rect r = BACK_BTN;
  return x >= r.x - 6 && x < r.x + r.w + 6 && y >= r.y - 6 && y < r.y + r.h + 6;
}

static bool insideHeaderBack(int x, int y) {
  const Rect r = HEADER_BACK;
  return x >= r.x - 6 && x < r.x + r.w + 6 && y >= r.y - 6 && y < r.y + r.h + 6;
}

// --------------------------------------------------------------- drawing ---

int batteryLevel = -1;  // -1 until the phone reports one
bool batteryCharging = false;

static const char *clockSourceLabel() {
  switch (clockSource) {
    case CLOCK_PHONE: return "phone time";
    case CLOCK_NOTICE: return "from notification";
    default: return "waiting for phone";
  }
}

static void drawGear(int cx, int cy, uint16_t color) {
  tft.drawCircle(cx, cy, 5, color);
  static const int8_t DX[8] = {0, 1, 1, 1, 0, -1, -1, -1};
  static const int8_t DY[8] = {-1, -1, 0, 1, 1, 1, 0, -1};
  for (int i = 0; i < 8; ++i) {
    tft.drawLine(cx + DX[i] * 5, cy + DY[i] * 5, cx + DX[i] * 7, cy + DY[i] * 7, color);
  }
}

// One link pill wherever link state is shown: a ring when nothing is on the
// link, a filled dot when a phone is, and the accent outline only when linked.
static void drawLinkPill(const Rect &pill) {
  const bool linked = phoneLinked();
  tft.fillRoundRect(pill.x, pill.y, pill.w, pill.h, 11, C_TILE);
  tft.drawRoundRect(pill.x, pill.y, pill.w, pill.h, 11, linked ? C_ACCENT : C_EDGE);
  if (linked) tft.fillCircle(pill.x + 12, pill.y + 11, 3, C_ACCENT);
  else tft.drawCircle(pill.x + 12, pill.y + 11, 3, C_DIM);
  drawTextAt(linked ? "LINKED" : "OFFLINE", pill.x + 20, pill.y + 8, 1,
             linked ? C_ACCENT : C_DIM, C_TILE);
}

static void drawHeader() {
  tft.fillRect(0, 0, SCREEN_W, HEADER_H, C_BG);
  tft.drawFastHLine(0, HEADER_H - 1, SCREEN_W, C_EDGE);

  // Back chip, then a two-line wordmark: the front page is the way home.
  tft.drawRoundRect(HEADER_BACK.x, HEADER_BACK.y, HEADER_BACK.w, HEADER_BACK.h,
                    7, C_EDGE);
  drawTextCentered("<", HEADER_BACK, 2, C_TEXT, C_BG);
  drawTextAt("PHONE DECK", 48, 5, 2, C_TEXT, C_BG);
  char subtitle[24];
  snprintf(subtitle, sizeof(subtitle), "esp32 // #%s", DEVICE_TAG);
  drawTextAt(subtitle, 48, 21, 1, C_DIM, C_BG);

  // Link pill, the same one settings carries.
  drawLinkPill(LINK_PILL);

  // Gear opens settings.
  tft.fillRoundRect(GEAR_BTN.x, GEAR_BTN.y, GEAR_BTN.w, GEAR_BTN.h, 6, C_TILE);
  tft.drawRoundRect(GEAR_BTN.x, GEAR_BTN.y, GEAR_BTN.w, GEAR_BTN.h, 6, C_EDGE);
  drawGear(GEAR_BTN.x + GEAR_BTN.w / 2, GEAR_BTN.y + GEAR_BTN.h / 2, C_TEXT);
}

static void drawSectionHeader() {
  tft.fillCircle(MARGIN, 125, 3, C_PURPLE);
  drawTextAt("01 // NOTIFICATIONS", MARGIN + 10, 121, 1, C_TEXT, C_BG);
  char right[16];
  const int unread = countUnread();
  if (unread > 0) snprintf(right, sizeof(right), "%d NEW", unread);
  else snprintf(right, sizeof(right), "DIRECT ACCESS");
  drawTextRight(right, SCREEN_W - MARGIN, 121, 1, unread > 0 ? C_YELLOW : C_DIM, C_BG);
}

// Battery reads at a glance: blue while charging, pink under 30%, green at or
// above it, dim until the phone reports a level.
static uint16_t batteryColor(int level, bool charging) {
  if (level < 0) return C_DIM;
  if (charging) return C_ACCENT;
  return level < 30 ? C_PINK : C_GREEN;
}

// One wide status card: terminal line, big clock, phone battery on the right.
static void drawStatusCard() {
  const Rect r = STATUS_CARD;
  tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_TILE);
  tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_EDGE);

  drawTextAt("$ sys.status --", r.x + CARD_PAD, r.y + 7, 1, C_DIM, C_TILE);
  // The right end of this row used to carry READY // ONLINE (or STANDBY, or BIND
  // FIRST PHONE). The header shows LINKED on every page now, so the line told a
  // reader the same thing twice on one screen; the row keeps its rule, and the
  // clock below it, and nothing else.
  tft.drawFastHLine(r.x + CARD_PAD, r.y + 20, r.w - 2 * CARD_PAD, C_EDGE);

  if (clockValid) {
    const time_t now = clockNow();
    struct tm parts;
    gmtime_r(&now, &parts);
    static const char *DAYS[7] = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    static const char *MONTHS[12] = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                                     "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    char hhmm[8], seconds[4], date[44];
    snprintf(hhmm, sizeof(hhmm), "%02d:%02d", parts.tm_hour, parts.tm_min);
    snprintf(seconds, sizeof(seconds), "%02d", parts.tm_sec);
    snprintf(date, sizeof(date), "%s %d %s  //  %s", DAYS[parts.tm_wday],
             parts.tm_mday, MONTHS[parts.tm_mon], clockSourceLabel());
    // The clock face is 18 px a digit, so five characters end at +112: the
    // seconds sit beside that, not at the old 6 px-per-character 134.
    drawTextAt(hhmm, r.x + CARD_PAD, r.y + 24, 4, C_TEXT, C_TILE);
    drawTextAt(seconds, r.x + 118, r.y + 36, 1, C_ACCENT, C_TILE);
    if (!nowTrack.valid) drawTextAt(date, r.x + CARD_PAD, r.y + 60, 1, C_DIM, C_TILE);
  } else {
    drawTextAt("--:--", r.x + CARD_PAD, r.y + 24, 4, C_DIM, C_TILE);
    if (!nowTrack.valid) {
      drawTextAt("no time yet  //  waiting", r.x + CARD_PAD, r.y + 60, 1, C_DIM, C_TILE);
    }
  }

  if (nowTrack.valid) {
    char elapsed[8], total[10], title[24], line[64];
    formatClock(nowPosition(), elapsed, sizeof(elapsed));
    if (nowTrack.duration > 0) formatClock(nowTrack.duration, total, sizeof(total));
    else snprintf(total, sizeof(total), "--:--");
    fitText(title, sizeof(title), nowTrack.title[0] != '\0' ? nowTrack.title : "(untitled)", 18);
    snprintf(line, sizeof(line), "%s %s %s  %s", nowTrack.playing ? ">>" : "||",
             elapsed, total, title);
    drawTextAt(line, r.x + CARD_PAD, r.y + 60, 1, C_ACCENT, C_TILE);
  }

  // The battery block is measured from the card's right edge, so the bar ends on
  // the same line the card does.
  const int battX = r.x + r.w - CARD_PAD - 58;
  drawTextAt("BATTERY", battX, r.y + 26, 1, C_DIM, C_TILE);
  char percent[8];
  if (batteryLevel >= 0) snprintf(percent, sizeof(percent), "%d%%", batteryLevel);
  else snprintf(percent, sizeof(percent), "--");
  drawTextAt(percent, battX, r.y + 36, 2, batteryColor(batteryLevel, batteryCharging), C_TILE);

  const Rect bar = {static_cast<int16_t>(battX), static_cast<int16_t>(r.y + 56), 58, 6};
  tft.drawRoundRect(bar.x, bar.y, bar.w, bar.h, 3, C_EDGE);
  if (batteryLevel >= 0) {
    int fill = (bar.w - 2) * batteryLevel / 100;
    if (fill < 2) fill = 2;
    tft.fillRoundRect(bar.x + 1, bar.y + 1, fill, bar.h - 2, 2,
                      batteryColor(batteryLevel, batteryCharging));
  }
  if (batteryCharging) drawTextAt("CHG", battX, r.y + 66, 1, batteryColor(batteryLevel, batteryCharging), C_TILE);
}

// ------------------------------------------------------------- front page ---
// The sheet's front page on 320x240: a wordmark, the two pills it uses for
// state, and a numbered tab list where only the pressed row goes solid. No
// graphic: a lens-shaped heat band sat under the tabs and was removed, because a
// tinted strip there read as decoration competing with the wordmark.
// Rows: messages, the mirrored now-playing page, settings. Calibration came off
// this page because settings already carries it, which left the middle row free
// for the Spotify deck's now page as far as this deck can honestly be it: the
// title, artist and progress the phone sends, and no transport buttons, because
// nothing here can ask Spotify anything on its own.
static const char *const TAB_LABELS[3] = {"MESSAGES", "SPOTIFY", "SETTINGS"};

// Unlit rows are ink on black; the pressed row inverts, which is the page's
// only state.
static void drawHomeTab(int index, bool lit) {
  const Rect r = HOME_TAB[index];
  const uint16_t bg = lit ? C_TEXT : C_BG;
  if (lit) tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_BUTTON, C_TEXT);
  char number[4];
  snprintf(number, sizeof(number), "%02d", index + 1);
  drawTextAt(number, r.x + 12, r.y + 13, 1, lit ? C_BG : C_DIM, bg);
  drawTextAt(TAB_LABELS[index], r.x + 40, r.y + 9, 2, lit ? C_BG : C_TEXT, bg);
  drawTextRight(">", r.x + r.w - 12, r.y + 9, 2, lit ? C_BG : C_DIM, bg);
}

static void drawHome() {
  screen = SCR_HOME;
  tft.fillScreen(C_BG);

  // One strip of state, one wordmark, three rows, one footer. The page used to
  // carry a status line, a badge, two pills, a wordmark and four rules at
  // roughly equal weight, so nothing on it ranked first.
  char left[40];
  if (clockValid) {
    const time_t now = clockNow();
    struct tm parts;
    gmtime_r(&now, &parts);
    snprintf(left, sizeof(left), "SYNC %02d:%02d // PHONE TIME", parts.tm_hour,
             parts.tm_min);
  } else {
    snprintf(left, sizeof(left), "NO TIME YET // WAITING");
  }
  drawTextAt(left, MARGIN, 8, 1, C_DIM, C_BG);

  char batt[16];
  // "BATTERY 82%" ran into "PHONE TIME" at the wide body advance; the strip says
  // BAT and leaves the number to carry the meaning.
  if (batteryLevel >= 0) snprintf(batt, sizeof(batt), "BAT %d%%", batteryLevel);
  else snprintf(batt, sizeof(batt), "BAT --");
  drawTextRight(batt, GEAR_BTN.x - 8, 8, 1,
                batteryColor(batteryLevel, batteryCharging), C_BG);

  tft.fillRoundRect(GEAR_BTN.x, GEAR_BTN.y, GEAR_BTN.w, GEAR_BTN.h, 6, C_TILE);
  tft.drawRoundRect(GEAR_BTN.x, GEAR_BTN.y, GEAR_BTN.w, GEAR_BTN.h, 6, C_EDGE);
  drawGear(GEAR_BTN.x + GEAR_BTN.w / 2, GEAR_BTN.y + GEAR_BTN.h / 2, C_TEXT);
  tft.drawFastHLine(MARGIN, 30, SCREEN_W - 2 * MARGIN, C_EDGE);

  // The wordmark is the page's one loud thing; the link state steps down to body
  // size beside it, and the battery is already in the strip above.
  drawTextAt("PHONE DECK", MARGIN, 40, 2, C_TEXT, C_BG);
  const bool linked = phoneLinked();
  const char *state = linked ? "LINKED" : "OFFLINE";
  const int badgeRight = SCREEN_W - MARGIN;
  drawTextRight(state, badgeRight, 46, 1, linked ? C_ACCENT : C_DIM, C_BG);
  const int dotX = badgeRight - 7 * static_cast<int>(strlen(state)) - 10;
  if (linked) tft.fillCircle(dotX, 50, 3, C_ACCENT);
  else tft.drawCircle(dotX, 50, 3, C_DIM);

  tft.drawFastHLine(MARGIN, 64, SCREEN_W - 2 * MARGIN, C_EDGE);
  for (int i = 0; i < 3; ++i) drawHomeTab(i, false);
  tft.drawFastHLine(MARGIN, 192, SCREEN_W - 2 * MARGIN, C_EDGE);

  // The two facts the pills used to carry, as one quiet footer line.
  const int unread = countUnread();
  char count[16];
  if (unread > 0) snprintf(count, sizeof(count), "%d NEW", unread);
  else snprintf(count, sizeof(count), "INBOX CLEAR");
  drawTextAt(count, MARGIN, 204, 1, unread > 0 ? C_YELLOW : C_ACCENT, C_BG);
  char foot[24];
  snprintf(foot, sizeof(foot), "THEME %02d %s", themeIndex,
           themeIndex == 0 ? "PAPER" : "INK");
  drawTextRight(foot, SCREEN_W - MARGIN, 204, 1, C_DIM, C_BG);
}

static void formatAge(time_t at, char *out, size_t outLen) {
  out[0] = '\0';
  if (at <= 0 || !clockValid) return;
  const long delta = static_cast<long>(clockNow()) - static_cast<long>(at);
  if (delta < 60) {
    snprintf(out, outLen, "now");
  } else if (delta < 3600) {
    snprintf(out, outLen, "%ldm", delta / 60);
  } else if (delta < 86400) {
    snprintf(out, outLen, "%ldh", delta / 3600);
  } else {
    snprintf(out, outLen, "%ldd", delta / 86400);
  }
}

static void drawNoticeRow(int index) {
  const Rect r = NOTIF_ROW[index];
  const bool has = index < noticeCount;
  if (!has) {
    tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_BG);
    if (index == 0) {
      tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_EDGE);
      drawTextCentered("no notifications", r, 1, C_DIM, C_BG);
    }
    return;
  }
  const Notice &n = notices[index];
  tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_TILE);
  tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, n.unread ? C_ACCENT_DIM : C_EDGE);

  const Rect icon = {static_cast<int16_t>(r.x + CARD_PAD),
                     static_cast<int16_t>(r.y + 6), 20, 20};
  tft.fillRoundRect(icon.x, icon.y, icon.w, icon.h, 6, C_TILE_HI);
  tft.drawRoundRect(icon.x, icon.y, icon.w, icon.h, 6, n.unread ? C_ACCENT_DIM : C_EDGE);
  char letter[2] = {n.app[0], '\0'};
  drawTextCentered(letter, icon, 2, n.unread ? C_YELLOW : C_DIM, C_TILE_HI);

  const int textX = r.x + CARD_PAD + 28;
  drawTextAt(n.app, textX, r.y + 4, 1, n.unread ? C_ACCENT : C_DIM, C_TILE);
  char when[16];
  formatAge(n.at, when, sizeof(when));
  drawTextRight(when, r.x + r.w - CARD_PAD, r.y + 4, 1, C_DIM, C_TILE);

  const uint16_t ink = n.unread ? C_TEXT : C_DIM;
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setFont(fontFor(2));
  tft.getTextBounds(n.title, 0, 0, &bx, &by, &bw, &bh);
  if (static_cast<int>(bw) <= r.w - 2 * CARD_PAD - 28) {
    drawTextAt(n.title, textX, r.y + 14, 2, ink, C_TILE);
  } else {
    char title[24];
    fitText(title, sizeof(title), n.title, 20);
    drawTextAt(title, textX, r.y + 16, 1, ink, C_TILE);
  }
}

static void drawBoardBody() {
  drawStatusCard();
  drawSectionHeader();
  for (int i = 0; i < 3; ++i) drawNoticeRow(i);
}

static void drawBoard() {
  screen = SCR_BOARD;
  tft.fillScreen(C_BG);
  drawHeader();
  drawBoardBody();
}

static void drawWrappedText(const char *text, int x, int y, int width,
                            uint8_t size, uint16_t fg, uint16_t bg, int maxLines) {
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
    snprintf(candidate, sizeof(candidate), "%s%s%s", line, used > 0 ? " " : "", word);
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

// The mirrored track: one card, a progress bar, and no transport buttons. A pause
// here would be a promise the deck cannot keep on its own, so the page says where
// the controls live instead of pretending to have them.
static void drawNow() {
  screen = SCR_NOW;
  tft.fillScreen(C_BG);
  drawHeader();

  tft.fillRoundRect(12, 40, 296, 96, CORNER_CARD, C_TILE);
  tft.drawRoundRect(12, 40, 296, 96, CORNER_CARD, C_EDGE);
  drawTextAt("02 // NOW PLAYING", 22, 46, 1, C_DIM, C_TILE);

  if (!nowTrack.valid) {
    drawTextAt("nothing playing", 22, 68, 2, C_DIM, C_TILE);
    drawTextAt(phoneLinked() ? "start something on the phone"
                             : "link the phone to mirror it",
               22, 96, 1, C_DIM, C_TILE);
  } else {
    drawWrappedText(nowTrack.title[0] != '\0' ? nowTrack.title : "(untitled)",
                    22, 62, 272, 2, C_TEXT, C_TILE, 2);
    drawTextAt(nowTrack.artist[0] != '\0' ? nowTrack.artist : "unknown artist",
               22, 104, 1, nowTrack.playing ? C_ACCENT : C_DIM, C_TILE);
    if (nowTrack.album[0] != '\0') {
      char album[48];
      fitText(album, sizeof(album), nowTrack.album, 44);
      drawTextAt(album, 22, 118, 1, C_DIM, C_TILE);
    }
  }

  const Rect bar = {12, 146, 296, 10};
  tft.drawRoundRect(bar.x, bar.y, bar.w, bar.h, 5, C_EDGE);
  const uint32_t position = nowPosition();
  if (nowTrack.valid && nowTrack.duration > 0) {
    int fill = static_cast<int>((bar.w - 4) * position / nowTrack.duration);
    if (fill < 0) fill = 0;
    if (fill > bar.w - 4) fill = bar.w - 4;
    if (fill > 0) tft.fillRoundRect(bar.x + 2, bar.y + 2, fill, bar.h - 4, 3, C_ACCENT);
  }

  char elapsed[8], total[8];
  formatClock(position, elapsed, sizeof(elapsed));
  if (nowTrack.valid && nowTrack.duration > 0) formatClock(nowTrack.duration, total, sizeof(total));
  else snprintf(total, sizeof(total), "--:--");
  drawTextAt(elapsed, 12, 160, 1, C_DIM, C_BG);
  drawTextRight(total, SCREEN_W - 12, 160, 1, C_DIM, C_BG);

  drawTextAt(nowTrack.valid && nowTrack.playing
                 ? "PLAYING // MIRRORED FROM THE PHONE"
                 : "PAUSED // MIRRORED FROM THE PHONE",
             12, 178, 1, nowTrack.valid && nowTrack.playing ? C_ACCENT : C_DIM, C_BG);

  drawButton(NOW_BACK, "BACK", 2, false);
}

static uint32_t detailUid = 0;

static void drawDetail() {
  tft.fillScreen(C_BG);
  tft.drawFastHLine(0, HEADER_H - 1, SCREEN_W, C_EDGE);

  const int index = findNotice(detailUid);
  if (index < 0) {
    drawBoard();
    return;
  }
  const Notice &n = notices[index];

  tft.fillRoundRect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.w, BACK_BTN.h, 6, C_TILE);
  tft.drawRoundRect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.w, BACK_BTN.h, 7, C_EDGE);
  drawTextCentered("<", BACK_BTN, 2, C_TEXT, C_TILE);
  drawTextAt(n.app, 48, 8, 2, C_TEXT, C_BG);

  char when[16];
  formatAge(n.at, when, sizeof(when));
  drawTextRight(when, SCREEN_W - 12, 13, 1, C_DIM, C_BG);

  drawWrappedText(n.title, 12, 44, SCREEN_W - 24, 2, C_TEXT, C_BG, 2);
  tft.drawFastHLine(12, 92, SCREEN_W - 24, C_EDGE);
  drawWrappedText(n.body[0] != '\0' ? n.body : "(no message)", 12, 100,
                  SCREEN_W - 24, 1, C_DIM, C_BG, 7);
  // A rule above the pinned pair: a short message used to end and leave the
  // buttons floating in an undefined gap.
  tft.drawFastHLine(12, 194, SCREEN_W - 24, C_EDGE);

  drawButton(DETAIL_DISMISS, "DISMISS", 2, true);
  drawButton(DETAIL_BACK, "BACK", 2, false);
}

static void drawSettings() {
  tft.fillScreen(C_BG);
  tft.drawFastHLine(0, HEADER_H - 1, SCREEN_W, C_EDGE);

  tft.fillRoundRect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.w, BACK_BTN.h, 6, C_TILE);
  tft.drawRoundRect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.w, BACK_BTN.h, 7, C_EDGE);
  drawTextCentered("<", BACK_BTN, 2, C_TEXT, C_TILE);
  drawTextAt("SETTINGS", 48, 8, 2, C_TEXT, C_BG);
  drawLinkPill(SET_LINK_PILL);

  // Brightness.
  tft.fillRoundRect(12, 38, 296, 58, CORNER_CARD, C_TILE);
  tft.drawRoundRect(12, 38, 296, 58, CORNER_CARD, C_EDGE);
  drawTextAt("BRIGHTNESS", 22, 44, 1, C_DIM, C_TILE);
  char value[8];
  snprintf(value, sizeof(value), "%u%%", static_cast<unsigned>(brightness));
  drawTextRight(value, 298, 46, 2, C_ACCENT, C_TILE);
  drawButton(SET_BRI_DOWN, "-", 3, false);
  drawButton(SET_BRI_UP, "+", 3, false);

  drawButton(SET_CAL, "CALIBRATE TOUCH", 1, false);
  drawButton(SET_CLOSE, "CLOSE", 2, true);
  drawTextAt(ownerKnown ? ownerLabel : "no phone bound", 12, 226, 1,
             ownerKnown ? C_PURPLE : C_DIM, C_BG);
  drawTextRight("hold gear = forget", SCREEN_W - 12, 226, 1, C_DIM, C_BG);
}

// --------------------------------------------------------- calibration UI ---

static void drawCrosshair(int x, int y) {
  tft.drawFastHLine(x - 11, y, 23, C_TEXT);
  tft.drawFastVLine(x, y - 11, 23, C_TEXT);
  tft.drawCircle(x, y, 7, C_ACCENT);
  tft.fillCircle(x, y, 1, C_ACCENT);
}

static void drawCalProgress(bool right, bool lower) {
  const int y = (lower ? 150 : 10) + 54;
  for (int i = 0; i < 3; ++i) {
    const int x = right ? SCREEN_W - 17 - i * 18 : 17 + i * 18;
    if (i <= calIndex) tft.fillCircle(x, y, 5, C_ACCENT);
    else tft.drawCircle(x, y, 5, C_EDGE);
  }
}

static void drawCalibrationScreen() {
  tft.fillScreen(C_BG);
  const int tx = CAL_TARGET_X[calIndex];
  const int ty = CAL_TARGET_Y[calIndex];
  drawCrosshair(tx, ty);

  // The words go in the corner the dot is not in. In the top-left they sat under
  // the first target: the ring and the rule cut straight through CALIBRATE.
  const bool right = tx < SCREEN_W / 2;
  const bool lower = ty < SCREEN_H / 2;
  const int y = lower ? 150 : 10;
  char title[24];
  snprintf(title, sizeof(title), "CALIBRATE %u/3",
           static_cast<unsigned>(calIndex) + 1);
  const int edge = SCREEN_W - 12;
  if (right) {
    drawTextRight(title, edge, y, 2, C_ACCENT, C_BG);
    drawTextRight("tap the dot in this corner", edge, y + 22, 1, C_TEXT, C_BG);
    drawTextRight("hold 2s anywhere = cancel", edge, y + 36, 1, C_DIM, C_BG);
  } else {
    drawTextAt(title, 12, y, 2, C_ACCENT, C_BG);
    drawTextAt("tap the dot in this corner", 12, y + 22, 1, C_TEXT, C_BG);
    drawTextAt("hold 2s anywhere = cancel", 12, y + 36, 1, C_DIM, C_BG);
  }
  drawCalProgress(right, lower);
}

// ------------------------------------------------------------------ touch ---

// Four conversions per poll, median per axis. The XPT2046 answers a wild sample
// often enough that a single read lands a tap on the neighbouring row, and the
// first conversions after touch-down are the worst. Pressure is used only to drop
// samples that did not convert at all: its scale differs per panel, and a
// threshold picked blind would kill touch outright.
static constexpr int TOUCH_SAMPLES = 4;
static constexpr int TOUCH_SPREAD_MAX = 300;  // raw units; beyond this a drag, not a tap

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
    while (j >= 0 && xs[j] > vx) { xs[j + 1] = xs[j]; --j; }
    xs[j + 1] = vx;
    j = i - 1;
    while (j >= 0 && ys[j] > vy) { ys[j + 1] = ys[j]; --j; }
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

// ----------------------------------------------------------------- actions ---

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
  drawHome();
}

static void saveTouchMapping() {
  saveMapping(mapping);
  mappingStored = true;
  drawHome();
}

static void updateRawReadout(int rawX, int rawY) {
  if (millis() - rawUiMs < 120) return;
  rawUiMs = millis();
  char line[28];
  snprintf(line, sizeof(line), "raw %4d %4d", rawX, rawY);
  drawTextAt(line, 34, 212, 1, C_DIM, C_BG);
}

static void handleCalibrationTap(int rawX, int rawY) {
  // The old mapping is deliberately not consulted: when it is wrong it would
  // reject exactly the taps meant to replace it.
  if (calIndex > 0) {
    const int dx = rawX - calRaw[calIndex - 1].x;
    const int dy = rawY - calRaw[calIndex - 1].y;
    if (dx * dx + dy * dy < 300 * 300) return;  // bounce, not a new corner
  }

  calRaw[calIndex].x = rawX;
  calRaw[calIndex].y = rawY;
  ++calIndex;

  if (calIndex < 3) {
    drawCalibrationScreen();
    return;
  }

  TouchMapping candidate;
  if (!fitMapping(calRaw, candidate)) {
    calIndex = 0;
    drawCalibrationScreen();
    drawTextAt("taps too close - try again", 10, 60, 1, C_PINK, C_BG);
    return;
  }
  mapping = candidate;
  saveTouchMapping();
}

static void openSettings() {
  screen = SCR_SETTINGS;
  drawSettings();
}

static void handleSettingsTap(int x, int y) {
  if (insideBack(x, y) || inside(x, y, SET_CLOSE)) {
    drawHome();
    return;
  }
  if (inside(x, y, SET_BRI_DOWN)) {
    const int next = static_cast<int>(brightness) - BRI_STEP;
    brightness = static_cast<uint8_t>(next < BRI_MIN ? BRI_MIN : next);
    applyBrightness();
    saveSettings();
    drawSettings();
    return;
  }
  if (inside(x, y, SET_BRI_UP)) {
    const int next = static_cast<int>(brightness) + BRI_STEP;
    brightness = static_cast<uint8_t>(next > BRI_MAX ? BRI_MAX : next);
    applyBrightness();
    saveSettings();
    drawSettings();
    return;
  }
  if (inside(x, y, SET_CAL)) {
    startCalibration();
    return;
  }
  if (inside(x, y, SET_CLOSE)) {
    drawHome();
    return;
  }
}

// The tab inverts under the finger, holds long enough to read, then opens.
static void handleHomeTap(int x, int y) {
  for (int i = 0; i < 3; ++i) {
    if (!inside(x, y, HOME_TAB[i])) continue;
    drawHomeTab(i, true);
    delay(90);
    switch (i) {
      case 0: drawBoard(); break;
      case 1: drawNow(); break;
      default: openSettings(); break;
    }
    return;
  }
}

// Opened by uid, not by row: a notification arriving between the press and the
// release shifts every row down by one.
static void openDetail(uint32_t uid) {
  const int index = findNotice(uid);
  if (index < 0) return;
  detailUid = uid;
  notices[index].unread = false;
  screen = SCR_DETAIL;
  drawDetail();
}

static void dismissOnPhone(uint32_t uid) {
  // 0 is "no id", and the top bit marks a local Phone Link entry that the
  // phone knows nothing about.
  if (ancsCmdQueue != nullptr && uid != 0 && uid < 0x80000000u && ancsReady) {
    xQueueSend(ancsCmdQueue, &uid, 0);
  }
}

static void handleDetailTap(int x, int y) {
  if (insideBack(x, y)) {
    detailUid = 0;
    drawBoard();
    return;
  }
  if (inside(x, y, DETAIL_DISMISS)) {
    dismissOnPhone(detailUid);
    dropNotice(findNotice(detailUid));
    detailUid = 0;
    drawBoard();
    return;
  }
  if (inside(x, y, DETAIL_BACK)) {
    detailUid = 0;
    drawBoard();
  }
}

// ------------------------------------------------------------------ events ---

static void applyUiEvent(const UiEvent &ev) {
  const bool onHome = (screen == SCR_HOME);
  switch (ev.type) {
    case EVT_LINK_ON:
    case EVT_LINK_OFF:
      // A lost link ends the mirror with it: the phone is the only thing that
      // knows what is playing, so a title left on screen would be a lie.
      if (ev.type == EVT_LINK_OFF) nowTrack = NowTrack();
      if (screen == SCR_BOARD) {
        drawHeader();
        drawNoticeRow(0);  // the empty-state hint depends on the link
        drawStatusCard();
      } else if (screen == SCR_NOW) {
        drawNow();
      }
      break;

    case EVT_NOW:
      nowTrack = ev.now;
      if (screen == SCR_BOARD) drawStatusCard();
      else if (screen == SCR_NOW) drawNow();
      break;

    case EVT_THEME:
      // The phone owns the switch. This board repaints in the palette it was
      // just told to use and keeps it, so the next power-up agrees with the app.
      applyTheme(ev.theme);
      saveSettings();
      if (screen == SCR_SETTINGS) drawSettings();
      else if (screen == SCR_BOARD || screen == SCR_DETAIL) drawBoard();
      else if (screen == SCR_NOW) drawNow();
      break;

    case EVT_NOTICE:
      insertNotice(ev.notice);
      if (!clockValid && ev.notice.at > 0) setClock(ev.notice.at, CLOCK_NOTICE);
      if (screen == SCR_BOARD) {
        drawHeader();
        for (int i = 0; i < 3; ++i) drawNoticeRow(i);
        if (!clockValid) drawStatusCard();
      }
      break;

    case EVT_NOTICE_GONE: {
      const int index = findNotice(ev.uid);
      if (index >= 0) dropNotice(index);
      if (screen == SCR_DETAIL && detailUid == ev.uid) {
        detailUid = 0;
        drawBoard();
      } else if (screen == SCR_BOARD) {
        drawHeader();
        for (int i = 0; i < 3; ++i) drawNoticeRow(i);
      }
      break;
    }

    case EVT_BATTERY:
      batteryLevel = ev.battery;
      batteryCharging = ev.charging != 0;
      if (screen == SCR_BOARD) drawStatusCard();
      break;

    case EVT_TIME:
      setClock(ev.epoch, CLOCK_PHONE);
      if (screen == SCR_BOARD) drawStatusCard();
      break;

    default:
      break;
  }

  // The front page carries the clock, the link state, the unread count and the
  // battery, so any event at all repaints it whole.
  if (onHome) drawHome();
}

static void processUiEvents() {
  if (uiQueue == nullptr) return;
  UiEvent ev;
  while (xQueueReceive(uiQueue, &ev, 0) == pdTRUE) applyUiEvent(ev);

  if (linkDirty) {
    linkDirty = false;
    if (screen == SCR_BOARD) drawHeader();
    else if (screen == SCR_HOME) drawHome();
  }

  // A control point write that never answered must not block the queue.
  if (requestInFlight && millis() - requestStartMs > 3000) {
    requestInFlight = false;
    pending = PendingNotice();
    requestNext();
  }
}

// -------------------------------------------------------------------- loop ---

// Touch state shared by every screen. Presses are latched on the way down and
// acted on when the finger lifts, so a redraw in between can never swallow the
// event.
static constexpr int TARGET_NONE = -1;
static constexpr int TARGET_GEAR = -2;
static constexpr int TARGET_BACK = -3;
static constexpr int TARGET_NOW = -4;

bool wasTouched = false;
bool ignoreTouchUntilRelease = false;
uint32_t touchStartMs = 0;
uint32_t lastActionMs = 0;
uint32_t pressedUid = 0;
int pressedTarget = TARGET_NONE;

static int targetAt(int x, int y) {
  if (insideHeaderBack(x, y)) return TARGET_BACK;
  if (inside(x, y, GEAR_BTN)) return TARGET_GEAR;
  // Only while a track is live: the strip is the date line otherwise, and a date
  // that opens a media page is a trap.
  if (nowTrack.valid && inside(x, y, NOW_STRIP)) return TARGET_NOW;
  for (int i = 0; i < 3 && i < noticeCount; ++i) {
    if (inside(x, y, NOTIF_ROW[i])) return i;
  }
  return TARGET_NONE;
}

void setup() {
  Serial.begin(115200);
  disableOnboardLed();

  uiQueue = xQueueCreate(8, sizeof(UiEvent));
  ancsCmdQueue = xQueueCreate(4, sizeof(uint32_t));

  // Explicit SPI pins let the sketch work without editing library setup files.
  SPI.begin(PIN_SPI_SCK, PIN_SPI_MISO, PIN_SPI_MOSI);
  tft.begin();
  tft.setRotation(1);
  tft.setTextWrap(false);

  loadSettings();
  initBacklight();
  tft.fillScreen(C_BG);

  touch.begin(SPI);

  loadMapping();
  initIdentity();
  startBluetooth();

  // A boot lands on the front page, always — a fresh flash included. Calibration
  // used to open here whenever the panel was held or no fit had been stored, so a
  // deck that had never been touched booted into a three-dot target instead of its
  // own front page. It is still one tap away in settings and one hold away on the
  // title, and the assumed mapping (raw axes rotated 90 degrees) is what a fresh
  // flash draws with until somebody runs it.
  drawHome();
}

void loop() {
  processUiEvents();

  int x = 0, y = 0;
  const bool down = readTouch(x, y);
  const uint32_t now = millis();

  if (ignoreTouchUntilRelease) {
    if (!down) ignoreTouchUntilRelease = false;
    wasTouched = down;
    delay(10);
    return;
  }

  // ------------------------------------------------------------- calibrate --
  if (screen == SCR_CAL) {
    int rawX = 0, rawY = 0;
    if (readRawTouch(rawX, rawY)) updateRawReadout(rawX, rawY);
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

  // ------------------------------------------------------------------ home --
  if (screen == SCR_HOME) {
    if (down && !wasTouched) {
      touchStartMs = now;
      // The gear takes the same two gestures it takes on the board: a tap opens
      // settings, a hold forgets the bound phone. Tabs still fire on press.
      pressedTarget = inside(x, y, GEAR_BTN) ? TARGET_GEAR : TARGET_NONE;
      if (pressedTarget == TARGET_NONE && now - lastActionMs > 150) {
        lastActionMs = now;
        handleHomeTap(x, y);
      }
    }
    if (down && pressedTarget == TARGET_GEAR && now - touchStartMs > 1200) {
      pressedTarget = TARGET_NONE;
      releaseOwner();
      drawHome();
      ignoreTouchUntilRelease = true;
      wasTouched = true;
      return;
    }
    if (!down && wasTouched && pressedTarget == TARGET_GEAR) {
      pressedTarget = TARGET_NONE;
      if (now - lastActionMs > 150) {
        lastActionMs = now;
        openSettings();
      }
    }
    wasTouched = down;
    delay(10);
    return;
  }

  // ------------------------------------------------------------------ now ---
  if (screen == SCR_NOW) {
    // The progress bar moves on its own between writes, so the page is redrawn
    // when the second changes rather than on every loop.
    static int nowDrawnSecond = -1;
    const int second = static_cast<int>(nowPosition());
    if (second != nowDrawnSecond) {
      nowDrawnSecond = second;
      drawNow();
    }
    if (down && !wasTouched) {
      touchStartMs = now;
      if (now - lastActionMs > 150) {
        lastActionMs = now;
        drawBoard();
      }
    }
    wasTouched = down;
    delay(10);
    return;
  }

  // --------------------------------------------------------------- settings --
  if (screen == SCR_SETTINGS) {
    if (down && !wasTouched) {
      touchStartMs = now;
      if (now - lastActionMs > 150) {
        lastActionMs = now;
        handleSettingsTap(x, y);
      }
    }
    wasTouched = down;
    delay(10);
    return;
  }

  // ----------------------------------------------------------------- detail --
  if (screen == SCR_DETAIL) {
    if (down && !wasTouched) {
      touchStartMs = now;
      if (now - lastActionMs > 150) {
        lastActionMs = now;
        handleDetailTap(x, y);
      }
    }
    // A long hold anywhere returns to the board, so nothing can trap you.
    if (down && wasTouched && now - touchStartMs > 1500) {
      detailUid = 0;
      drawBoard();
      ignoreTouchUntilRelease = true;
      wasTouched = true;
      return;
    }
    wasTouched = down;
    delay(10);
    return;
  }

  // ------------------------------------------------------------------ board --
  static int lastDrawnSecond = -1;
  static bool lastClockValid = false;
  if (clockValid) {
    const time_t nowSec = clockNow();
    if (nowSec != lastDrawnSecond || !lastClockValid) {
      lastDrawnSecond = static_cast<int>(nowSec);
      lastClockValid = true;
      drawStatusCard();
    }
  } else if (lastClockValid) {
    lastClockValid = false;
    drawStatusCard();
  }

  // The mirrored strip moves on its own between writes, and it has to keep
  // moving when the clock is not set yet, which is the one case the block above
  // does not redraw every second.
  static int lastMirrorSecond = -1;
  if (nowTrack.valid) {
    const int second = static_cast<int>(nowPosition());
    if (second != lastMirrorSecond) {
      lastMirrorSecond = second;
      drawStatusCard();
    }
  }

  if (down && !wasTouched) {
    touchStartMs = now;
    pressedTarget = targetAt(x, y);
    pressedUid = (pressedTarget >= 0 && pressedTarget < noticeCount)
                     ? notices[pressedTarget].uid
                     : 0;
  }

  // Hold the title bar for 1.5 s to calibrate.
  if (down && pressedTarget == TARGET_NONE && y < HEADER_H && x > 40 && x < 200 &&
      now - touchStartMs > 1500) {
    startCalibration();
    return;
  }

  // Hold the gear for 1.2 s to forget the bound phone, so the next phone to
  // connect can claim this board instead.
  if (down && pressedTarget == TARGET_GEAR && now - touchStartMs > 1200) {
    pressedTarget = TARGET_NONE;
    releaseOwner();
    drawHeader();
    for (int i = 0; i < 3; ++i) drawNoticeRow(i);
    ignoreTouchUntilRelease = true;
    wasTouched = true;
    return;
  }

  // Hold a notification for 0.7 s to dismiss it on the phone.
  if (down && pressedTarget >= 0 && now - touchStartMs > 700) {
    const uint32_t uid = pressedUid;
    pressedTarget = TARGET_NONE;
    pressedUid = 0;
    if (uid != 0) {
      dismissOnPhone(uid);
      dropNotice(findNotice(uid));
      drawHeader();
      for (int i = 0; i < 3; ++i) drawNoticeRow(i);
    }
    ignoreTouchUntilRelease = true;
    wasTouched = true;
    return;
  }

  // Taps fire on release, so a hold can become a dismiss instead.
  if (!down && wasTouched && pressedTarget != TARGET_NONE) {
    if (now - lastActionMs > 180) {
      lastActionMs = now;
      if (pressedTarget == TARGET_GEAR) {
        openSettings();
      } else if (pressedTarget == TARGET_BACK) {
        drawHome();
      } else if (pressedTarget == TARGET_NOW) {
        drawNow();
      } else if (pressedTarget >= 0 && pressedUid != 0) {
        openDetail(pressedUid);
      }
    }
    pressedTarget = TARGET_NONE;
    pressedUid = 0;
  }

  wasTouched = down;
  delay(10);
}
