// AI Smart Home: ESP32-S3 sensor node.
//
//   loop()  (core 1): sensors + LEDs + gas warning. Entirely local; works with no Wi-Fi, MQTT or backend.
//   network (core 0): Wi-Fi, pairing, authenticated MQTT, telemetry. See NetLink.cpp.
//
// Setup, flashing and verification: see the "ESP32 firmware" section of the project README.

#include <esp_system.h>

#include "NetLink.h"
#include "Sensors.h"
#include "config.h"

static const char* resetReasonName(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON: return "POWERON";
    case ESP_RST_EXT: return "EXTERNAL_PIN";
    case ESP_RST_SW: return "SOFTWARE";
    case ESP_RST_PANIC: return "PANIC (crash)";
    case ESP_RST_INT_WDT: return "INT_WDT (interrupt watchdog)";
    case ESP_RST_TASK_WDT: return "TASK_WDT (task watchdog)";
    case ESP_RST_WDT: return "WDT (other watchdog)";
    case ESP_RST_DEEPSLEEP: return "DEEPSLEEP";
    case ESP_RST_BROWNOUT: return "BROWNOUT (supply voltage dropped)";
    case ESP_RST_SDIO: return "SDIO";
    default: return "UNKNOWN";
  }
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== AI Smart Home node starting ===");
  // Why the previous run ended. Repeated BROWNOUT means the supply sags (MQ-2 heater + Wi-Fi on a weak USB port/cable);
  // PANIC / TASK_WDT / INT_WDT mean a firmware crash or hang.
  Serial.printf("[boot] reset reason: %s\n", resetReasonName(esp_reset_reason()));

  sensorsBegin();   // local monitoring first, so it never depends on the network
  networkBegin();
}

void loop() {
  sensorsUpdate();
  delay(5);
}
