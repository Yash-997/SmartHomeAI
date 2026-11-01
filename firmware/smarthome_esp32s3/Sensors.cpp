#include "Sensors.h"

#include <DHT.h>

#include "Logic.h"
#include "config.h"

static DHT dht(PIN_DHT22, DHT22);
static GasWarning gasWarning(GAS_WARN_RAW, GAS_CLEAR_RAW, GAS_CONFIRM_SAMPLES);

// Shared with the network task; guarded by `lock`.
static portMUX_TYPE lock = portMUX_INITIALIZER_UNLOCKED;
static Snapshot shared;
static LedMode pirMode = LedMode::Auto, gasMode = LedMode::Auto;   // remote overrides (also under `lock`)
static uint32_t pirModeSince = 0, gasModeSince = 0;

// Private to sensorsUpdate().
static uint32_t lastPirMs = 0, lastDhtMs = 0, lastMq2Ms = 0;
static uint32_t lastGoodTempMs = 0, lastGoodHumMs = 0;
static bool haveTemp = false, haveHum = false;
static float tempC = 0, humPct = 0;
static int mq2Ring[MQ2_AVG_SAMPLES];
static int mq2Count = 0, mq2Next = 0;
static bool warningLatched = false;
static bool motionNow = false;

void sensorsBegin() {
  pinMode(PIN_PIR, INPUT);
  pinMode(PIN_PIR_LED, OUTPUT);
  pinMode(PIN_MQ2_LED, OUTPUT);
  digitalWrite(PIN_PIR_LED, LOW);
  digitalWrite(PIN_MQ2_LED, LOW);

  analogReadResolution(12);                    // 0-4095
  analogSetPinAttenuation(PIN_MQ2, ADC_11db);  // ~0-3.1 V input range
  pinMode(PIN_MQ2, INPUT);

  dht.begin();
}

void sensorsSetPirLedMode(LedMode mode) {
  portENTER_CRITICAL(&lock);
  pirMode = mode;
  pirModeSince = millis();
  portEXIT_CRITICAL(&lock);
}

void sensorsSetGasLedMode(LedMode mode) {
  if (mode == LedMode::Off) mode = LedMode::Auto;  // defence in depth: the command layer already refuses "off"
  portENTER_CRITICAL(&lock);
  gasMode = mode;
  gasModeSince = millis();
  portEXIT_CRITICAL(&lock);
}

void sensorsUpdate() {
  const uint32_t now = millis();

  // --- Remote overrides quietly expire back to automatic ---
  LedMode pirM, gasM;
  portENTER_CRITICAL(&lock);
  if (pirMode != LedMode::Auto && now - pirModeSince > OVERRIDE_MAX_MS) pirMode = LedMode::Auto;
  if (gasMode != LedMode::Auto && now - gasModeSince > OVERRIDE_MAX_MS) gasMode = LedMode::Auto;
  pirM = pirMode;
  gasM = gasMode;
  portEXIT_CRITICAL(&lock);

  // --- PIR: polled fast so its LED follows motion immediately ---
  if (now - lastPirMs >= PIR_POLL_MS) {
    lastPirMs = now;
    motionNow = digitalRead(PIN_PIR) == HIGH;
    portENTER_CRITICAL(&lock);
    shared.motionNow = motionNow;
    if (motionNow) shared.motionSinceLast = true;
    portEXIT_CRITICAL(&lock);
  }

  // --- DHT22: slow sensor, read on its own schedule; a failed read keeps the old value only until it expires ---
  if (now - lastDhtMs >= DHT_INTERVAL_MS) {
    lastDhtMs = now;
    float t = dht.readTemperature();
    float h = dht.readHumidity();
    if (!isnan(t)) { tempC = t; haveTemp = true; lastGoodTempMs = now; }
    if (!isnan(h)) { humPct = h; haveHum = true; lastGoodHumMs = now; }
  }

  // --- MQ-2: average raw counts; the warning has hysteresis and is disabled during warm-up ---
  // NOTE: this block is the local safety logic. It never reads the remote override modes.
  if (now - lastMq2Ms >= MQ2_SAMPLE_MS) {
    lastMq2Ms = now;
    mq2Ring[mq2Next] = analogRead(PIN_MQ2);
    mq2Next = (mq2Next + 1) % MQ2_AVG_SAMPLES;
    if (mq2Count < MQ2_AVG_SAMPLES) mq2Count++;

    long sum = 0;
    for (int i = 0; i < mq2Count; i++) sum += mq2Ring[i];
    int avg = (int)(sum / mq2Count);

    bool warmingUp = now < MQ2_WARMUP_MS || mq2Count < MQ2_AVG_SAMPLES;
    if (warmingUp) {
      gasWarning.reset();
    } else {
      gasWarning.update(avg);
    }
    warningLatched = gasWarning.active();

    portENTER_CRITICAL(&lock);
    shared.gasRaw = avg;
    shared.gasValid = !warmingUp;
    shared.gasWarmingUp = warmingUp;
    shared.gasWarning = warningLatched;
    portEXIT_CRITICAL(&lock);
  }

  // --- LEDs: driven every pass from the pure functions in Logic.h. The gas LED is always on while a gas warning is
  //     active, whatever the remote mode (see gasLedOutput). ---
  const bool pirLed = pirLedOutput(pirM, motionNow);
  const bool gasLed = gasLedOutput(gasM, warningLatched);
  digitalWrite(PIN_PIR_LED, pirLed ? HIGH : LOW);
  digitalWrite(PIN_MQ2_LED, gasLed ? HIGH : LOW);

  // --- Publish validity of the DHT values (expired values become "no reading") and the output state ---
  const bool tOk = haveTemp && (now - lastGoodTempMs) <= DHT_VALID_MS;
  const bool hOk = haveHum && (now - lastGoodHumMs) <= DHT_VALID_MS;
  portENTER_CRITICAL(&lock);
  shared.tempValid = tOk;
  shared.temperatureC = tempC;
  shared.humValid = hOk;
  shared.humidityPct = humPct;
  shared.pirMode = pirM;
  shared.pirLedOn = pirLed;
  shared.gasMode = gasM;
  shared.gasLedOn = gasLed;
  portEXIT_CRITICAL(&lock);
}

Snapshot sensorsSnapshot() {
  Snapshot copy;
  portENTER_CRITICAL(&lock);
  copy = shared;
  portEXIT_CRITICAL(&lock);
  return copy;
}

void sensorsClearMotionLatch() {
  portENTER_CRITICAL(&lock);
  shared.motionSinceLast = shared.motionNow;
  portEXIT_CRITICAL(&lock);
}
