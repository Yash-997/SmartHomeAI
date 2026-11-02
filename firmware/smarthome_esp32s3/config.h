// Non-secret configuration: pins, timings, thresholds. Secrets (Wi-Fi, server addresses) live in secrets.h.
#pragma once

#include <Arduino.h>
#include <sdkconfig.h>

// ---------------------------------------------------------------------------------------------------------------------
// Pins
// ---------------------------------------------------------------------------------------------------------------------
// Original assignment: DHT22=14, PIR=26, PIR LED=12, MQ-2=35, MQ-2 LED=13 (these suit a classic ESP32 DevKit).
// On the ESP32-S3, GPIO35 has no ADC and GPIO26 is wired to flash/PSRAM, so only those two were moved.
// These match the board's existing wiring: do not change them without rewiring.
constexpr uint8_t PIN_DHT22   = 14;  // DHT22 data                      (unchanged)
constexpr uint8_t PIN_PIR     = 5;   // PIR motion sensor output        (was 26: flash/PSRAM range on the S3)
constexpr uint8_t PIN_PIR_LED = 12;  // lights while motion is detected (unchanged)
constexpr uint8_t PIN_MQ2     = 4;   // MQ-2 analog output (AO)         (was 35: no ADC on the S3)
constexpr uint8_t PIN_MQ2_LED = 13;  // gas warning LED, active-high    (unchanged)

// ---------------------------------------------------------------------------------------------------------------------
// Compile-time pin check. A wrong pin fails here with an explanation instead of misbehaving later.
// (Facts: ESP32-S3 ADC1 = GPIO1-10, ADC2 = GPIO11-20. GPIO26-32 talk to the module's SPI flash/PSRAM, and GPIO33-37
// are taken by octal flash/PSRAM on the common N8R8/N16R8 modules. Classic ESP32: ADC1 = GPIO32-39,
// ADC2 = GPIO0/2/4/12-15/25-27, GPIO6-11 are the SPI flash, GPIO34-39 are input only.)
//
// MQ2_ON_ADC2: ADC2 is shared with the Wi-Fi radio and cannot be read at all while Wi-Fi is running (the read fails;
// Arduino's analogRead() then silently returns 0). On a classic ESP32, GPIO4 is ADC2, so the firmware briefly stops
// the radio to sample the MQ-2 (see "Gas sampling with ADC2" below). On the S3, GPIO4 is ADC1 and is read normally.
// ---------------------------------------------------------------------------------------------------------------------
#if CONFIG_IDF_TARGET_ESP32S3
constexpr bool pinReservedOnS3(int p) { return (p >= 26 && p <= 37); }
constexpr bool pinIsAdc1OnS3(int p) { return p >= 1 && p <= 10; }
constexpr bool MQ2_ON_ADC2 = false;

