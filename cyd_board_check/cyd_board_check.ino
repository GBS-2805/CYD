/*
 * CYD Board Check - ESP32-2432S024R (2.4" resistive touch CYD)
 *
 * The sketch to flash before the deck firmware, and the one to re-flash when a
 * board misbehaves. It proves, on one screen and in one serial report, that the
 * panel, the backlight, the touch panel, the three RGB LED channels and the
 * flash size are what the board was sold as. No Bluetooth, no phone, no
 * companion app: nothing here can fail for a reason outside the board.
 *
 * Libraries needed (the same three the deck uses):
 *   - Adafruit GFX Library
 *   - Adafruit ILI9341
 *   - XPT2046_Touchscreen (Paul Stoffregen)
 *
 * Touch is reported raw and mapped, never calibrated: the raw numbers are the
 * measurement, and they are what tells you whether the axes are swapped or
 * mirrored. Nothing is written to flash.
 */

#include <Arduino.h>
#include <SPI.h>
#include <esp_mac.h>
#include <esp_system.h>

#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include <XPT2046_Touchscreen.h>

namespace {

// Pins fixed by the ESP32-2432S024R board.
constexpr int PIN_TFT_CS = 15;
constexpr int PIN_TFT_DC = 2;
constexpr int PIN_TFT_BL = 27;
constexpr int PIN_TOUCH_CS = 33;
constexpr int PIN_SCK = 14;
constexpr int PIN_MISO = 12;
constexpr int PIN_MOSI = 13;
constexpr int PIN_LED_R = 4;
constexpr int PIN_LED_G = 16;
constexpr int PIN_LED_B = 17;

// The box the raw touch values are mapped through. These are starting points,
// not measurements: the RAW line on screen is the real reading, so widen or
// narrow these only once you have seen a panel report its own corners.
constexpr int TOUCH_MIN_X = 300;
constexpr int TOUCH_MAX_X = 3800;
constexpr int TOUCH_MIN_Y = 300;
constexpr int TOUCH_MAX_Y = 3800;

constexpr int16_t SCREEN_W = 320;
constexpr int16_t SCREEN_H = 240;
constexpr uint32_t LED_STEP_MS = 4000;
constexpr uint32_t TOUCH_HOLD_MS = 2500;

constexpr uint16_t BG = 0x0841;
constexpr uint16_t BAR = 0x03EF;
constexpr uint16_t DIM = 0x8410;
constexpr uint16_t TEXT = 0xFFFF;
constexpr uint16_t GOOD = 0x07E0;
constexpr uint16_t WARN = 0xFD20;

constexpr int ROW_TOP = 34;
constexpr int ROW_H = 16;
constexpr int ROW_COUNT = 9;

Adafruit_ILI9341 tft(PIN_TFT_CS, PIN_TFT_DC);
XPT2046_Touchscreen touch(PIN_TOUCH_CS);

const char* const LED_NAME[4] = {"off", "red", "green", "blue"};
const char* const RESET_NAME[] = {"unknown", "power-on", "external pin", "software",
                                  "panic", "interrupt watchdog", "task watchdog",
                                  "other watchdog", "deep sleep", "brownout",
                                  "sdio"};

int ledStep = 0;
uint32_t ledAt = 0;

int tapCount = 0;
uint32_t tappedAt = 0;
bool wasDown = false;
int16_t dotX = -1;
int16_t dotY = -1;

int rawMinX = 4095, rawMaxX = 0, rawMinY = 4095, rawMaxY = 0;

// ---------------------------------------------------------------- helpers ----

int rowY(int row) { return ROW_TOP + row * ROW_H; }

void row(int index, const char* name, const String& value, uint16_t colour) {
  const int y = rowY(index);
  tft.fillRect(0, y, SCREEN_W, ROW_H, BG);
  tft.setTextSize(1);
  tft.setCursor(6, y + 4);
  tft.setTextColor(DIM, BG);
  tft.print(name);
  tft.setCursor(104, y + 4);
  tft.setTextColor(colour, BG);
  tft.print(value);
}

const char* resetReasonName() {
  const esp_reset_reason_t reason = esp_reset_reason();
  const int index = static_cast<int>(reason);
  if (index < 0 || index >= static_cast<int>(sizeof RESET_NAME / sizeof RESET_NAME[0])) {
    return RESET_NAME[0];
  }
  return RESET_NAME[index];
}

String macText() {
  uint8_t mac[6] = {0};
  if (esp_read_mac(mac, ESP_MAC_WIFI_STA) != ESP_OK) {
    return "unreadable";
  }
  char text[18];
  snprintf(text, sizeof text, "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2],
           mac[3], mac[4], mac[5]);
  return String(text);
}

String kilobytes(uint32_t bytes) { return String(bytes / 1024) + " kB"; }

String psramText() { return psramFound() ? kilobytes(ESP.getPsramSize()) : "none found"; }

void applyLed(int step) {
  // Active low: each channel lights when its pin is LOW.
  digitalWrite(PIN_LED_R, step == 1 ? LOW : HIGH);
  digitalWrite(PIN_LED_G, step == 2 ? LOW : HIGH);
  digitalWrite(PIN_LED_B, step == 3 ? LOW : HIGH);
}

void serialReport() {
  Serial.println();
  Serial.println("== CYD board check ==");
  Serial.printf("chip       %s rev %u, %u core(s) at %u MHz\n", ESP.getChipModel(),
                ESP.getChipRevision(), ESP.getChipCores(), getCpuFrequencyMhz());
  Serial.printf("flash      %lu kB, sketch %lu kB, %lu kB free\n",
                (unsigned long)(ESP.getFlashChipSize() / 1024),
                (unsigned long)(ESP.getSketchSize() / 1024),
                (unsigned long)(ESP.getFreeSketchSpace() / 1024));
  Serial.printf("psram      %s\n", psramText().c_str());
  Serial.print("mac        ");
  Serial.println(macText());
  Serial.printf("reset      %s\n", resetReasonName());
  Serial.printf("heap       %lu kB free\n", (unsigned long)(ESP.getFreeHeap() / 1024));
  Serial.println("touch      raw values, uncalibrated - tap all four corners");
  Serial.println();
}

void drawHeader() {
  tft.fillRect(0, 0, SCREEN_W, 26, BAR);
  tft.setTextSize(2);
  tft.setTextColor(BG, BAR);
  tft.setCursor(8, 5);
  tft.print("CYD BOARD CHECK");
}

void drawRows() {
  row(0, "chip", String(ESP.getChipModel()) + " rev " + String(ESP.getChipRevision()) +
                   " / " + String(ESP.getChipCores()) + " cores",
      TEXT);
  row(1, "flash", kilobytes(ESP.getFlashChipSize()) + " / sketch " +
                     kilobytes(ESP.getSketchSize()),
      TEXT);
  row(2, "psram", psramText(), psramFound() ? GOOD : WARN);
  row(3, "mac", macText(), TEXT);
  row(4, "reset", resetReasonName(), TEXT);
  row(5, "heap", kilobytes(ESP.getFreeHeap()) + " free", TEXT);
  row(6, "led", String(LED_NAME[ledStep]) + " (active low, 4s each)", TEXT);
  row(7, "touch", "waiting for a tap", DIM);
  row(8, "raw", "x -, y -", DIM);
}

void drawDot(int16_t x, int16_t y, uint16_t colour) {
  tft.fillRect(x - 3, y - 3, 7, 7, colour);
}

void handleTouch() {
  if (!touch.touched()) {
    if (wasDown) {
      wasDown = false;
      if (dotX >= 0) {
        drawDot(dotX, dotY, BG);
        dotX = dotY = -1;
      }
    }
    if (tapCount > 0 && millis() - tappedAt > TOUCH_HOLD_MS) {
      row(7, "touch", "waiting for a tap", DIM);
    }
    return;
  }

  const TS_Point point = touch.getPoint();
  if (point.z < 200) {  // pressure floor: a light noisy touch is not a tap
    return;
  }

  const bool fresh = !wasDown;
  wasDown = true;
  if (fresh) {
    ++tapCount;
    tappedAt = millis();
    Serial.printf("tap %d   raw %d,%d  pressure %d\n", tapCount, point.x, point.y, point.z);
  }
  tappedAt = millis();

  if (point.x < rawMinX) rawMinX = point.x;
  if (point.x > rawMaxX) rawMaxX = point.x;
  if (point.y < rawMinY) rawMinY = point.y;
  if (point.y > rawMaxY) rawMaxY = point.y;

  int16_t x = map(point.x, TOUCH_MIN_X, TOUCH_MAX_X, 0, SCREEN_W - 1);
  int16_t y = map(point.y, TOUCH_MIN_Y, TOUCH_MAX_Y, 0, SCREEN_H - 1);
  x = constrain(x, 0, SCREEN_W - 1);
  y = constrain(y, 26, SCREEN_H - 1);

  if (fresh) {
    row(7, "touch", String("tap ") + tapCount + " -> " + x + "," + y, GOOD);
    row(8, "raw", String("x ") + rawMinX + "-" + rawMaxX + "  y " + rawMinY + "-" +
                      rawMaxY,
        TEXT);
  }
  if (dotX >= 0) {
    drawDot(dotX, dotY, BG);
  }
  dotX = x;
  dotY = y;
  drawDot(dotX, dotY, WARN);
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(200);

  // Keep the RGB LED dark before anything else draws.
  pinMode(PIN_LED_R, OUTPUT);
  pinMode(PIN_LED_G, OUTPUT);
  pinMode(PIN_LED_B, OUTPUT);
  applyLed(0);

  pinMode(PIN_TFT_BL, OUTPUT);
  digitalWrite(PIN_TFT_BL, HIGH);

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI);
  tft.begin();
  tft.setRotation(1);
  tft.fillScreen(BG);

  touch.begin(SPI);
  touch.setRotation(0);  // raw axes on purpose; the RAW line is the measurement

  drawHeader();
  drawRows();
  serialReport();

  ledAt = millis();
}

void loop() {
  if (millis() - ledAt >= LED_STEP_MS) {
    ledAt = millis();
    ledStep = (ledStep + 1) % 4;
    applyLed(ledStep);
    row(6, "led", String(LED_NAME[ledStep]) + " (active low, 4s each)", TEXT);
  }

  handleTouch();
  delay(20);
}
