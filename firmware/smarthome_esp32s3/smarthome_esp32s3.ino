// AI Smart Home: ESP32-S3 sensor node.
//
//   loop()  (core 1): sensors + LEDs + gas warning. Entirely local; works with no Wi-Fi, MQTT or backend.
//   network (core 0): Wi-Fi, pairing, authenticated MQTT, telemetry. See NetLink.cpp.
//
// Setup, flashing and verification: see the "ESP32 firmware" section of the project README.

#include "NetLink.h"
#include "Sensors.h"
#include "config.h"

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== AI Smart Home node starting ===");

  sensorsBegin();   // local monitoring first, so it never depends on the network
  networkBegin();
}

void loop() {
  sensorsUpdate();
  delay(5);
}