static_assert(!pinReservedOnS3(PIN_DHT22),   "ESP32-S3: PIN_DHT22 is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(!pinReservedOnS3(PIN_PIR),     "ESP32-S3: PIN_PIR is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(!pinReservedOnS3(PIN_PIR_LED), "ESP32-S3: PIN_PIR_LED is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(!pinReservedOnS3(PIN_MQ2),     "ESP32-S3: PIN_MQ2 is in GPIO26-37, reserved for flash/PSRAM. Pick an ADC1 pin (GPIO1-10).");
static_assert(!pinReservedOnS3(PIN_MQ2_LED), "ESP32-S3: PIN_MQ2_LED is in GPIO26-37, reserved for flash/PSRAM. Pick another pin.");
static_assert(pinIsAdc1OnS3(PIN_MQ2),        "ESP32-S3: PIN_MQ2 must be an ADC1 pin (GPIO1-10); this pin cannot do analogRead.");
#elif CONFIG_IDF_TARGET_ESP32
constexpr bool pinIsFlashOnEsp32(int p) { return p >= 6 && p <= 11; }
constexpr bool pinIsAdc1OnEsp32(int p) { return p >= 32 && p <= 39; }
constexpr bool pinIsAdc2OnEsp32(int p) { return p == 0 || p == 2 || p == 4 || (p >= 12 && p <= 15) || (p >= 25 && p <= 27); }
constexpr bool MQ2_ON_ADC2 = pinIsAdc2OnEsp32(PIN_MQ2);

static_assert(!pinIsFlashOnEsp32(PIN_DHT22) && !pinIsFlashOnEsp32(PIN_PIR) && !pinIsFlashOnEsp32(PIN_PIR_LED) &&
              !pinIsFlashOnEsp32(PIN_MQ2_LED) && !pinIsFlashOnEsp32(PIN_MQ2), "ESP32: GPIO6-11 are wired to the SPI flash.");
static_assert(pinIsAdc1OnEsp32(PIN_MQ2) || pinIsAdc2OnEsp32(PIN_MQ2), "ESP32: PIN_MQ2 must be an ADC pin.");
static_assert(PIN_MQ2_LED < 34 && PIN_PIR_LED < 34, "ESP32: GPIO34-39 are input only and cannot drive an LED.");
#else
#error "Unsupported board: select 'ESP32 Dev Module' or 'ESP32S3 Dev Module' (pins are only checked for those)."
#endif

// ---------------------------------------------------------------------------------------------------------------------
// Sensing and local behaviour (all of this works with no network)
// ---------------------------------------------------------------------------------------------------------------------
constexpr uint32_t PIR_POLL_MS      = 20;      // PIR is checked this often; the LED follows it immediately
constexpr uint32_t DHT_INTERVAL_MS  = 2500;    // DHT22 needs >= 2 s between reads
constexpr uint32_t DHT_VALID_MS     = 15000;   // a DHT value older than this is treated as "no reading"
constexpr uint32_t DHT_START_LOW_MS = 2;       // start signal: DHT22 needs >= 1 ms LOW
constexpr uint32_t DHT_PULSE_TIMEOUT_US = 100; // longest DHT22 pulse is ~80 us; a missing pulse gives up after this
constexpr uint32_t MQ2_SAMPLE_MS    = 250;     // MQ-2 sampling period
constexpr int      MQ2_AVG_SAMPLES  = 8;       // moving average length (raw ADC counts, 12-bit: 0-4095)

// MQ-2 heater warm-up. Readings during warm-up are unreliable, so they are neither used for the warning
// nor published. (A brand-new sensor may need much longer to settle; see the README.)
constexpr uint32_t MQ2_WARMUP_MS    = 60000;

// Gas warning thresholds are RAW ADC counts, not ppm. Recovery is 15% below the warning level, the same margin the
// backend's email alerts use (ALERT_GAS_THRESHOLD, ALERT_GAS_RECOVERY_MARGIN_PERCENT); the email threshold is set
// separately in the backend's .env. Hysteresis stops the LED flickering around the threshold.
constexpr int      GAS_WARN_RAW     = 600;     // warning (red LED) starts at or above this
constexpr int      GAS_CLEAR_RAW    = 510;     // warning ends below this
constexpr int      GAS_CONFIRM_SAMPLES = 2;    // consecutive samples needed to switch state

// A reading pinned at 0 after warm-up means AO is not reaching the ADC (unwired or no power). Real MQ-2 output in clean
// air is well above 0, so this is reported on the serial console as a wiring fault.
constexpr uint32_t MQ2_STUCK_ZERO_MS = 30000;

// Each MQ-2 sample is the average of this many back-to-back ADC conversions (a few hundred microseconds in total).
// Conversions that fail (ADC2 busy with Wi-Fi) are discarded, never counted as 0.
constexpr int      MQ2_BURST_READS  = 16;
constexpr int      MQ2_DISCARD_READS = 2;     // conversions thrown away before each burst (ADC settling)
constexpr uint32_t MQ2_SETTLE_US    = 2000;  // wait after the radio stops before sampling ADC2

// A gas value older than this is not published (the dashboard shows "no reading" instead of an old number).
// The gas warning itself is never cleared by missing samples: it only ends on real readings below GAS_CLEAR_RAW.
constexpr uint32_t MQ2_SAMPLE_MAX_AGE_MS = 45000;

// Gas sampling with ADC2 (classic ESP32, MQ-2 on GPIO4; see MQ2_ON_ADC2). The network task stops the Wi-Fi radio for a
// moment, the MQ-2 is sampled, and the radio restarts and reconnects (typically 1-3 s offline). Commands sent meanwhile
// are held by the broker (persistent MQTT session). While Wi-Fi is down for any other reason the MQ-2 is sampled
// continuously, so the local warning LED never depends on the network.
constexpr uint32_t GAS_PAUSE_INTERVAL_MS      = 15000;  // normal
constexpr uint32_t GAS_PAUSE_INTERVAL_FAST_MS = 8000;   // while a reading is near/above the threshold or a warning is on
constexpr uint32_t GAS_PAUSE_MAX_MS           = 600;    // give up waiting for a sample after this long

// ---------------------------------------------------------------------------------------------------------------------
// PIR motion sensor. GPIO5 is a boot-strapping pin with a pull-up at reset; with no pull afterwards, a PIR output that is
// not actively driving the line leaves the pin floating, and it can read HIGH ("motion") indefinitely. A pull-down keeps
// an undriven line LOW and has no effect on a PIR that drives its output.
// ---------------------------------------------------------------------------------------------------------------------
constexpr uint32_t PIR_DEBOUNCE_MS  = 150;      // a level change must be stable this long (ignores spikes and glitches)
constexpr uint32_t PIR_HOLD_MS      = 5000;     // motion stays true this long after the output returns LOW
constexpr uint32_t PIR_WARMUP_MS    = 30000;    // PIR settling time after power-on: its output is ignored meanwhile
constexpr uint32_t PIR_STUCK_HIGH_MS = 600000;  // output HIGH this long without a break is reported on the console
// PIR LED (GPIO12) in Auto: on while motion is detected, and for exactly this long after it stops; new motion restarts
// the countdown. This is the LED only: the motion state sent to the backend keeps its own short PIR_HOLD_MS.
constexpr uint32_t PIR_LED_HOLD_MS      = 120000;  // 2 minutes
// A remote "on" for the PIR LED (e.g. Security Away "light on motion") lasts at most this long, then Auto takes over.
// Security re-sends "on" per motion report, so the LED stays on while motion continues and goes off when it stops.
constexpr uint32_t PIR_LED_REMOTE_ON_MS = 120000;
constexpr bool     PIR_DEBUG_DEFAULT = true;     // print a [PIR DEBUG] line every PIR_DEBUG_INTERVAL_MS (toggle: "pirdebug")
constexpr uint32_t PIR_DEBUG_INTERVAL_MS = 1000;
constexpr uint32_t PIR_RADIO_WINDOW_MS = 2000;   // a PIR trigger this soon after a Wi-Fi radio start is counted separately

// ---------------------------------------------------------------------------------------------------------------------
// Serial-console self-tests (USB only; never reachable over the network).
// ---------------------------------------------------------------------------------------------------------------------
constexpr uint32_t GASTEST_MS       = 60000;   // "gas": Wi-Fi off, real MQ-2 readings printed 4x a second
constexpr uint32_t PIRTEST_MS       = 600000;  // "pir": PIR pin changes and a summary every minute
constexpr uint32_t LEDTEST_HIGH_MS  = 5000;    // "ledtest": SIMULATED high reading for this long (LED must light) ...
constexpr uint32_t LEDTEST_LOW_MS   = 5000;    // ... then a SIMULATED low reading (LED must go off)
constexpr int      LEDTEST_HIGH_RAW = 2800;    // above GAS_WARN_RAW
constexpr int      LEDTEST_LOW_RAW  = 300;     // below GAS_CLEAR_RAW
static_assert(LEDTEST_LOW_RAW < GAS_CLEAR_RAW && GAS_CLEAR_RAW < GAS_WARN_RAW && GAS_WARN_RAW <= LEDTEST_HIGH_RAW,
              "Gas thresholds out of order: need LEDTEST_LOW_RAW < GAS_CLEAR_RAW < GAS_WARN_RAW <= LEDTEST_HIGH_RAW");

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
