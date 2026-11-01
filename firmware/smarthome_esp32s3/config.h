// Non-secret configuration: pins, timings, thresholds. Secrets (Wi-Fi, server addresses) live in secrets.h.
#pragma once

#include <Arduino.h>
#include <sdkconfig.h>

// ---------------------------------------------------------------------------------------------------------------------
// Pins
// ---------------------------------------------------------------------------------------------------------------------
// Original assignment: DHT22=14, PIR=26, PIR LED=12, MQ-2=35, MQ-2 LED=13 (these suit a classic ESP32 DevKit).
// On the ESP32-S3, GPIO35 has no ADC and GPIO26 is wired to flash/PSRAM, so only those two were moved.
constexpr uint8_t PIN_DHT22   = 14;  // DHT22 data                      (unchanged)
constexpr uint8_t PIN_PIR     = 5;   // PIR motion sensor output        (was 26: flash/PSRAM range on the S3)
constexpr uint8_t PIN_PIR_LED = 12;  // lights while motion is detected (unchanged)
constexpr uint8_t PIN_MQ2     = 4;   // MQ-2 analog output (AO), ADC1_CH3 (was 35: no ADC on the S3)
constexpr uint8_t PIN_MQ2_LED = 13;  // gas warning LED                 (unchanged)

// ---------------------------------------------------------------------------------------------------------------------
// Compile-time pin check. A wrong pin on an ESP32-S3 fails here with an explanation instead of misbehaving later.
// (Facts: ESP32-S3 ADC1 = GPIO1-10, ADC2 = GPIO11-20. GPIO26-32 talk to the module's SPI flash/PSRAM, and GPIO33-37
// are taken by octal flash/PSRAM on the common N8R8/N16R8 modules.)
// ---------------------------------------------------------------------------------------------------------------------
#if CONFIG_IDF_TARGET_ESP32S3
constexpr bool pinReservedOnS3(int p) { return (p >= 26 && p <= 37); }
constexpr bool pinIsAdc1OnS3(int p) { return p >= 1 && p <= 10; }

static_assert(!pinReservedOnS3(PIN_DHT22),   "ESP32-S3: PIN_DHT22 is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(!pinReservedOnS3(PIN_PIR),     "ESP32-S3: PIN_PIR is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(!pinReservedOnS3(PIN_PIR_LED), "ESP32-S3: PIN_PIR_LED is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(!pinReservedOnS3(PIN_MQ2),     "ESP32-S3: PIN_MQ2 is in GPIO26-37, reserved for flash/PSRAM. Pick an ADC1 pin (GPIO1-10).");
static_assert(!pinReservedOnS3(PIN_MQ2_LED), "ESP32-S3: PIN_MQ2_LED is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(pinIsAdc1OnS3(PIN_MQ2),        "ESP32-S3: PIN_MQ2 must be an ADC1 pin (GPIO1-10); this pin cannot do analogRead.");
#endif

// ---------------------------------------------------------------------------------------------------------------------
// Sensing and local behaviour (all of this works with no network)
// ---------------------------------------------------------------------------------------------------------------------
constexpr uint32_t PIR_POLL_MS      = 20;      // PIR is checked this often; the LED follows it immediately
constexpr uint32_t DHT_INTERVAL_MS  = 2500;    // DHT22 needs >= 2 s between reads
constexpr uint32_t DHT_VALID_MS     = 15000;   // a DHT value older than this is treated as "no reading"
constexpr uint32_t MQ2_SAMPLE_MS    = 250;     // MQ-2 sampling period
constexpr int      MQ2_AVG_SAMPLES  = 8;       // moving average length (raw ADC counts, 12-bit: 0-4095)

// MQ-2 heater warm-up. Readings during warm-up are unreliable, so they are neither used for the warning
// nor published. (A brand-new sensor may need much longer to settle; see the README.)
constexpr uint32_t MQ2_WARMUP_MS    = 60000;

// Gas warning thresholds are RAW ADC counts, not ppm. THESE ARE PLACEHOLDERS: measure your own sensor in clean air
// and with a gas source, then set them. Hysteresis stops the LED flickering around the threshold.
constexpr int      GAS_WARN_RAW     = 2000;    // warning starts at or above this
constexpr int      GAS_CLEAR_RAW    = 1800;    // warning ends below this
constexpr int      GAS_CONFIRM_SAMPLES = 3;    // consecutive samples needed to switch state

// ---------------------------------------------------------------------------------------------------------------------
// Remote LED control. Remote commands only change how the two indicator LEDs are DRIVEN; they cannot touch sensing,
// the gas warning logic, or publishing. The gas warning always lights the gas LED (see gasLedOutput in Logic.h).
// ---------------------------------------------------------------------------------------------------------------------
constexpr uint32_t OVERRIDE_MAX_MS = 3600000;       // a forced LED mode returns to Auto after 1 hour (and on reboot)
constexpr int64_t  COMMAND_MAX_LIFETIME_S = 600;    // refuse commands whose expiry is further away than this
constexpr uint32_t STATE_MIN_INTERVAL_MS = 1000;    // at most one state report per second on changes

// ---------------------------------------------------------------------------------------------------------------------
// Networking
// ---------------------------------------------------------------------------------------------------------------------
constexpr uint32_t PUBLISH_INTERVAL_MS = 30000;  // telemetry period (the backend treats > 90 s of silence as offline)
constexpr uint16_t MQTT_KEEPALIVE_S    = 30;
constexpr uint32_t WIFI_CONNECT_TIMEOUT_MS = 20000;

// Reconnect back-off: starts at MIN, doubles after each failure, capped at MAX, +/-20% jitter, resets on success.
constexpr uint32_t WIFI_BACKOFF_MIN_MS = 1000;
constexpr uint32_t WIFI_BACKOFF_MAX_MS = 60000;
constexpr uint32_t MQTT_BACKOFF_MIN_MS = 2000;
constexpr uint32_t MQTT_BACKOFF_MAX_MS = 120000;

// After this many consecutive "not authorised" answers the device stops hoping and tells you to re-pair.
constexpr int      MQTT_AUTH_FAILS_BEFORE_HINT = 3;

// Topics; the backend defines these (home/devices/{deviceId}/telemetry|status).
constexpr const char* TOPIC_PREFIX = "home/devices/";
