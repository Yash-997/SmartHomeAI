// Host-side tests for remote-command handling: validation, LED safety, duplicate protection, reply formats and
// parsing of the backend's real wire format. No hardware needed.
//
//   g++ -std=c++17 -Wall -Wextra -I../smarthome_esp32s3 -I<path to ArduinoJson>/src command_test.cpp -o command_test
//   (ArduinoJson is header-only; the Arduino library folder has it at <libraries>/ArduinoJson/src.)
#include <cstdio>
#include <cstring>
#include <string>

#include "CommandParser.h"
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

static const char* ID = "3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b";
static const int64_t NOW = 1792836000;

static CommandFields cmd(const char* action, const char* value, int64_t expiresAt = NOW + 30, const char* id = ID) {
  CommandFields f;
  f.id = id; f.action = action; f.value = value; f.hasExpiry = true; f.expiresAt = expiresAt;
  return f;
}

static CmdVerdict verdict(const CommandFields& f, bool clock = true, int64_t now = NOW) {
  return validateCommand(f, clock, now, 600).verdict;
}

static void testLedSafety() {
  // THE safety property: with a gas warning active the gas LED is on in every mode, so nothing remote can hide it.
  for (LedMode m : {LedMode::Auto, LedMode::On, LedMode::Off}) CHECK(gasLedOutput(m, true));
  CHECK(!gasLedOutput(LedMode::Auto, false));
  CHECK(gasLedOutput(LedMode::On, false));      // forced on is allowed (identify the device)
  CHECK(!gasLedOutput(LedMode::Off, false));

  // PIR LED follows motion in auto, and can be forced either way (it is only an indicator)
  CHECK(pirLedOutput(LedMode::Auto, true));  CHECK(!pirLedOutput(LedMode::Auto, false));
  CHECK(pirLedOutput(LedMode::On, false));   CHECK(!pirLedOutput(LedMode::Off, true));

  // Local safety logic is independent of modes: GasWarning has no mode input at all, and still trips.
  GasWarning g(2000, 1800, 3);
  g.update(3000); g.update(3000);
  CHECK(g.update(3000));
  CHECK(gasLedOutput(LedMode::Off, g.active()));
}

static void testUuid() {
  CHECK(isUuid(ID));
  CHECK(isUuid("3F2B8C1E-5D4A-4E7B-9A10-0C6D7E8F9A2B"));
  CHECK(!isUuid(nullptr));
  CHECK(!isUuid(""));
  CHECK(!isUuid("3f2b8c1e5d4a4e7b9a100c6d7e8f9a2b"));            // no dashes
  CHECK(!isUuid("3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2"));         // too short
  CHECK(!isUuid("3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2bb"));       // too long
  CHECK(!isUuid("3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2g"));        // non-hex
  CHECK(!isUuid("3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2\";"));      // injection attempt
}

