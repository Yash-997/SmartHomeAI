#include "Sensors.h"

#include <driver/gpio.h>
#include <esp_adc/adc_oneshot.h>
#include <esp_system.h>
#include <soc/gpio_reg.h>

#include "Logic.h"
#include "config.h"

static GasWarning gasWarning(GAS_WARN_RAW, GAS_CLEAR_RAW, GAS_CONFIRM_SAMPLES);
static MotionFilter motion(PIR_DEBOUNCE_MS, PIR_HOLD_MS, PIR_WARMUP_MS);

// Shared with the network task; guarded by `lock`.
static portMUX_TYPE lock = portMUX_INITIALIZER_UNLOCKED;
static Snapshot shared;
static LedMode pirMode = LedMode::Auto, gasMode = LedMode::Auto;   // remote overrides (also under `lock`)
static uint32_t pirModeSince = 0, gasModeSince = 0;
static volatile bool gasSampleRequested = false;
static volatile bool ledTestRequested = false;

// Private to sensorsUpdate().
static uint32_t lastPirMs = 0, lastDhtMs = 0, lastMq2Ms = 0;
static uint32_t lastGoodTempMs = 0, lastGoodHumMs = 0;
static bool haveTemp = false, haveHum = false;
static float tempC = 0, humPct = 0;
static int mq2Ring[MQ2_AVG_SAMPLES];
static int mq2Count = 0, mq2Next = 0;
static int gasValue = 0;               // the value the warning logic last saw (published as gasRaw)
static bool haveGasSample = false;
static bool haveValidGas = false;      // a post-warm-up sample exists
static uint32_t lastValidGasMs = 0;
static uint32_t gasSeq = 0;
static bool warningLatched = false;
static uint32_t mq2NonZeroMs = 0;      // last time the MQ-2 reading was above 0
static bool motionNow = false;
static bool pirLastRaw = false;
static uint32_t pirRises = 0, pirHighMs = 0;
static uint32_t pirRawHighSince = 0, pirLastRiseMs = 0;
static bool ledTestOn = false;
static uint32_t ledTestStart = 0;
static int burstMin = 0, burstMax = 0;
static uint32_t dhtOk = 0, dhtFail = 0;
static DhtResult dhtLast = DhtResult::Timeout;

// ---------------------------------------------------------------------------------------------------------------------
// DHT22 on GPIO14. Replaces the Adafruit DHT library, whose pulse timeout is a loop count (240,000 digitalRead() calls,
// ~0.3 s on this core) run with interrupts off: one missed pulse tripped the 300 ms interrupt watchdog and rebooted the
// board. Here every wait is bounded in real microseconds, so the whole frame takes ~5 ms (8.3 ms worst case) and a
// failed read just means "no temperature". The pin is open-drain with the pull-up on, so it never switches direction.
// ---------------------------------------------------------------------------------------------------------------------
static portMUX_TYPE dhtMux = portMUX_INITIALIZER_UNLOCKED;

static void dhtBegin() {
  gpio_config_t io = {};
  io.pin_bit_mask = 1ULL << PIN_DHT22;
  io.mode = GPIO_MODE_INPUT_OUTPUT_OD;
  io.pull_up_en = GPIO_PULLUP_ENABLE;
  io.pull_down_en = GPIO_PULLDOWN_DISABLE;
  io.intr_type = GPIO_INTR_DISABLE;
  gpio_config(&io);
  gpio_set_level((gpio_num_t)PIN_DHT22, 1);   // released: idle HIGH
}

/** How long the line stays at `level`, in us; -1 if it is still there after timeoutUs. */
static int32_t dhtWhile(int level, uint32_t timeoutUs) {
  const uint32_t t0 = micros();
  while (gpio_get_level((gpio_num_t)PIN_DHT22) == level) {
    if (micros() - t0 > timeoutUs) return -1;
  }
  return (int32_t)(micros() - t0);
}

static DhtResult dhtRead(float& t, float& h) {
  int32_t lows[40], highs[40];
  gpio_set_level((gpio_num_t)PIN_DHT22, 0);   // start signal: >= 1 ms LOW (interrupts stay on meanwhile)
  delay(DHT_START_LOW_MS);
  bool ok;
  portENTER_CRITICAL(&dhtMux);                // only the frame itself (~5 ms) runs with interrupts off
  gpio_set_level((gpio_num_t)PIN_DHT22, 1);   // release; the sensor answers after 20-40 us
  ok = dhtWhile(1, DHT_PULSE_TIMEOUT_US) >= 0 &&   // our release -> sensor pulls LOW
       dhtWhile(0, DHT_PULSE_TIMEOUT_US) >= 0 &&   // ~80 us LOW
       dhtWhile(1, DHT_PULSE_TIMEOUT_US) >= 0;     // ~80 us HIGH
  for (int i = 0; i < 40; i++) {
    lows[i] = ok ? dhtWhile(0, DHT_PULSE_TIMEOUT_US) : -1;
    highs[i] = ok ? dhtWhile(1, DHT_PULSE_TIMEOUT_US) : -1;
    if (lows[i] < 0 || highs[i] < 0) ok = false;
  }
  portEXIT_CRITICAL(&dhtMux);
  return dht22Decode(lows, highs, 40, t, h);
}

