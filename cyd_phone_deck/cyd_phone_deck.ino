/*
 * Phone Deck - ESP32-2432S024R (2.4" resistive touch CYD)
 *
 * A desk dashboard for your phone: clock, battery and notifications, drawn in
 * the GMK Dracula keycap palette.
 *
 * Where the data comes from:
 *   - Notifications: Apple Notification Center Service (ANCS). The deck
 *     advertises the ANCS service, the iPhone pairs and pushes notifications,
 *     and the deck asks for the app / title / message / date of each one.
 *   - Time: the date that comes with every ANCS notification, or the phone's
 *     Current Time Service when it has one.
 *   - Battery: the phone's Battery Service when it exposes one. iOS does not,
 *     so a phone-side helper can push it through the "Phone Link" service
 *     instead (see README.md for the three-characteristic protocol).
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
 *   - Hold the title for 1.5 s, or touch the screen at boot, to calibrate.
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
// GMK Dracula keycaps: near-black purple base, a lighter modifier, warm white
// alphas, and the pastel accent caps from the set's accent kits. Flat cards,
// hairline borders, one pastel hue per role - cyan for the link, rose for the
// one action, lavender for the section marker, butter for the unread count,
// mint for charging.

static constexpr uint16_t C_BG = 0x2946;         // #282a36 base
static constexpr uint16_t C_PANEL = 0x422B;      // #44475a modifier
static constexpr uint16_t C_TILE = 0x422B;       // #44475a modifier
static constexpr uint16_t C_TILE_HI = 0x52AD;    // #52566b modifier, lifted
static constexpr uint16_t C_EDGE = 0x6394;       // #6272a4 comment hairline
static constexpr uint16_t C_TEXT = 0xFFDE;       // #f8f8f2 alphas
static constexpr uint16_t C_DIM = 0xADB9;        // #adb5cb comment lifted 50%
static constexpr uint16_t C_ACCENT = 0x9F7E;     // #9decf5 pastel cyan
static constexpr uint16_t C_ACCENT_DIM = 0x6C94; // pastel cyan at 45% over C_TILE
static constexpr uint16_t C_PINK = 0xFD5A;       // #ffa8d5 pastel rose
static constexpr uint16_t C_PURPLE = 0xC5BF;     // #c6b6ff pastel lavender
static constexpr uint16_t C_YELLOW = 0xF775;     // #f4efa8 pastel butter
static constexpr uint16_t C_MINT = 0xAF5A;       // #aaead4 pastel mint

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

enum Screen : uint8_t { SCR_BOARD, SCR_DETAIL, SCR_SETTINGS, SCR_CAL, SCR_VERIFY };

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

enum EventType : uint8_t {
  EVT_LINK_ON,
  EVT_LINK_OFF,
  EVT_NOTICE,
  EVT_NOTICE_GONE,
  EVT_BATTERY,
  EVT_TIME,
};

struct UiEvent {
  uint8_t type;
  Notice notice;
  uint32_t uid;
  int16_t battery;
  uint8_t charging;
  time_t epoch;
};

// ----------------------------------------------------------- calibration ---

TouchMapping mapping;
Preferences calPrefs;

// Bumped whenever a stored touch fit should stop being trusted. A mismatch
// makes loadMapping() fall back to the factory default and re-opens the
// calibration screen on boot, which is what a fresh flash needs.
static constexpr uint8_t CAL_MAP_VERSION = 2;
bool mappingStored = false;

Screen screen = SCR_BOARD;
uint8_t calIndex = 0;
TouchPoint calRaw[4];
uint32_t calTapMs = 0;

uint8_t verifyIndex = 0;
bool verifyFailed = false;
int verifyWorst2 = 0;  // squared pixel error of the worst check tap
uint32_t rawUiMs = 0;

static constexpr int CAL_INSET = 26;
static const int CAL_TARGET_X[4] = {CAL_INSET, SCREEN_W - 1 - CAL_INSET,
                                    SCREEN_W - 1 - CAL_INSET, CAL_INSET};
static const int CAL_TARGET_Y[4] = {CAL_INSET, CAL_INSET,
                                    SCREEN_H - 1 - CAL_INSET,
                                    SCREEN_H - 1 - CAL_INSET};

// Check taps: after the corners are fitted these three points must land where
// they are drawn before the mapping is allowed into flash. They are spread far
// apart so a mirror or an axis swap cannot slip through.
static const int VERIFY_X[3] = {64, 256, 160};
static const int VERIFY_Y[3] = {68, 172, 118};
static constexpr int VERIFY_TOLERANCE = 55;

// Recovery buttons, only shown when a check tap misses.
static const Rect ADJ_SWAP = {8, 96, 148, 36};
static const Rect ADJ_FLIP_X = {164, 96, 148, 36};
static const Rect ADJ_FLIP_Y = {8, 138, 148, 36};
static const Rect ADJ_REDO = {164, 138, 148, 36};
static const Rect ADJ_SAVE = {8, 188, 304, 40};

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

// Gaussian elimination with partial pivoting on a 3x4 augmented matrix.
static bool solve3(double mat[3][4], double out[3]) {
  for (int col = 0; col < 3; ++col) {
    int pivot = col;
    for (int r = col + 1; r < 3; ++r) {
      if (fabs(mat[r][col]) > fabs(mat[pivot][col])) pivot = r;
    }
    if (fabs(mat[pivot][col]) < 1e-6) return false;
    if (pivot != col) {
      for (int k = 0; k < 4; ++k) {
        const double tmp = mat[col][k];
        mat[col][k] = mat[pivot][k];
        mat[pivot][k] = tmp;
      }
    }
    for (int r = 0; r < 3; ++r) {
      if (r == col) continue;
      const double factor = mat[r][col] / mat[col][col];
      for (int k = col; k < 4; ++k) mat[r][k] -= factor * mat[col][k];
    }
  }
  for (int i = 0; i < 3; ++i) out[i] = mat[i][3] / mat[i][i];
  return true;
}

// Least-squares fit of the four tapped corners to the screen corners.
static bool fitMapping(const TouchPoint raw[4], TouchMapping &m) {
  const double targetX[4] = {0.0, SCREEN_W - 1.0, SCREEN_W - 1.0, 0.0};
  const double targetY[4] = {0.0, 0.0, SCREEN_H - 1.0, SCREEN_H - 1.0};

  double normal[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
  double rhsX[3] = {0, 0, 0};
  double rhsY[3] = {0, 0, 0};

  for (int i = 0; i < 4; ++i) {
    const double p[3] = {static_cast<double>(raw[i].x),
                         static_cast<double>(raw[i].y), 1.0};
    for (int r = 0; r < 3; ++r) {
      for (int c = 0; c < 3; ++c) normal[r][c] += p[r] * p[c];
      rhsX[r] += p[r] * targetX[i];
      rhsY[r] += p[r] * targetY[i];
    }
  }

  double mx[3][4], my[3][4];
  for (int r = 0; r < 3; ++r) {
    for (int c = 0; c < 3; ++c) {
      mx[r][c] = normal[r][c];
      my[r][c] = normal[r][c];
    }
    mx[r][3] = rhsX[r];
    my[r][3] = rhsY[r];
  }

  double cx[3], cy[3];
  if (!solve3(mx, cx) || !solve3(my, cy)) return false;

  m.a = cx[0]; m.b = cx[1]; m.c = cx[2];
  m.d = cy[0]; m.e = cy[1]; m.f = cy[2];

  // Reject a fit that maps the tapped corners far from the screen corners.
  for (int i = 0; i < 4; ++i) {
    const double px = m.a * raw[i].x + m.b * raw[i].y + m.c;
    const double py = m.d * raw[i].x + m.e * raw[i].y + m.f;
    if (fabs(px - targetX[i]) > 60.0 || fabs(py - targetY[i]) > 60.0) {
      return false;
    }
  }
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

static void applyBrightness() {
  writeBacklightDuty(static_cast<uint32_t>(brightness) * 255 / 100);
}

static void initBacklight() {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcAttach(PIN_TFT_BL, 5000, 8);
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

static void loadSettings() {
  settingsPrefs.begin("settings", true);
  brightness = settingsPrefs.getUChar("bri", 80);
  settingsPrefs.end();
  brightness = constrain(brightness, BRI_MIN, BRI_MAX);
}

static void saveSettings() {
  settingsPrefs.begin("settings", false);
  settingsPrefs.putUChar("bri", brightness);
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
// and every other phone is refused (writes ignored, link not counted) until the
// gear is held for 1.2 s on the board screen, which clears the binding.

Preferences identityPrefs;
char DEVICE_TAG[7] = "000000";
char DEVICE_NAME[32] = "Phone Deck";
bool ownerKnown = false;
uint8_t ownerBda[6] = {0, 0, 0, 0, 0, 0};
char ownerLabel[18] = "unbound";

// True only while the connected peer is this board's owner; the Phone Link
// write callbacks check it, so a stranger's writes never reach the UI.
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

// True when this peer may talk to the deck. An unbound board adopts the first
// phone that arrives, which is what makes a fresh flash answer to its own
// phone and to nothing else afterwards.
static bool claimPeer(const uint8_t bda[6]) {
  if (!ownerKnown) {
    memcpy(ownerBda, bda, sizeof(ownerBda));
    ownerKnown = true;
    bdaToText(ownerBda, ownerLabel, sizeof(ownerLabel));
    identityPrefs.begin("identity", false);
    identityPrefs.putBytes("owner", ownerBda, sizeof(ownerBda));
    identityPrefs.end();
    Serial.printf("[id] bound to %s\n", ownerLabel);
    return true;
  }
  return memcmp(ownerBda, bda, sizeof(ownerBda)) == 0;
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
    peerAllowed = claimPeer(param->connect.remote_bda);
    if (!peerAllowed) {
      Serial.println("[ble] refused: this deck is bound to another phone");
      linkDirty = true;
      return;
    }
    serverConnected = true;
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

// 26x22 was under a fingertip on a resistive panel; 32x24 clears it.
static const Rect GEAR_BTN = {284, 5, 32, 24};
static const Rect LINK_PILL = {204, 6, 76, 22};
static const Rect BACK_BTN = {8, 6, 24, 24};

static const Rect STATUS_CARD = {8, 40, 304, 76};
static const Rect NOTIF_ROW[3] = {{8, 134, 304, 32}, {8, 168, 304, 32},
                                  {8, 202, 304, 32}};

static const Rect DETAIL_DISMISS = {12, 202, 148, 30};
static const Rect DETAIL_BACK = {168, 202, 140, 30};

static const Rect SET_BRI_DOWN = {16, 58, 54, 30};
static const Rect SET_BRI_UP = {250, 58, 54, 30};
static const Rect SET_CLEAR = {232, 106, 72, 26};
static const Rect SET_CAL = {8, 146, 304, 32};
static const Rect SET_CLOSE = {8, 186, 304, 34};

// -------------------------------------------------------------- draw utils ---

static void drawTextAt(const char *text, int x, int y, uint8_t size, uint16_t fg,
                       uint16_t bg) {
  tft.setTextSize(size);
  tft.setTextColor(fg, bg);
  tft.setCursor(x, y);
  tft.print(text);
}

static void drawTextCentered(const char *text, Rect r, uint8_t size, uint16_t fg,
                             uint16_t bg) {
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setTextSize(size);
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
  tft.setTextSize(size);
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

static void drawHeader() {
  tft.fillRect(0, 0, SCREEN_W, HEADER_H, C_BG);
  tft.drawFastHLine(0, HEADER_H - 1, SCREEN_W, C_EDGE);

  // Terminal chip, then a two-line wordmark.
  const Rect chip = {8, 7, 22, 20};
  tft.drawRoundRect(chip.x, chip.y, chip.w, chip.h, 6, C_EDGE);
  drawTextAt(">_", chip.x + 4, chip.y + 6, 1, C_ACCENT, C_BG);
  drawTextAt("Phone Deck", 38, 5, 2, C_TEXT, C_BG);
  char subtitle[24];
  snprintf(subtitle, sizeof(subtitle), "esp32 // #%s", DEVICE_TAG);
  drawTextAt(subtitle, 38, 21, 1, C_DIM, C_BG);

  // Link pill.
  const Rect pill = LINK_PILL;
  tft.fillRoundRect(pill.x, pill.y, pill.w, pill.h, 11, C_TILE);
  const bool linked = phoneLinked();
  tft.drawRoundRect(pill.x, pill.y, pill.w, pill.h, 11, linked ? C_ACCENT : C_EDGE);
  tft.fillCircle(pill.x + 12, pill.y + 11, 3, linked ? C_ACCENT : C_DIM);
  drawTextAt(linked ? "LINKED" : "OFFLINE",
             pill.x + 20, pill.y + 8, 1, linked ? C_ACCENT : C_DIM, C_TILE);

  // Gear opens settings.
  tft.fillRoundRect(GEAR_BTN.x, GEAR_BTN.y, GEAR_BTN.w, GEAR_BTN.h, 6, C_TILE);
  tft.drawRoundRect(GEAR_BTN.x, GEAR_BTN.y, GEAR_BTN.w, GEAR_BTN.h, 6, C_EDGE);
  drawGear(GEAR_BTN.x + GEAR_BTN.w / 2, GEAR_BTN.y + GEAR_BTN.h / 2, C_TEXT);
}

static void drawSectionHeader() {
  tft.fillCircle(12, 128, 3, C_PURPLE);
  drawTextAt("01 // NOTIFICATIONS", 20, 124, 1, C_TEXT, C_BG);

  char right[16];
  const int unread = countUnread();
  if (unread > 0) snprintf(right, sizeof(right), "%d NEW", unread);
  else snprintf(right, sizeof(right), "DIRECT ACCESS");
  drawTextRight(right, SCREEN_W - 10, 124, 1, unread > 0 ? C_YELLOW : C_DIM, C_BG);
}

// One wide status card: terminal line, big clock, phone battery on the right.
static void drawStatusCard() {
  const Rect r = STATUS_CARD;
  tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_TILE);
  tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_EDGE);

  drawTextAt("$ sys.status --", r.x + 12, r.y + 7, 1, C_DIM, C_TILE);
  const bool linked = phoneLinked();
  const char *state = linked ? "READY // ONLINE"
                             : (ownerKnown ? "STANDBY // OFFLINE"
                                           : "BIND // FIRST PHONE");
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setTextSize(1);
  tft.getTextBounds(state, 0, 0, &bx, &by, &bw, &bh);
  const int textRight = r.x + r.w - 12;
  drawTextAt(state, textRight - static_cast<int>(bw), r.y + 7, 1,
             linked ? C_ACCENT : C_DIM, C_TILE);
  tft.fillCircle(textRight - static_cast<int>(bw) - 9, r.y + 11, 3,
                 linked ? C_ACCENT : C_PINK);

  tft.drawFastHLine(r.x + 12, r.y + 20, r.w - 24, C_EDGE);

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
    drawTextAt(hhmm, r.x + 12, r.y + 24, 4, C_TEXT, C_TILE);
    drawTextAt(seconds, r.x + 134, r.y + 36, 1, C_ACCENT, C_TILE);
    drawTextAt(date, r.x + 12, r.y + 60, 1, C_DIM, C_TILE);
  } else {
    drawTextAt("--:--", r.x + 12, r.y + 24, 4, C_DIM, C_TILE);
    drawTextAt("no time yet  //  waiting", r.x + 12, r.y + 60, 1, C_DIM, C_TILE);
  }

  const int battX = r.x + 236;
  drawTextAt("BATTERY", battX, r.y + 26, 1, C_DIM, C_TILE);
  char percent[8];
  if (batteryLevel >= 0) snprintf(percent, sizeof(percent), "%d%%", batteryLevel);
  else snprintf(percent, sizeof(percent), "--");
  drawTextAt(percent, battX, r.y + 36, 2, batteryLevel < 0 ? C_DIM : C_TEXT, C_TILE);

  const Rect bar = {static_cast<int16_t>(battX), static_cast<int16_t>(r.y + 56), 58, 6};
  tft.drawRoundRect(bar.x, bar.y, bar.w, bar.h, 3, C_EDGE);
  if (batteryLevel >= 0) {
    int fill = (bar.w - 2) * batteryLevel / 100;
    if (fill < 2) fill = 2;
    tft.fillRoundRect(bar.x + 1, bar.y + 1, fill, bar.h - 2, 2,
                      batteryLevel <= 20 ? C_PINK : C_ACCENT);
  }
  if (batteryCharging) drawTextAt("CHG", battX + 46, r.y + 26, 1, C_MINT, C_TILE);
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

static void drawNoticeRow(int slot) {
  const Rect r = NOTIF_ROW[slot];
  const bool has = slot < noticeCount;

  tft.fillRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, has ? C_TILE : C_BG);
  if (!has) {
    tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, C_EDGE);
    if (slot == 0) {
      const char *empty = phoneLinked() ? "no notifications"
                                        : "waiting for companion";
      drawTextCentered(empty, r, 1, C_DIM, C_BG);
    }
    return;
  }

  const Notice &n = notices[slot];
  tft.drawRoundRect(r.x, r.y, r.w, r.h, CORNER_CARD, n.unread ? C_ACCENT_DIM : C_EDGE);

  // App tile with the first letter, like the module icons on the reference.
  const Rect icon = {static_cast<int16_t>(r.x + 8), static_cast<int16_t>(r.y + 6), 20, 20};
  tft.fillRoundRect(icon.x, icon.y, icon.w, icon.h, 6, C_TILE_HI);
  tft.drawRoundRect(icon.x, icon.y, icon.w, icon.h, 6, n.unread ? C_ACCENT_DIM : C_EDGE);
  const char initial[2] = {n.app[0], '\0'};
  drawTextCentered(initial, icon, 2, n.unread ? C_YELLOW : C_DIM, C_TILE_HI);

  drawTextAt(n.app, r.x + 36, r.y + 4, 1, n.unread ? C_ACCENT : C_DIM, C_TILE);

  char age[8];
  formatAge(n.at, age, sizeof(age));
  drawTextRight(age, r.x + r.w - 10, r.y + 4, 1, C_DIM, C_TILE);

  char title[28];
  int16_t bx = 0, by = 0;
  uint16_t bw = 0, bh = 0;
  tft.setTextSize(2);
  tft.getTextBounds(n.title, 0, 0, &bx, &by, &bw, &bh);
  if (static_cast<int>(bw) <= r.w - 46) {
    drawTextAt(n.title, r.x + 36, r.y + 14, 2, n.unread ? C_TEXT : C_DIM, C_TILE);
  } else {
    fitText(title, sizeof(title), n.title, 20);
    drawTextAt(title, r.x + 36, r.y + 16, 1, n.unread ? C_TEXT : C_DIM, C_TILE);
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
    snprintf(candidate, sizeof(candidate), "%s%s%s", line, used > 0 ? " " : "", word);
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
  tft.drawRoundRect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.w, BACK_BTN.h, 6, C_EDGE);
  drawTextCentered("<", BACK_BTN, 1, C_TEXT, C_TILE);
  drawTextAt(n.app, 40, 11, 1, C_ACCENT, C_BG);

  char when[16];
  formatAge(n.at, when, sizeof(when));
  drawTextRight(when, SCREEN_W - 12, 11, 1, C_DIM, C_BG);

  drawWrappedText(n.title, 12, 44, SCREEN_W - 24, 2, C_TEXT, C_BG, 2);
  tft.drawFastHLine(12, 88, SCREEN_W - 24, C_EDGE);
  drawWrappedText(n.body[0] != '\0' ? n.body : "(no message)", 12, 98,
                  SCREEN_W - 24, 1, C_DIM, C_BG, 9);

  drawButton(DETAIL_DISMISS, "DISMISS", 2, true);
  drawButton(DETAIL_BACK, "BACK", 2, false);
}

static void drawSettings() {
  tft.fillScreen(C_BG);
  tft.drawFastHLine(0, HEADER_H - 1, SCREEN_W, C_EDGE);

  tft.fillRoundRect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.w, BACK_BTN.h, 6, C_TILE);
  tft.drawRoundRect(BACK_BTN.x, BACK_BTN.y, BACK_BTN.w, BACK_BTN.h, 6, C_EDGE);
  drawTextCentered("<", BACK_BTN, 1, C_TEXT, C_TILE);
  drawTextAt("settings", 40, 8, 2, C_TEXT, C_BG);
  drawTextRight(phoneLinked() ? "LINKED" : "OFFLINE", SCREEN_W - 12, 13, 1,
                phoneLinked() ? C_ACCENT : C_DIM, C_BG);

  // Brightness.
  tft.fillRoundRect(8, 38, 304, 58, CORNER_CARD, C_TILE);
  tft.drawRoundRect(8, 38, 304, 58, CORNER_CARD, C_EDGE);
  drawTextAt("BRIGHTNESS", 18, 44, 1, C_DIM, C_TILE);
  drawButton(SET_BRI_DOWN, "-", 3, false);
  drawButton(SET_BRI_UP, "+", 3, false);
  char value[8];
  snprintf(value, sizeof(value), "%u%%", static_cast<unsigned>(brightness));
  const Rect valueRect = {78, 62, 164, 24};
  drawTextCentered(value, valueRect, 2, C_ACCENT, C_TILE);

  // Notifications.
  tft.fillRoundRect(8, 102, 304, 38, CORNER_CARD, C_TILE);
  tft.drawRoundRect(8, 102, 304, 38, CORNER_CARD, C_EDGE);
  char count[28];
  snprintf(count, sizeof(count), "%d waiting", noticeCount);
  drawTextAt("NOTIFICATIONS", 18, 108, 1, C_DIM, C_TILE);
  drawTextAt(count, 18, 120, 1, C_TEXT, C_TILE);
  drawButton(SET_CLEAR, "CLEAR", 1, false);

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

static void drawCalProgress() {
  for (int i = 0; i < 4; ++i) {
    const int x = 250 + i * 18;
    if (i < calIndex) tft.fillCircle(x, 22, 5, C_ACCENT);
    else tft.drawCircle(x, 22, 5, C_EDGE);
  }
}

static void drawCalibrationScreen() {
  tft.fillScreen(C_BG);
  drawCrosshair(CAL_TARGET_X[calIndex], CAL_TARGET_Y[calIndex]);

  char title[24];
  snprintf(title, sizeof(title), "CALIBRATE %u/4",
           static_cast<unsigned>(calIndex) + 1);
  drawTextAt(title, 10, 10, 2, C_ACCENT, C_BG);
  drawTextAt("Tap the dot in this corner", 10, 36, 1, C_TEXT, C_BG);
  drawTextAt("order: TL TR BR BL", 10, 48, 1, C_DIM, C_BG);
  drawTextAt("hold 2s anywhere = cancel", 10, 226, 1, C_DIM, C_BG);
  drawTextAt("raw", 10, 212, 1, C_DIM, C_BG);
  drawCalProgress();
}

static void drawVerifyScreen() {
  tft.fillScreen(C_BG);
  drawCrosshair(VERIFY_X[verifyIndex], VERIFY_Y[verifyIndex]);

  char title[24];
  snprintf(title, sizeof(title), "CHECK %u/3",
           static_cast<unsigned>(verifyIndex) + 1);
  drawTextAt(title, 10, 10, 2, C_ACCENT, C_BG);
  drawTextAt("Tap the dot to confirm", 10, 36, 1, C_TEXT, C_BG);
  drawTextAt("all three must land on target", 10, 48, 1, C_DIM, C_BG);
  drawTextAt("hold 2s anywhere = cancel", 10, 226, 1, C_DIM, C_BG);
}

static void drawVerifyFailed(int worst) {
  tft.fillScreen(C_BG);
  drawTextAt("TOUCH MAP IS OFF", 10, 14, 2, C_ACCENT, C_BG);

  char line[44];
  snprintf(line, sizeof(line), "a check tap missed by %d px", worst);
  drawTextAt(line, 10, 42, 1, C_TEXT, C_BG);
  drawTextAt("apply one correction, then re-check", 10, 56, 1, C_DIM, C_BG);

  drawButton(ADJ_SWAP, "SWAP AXES", 1, false);
  drawButton(ADJ_FLIP_X, "FLIP X", 1, false);
  drawButton(ADJ_FLIP_Y, "FLIP Y", 1, false);
  drawButton(ADJ_REDO, "REDO CORNERS", 1, false);
  drawButton(ADJ_SAVE, "SAVE AS IS", 2, true);
  drawTextAt("hold 2s anywhere = cancel", 10, 232, 1, C_DIM, C_BG);
}

static void showCalibrationFailed() {
  tft.fillScreen(C_BG);
  drawTextAt("Corners did not fit", 10, 100, 2, C_ACCENT, C_BG);
  drawTextAt("tap squarely on each dot", 10, 128, 1, C_DIM, C_BG);
  delay(1500);
}

// ------------------------------------------------------------------ touch ---

static bool readRawTouch(int &rawX, int &rawY) {
  if (!touch.touched()) return false;
  const TS_Point p = touch.getPoint();
  rawX = p.x;
  rawY = p.y;
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
  verifyIndex = 0;
  verifyFailed = false;
  verifyWorst2 = 0;
  calTapMs = 0;
  drawCalibrationScreen();
}

// Abandoning a run throws the candidate mapping away and keeps whatever was
// stored before, so a failed attempt can never leave the deck unusable.
static void cancelCalibration() {
  loadMapping();
  drawBoard();
}

static void saveTouchMapping() {
  saveMapping(mapping);
  mappingStored = true;
  drawBoard();
}

static void updateRawReadout(int rawX, int rawY) {
  if (millis() - rawUiMs < 120) return;
  rawUiMs = millis();
  char line[28];
  snprintf(line, sizeof(line), "raw %4d %4d", rawX, rawY);
  drawTextAt(line, 34, 212, 1, C_DIM, C_BG);
}

static void startVerify() {
  screen = SCR_VERIFY;
  verifyIndex = 0;
  verifyFailed = false;
  verifyWorst2 = 0;
  drawVerifyScreen();
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

  if (calIndex < 4) {
    drawCalibrationScreen();
    return;
  }

  TouchMapping candidate;
  if (!fitMapping(calRaw, candidate)) {
    showCalibrationFailed();
    calIndex = 0;
    drawCalibrationScreen();
    return;
  }
  mapping = candidate;  // RAM only until the checks pass
  startVerify();
}

static void handleVerifyTap(int x, int y) {
  const int dx = x - VERIFY_X[verifyIndex];
  const int dy = y - VERIFY_Y[verifyIndex];
  const int err2 = dx * dx + dy * dy;
  if (err2 > verifyWorst2) verifyWorst2 = err2;

  ++verifyIndex;
  if (verifyIndex < 3) {
    drawVerifyScreen();
    return;
  }

  if (verifyWorst2 <= VERIFY_TOLERANCE * VERIFY_TOLERANCE) {
    saveTouchMapping();
    return;
  }
  verifyFailed = true;
  drawVerifyFailed(static_cast<int>(sqrtf(static_cast<float>(verifyWorst2))));
}

// One-button fixes for the two failures an axis-insensitive fit can produce.
static void handleVerifyFixTap(int x, int y) {
  if (inside(x, y, ADJ_SWAP)) {
    const float a = mapping.a, b = mapping.b;
    const float d = mapping.d, e = mapping.e;
    mapping.a = b;
    mapping.b = a;
    mapping.d = e;
    mapping.e = d;
    startVerify();
    return;
  }
  if (inside(x, y, ADJ_FLIP_X)) {
    mapping.a = -mapping.a;
    mapping.b = -mapping.b;
    mapping.c = (SCREEN_W - 1) - mapping.c;
    startVerify();
    return;
  }
  if (inside(x, y, ADJ_FLIP_Y)) {
    mapping.d = -mapping.d;
    mapping.e = -mapping.e;
    mapping.f = (SCREEN_H - 1) - mapping.f;
    startVerify();
    return;
  }
  if (inside(x, y, ADJ_REDO)) {
    startCalibration();
    return;
  }
  if (inside(x, y, ADJ_SAVE)) saveTouchMapping();
}

static void openSettings() {
  screen = SCR_SETTINGS;
  drawSettings();
}

static void handleSettingsTap(int x, int y) {
  if (inside(x, y, BACK_BTN) || inside(x, y, SET_CLOSE)) {
    drawBoard();
    return;
  }
  if (inside(x, y, SET_BRI_DOWN)) {
    brightness = brightness > BRI_MIN + BRI_STEP - 1 ? brightness - BRI_STEP : BRI_MIN;
    applyBrightness();
    saveSettings();
    drawSettings();
    return;
  }
  if (inside(x, y, SET_BRI_UP)) {
    brightness = brightness < BRI_MAX ? brightness + BRI_STEP : BRI_MAX;
    applyBrightness();
    saveSettings();
    drawSettings();
    return;
  }
  if (inside(x, y, SET_CLEAR)) {
    noticeCount = 0;
    drawSettings();
    return;
  }
  if (inside(x, y, SET_CAL)) {
    startCalibration();
    return;
  }
  if (inside(x, y, SET_CLOSE)) {
    drawBoard();
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
  if (inside(x, y, BACK_BTN)) {
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
  switch (ev.type) {
    case EVT_LINK_ON:
    case EVT_LINK_OFF:
      if (screen == SCR_BOARD) {
        drawHeader();
        drawNoticeRow(0);  // the empty-state hint depends on the link
      }
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
}

static void processUiEvents() {
  if (uiQueue == nullptr) return;
  UiEvent ev;
  while (xQueueReceive(uiQueue, &ev, 0) == pdTRUE) applyUiEvent(ev);

  if (linkDirty) {
    linkDirty = false;
    if (screen == SCR_BOARD) drawHeader();
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

bool wasTouched = false;
bool ignoreTouchUntilRelease = false;
uint32_t touchStartMs = 0;
uint32_t lastActionMs = 0;
uint32_t pressedUid = 0;
int pressedTarget = TARGET_NONE;

static int targetAt(int x, int y) {
  if (inside(x, y, GEAR_BTN)) return TARGET_GEAR;
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

  // Holding the panel while the board boots always opens calibration, even if
  // the stored mapping is wrong. A fresh flash calibrates before the deck
  // appears, because a wrong mapping turns every key into a guess.
  if (!mappingStored || touch.touched()) {
    startCalibration();
  } else {
    drawBoard();
  }
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
  if (screen == SCR_CAL || screen == SCR_VERIFY) {
    if (screen == SCR_CAL) {
      int rawX = 0, rawY = 0;
      if (readRawTouch(rawX, rawY)) updateRawReadout(rawX, rawY);
    }
    if (down && !wasTouched) {
      touchStartMs = now;
      if (now - calTapMs > 250) {
        calTapMs = now;
        if (screen == SCR_CAL) {
          int rawX = 0, rawY = 0;
          readRawTouch(rawX, rawY);
          handleCalibrationTap(rawX, rawY);
        } else if (verifyFailed) {
          handleVerifyFixTap(x, y);
        } else {
          handleVerifyTap(x, y);
        }
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

  if (down && !wasTouched) {
    touchStartMs = now;
    pressedTarget = targetAt(x, y);
    pressedUid = (pressedTarget >= 0 && pressedTarget < noticeCount)
                     ? notices[pressedTarget].uid
                     : 0;
  }

  // Hold the title bar for 1.5 s to calibrate.
  if (down && pressedTarget == TARGET_NONE && y < HEADER_H && x < 200 &&
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