static void testValidation() {
  // Every allowed combination
  CHECK(verdict(cmd("set_pir_led", "auto")) == CmdVerdict::Ok);
  CHECK(verdict(cmd("set_pir_led", "on")) == CmdVerdict::Ok);
  CHECK(verdict(cmd("set_pir_led", "off")) == CmdVerdict::Ok);
  CHECK(verdict(cmd("set_gas_led", "auto")) == CmdVerdict::Ok);
  CHECK(verdict(cmd("set_gas_led", "on")) == CmdVerdict::Ok);

  CommandCheck c = validateCommand(cmd("set_gas_led", "on"), true, NOW, 600);
  CHECK(c.action == CmdAction::SetGasLed && c.mode == LedMode::On);

  // The gas LED can never be switched off remotely
  CHECK(verdict(cmd("set_gas_led", "off")) == CmdVerdict::ForbiddenValue);

  // Unsupported actions
  for (const char* a : {"reboot", "set_relay", "SET_PIR_LED", "set_pir_led ", "", "set_dht"}) CHECK(verdict(cmd(a, "on")) == CmdVerdict::BadAction);
  CHECK(verdict(cmd(nullptr, "on")) == CmdVerdict::BadAction);

  // Unsupported values
  for (const char* v : {"ON", "blink", "1", "", "true", "on;"}) CHECK(verdict(cmd("set_pir_led", v)) == CmdVerdict::BadValue);
  CHECK(verdict(cmd("set_pir_led", nullptr)) == CmdVerdict::BadValue);

  // Ids
  CHECK(verdict(cmd("set_pir_led", "on", NOW + 30, "not-a-uuid")) == CmdVerdict::BadId);
  CHECK(verdict(cmd("set_pir_led", "on", NOW + 30, nullptr)) == CmdVerdict::BadId);

  // Expiry
  CommandFields noExpiry = cmd("set_pir_led", "on"); noExpiry.hasExpiry = false;
  CHECK(verdict(noExpiry) == CmdVerdict::BadExpiry);
  CHECK(verdict(cmd("set_pir_led", "on", NOW - 1)) == CmdVerdict::Expired);
  CHECK(verdict(cmd("set_pir_led", "on", NOW)) == CmdVerdict::Ok);            // exactly at the deadline is still valid
  CHECK(verdict(cmd("set_pir_led", "on", NOW + 600)) == CmdVerdict::Ok);       // at the maximum lifetime
  CHECK(verdict(cmd("set_pir_led", "on", NOW + 601)) == CmdVerdict::ExpiryTooFar);
  CHECK(verdict(cmd("set_pir_led", "on", INT64_MAX)) == CmdVerdict::ExpiryTooFar);   // no overflow trick
  CHECK(verdict(cmd("set_pir_led", "on", 0)) == CmdVerdict::Expired);

  // Without a trustworthy clock nothing is applied, since expiry can't be judged
  CHECK(verdict(cmd("set_pir_led", "on"), false) == CmdVerdict::ClockNotSet);

  // Ack mapping the backend understands
  CHECK_STR(verdictStatus(CmdVerdict::Ok), "applied");
  CHECK_STR(verdictStatus(CmdVerdict::Expired), "expired");
  for (CmdVerdict v : {CmdVerdict::BadId, CmdVerdict::BadAction, CmdVerdict::BadValue, CmdVerdict::ForbiddenValue,
                       CmdVerdict::BadExpiry, CmdVerdict::ClockNotSet, CmdVerdict::ExpiryTooFar}) {
    CHECK_STR(verdictStatus(v), "rejected");
    CHECK(strlen(verdictDetail(v)) > 0);
  }
  // Detail text is embedded in JSON without escaping, so it must never contain quotes, backslashes or control chars.
  for (CmdVerdict v : {CmdVerdict::Ok, CmdVerdict::BadId, CmdVerdict::BadAction, CmdVerdict::BadValue, CmdVerdict::ForbiddenValue,
                       CmdVerdict::BadExpiry, CmdVerdict::ClockNotSet, CmdVerdict::Expired, CmdVerdict::ExpiryTooFar}) {
    for (const char* p = verdictDetail(v); *p; p++) CHECK(*p != '"' && *p != '\\' && (unsigned char)*p >= 0x20);
  }
}