// MQ-2 ADC, through the IDF one-shot driver rather than analogRead(): a conversion that fails (ADC2 is unavailable
// while Wi-Fi runs) is reported as a failure, where analogRead() would silently return 0.
static adc_oneshot_unit_handle_t mq2Adc = nullptr;
static adc_channel_t mq2Channel;
static bool mq2AdcReady = false;

static void mq2AdcBegin() {
  adc_unit_t unit;
  if (adc_oneshot_io_to_channel(PIN_MQ2, &unit, &mq2Channel) != ESP_OK) return;
  adc_oneshot_unit_init_cfg_t unitCfg = {};
  unitCfg.unit_id = unit;
  unitCfg.ulp_mode = ADC_ULP_MODE_DISABLE;
  if (adc_oneshot_new_unit(&unitCfg, &mq2Adc) != ESP_OK) return;
  adc_oneshot_chan_cfg_t chanCfg = {};
  chanCfg.atten = ADC_ATTEN_DB_12;      // ~0-3.1 V input range
  chanCfg.bitwidth = ADC_BITWIDTH_12;   // 0-4095
  mq2AdcReady = adc_oneshot_config_channel(mq2Adc, mq2Channel, &chanCfg) == ESP_OK;
}

/** One sample = the median of a burst of conversions. False when the ADC could not be read (e.g. radio running).
 *  The first MQ2_DISCARD_READS conversions are thrown away: right after the radio stops, ADC2 needs a moment. */
static bool mq2Sample(int& out) {
  if (!mq2AdcReady) return false;
  int values[MQ2_BURST_READS];
  bool ok[MQ2_BURST_READS];
  int dummy;
  for (int i = 0; i < MQ2_DISCARD_READS; i++) adc_oneshot_read(mq2Adc, mq2Channel, &dummy);
  for (int i = 0; i < MQ2_BURST_READS; i++) ok[i] = adc_oneshot_read(mq2Adc, mq2Channel, &values[i]) == ESP_OK;
  return medianValidReads(values, ok, MQ2_BURST_READS, out, &burstMin, &burstMax);
}

void sensorsBegin() {
  pinMode(PIN_PIR, INPUT_PULLDOWN);   // an undriven PIR line reads LOW (no motion) instead of floating HIGH
  pinMode(PIN_PIR_LED, OUTPUT);
  pinMode(PIN_MQ2_LED, OUTPUT);
  digitalWrite(PIN_PIR_LED, LOW);
  digitalWrite(PIN_MQ2_LED, LOW);

  mq2AdcBegin();
  if (!mq2AdcReady) Serial.printf("[gas] ADC setup for GPIO%u failed; no gas readings will be available\n", (unsigned)PIN_MQ2);

  // The PIR only needs to settle when its power was (re)applied, which is what a power-on or brownout reset means.
  const esp_reset_reason_t why = esp_reset_reason();
  const bool pirRepowered = why == ESP_RST_POWERON || why == ESP_RST_BROWNOUT;
  motion = MotionFilter(PIR_DEBOUNCE_MS, PIR_HOLD_MS, pirRepowered ? PIR_WARMUP_MS : 0);
  motion.start(millis());

  dhtBegin();
}

void sensorsRequestGasSample() { gasSampleRequested = true; }

