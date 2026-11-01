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

int main() {
  testPayload();
  testPairingCode();
  testBackoff();
  testGasWarning();
  printf("%d checks, %d failures\n", checks, failures);
  return failures ? 1 : 0;
}