static void testDuplicates() {
  RecentCommands r;
  CHECK(r.find(ID) == nullptr);
  r.remember(ID, "applied", "");
  const RecentCommands::Entry* e = r.find(ID);
  CHECK(e != nullptr && std::string(e->status) == "applied");

  // A rejected command is remembered too, so a duplicate gets the same rejection and never gets applied later
  r.remember("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "rejected", "unsupported action");
  e = r.find("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  CHECK(e != nullptr && std::string(e->detail) == "unsupported action");

  // Oldest entries are evicted once the ring is full; recent ones survive
  for (int i = 0; i < RecentCommands::CAPACITY; i++) {
    char id[37];
    snprintf(id, sizeof id, "%08x-0000-0000-0000-000000000000", i + 1);
    r.remember(id, "applied", "");
  }
  CHECK(r.find(ID) == nullptr);
  char newest[37];
  snprintf(newest, sizeof newest, "%08x-0000-0000-0000-000000000000", RecentCommands::CAPACITY);
  CHECK(r.find(newest) != nullptr);
}

static void testReplyFormats() {
  char buf[256];
  // ack
  CHECK(buildAckJson(buf, sizeof buf, ID, "applied", "") > 0);
  CHECK_STR(buf, "{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"status\":\"applied\"}");
  CHECK(buildAckJson(buf, sizeof buf, ID, "rejected", "unsupported action") > 0);
  CHECK_STR(buf, "{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"status\":\"rejected\",\"detail\":\"unsupported action\"}");
  char tiny[20];
  CHECK(buildAckJson(tiny, sizeof tiny, ID, "applied", "") == -1);

  // state
  CHECK(buildStateJson(buf, sizeof buf, LedMode::Auto, false, LedMode::Auto, false, false, nullptr) > 0);
  CHECK_STR(buf, "{\"pirLed\":{\"mode\":\"auto\",\"on\":false},\"gasLed\":{\"mode\":\"auto\",\"on\":false},\"gasWarning\":false}");
  CHECK(buildStateJson(buf, sizeof buf, LedMode::On, true, LedMode::Auto, true, true, ID) > 0);
  CHECK_STR(buf, "{\"pirLed\":{\"mode\":\"on\",\"on\":true},\"gasLed\":{\"mode\":\"auto\",\"on\":true},\"gasWarning\":true,"
                 "\"lastCommandId\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\"}");
  CHECK(buildStateJson(buf, sizeof buf, LedMode::Off, false, LedMode::On, true, false, "") > 0);
  CHECK_STR(buf, "{\"pirLed\":{\"mode\":\"off\",\"on\":false},\"gasLed\":{\"mode\":\"on\",\"on\":true},\"gasWarning\":false}");
  CHECK(buildStateJson(tiny, sizeof tiny, LedMode::Auto, false, LedMode::Auto, false, false, ID) == -1);
}

static CmdVerdict parseAndValidate(const char* json, int64_t now = NOW, bool clock = true) {
  ParsedCommand p;
  if (!parseCommandJson((const uint8_t*)json, strlen(json), p)) return CmdVerdict::BadId;   // unparseable: nothing to answer
  return validateCommand(p.fields, clock, now, 600).verdict;
}

static void testParsingTheBackendWireFormat() {
  // Exactly what CommandService.wirePayload() produces: id, action, value, expiresAt (epoch seconds, integer).
  const char* real = "{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":\"set_pir_led\",\"value\":\"on\",\"expiresAt\":1792836030}";
  ParsedCommand p;
  CHECK(parseCommandJson((const uint8_t*)real, strlen(real), p));
  CHECK_STR(p.fields.id, ID);
  CHECK_STR(p.fields.action, "set_pir_led");
  CHECK_STR(p.fields.value, "on");
  CHECK(p.fields.hasExpiry && p.fields.expiresAt == 1792836030);
  CHECK(parseAndValidate(real) == CmdVerdict::Ok);
  CHECK(parseAndValidate(real, 1792836031) == CmdVerdict::Expired);       // one second late

  // Unknown extra fields are ignored; they can't smuggle anything in
  CHECK(parseAndValidate("{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":\"set_pir_led\",\"value\":\"on\",\"expiresAt\":1792836030,\"ownerUserId\":\"x\",\"force\":true}") == CmdVerdict::Ok);

  // Wrong types must fail validation rather than being coerced
  CHECK(parseAndValidate("{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":\"set_pir_led\",\"value\":\"on\",\"expiresAt\":\"1792836030\"}") == CmdVerdict::BadExpiry);
  CHECK(parseAndValidate("{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":\"set_pir_led\",\"value\":\"on\",\"expiresAt\":1792836030.5}") == CmdVerdict::BadExpiry);
  CHECK(parseAndValidate("{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":\"set_pir_led\",\"value\":\"on\"}") == CmdVerdict::BadExpiry);
  CHECK(parseAndValidate("{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":5,\"value\":\"on\",\"expiresAt\":1792836030}") == CmdVerdict::BadAction);
  CHECK(parseAndValidate("{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":\"set_pir_led\",\"value\":true,\"expiresAt\":1792836030}") == CmdVerdict::BadValue);
  CHECK(parseAndValidate("{\"id\":12345,\"action\":\"set_pir_led\",\"value\":\"on\",\"expiresAt\":1792836030}") == CmdVerdict::BadId);

  // Gas LED off is refused even though the JSON is otherwise perfect
  CHECK(parseAndValidate("{\"id\":\"3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b\",\"action\":\"set_gas_led\",\"value\":\"off\",\"expiresAt\":1792836030}") == CmdVerdict::ForbiddenValue);

  // Over-long strings are rejected, not truncated into something valid
  std::string longAction = std::string("{\"id\":\"") + ID + "\",\"action\":\"" + std::string(200, 'a') + "\",\"value\":\"on\",\"expiresAt\":1792836030}";
  CHECK(parseAndValidate(longAction.c_str()) == CmdVerdict::BadAction);
  std::string longValue = std::string("{\"id\":\"") + ID + "\",\"action\":\"set_pir_led\",\"value\":\"onnnnnnnnnnnnnnnnnnn\",\"expiresAt\":1792836030}";
  CHECK(parseAndValidate(longValue.c_str()) == CmdVerdict::BadValue);

  // Not JSON / not an object -> nothing to answer
  for (const char* junk : {"", "garbage", "[1,2]", "\"str\"", "42", "{\"id\":", "null"}) {
    ParsedCommand q;
    CHECK(!parseCommandJson((const uint8_t*)junk, strlen(junk), q));
  }
}

int main() {
  testLedSafety();
  testUuid();
  testValidation();
  testDuplicates();
  testReplyFormats();
  testParsingTheBackendWireFormat();
  printf("%d checks, %d failures\n", checks, failures);
  return failures ? 1 : 0;
}