void sensorsStartLedTest() { ledTestRequested = true; }

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

  // --- PIR: polled fast; debounced, held and ignored while it settles (MotionFilter in Logic.h) ---
  if (now - lastPirMs >= PIR_POLL_MS) {
    lastPirMs = now;
    const bool raw = digitalRead(PIN_PIR) == HIGH;
    if (raw && !pirLastRaw) { pirRises++; pirLastRiseMs = now; pirRawHighSince = now; }
    if (raw && pirLastRaw) pirHighMs += PIR_POLL_MS;
    pirLastRaw = raw;
    motionNow = motion.update(raw, now);
    portENTER_CRITICAL(&lock);
    shared.motionNow = motionNow;
    if (motionNow) shared.motionSinceLast = true;
    shared.pirRaw = raw;
    shared.pirWarmingUp = motion.warmingUp(now);
    shared.pirStuckHigh = motion.highFor(now) >= PIR_STUCK_HIGH_MS;
    shared.pirRises = pirRises;
    shared.pirHighMs = pirHighMs;
    shared.pirRawHighForMs = raw ? now - pirRawHighSince : 0;
    shared.pirLastRiseMs = pirLastRiseMs;
    shared.pirStable = motion.stable();
    shared.pirCandidate = motion.candidate();
    portEXIT_CRITICAL(&lock);
  }

  // --- DHT22: slow sensor, read on its own schedule; a failed read keeps the old value only until it expires ---
  if (now - lastDhtMs >= DHT_INTERVAL_MS) {
    lastDhtMs = now;
    float t, h;
    dhtLast = dhtRead(t, h);
    if (dhtLast == DhtResult::Ok) {
      tempC = t; haveTemp = true; lastGoodTempMs = now;
      humPct = h; haveHum = true; lastGoodHumMs = now;
      dhtOk++;
    } else {
      dhtFail++;
    }
  }

  // --- MQ-2: the warning has hysteresis and is disabled during warm-up ---
  // NOTE: this block is the local safety logic. It never reads the remote override modes. A sample is only taken when
  // the ADC can actually be read; on an ADC2 board that is while the radio is off. Missing samples never clear an active
  // warning: it only ends on real readings below GAS_CLEAR_RAW.
  if (ledTestRequested) {
    ledTestRequested = false;
    ledTestOn = true;
    ledTestStart = now;
    gasWarning.reset();
    lastMq2Ms = now - MQ2_SAMPLE_MS;   // first simulated sample right away
  }
  int simRaw = 0;
  if (ledTestOn && !ledTestValue(now - ledTestStart, LEDTEST_HIGH_MS, LEDTEST_LOW_MS, LEDTEST_HIGH_RAW, LEDTEST_LOW_RAW, simRaw)) {
    ledTestOn = false;
    gasWarning.reset();                // back to real readings, starting from "no warning"
    warningLatched = false;
  }
  if (ledTestOn) {
    // SIMULATED: only the warning logic sees these values. They never become gasValue, so they are never published.
    if (now - lastMq2Ms >= MQ2_SAMPLE_MS) {
      lastMq2Ms = now;
      gasWarning.update(simRaw);
      warningLatched = gasWarning.active();
    }
  } else if (gasSampleRequested || now - lastMq2Ms >= MQ2_SAMPLE_MS) {
    lastMq2Ms = now;
    gasSampleRequested = false;   // one immediate attempt; after that the regular period retries
    int sample;
    if (mq2Sample(sample)) {
      haveGasSample = true;
      if (MQ2_ON_ADC2) {
        gasValue = sample;   // samples are seconds apart: the burst average is the reading
      } else {
        mq2Ring[mq2Next] = sample;
        mq2Next = (mq2Next + 1) % MQ2_AVG_SAMPLES;
        if (mq2Count < MQ2_AVG_SAMPLES) mq2Count++;
        long sum = 0;
        for (int i = 0; i < mq2Count; i++) sum += mq2Ring[i];
        gasValue = (int)(sum / mq2Count);
      }
      const bool warmingUp = now < MQ2_WARMUP_MS || (!MQ2_ON_ADC2 && mq2Count < MQ2_AVG_SAMPLES);
      if (warmingUp) {
        gasWarning.reset();
      } else {
        gasWarning.update(gasValue);
        haveValidGas = true;
        lastValidGasMs = now;
      }
      warningLatched = gasWarning.active();
      if (gasValue > 0 || warmingUp) mq2NonZeroMs = now;
      gasSeq++;
    }
  }
  const bool gasFresh = haveValidGas && now - lastValidGasMs <= MQ2_SAMPLE_MAX_AGE_MS;
  portENTER_CRITICAL(&lock);
  shared.gasRaw = gasValue;
  shared.gasValid = gasFresh && !ledTestOn;
  shared.gasBurstMin = burstMin;
  shared.gasBurstMax = burstMax;
  shared.gasLedTest = ledTestOn;
  shared.gasSimRaw = simRaw;
  shared.gasHaveSample = haveGasSample;
  shared.gasWarmingUp = now < MQ2_WARMUP_MS || !haveValidGas;
  shared.gasWarning = warningLatched;
  shared.gasStuckZero = haveValidGas && now - mq2NonZeroMs >= MQ2_STUCK_ZERO_MS;
  shared.gasSampleSeq = gasSeq;
  portEXIT_CRITICAL(&lock);

  // --- LEDs: driven every pass from the pure functions in Logic.h. The gas LED is always on while a gas warning is
  //     active, whatever the remote mode (see gasLedOutput). ---
  const bool pirLed = pirLedOutput(pirM, motionNow);
  const bool gasLed = gasLedOutput(gasM, warningLatched);
  digitalWrite(PIN_PIR_LED, pirLed ? HIGH : LOW);
  digitalWrite(PIN_MQ2_LED, gasLed ? HIGH : LOW);
  static_assert(PIN_PIR_LED < 32, "GPIO12 read-back below uses the GPIO0-31 output register");
  const bool pirLedPin = (REG_READ(GPIO_OUT_REG) >> PIN_PIR_LED) & 1;   // what the pin is really driven to

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
  shared.pirLedPinHigh = pirLedPin;
  shared.dhtOk = dhtOk;
  shared.dhtFail = dhtFail;
  shared.dhtLast = dhtLast;
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
