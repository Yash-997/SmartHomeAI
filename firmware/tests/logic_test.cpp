// Host-side unit tests for the firmware's pure logic (Logic.h). No hardware or Arduino needed.
//   g++ -std=c++17 -Wall -Wextra -I../smarthome_esp32s3 logic_test.cpp -o logic_test && ./logic_test
#include <cstdio>
#include <cstring>
#include <string>

#include "Logic.h"

static int failures = 0, checks = 0;
#define CHECK(cond)                                                          \
  do {                                                                       \
    checks++;                                                                \
    if (!(cond)) { failures++; printf("FAIL line %d: %s\n", __LINE__, #cond); } \
  } while (0)
#define CHECK_STR(actual, expected)                                                                   \
  do {                                                                                                \
    checks++;                                                                                         \
    if (std::string(actual) != std::string(expected)) {                                               \
      failures++;                                                                                     \
      printf("FAIL line %d:\n  got:      %s\n  expected: %s\n", __LINE__, std::string(actual).c_str(), std::string(expected).c_str()); \
    }                                                                                                 \
  } while (0)

static std::string json(const TelemetryFields& f) {
  char buf[256];
  int n = buildTelemetryJson(buf, sizeof buf, f);
  return n < 0 ? "<overflow>" : std::string(buf, (size_t)n);
}

static void testPayload() {
  TelemetryFields f;
  f.seq = 42;
  f.hasTs = true; f.ts = 1792830977;
  f.hasTemp = true; f.temperatureC = 24.5f;
  f.hasHum = true; f.humidityPct = 55.2f;
  f.hasMotion = true; f.motion = true;
  f.hasGas = true; f.gasRaw = 1834;
  // The exact shape documented in the backend contract.
  CHECK_STR(json(f), "{\"seq\":42,\"ts\":1792830977,\"temperature\":24.5,\"humidity\":55.2,\"motion\":true,\"gasRaw\":1834}");

  // Missing readings are omitted, never zero/null.
  TelemetryFields g;
  g.seq = 7; g.hasMotion = true; g.motion = false;
  CHECK_STR(json(g), "{\"seq\":7,\"motion\":false}");

  // No clock -> no ts.
  TelemetryFields h = f; h.hasTs = false; h.hasTemp = false; h.hasHum = false; h.hasGas = false;
  CHECK_STR(json(h), "{\"seq\":42,\"motion\":true}");

  // Raw ADC bounds pass through untouched (backend accepts 0..4095).
  TelemetryFields z; z.seq = 1; z.hasGas = true; z.gasRaw = 0;
  CHECK_STR(json(z), "{\"seq\":1,\"gasRaw\":0}");
  z.gasRaw = 4095;
  CHECK_STR(json(z), "{\"seq\":1,\"gasRaw\":4095}");

  // seq after many reboots: boots * 1e6 + n must survive as a full 64-bit integer.
  TelemetryFields big; big.seq = 123ULL * 1000000ULL + 999999ULL; big.hasMotion = true; big.motion = true;
  CHECK_STR(json(big), "{\"seq\":123999999,\"motion\":true}");

  // Negative temperature keeps its sign.
  TelemetryFields neg; neg.seq = 1; neg.hasTemp = true; neg.temperatureC = -12.34f;
  CHECK_STR(json(neg), "{\"seq\":1,\"temperature\":-12.3}");

  // Output never contains a deviceId or ppm: identity is the topic, and gas is raw only.
  CHECK(json(f).find("deviceId") == std::string::npos);
  CHECK(json(f).find("ppm") == std::string::npos);

  // Too-small buffer is reported, not silently truncated.
  char tiny[10];
  CHECK(buildTelemetryJson(tiny, sizeof tiny, f) == -1);
  CHECK(buildTelemetryJson(tiny, 0, f) == -1);
}

static void testPairingCode() {
  char out[16];
  CHECK(normalizePairingCode("ABCD-2345", out)); CHECK_STR(out, "ABCD2345");
  CHECK(normalizePairingCode("abcd2345", out));  CHECK_STR(out, "ABCD2345");
  CHECK(normalizePairingCode("  abcd 2345 \r\n", out)); CHECK_STR(out, "ABCD2345");
  CHECK(!normalizePairingCode("ABCD-234", out));        // too short
  CHECK(!normalizePairingCode("ABCD-23456", out));      // too long
  CHECK(!normalizePairingCode("ABCD-23!5", out));       // bad character
  CHECK(!normalizePairingCode("", out));
  CHECK(!normalizePairingCode("\"};drop", out));        // can't smuggle JSON/quotes into the request body
}

static void testBackoff() {
  Backoff b(1000, 60000);
  uint32_t prevBase = 0;
  uint32_t bases[] = {1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000};
  for (uint32_t base : bases) {
    for (uint32_t rnd : {0u, 1u, 12345u, 0xFFFFFFFFu}) {
      Backoff probe = b;  // same state, different random draws
      uint32_t d = probe.nextDelay(rnd);
      CHECK(d >= base - base / 5 && d <= base + base / 5);  // +/-20 %
    }
    b.nextDelay(777);
    CHECK(base >= prevBase);  // never shrinks while failing
    prevBase = base;
  }
  CHECK(b.current() == 60000);  // capped
  b.reset();
  CHECK(b.current() == 1000);   // success resets

  // Large max with doubling must not overflow.
  Backoff big(2000, 120000);
  for (int i = 0; i < 40; i++) big.nextDelay(1);
  CHECK(big.current() == 120000);
}

static void testGasWarning() {
  GasWarning g(2000, 1800, 3);
  CHECK(!g.update(100));
  CHECK(!g.update(2500)); CHECK(!g.update(2500));   // 2 samples above: not yet
  CHECK(g.update(2500));                            // 3rd: warning on
  CHECK(g.update(1900)); CHECK(g.update(1900)); CHECK(g.update(1900)); CHECK(g.update(1900));  // between clear and warn: stays on (hysteresis)
  CHECK(g.update(1000)); CHECK(g.update(1000));     // 2 below clear: still on
  CHECK(!g.update(1000));                           // 3rd: off

  GasWarning noisy(2000, 1800, 3);
  for (int i = 0; i < 20; i++) CHECK(!noisy.update(i % 2 ? 3000 : 100));  // flapping never confirms

  GasWarning r(2000, 1800, 1);
  CHECK(r.update(4095)); r.reset(); CHECK(!r.active());  // warm-up reset
}

// The production settings (config.h): on at >= 600, off below 510, 2 consecutive samples. The LED follows the
// warning through gasLedOutput, which depends on nothing network-related.
static void testGasWarningWithProductionThresholds() {
  GasWarning g(600, 510, 2);
  CHECK(!g.update(150)); CHECK(!gasLedOutput(LedMode::Auto, g.active()));     // normal (clean air ~130-160): LED off
  CHECK(!g.update(599)); CHECK(!g.update(599));                               // just below: never on
  CHECK(!g.update(600)); CHECK(!gasLedOutput(LedMode::Auto, g.active()));    // one reading at 600: still off
  CHECK(g.update(640)); CHECK(gasLedOutput(LedMode::Auto, g.active()));       // second: LED on
  // No samples arrive (Wi-Fi/MQTT down, ADC busy): nothing calls update(), the warning and the LED stay on.
  CHECK(g.active()); CHECK(gasLedOutput(LedMode::Auto, g.active()));
  CHECK(g.update(550));                                                       // hysteresis band (510-599): still on
  CHECK(g.update(500));                                                       // 1st below 510: still on
  CHECK(!g.update(300)); CHECK(!gasLedOutput(LedMode::Auto, g.active()));     // 2nd: off
  // Remote modes can never hide an active warning ("off" is also refused earlier, by validateCommand).
  GasWarning h(600, 510, 1);
  h.update(3000);
  CHECK(gasLedOutput(LedMode::Off, h.active()));
  CHECK(gasLedOutput(LedMode::Auto, h.active()));
}

static void testBurstMedian() {
  int v[4] = {1000, 1100, 0, 0};
  bool ok[4] = {true, true, false, false};
  int out = -1, lo = -1, hi = -1;
  CHECK(medianValidReads(v, ok, 4, out, &lo, &hi)); CHECK(out == 1050); CHECK(lo == 1000); CHECK(hi == 1100);  // failed reads not counted
  bool few[4] = {true, false, false, false};
  CHECK(!medianValidReads(v, few, 4, out));                             // fewer than half succeeded: no sample
  bool none[4] = {false, false, false, false};
  CHECK(!medianValidReads(v, none, 4, out));                            // ADC busy the whole time: no sample, not 0
  int bad[2] = {5000, -1};
  bool all[2] = {true, true};
  CHECK(!medianValidReads(bad, all, 2, out));                           // out-of-range values rejected
  int zero[2] = {0, 0};
  CHECK(medianValidReads(zero, all, 2, out)); CHECK(out == 0);          // a real 0 is still reported as 0
  // Stray zeros from an unsettled ADC: the mean would be 908 (well below the true level), the median is not affected.
  int stray[16] = {0, 0, 0, 1460, 1452, 1449, 1471, 1455, 1462, 1458, 1450, 1466, 1453, 1459, 1461, 1457};
  bool ok16[16];
  for (bool& b : ok16) b = true;
  CHECK(medianValidReads(stray, ok16, 16, out)); CHECK(out >= 1449 && out <= 1466);
  // A real signal above the threshold stays above it.
  int high[5] = {2810, 2795, 2802, 2790, 2805};
  CHECK(medianValidReads(high, ok16, 5, out)); CHECK(out == 2802);
}

static void testLedTestSchedule() {
  int v = 0;
  CHECK(ledTestValue(0, 5000, 5000, 2800, 300, v) && v == 2800);
  CHECK(ledTestValue(4999, 5000, 5000, 2800, 300, v) && v == 2800);
  CHECK(ledTestValue(5000, 5000, 5000, 2800, 300, v) && v == 300);
  CHECK(!ledTestValue(10000, 5000, 5000, 2800, 300, v));
  // Driven through the production warning logic, sampled every 250 ms: LED on during the high phase, off by the end.
  GasWarning g(600, 510, 2);
  bool onDuringHigh = false, onAtEnd = true;
  for (uint32_t t = 0; ledTestValue(t, 5000, 5000, 2800, 300, v); t += 250) {
    g.update(v);
    if (t < 5000 && gasLedOutput(LedMode::Auto, g.active())) onDuringHigh = true;
    onAtEnd = gasLedOutput(LedMode::Auto, g.active());
  }
  CHECK(onDuringHigh);
  CHECK(!onAtEnd);
}

static void testGasPauseInterval() {
  CHECK(gasPauseInterval(150, true, false, 510, 15000, 8000) == 15000);
  CHECK(gasPauseInterval(530, true, false, 510, 15000, 8000) == 8000);    // near the threshold: sample sooner
  CHECK(gasPauseInterval(150, true, true, 510, 15000, 8000) == 8000);     // warning on: sample sooner to see recovery
  CHECK(gasPauseInterval(3000, false, false, 510, 15000, 8000) == 15000); // no valid sample yet
}

static void testMotionFilter() {
  MotionFilter m(150, 5000, 30000);
  m.start(0);
  // Warm-up: a HIGH output is ignored.
  for (uint32_t t = 0; t < 30000; t += 20) CHECK(!m.update(true, t));
  CHECK(m.warmingUp(29980)); CHECK(!m.warmingUp(30000));
  // After warm-up a sustained HIGH (already stable) is motion.
  CHECK(m.update(true, 30000));

  MotionFilter p(150, 5000, 0);
  p.start(0);
  CHECK(!p.update(false, 0));
  for (uint32_t t = 20; t <= 2000; t += 20) CHECK(!p.update(false, t));          // no motion
  // Glitches shorter than the debounce never count.
  for (int i = 0; i < 50; i++) {
    uint32_t t = 2000 + i * 200;
    CHECK(!p.update(true, t)); CHECK(!p.update(true, t + 100)); CHECK(!p.update(false, t + 120));
  }
  // Real motion: HIGH for 1 s.
  uint32_t t = 20000;
  CHECK(!p.update(true, t));
  CHECK(p.update(true, t + 160));                                                   // debounced: motion
  CHECK(p.update(true, t + 1000));
  CHECK(p.update(false, t + 1000)); CHECK(p.update(false, t + 1160));               // LOW: held
  CHECK(p.update(false, t + 1160 + 4900));                                          // still within hold
  CHECK(!p.update(false, t + 1160 + 5000));                                         // hold over: false again
  for (uint32_t u = t + 7000; u < t + 60000; u += 20) CHECK(!p.update(false, u));   // stays false
  // A one-sample dip during real motion does not flicker it off.
  CHECK(!p.update(true, 100000)); CHECK(p.update(true, 100200));
  CHECK(p.update(false, 100220)); CHECK(p.update(true, 100240)); CHECK(p.update(true, 101000));
  CHECK(p.highFor(101000) >= 800);
  // Stuck HIGH is visible through highFor().
  CHECK(p.highFor(101000 + 600000) >= 600000);
}

// PIR scenarios at the firmware's real settings (config.h): 20 ms polling, 150 ms debounce, 5 s hold, 30 s warm-up after
// power-on. "LED" is GPIO12 in Auto mode, i.e. pirLedOutput(Auto, motion).
namespace pir {
const uint32_t POLL = 20, DEBOUNCE = 150, HOLD = 5000, WARMUP = 30000;

/** Feeds `level` from `from` to `to` (exclusive); returns true if motion was ever reported, and the last state in `last`. */
static bool run(MotionFilter& m, bool level, uint32_t from, uint32_t to, bool& last) {
  bool any = false;
  for (uint32_t t = from; t < to; t += POLL) {
    last = m.update(level, t);
    any = any || last;
  }
  return any;
}
}  // namespace pir

static void testPirScenarios() {
  using namespace pir;
  bool last = false;

  {  // No motion for 5 minutes: never motion, LED never on.
    MotionFilter m(DEBOUNCE, HOLD, 0);
    m.start(0);
    CHECK(!run(m, false, 0, 300000, last));
    CHECK(!pirLedOutput(LedMode::Auto, last));
    CHECK(!m.stable()); CHECK(!m.candidate()); CHECK(m.highFor(300000) == 0);
  }

  {  // Motion detected, then stops: true within debounce, held 5 s after LOW, then false and LED off.
    MotionFilter m(DEBOUNCE, HOLD, 0);
    m.start(0);
    run(m, false, 0, 10000, last);
    CHECK(!m.update(true, 10000));                       // first HIGH sample: still debouncing
    CHECK(m.candidate() && !m.stable());
    CHECK(run(m, true, 10020, 13000, last) && last);     // motion
    CHECK(pirLedOutput(LedMode::Auto, last));            // LED on
    CHECK(run(m, false, 13000, 13000 + DEBOUNCE + HOLD - 100, last) && last);  // still held just before expiry
    run(m, false, 13000 + DEBOUNCE + HOLD - 100, 13000 + DEBOUNCE + HOLD + 200, last);
    CHECK(!last);                                        // hold expired
    CHECK(!pirLedOutput(LedMode::Auto, last));           // LED off
    CHECK(!run(m, false, 20000, 200000, last));          // and it stays off
  }

  {  // Brief HIGH glitches (40-140 ms) never trigger motion, however often they occur.
    MotionFilter m(DEBOUNCE, HOLD, 0);
    m.start(0);
    bool any = false;
    for (uint32_t t = 0, len = 40; t < 120000; t += 1000, len = len >= 140 ? 40 : len + 20) {
      any = run(m, true, t, t + len, last) || any;
      any = run(m, false, t + len, t + 1000, last) || any;
    }
    CHECK(!any);
  }

  {  // Continuously HIGH: motion stays true (a real signal is never hidden) and highFor() grows for the stuck warning.
    MotionFilter m(DEBOUNCE, HOLD, 0);
    m.start(0);
    CHECK(run(m, true, 0, 660000, last) && last);
    CHECK(m.stable());
    CHECK(m.highFor(660000 - POLL) >= 600000);           // >= PIR_STUCK_HIGH_MS: firmware prints the warning
  }

  {  // Start-up warm-up after power-on: a HIGH output is ignored for 30 s, then reported.
    MotionFilter m(DEBOUNCE, HOLD, WARMUP);
    m.start(0);
    CHECK(!run(m, true, 0, WARMUP, last));
    CHECK(m.warmingUp(WARMUP - POLL));
    CHECK(run(m, true, WARMUP, WARMUP + 100, last) && last);
    MotionFilter quiet(DEBOUNCE, HOLD, WARMUP);          // and a LOW output after warm-up is no motion
    quiet.start(0);
    CHECK(!run(quiet, false, 0, 120000, last));
  }

  {  // Repeated separate motion events: each one is reported, and motion returns to false between them.
    MotionFilter m(DEBOUNCE, HOLD, 0);
    m.start(0);
    int events = 0, falls = 0;
    bool prev = false;
    for (int i = 0; i < 5; i++) {
      const uint32_t t = 10000 + i * 30000;
      for (uint32_t u = t - 10000; u < t; u += POLL) { bool v = m.update(false, u); if (prev && !v) falls++; prev = v; }
      for (uint32_t u = t; u < t + 2000; u += POLL) { bool v = m.update(true, u); if (!prev && v) events++; prev = v; }
      for (uint32_t u = t + 2000; u < t + 20000; u += POLL) { bool v = m.update(false, u); if (prev && !v) falls++; prev = v; }
    }
    CHECK(events == 5);
    CHECK(falls == 5);
  }

  {  // GPIO12 follows the filtered motion in Auto; a remote "on" forces it regardless of motion.
    CHECK(pirLedOutput(LedMode::On, false));             // the Security Away "light on motion" case
    CHECK(!pirLedOutput(LedMode::Off, true));
    CHECK(pirLedOutput(LedMode::Auto, true));
    CHECK(!pirLedOutput(LedMode::Auto, false));
  }
}

// DHT22 frames are built from bytes the way the sensor sends them: per bit a ~50 us LOW, then 27 us (0) or 70 us (1).
namespace dht {
static void frame(const uint8_t b[5], int32_t lows[40], int32_t highs[40]) {
  for (int i = 0; i < 40; i++) {
    const bool one = (b[i / 8] >> (7 - i % 8)) & 1;
    lows[i] = 50 + (i % 3);            // a little jitter
    highs[i] = one ? 70 - (i % 4) : 26 + (i % 3);
  }
}
static void bytesFor(float hum, float temp, uint8_t b[5]) {
  const int h = (int)(hum * 10 + 0.5f);
  const int t = (int)((temp < 0 ? -temp : temp) * 10 + 0.5f);
  b[0] = (uint8_t)(h >> 8); b[1] = (uint8_t)h;
  b[2] = (uint8_t)((t >> 8) | (temp < 0 ? 0x80 : 0)); b[3] = (uint8_t)t;
  b[4] = (uint8_t)(b[0] + b[1] + b[2] + b[3]);
}
}  // namespace dht

static void testDht22Decode() {
  int32_t lows[40], highs[40];
  uint8_t b[5];
  float t = -99, h = -99;

  dht::bytesFor(60.6f, 30.4f, b);       // the values the board actually reported
  dht::frame(b, lows, highs);
  CHECK(dht22Decode(lows, highs, 40, t, h) == DhtResult::Ok);
  CHECK(t > 30.35f && t < 30.45f); CHECK(h > 60.55f && h < 60.65f);

  dht::bytesFor(45.0f, -12.3f, b);      // negative temperature (sign bit)
  dht::frame(b, lows, highs);
  CHECK(dht22Decode(lows, highs, 40, t, h) == DhtResult::Ok);
  CHECK(t < -12.25f && t > -12.35f);

  dht::bytesFor(60.6f, 30.4f, b);       // one flipped bit: checksum catches it, values untouched
  dht::frame(b, lows, highs);
  highs[13] = highs[13] > lows[13] ? 26 : 70;
  t = h = -99;
  CHECK(dht22Decode(lows, highs, 40, t, h) == DhtResult::Checksum);
  CHECK(t == -99 && h == -99);

  dht::frame(b, lows, highs);           // a pulse that never came (timeout) anywhere in the frame
  lows[39] = -1;
  CHECK(dht22Decode(lows, highs, 40, t, h) == DhtResult::Timeout);
  dht::frame(b, lows, highs);
  highs[0] = -1;
  CHECK(dht22Decode(lows, highs, 40, t, h) == DhtResult::Timeout);
  CHECK(dht22Decode(lows, highs, 39, t, h) == DhtResult::Timeout);   // short frame

  uint8_t zero[5] = {0, 0, 0, 0, 0};    // line stuck LOW: decodes to zeros with a "valid" checksum, still rejected
  dht::frame(zero, lows, highs);
  CHECK(dht22Decode(lows, highs, 40, t, h) == DhtResult::AllZero);

  uint8_t big[5] = {0x03, 0xE9, 0x00, 0xC8, 0};   // humidity 100.1 %: valid checksum but impossible
  big[4] = (uint8_t)(big[0] + big[1] + big[2] + big[3]);
  dht::frame(big, lows, highs);
  CHECK(dht22Decode(lows, highs, 40, t, h) == DhtResult::OutOfRange);

  // Worst case time with interrupts off: 3 start waits + 80 bit waits, each capped at 100 us: 8.3 ms << 300 ms watchdog.
}

int main() {
  testDht22Decode();
  testPirScenarios();
  testPayload();
  testPairingCode();
  testBackoff();
  testGasWarning();
  testGasWarningWithProductionThresholds();
  testBurstMedian();
  testLedTestSchedule();
  testGasPauseInterval();
  testMotionFilter();
  printf("%d checks, %d failures\n", checks, failures);
  return failures ? 1 : 0;
}
