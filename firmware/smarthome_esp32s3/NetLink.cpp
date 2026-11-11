#include "NetLink.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <PubSubClient.h>
#include <WiFi.h>
#include <esp_mac.h>
#include <esp_random.h>
#include <time.h>

#include "CommandParser.h"
#include "Logic.h"
#include "Sensors.h"
#include "config.h"
#include "secrets.h"  // copy secrets.example.h to secrets.h

// ---------------------------------------------------------------------------------------------------------------------
// Identity and stored credential
//   deviceId   = "esp32-" + Wi-Fi MAC. It names the device; it is NOT proof of anything. Ownership comes only from the
//                one-time pairing code the owner generates in the web app.
//   credential = issued by POST /api/device/pair, kept in flash (NVS), used as the MQTT password. Never printed.
// ---------------------------------------------------------------------------------------------------------------------
static char deviceId[24];
static char statusTopic[80];
static char telemetryTopic[80];
static char commandsTopic[80];  // we SUBSCRIBE to this one (and only this one)
static char acksTopic[80];
static char stateTopic[80];

static Preferences prefs;
static String credential;      // empty = not paired
static uint64_t bootCount = 0;  // persisted; makes seq increase across reboots

static WiFiClient wifiClient;
static PubSubClient mqtt(wifiClient);

static Backoff wifiBackoff(WIFI_BACKOFF_MIN_MS, WIFI_BACKOFF_MAX_MS);
static Backoff mqttBackoff(MQTT_BACKOFF_MIN_MS, MQTT_BACKOFF_MAX_MS);

static uint64_t seqCounter = 0;
static int authFailures = 0;
static bool ntpStarted = false;
static char lastCommandId[37] = "";  // last remote command this device actually applied (reported in its state)

static const char* const NVS_NAMESPACE = "smarthome";

static void note(const char* msg) { Serial.printf("[net] %s\n", msg); }

// ---------------------------------------------------------------------------------------------------------------------
// Wi-Fi: non-blocking state machine with exponential back-off
// ---------------------------------------------------------------------------------------------------------------------
enum class WifiState { Idle, Connecting, Connected, Waiting };
static WifiState wifiState = WifiState::Idle;
static uint32_t wifiStateSince = 0, wifiWaitMs = 0;

static uint32_t lastRadioStartMs = 0;   // millis() of the last Wi-Fi (re)connect attempt (for the PIR diagnostics)

static void maintainWifi() {
  const uint32_t now = millis();
  switch (wifiState) {
    case WifiState::Idle:
      Serial.printf("[net] Wi-Fi: connecting to \"%s\"\n", WIFI_SSID);
      WiFi.mode(WIFI_STA);
      WiFi.setAutoReconnect(false);  // we do our own back-off
      WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
      lastRadioStartMs = millis();
      wifiState = WifiState::Connecting;
      wifiStateSince = now;
      break;

    case WifiState::Connecting:
      if (WiFi.status() == WL_CONNECTED) {
        wifiState = WifiState::Connected;
        wifiBackoff.reset();
        Serial.printf("[net] Wi-Fi connected, IP %s\n", WiFi.localIP().toString().c_str());
      } else if (now - wifiStateSince > WIFI_CONNECT_TIMEOUT_MS) {
        WiFi.disconnect(true);
        wifiWaitMs = wifiBackoff.nextDelay(esp_random());
        Serial.printf("[net] Wi-Fi connect failed; retrying in %lu ms (sensors keep running locally)\n", (unsigned long)wifiWaitMs);
        wifiState = WifiState::Waiting;
        wifiStateSince = now;
      }
      break;

    case WifiState::Connected:
      if (WiFi.status() != WL_CONNECTED) {
        mqtt.disconnect();
        wifiWaitMs = wifiBackoff.nextDelay(esp_random());
        Serial.printf("[net] Wi-Fi lost; retrying in %lu ms\n", (unsigned long)wifiWaitMs);
        wifiState = WifiState::Waiting;
        wifiStateSince = now;
      }
      break;

    case WifiState::Waiting:
      if (now - wifiStateSince >= wifiWaitMs) wifiState = WifiState::Idle;
      break;
  }
}

static bool wifiUp() { return wifiState == WifiState::Connected; }

// ---------------------------------------------------------------------------------------------------------------------
// Time (NTP). Telemetry carries "ts" only once the clock is genuinely set; otherwise the field is omitted and the
// backend uses its own receive time.
// ---------------------------------------------------------------------------------------------------------------------
static bool timeIsSet() { return time(nullptr) > 1700000000L; }  // after Nov 2023, same floor the backend enforces

static void maintainTime() {
  if (wifiUp() && !ntpStarted) {
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    ntpStarted = true;
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Pairing: POST /api/device/pair {"pairingCode","deviceId"} -> {"credential"}  (same protocol as the web app expects)
// ---------------------------------------------------------------------------------------------------------------------
static bool pairWithCode(const char* code8) {
  if (!wifiUp()) {
    note("Cannot pair yet: Wi-Fi is not connected.");
    return false;
  }
  char body[160];
  snprintf(body, sizeof body, "{\"pairingCode\":\"%s\",\"deviceId\":\"%s\"}", code8, deviceId);

  WiFiClient httpClient;  // separate from the MQTT socket
  HTTPClient http;
  http.setTimeout(8000);
  http.begin(httpClient, String(API_BASE_URL) + "/api/device/pair");
  http.addHeader("Content-Type", "application/json");
  int status = http.POST((uint8_t*)body, strlen(body));
  String response = status > 0 ? http.getString() : String();
  http.end();

  if (status <= 0) {
    Serial.printf("[pair] Could not reach the backend at %s (%s). Check API_BASE_URL in secrets.h.\n", API_BASE_URL,
                  HTTPClient::errorToString(status).c_str());
    return false;
  }

  JsonDocument doc;
  bool parsed = !deserializeJson(doc, response);
  if (status != 200) {
    const char* msg = parsed ? doc["message"] | "" : "";
    switch (status) {
      case 400: Serial.printf("[pair] Rejected: %s\n", *msg ? msg : "invalid or expired pairing code. Generate a new one in the web app."); break;
      case 409: Serial.println("[pair] This device is already paired to an account. Disown it in the web app first, then pair again."); break;
      case 429: Serial.println("[pair] Too many wrong codes. Wait a few minutes and try again."); break;
      case 503: Serial.println("[pair] The backend cannot reach the MQTT broker right now. Start it and retry with the SAME code."); break;
      default:  Serial.printf("[pair] Unexpected response (HTTP %d)\n", status);
    }
    return false;
  }

  const char* cred = parsed ? doc["credential"] | "" : "";
  if (strlen(cred) < 16) {
    note("[pair] The backend's answer did not contain a credential.");
    return false;
  }
  credential = cred;
  prefs.putString("cred", credential);
  authFailures = 0;
  mqttBackoff.reset();
  Serial.println("[pair] Success. Credential stored in flash (not shown). Connecting to MQTT...");
  return true;
}

// ---------------------------------------------------------------------------------------------------------------------
// Serial console. Local only (USB cable): pair, status, reset. These are not remote commands.
// ---------------------------------------------------------------------------------------------------------------------
static void printStatus() {
  Serial.printf("[status] deviceId=%s\n", deviceId);
  Serial.printf("[status] paired=%s  wifi=%s  mqtt=%s  time=%s\n", credential.length() ? "yes" : "NO",
                wifiUp() ? "up" : "down", mqtt.connected() ? "connected" : "disconnected", timeIsSet() ? "synced" : "not set");
  Snapshot s = sensorsSnapshot();
  Serial.printf("[status] temp=%s hum=%s gasRaw=%s%s%s gasWarning=%d (MQ-2 on GPIO%u, %s)\n",
                s.tempValid ? String(s.temperatureC, 1).c_str() : "n/a", s.humValid ? String(s.humidityPct, 1).c_str() : "n/a",
                s.gasValid ? String(s.gasRaw).c_str() : "n/a", s.gasWarmingUp ? " (warming up)" : "",
                s.gasStuckZero ? " (STUCK AT 0: check AO wiring)" : "", s.gasWarning, (unsigned)PIN_MQ2,
                "ADC1, read continuously");
  Serial.printf("[status] DHT22 reads ok=%lu failed=%lu last=%s\n", (unsigned long)s.dhtOk, (unsigned long)s.dhtFail,
                dhtResultName(s.dhtLast));
  Serial.printf("[status] motion=%d pirPin=%s%s%s\n", s.motionNow, s.pirRaw ? "HIGH" : "LOW",
                s.pirWarmingUp ? " (PIR warming up)" : "", s.pirStuckHigh ? " (PIR output stuck HIGH)" : "");
}

static void printOutputs() {
  Snapshot s = sensorsSnapshot();
  Serial.printf("[status] pirLed=%s(%s) gasLed=%s(%s) lastCommand=%s\n", s.pirLedOn ? "on" : "off", ledModeName(s.pirMode),
                s.gasLedOn ? "on" : "off", ledModeName(s.gasMode), lastCommandId[0] ? lastCommandId : "none");
}

static void printHelp() {
  Serial.println("Commands (type then press Enter):");
  Serial.println("  <pairing code>   e.g. ABCD-2345 : pair this device (code from the web app: Devices > Add device)");
  Serial.println("  status           show state");
  Serial.println("  reset            forget the stored credential (use after you disowned the device)");
  Serial.println("  gas              60 s of REAL MQ-2 readings, 4 per second");
  Serial.println("  ledtest          SIMULATED high then low gas reading: the red LED must turn on, then off");
  Serial.println("  pir              10 min log of the PIR pin (type again to stop)");
  Serial.println("  pirdebug         turn the once-a-second [PIR DEBUG] line on/off");
}

static void runGasTest();
static void runLedTest();
static void togglePirTest();
static void togglePirDebug();

static void handleLine(String line) {
  line.trim();
  if (line.isEmpty()) return;
  String lower = line;
  lower.toLowerCase();

  if (lower == "status") { printStatus(); printOutputs(); return; }
  if (lower == "help" || lower == "?") { printHelp(); return; }
  if (lower == "gas") { runGasTest(); return; }
  if (lower == "ledtest") { runLedTest(); return; }
  if (lower == "pir") { togglePirTest(); return; }
  if (lower == "pirdebug") { togglePirDebug(); return; }
  if (lower == "reset") {
    prefs.remove("cred");
    credential = "";
    authFailures = 0;
    mqtt.disconnect();
    note("Credential erased. Generate a new pairing code and enter it here.");
    return;
  }

  // Anything else is treated as a pairing code (optionally prefixed with "pair ").
  if (lower.startsWith("pair ")) line = line.substring(5);
  char code[16];
  if (!normalizePairingCode(line.c_str(), code)) {
    Serial.println("[pair] That doesn't look like a pairing code (8 letters/digits). Type 'help' for commands.");
    return;
  }
  if (credential.length()) {
    Serial.println("[pair] This device already has a credential. Type 'reset' first if you want to pair again.");
    return;
  }
  pairWithCode(code);
}

static void pollSerial() {
  static String buf;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      handleLine(buf);
      buf = "";
    } else if (buf.length() < 64) {
      buf += c;
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Remote commands (LED outputs only)
//   subscribe : home/devices/<id>/commands   {"id","action","value","expiresAt"}
//   reply     : home/devices/<id>/acks       {"id","status","detail"}
//   report    : home/devices/<id>/state      what the LEDs are actually doing
// Every command is validated (id, allow-listed action/value, clock, expiry) before anything changes. Remote commands
// can only change how the two LEDs are driven; they cannot touch sensing or the gas warning (see Logic.h).
// ---------------------------------------------------------------------------------------------------------------------
static RecentCommands recentCommands;
static bool stateDirty = true;
static uint32_t stateDueMs = 0;          // do not report before this time (lets the LEDs update first)
static uint32_t lastStateMs = 0;
static LedMode reportedPirMode = LedMode::Auto, reportedGasMode = LedMode::Auto;
static bool reportedGasWarning = false, reportedGasLedOn = false, reportedPirLedOn = false;

static void publishState() {
  Snapshot s = sensorsSnapshot();
  char buf[240];
  int n = buildStateJson(buf, sizeof buf, s.pirMode, s.pirLedOn, s.gasMode, s.gasLedOn, s.gasWarning, lastCommandId);
  if (n < 0) { note("state report too large; skipped"); return; }
  if (mqtt.publish(stateTopic, buf)) {
    reportedPirMode = s.pirMode;
    reportedGasMode = s.gasMode;
    reportedGasWarning = s.gasWarning;
    reportedGasLedOn = s.gasLedOn;
    reportedPirLedOn = s.pirLedOn;
    lastStateMs = millis();
    stateDirty = false;
  }
}

/** Report promptly when something that matters changed (a mode, the gas warning, or either LED switching on/off), so
 *  the dashboard's device twin shows what the LEDs are really doing. At most one report per STATE_MIN_INTERVAL_MS. */
static void maybePublishState() {
  Snapshot s = sensorsSnapshot();
  if (s.pirMode != reportedPirMode || s.gasMode != reportedGasMode || s.gasWarning != reportedGasWarning ||
      s.gasLedOn != reportedGasLedOn || s.pirLedOn != reportedPirLedOn) {
    stateDirty = true;
  }
  const uint32_t now = millis();
  if (stateDirty && (int32_t)(now - stateDueMs) >= 0 && now - lastStateMs >= STATE_MIN_INTERVAL_MS) publishState();
}

static void sendAck(const char* id, const char* status, const char* detail) {
  char buf[200];
  int n = buildAckJson(buf, sizeof buf, id, status, detail);
  if (n < 0 || !mqtt.publish(acksTopic, buf)) note("could not send command acknowledgement");
}

static void onMqttMessage(char* topic, uint8_t* payload, unsigned int length) {
  // The broker only delivers our own command topic to us; check anyway.
  if (strcmp(topic, commandsTopic) != 0) return;

  ParsedCommand cmd;
  if (!parseCommandJson(payload, length, cmd)) { note("ignored a command that is not valid JSON"); return; }
  if (!isUuid(cmd.fields.id)) { note("ignored a command without a valid id (cannot be answered)"); return; }

  // The broker can deliver the same QoS 1 message twice. Never act twice: repeat the original answer.
  if (const RecentCommands::Entry* seen = recentCommands.find(cmd.id)) {
    sendAck(cmd.id, seen->status, seen->detail);
    return;
  }

  CommandCheck check = validateCommand(cmd.fields, timeIsSet(), (int64_t)time(nullptr), COMMAND_MAX_LIFETIME_S);
  const char* status = verdictStatus(check.verdict);
  const char* detail = verdictDetail(check.verdict);

  if (check.verdict == CmdVerdict::Ok) {
    if (check.action == CmdAction::SetPirLed) {
      const RemotePirResult r = sensorsSetPirLedMode(check.mode);
      if (r != RemotePirResult::Applied) detail = remotePirDetail(r);   // acknowledged, but the automatic LED stays in charge
    } else {
      sensorsSetGasLedMode(check.mode);
    }
    strncpy(lastCommandId, cmd.id, 36);
    lastCommandId[36] = '\0';
    stateDirty = true;
    stateDueMs = millis() + 100;  // let the loop task drive the LED before we report it
  }
  recentCommands.remember(cmd.id, status, detail);
  sendAck(cmd.id, status, detail);
  Serial.printf("[cmd] %s %s -> %s%s%s\n", cmd.fields.action ? cmd.fields.action : "?", cmd.fields.value ? cmd.fields.value : "?",
                status, *detail ? ": " : "", detail);
}

// ---------------------------------------------------------------------------------------------------------------------
// MQTT
// ---------------------------------------------------------------------------------------------------------------------
static uint32_t mqttWaitUntil = 0;  // millis() before which we don't try to connect
static uint32_t lastPublishMs = 0;
static bool needFirstPublish = false;

static void connectMqtt() {
  if (credential.isEmpty() || !wifiUp() || mqtt.connected()) return;
  if ((int32_t)(millis() - mqttWaitUntil) < 0) return;

  // Last Will: if we vanish, the broker publishes this for us (retained so late subscribers see it).
  const char* will = "{\"state\":\"offline\"}";
  // cleanSession=false: the broker keeps our subscription and holds QoS 1 commands while we are briefly away (Wi-Fi
  // dropouts). Stale commands are still refused: every command carries an expiry.
  Serial.printf("[net] MQTT: connecting to %s:%d as %s\n", MQTT_HOST, MQTT_BROKER_PORT, deviceId);
  bool ok = mqtt.connect(deviceId, deviceId, credential.c_str(), statusTopic, 1, true, will, false);

  if (ok) {
    mqttBackoff.reset();
    authFailures = 0;
    mqtt.publish(statusTopic, "{\"state\":\"online\"}", true);
    needFirstPublish = true;
    stateDirty = true;
    stateDueMs = 0;
    if (!mqtt.subscribe(commandsTopic, 1)) note("could not subscribe to the command topic");
    note("MQTT connected; announced online; listening for commands.");
    return;
  }

  int rc = mqtt.state();
  uint32_t wait = mqttBackoff.nextDelay(esp_random());
  // 4 = bad credentials, 5 = not authorised: the broker doesn't (or no longer) know this credential.
  if (rc == MQTT_CONNECT_BAD_CREDENTIALS || rc == MQTT_CONNECT_UNAUTHORIZED) {
    if (++authFailures >= MQTT_AUTH_FAILS_BEFORE_HINT) {
      note("MQTT rejected our credential repeatedly. The device was probably disowned in the web app, or the broker was reset.");
      note("Type 'reset', generate a new pairing code in the web app and enter it here.");
    }
  }
  Serial.printf("[net] MQTT connect failed (rc=%d); retrying in %lu ms\n", rc, (unsigned long)wait);
  mqttWaitUntil = millis() + wait;
}

static void publishTelemetry() {
  Snapshot s = sensorsSnapshot();

  TelemetryFields f;
  f.seq = bootCount * 1000000ULL + seqCounter++;  // always increases, also across reboots
  time_t now = time(nullptr);
  if (timeIsSet()) { f.hasTs = true; f.ts = (int64_t)now; }
  if (s.tempValid) { f.hasTemp = true; f.temperatureC = s.temperatureC; }
  if (s.humValid) { f.hasHum = true; f.humidityPct = s.humidityPct; }
  f.hasMotion = true;
  f.motion = s.motionNow || s.motionSinceLast;  // "motion at any time since the last report"
  if (s.gasValid) { f.hasGas = true; f.gasRaw = s.gasRaw; }  // RAW counts; omitted during warm-up

  char payload[200];
  int n = buildTelemetryJson(payload, sizeof payload, f);
  if (n < 0) { note("telemetry too large; skipped"); return; }

  if (mqtt.publish(telemetryTopic, payload)) {
    sensorsClearMotionLatch();
    Serial.printf("[net] published %s\n", payload);
  } else {
    note("publish failed (will retry next interval)");
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// The task
// ---------------------------------------------------------------------------------------------------------------------
/** Console-only fault hints (once a minute / once per stuck period). Work with no network. */
static void warnIfSensorStuck() {
  static uint32_t lastGasWarn = 0, lastPirWarn = 0;
  const Snapshot s = sensorsSnapshot();
  if (s.gasStuckZero && (!lastGasWarn || millis() - lastGasWarn >= 60000)) {
    lastGasWarn = millis();
    Serial.printf("[gas] MQ-2 reads 0 continuously. Check that its AO pin reaches GPIO%u and the module has power.\n",
                  (unsigned)PIN_MQ2);
  }
  if (s.pirStuckHigh && (!lastPirWarn || millis() - lastPirWarn >= PIR_STUCK_HIGH_MS)) {
    lastPirWarn = millis();
    Serial.println("[pir] PIR output has been HIGH without a break for 10+ minutes. Likely causes: module power (HC-SR501 "
                   "needs 5 V), its time/sensitivity knobs at maximum, or a heat source/draught in view.");
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Serial-console self-tests (USB only). "gas" and "ledtest" pause the network task while they run; "pir" does not.
// Wi-Fi itself stays on throughout: the MQ-2 is on ADC1, which does not need the radio stopped.
// ---------------------------------------------------------------------------------------------------------------------
/** True if a key was pressed (the key is consumed), so a running test can be stopped. */
static bool keyPressed() {
  bool any = false;
  while (Serial.available()) { Serial.read(); any = true; }
  return any;
}

static void runGasTest() {
  Serial.printf("[gastest] REAL readings from the MQ-2 on GPIO%u for %lu s, 4 per second. Press Enter to stop early.\n",
                (unsigned)PIN_MQ2, (unsigned long)(GASTEST_MS / 1000));
  Serial.printf("[gastest] Warning on at >= %d (2 readings in a row), off below %d. Telemetry pauses meanwhile.\n",
                GAS_WARN_RAW, GAS_CLEAR_RAW);
  keyPressed();
  const uint32_t t0 = millis();
  int peak = -1, low = 5000;
  uint32_t lastSeq = sensorsSnapshot().gasSampleSeq;
  while (millis() - t0 < GASTEST_MS && !keyPressed()) {
    const Snapshot s = sensorsSnapshot();
    if (s.gasSampleSeq != lastSeq) {
      lastSeq = s.gasSampleSeq;
      if (s.gasRaw > peak) peak = s.gasRaw;
      if (s.gasRaw < low) low = s.gasRaw;
      Serial.printf("[gastest] %5.2fs raw=%4d (burst %d-%d)%s warning=%s redLED=%s\n", (millis() - t0) / 1000.0, s.gasRaw,
                    s.gasBurstMin, s.gasBurstMax, s.gasWarmingUp ? " (warming up: not used for the warning)" : "",
                    s.gasWarning ? "ON" : "off", s.gasLedOn ? "ON" : "off");
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  if (peak < 0) Serial.println("[gastest] No readings could be taken (ADC unavailable).");
  else Serial.printf("[gastest] Done. Lowest %d, highest %d (alert level %d).\n", low, peak, GAS_WARN_RAW);
}

static void runLedTest() {
  Serial.printf("[ledtest] SIMULATED readings (not from the sensor, never sent as telemetry): %d for %lu s, then %d for %lu s.\n",
                LEDTEST_HIGH_RAW, (unsigned long)(LEDTEST_HIGH_MS / 1000), LEDTEST_LOW_RAW, (unsigned long)(LEDTEST_LOW_MS / 1000));
  Serial.println("[ledtest] Expected: red LED (GPIO13) ON within about 0.5 s, then OFF in the second half.");
  sensorsStartLedTest();
  vTaskDelay(pdMS_TO_TICKS(50));
  const uint32_t t0 = millis();
  uint32_t lastPrint = 0;
  bool litWhileHigh = false, litAtEnd = false;
  while (sensorsSnapshot().gasLedTest || millis() - t0 < 200) {
    const Snapshot s = sensorsSnapshot();
    const uint32_t el = millis() - t0;
    if (el < LEDTEST_HIGH_MS && s.gasLedOn) litWhileHigh = true;
    litAtEnd = s.gasLedOn;
    if (el - lastPrint >= 500 || lastPrint == 0) {
      lastPrint = el ? el : 1;
      Serial.printf("[ledtest] %4.1fs SIMULATED gas=%d warning=%s redLED=%s\n", el / 1000.0, s.gasSimRaw,
                    s.gasWarning ? "ON" : "off", s.gasLedOn ? "ON" : "off");
    }
    if (mqtt.connected()) { mqtt.loop(); maybePublishState(); }   // the dashboard sees the real LED state
    if (el > LEDTEST_HIGH_MS + LEDTEST_LOW_MS + 2000) break;
    vTaskDelay(pdMS_TO_TICKS(20));
  }
  const Snapshot end = sensorsSnapshot();
  litAtEnd = end.gasLedOn;
  Serial.printf("[ledtest] %s: LED %s during the high phase, %s afterwards.%s\n", litWhileHigh && !litAtEnd ? "PASS" : "FAIL",
                litWhileHigh ? "lit" : "did NOT light", litAtEnd ? "still ON" : "off",
                end.gasMode == LedMode::On ? " (note: gas LED is forced ON from the dashboard; set it to Auto)" : "");
}

// [PIR DEBUG] once a second: every stage from the PIR input (GPIO26) to the PIR LED (GPIO12), so a stuck input can be
// told apart from a software or remote-command cause. Also counts PIR triggers that come right after a Wi-Fi connect
// attempt (radio bursts and the supply dip they cause are a known cause of false PIR triggers).
static bool pirDebugOn = PIR_DEBUG_DEFAULT;
static uint32_t pirDebugLastMs = 0, pirDebugSeenRises = 0, pirRisesNearRadio = 0;

static void togglePirDebug() {
  pirDebugOn = !pirDebugOn;
  Serial.printf("[PIR DEBUG] %s\n", pirDebugOn ? "on" : "off");
}

static void servicePirDebug() {
  const Snapshot s = sensorsSnapshot();
  if (s.pirRises != pirDebugSeenRises) {
    pirDebugSeenRises = s.pirRises;
    if (lastRadioStartMs && s.pirLastRiseMs >= lastRadioStartMs && s.pirLastRiseMs - lastRadioStartMs < PIR_RADIO_WINDOW_MS) {
      pirRisesNearRadio++;
    }
  }
  const uint32_t now = millis();
  if (!pirDebugOn || now - pirDebugLastMs < PIR_DEBUG_INTERVAL_MS) return;
  pirDebugLastMs = now;
  Serial.printf("[PIR DEBUG] GPIO%u=%s motion=%s stable=%s candidate=%s warmup=%s rawHighFor=%.1fs rises=%lu "
                "(%lu within %.0fs of a Wi-Fi start) lastRise=%s GPIO%u=%s ledTimer=%.1fs pirLedMode=%s%s\n",
                (unsigned)PIN_PIR, s.pirRaw ? "HIGH" : "LOW", s.motionNow ? "TRUE" : "FALSE", s.pirStable ? "HIGH" : "LOW",
                s.pirCandidate ? "HIGH" : "LOW", s.pirWarmingUp ? "yes" : "no", s.pirRawHighForMs / 1000.0,
                (unsigned long)s.pirRises, (unsigned long)pirRisesNearRadio, PIR_RADIO_WINDOW_MS / 1000.0,
                s.pirLastRiseMs ? (String((now - s.pirLastRiseMs) / 1000.0, 1) + "s ago").c_str() : "never",
                (unsigned)PIN_PIR_LED, s.pirLedPinHigh ? "HIGH" : "LOW", s.pirLedHoldLeftMs / 1000.0,
                s.pirMode == LedMode::On ? "ON" : s.pirMode == LedMode::Off ? "OFF" : "AUTO",
                s.pirMode == LedMode::On ? " (remote command, e.g. Security Away 'light on motion'; returns to AUTO within 2 min)" : "");
  if (s.pirStuckHigh) Serial.printf("[PIR DEBUG] WARNING: GPIO%u has been HIGH without a break for 10+ minutes.\n", (unsigned)PIN_PIR);
}

static bool pirTestOn = false, pirTestLastRaw = false;
static uint32_t pirTestStart = 0, pirTestNextSummary = 0, pirTestRises0 = 0, pirTestHigh0 = 0;

static void togglePirTest() {
  const Snapshot s = sensorsSnapshot();
  if (pirTestOn) {
    pirTestOn = false;
    Serial.println("[pirtest] stopped.");
    return;
  }
  pirTestOn = true;
  pirTestStart = millis();
  pirTestNextSummary = 60000;
  pirTestRises0 = s.pirRises;
  pirTestHigh0 = s.pirHighMs;
  pirTestLastRaw = s.pirRaw;
  Serial.printf("[pirtest] Logging the PIR pin (GPIO%u) for %lu min; type 'pir' again to stop. Stay out of its view.\n",
                (unsigned)PIN_PIR, (unsigned long)(PIRTEST_MS / 60000));
  Serial.printf("[pirtest] now %s%s\n", s.pirRaw ? "HIGH" : "LOW", s.pirWarmingUp ? " (PIR still settling after power-on)" : "");
}

static void servicePirTest() {
  if (!pirTestOn) return;
  const Snapshot s = sensorsSnapshot();
  const uint32_t el = millis() - pirTestStart;
  if (s.pirRaw != pirTestLastRaw) {
    pirTestLastRaw = s.pirRaw;
    Serial.printf("[pirtest] %7.2fs pin %s  (motion=%d)\n", el / 1000.0, s.pirRaw ? "HIGH" : "LOW", s.motionNow);
  }
  const bool done = el >= PIRTEST_MS;
  if (el >= pirTestNextSummary || done) {
    pirTestNextSummary += 60000;
    const uint32_t high = s.pirHighMs - pirTestHigh0;
    Serial.printf("[pirtest] %lu s: pin HIGH %lu%% of the time, %lu trigger(s); now %s\n", (unsigned long)(el / 1000),
                  (unsigned long)(el ? (uint64_t)high * 100 / el : 0), (unsigned long)(s.pirRises - pirTestRises0),
                  s.pirRaw ? "HIGH" : "LOW");
  }
  if (done) {
    pirTestOn = false;
    Serial.println("[pirtest] finished.");
  }
}

static void networkTask(void*) {
  uint32_t lastReminder = 0;
  bool publishedGasWarning = false;
  bool motionBefore = false;          // filtered motion state on the previous pass (for the motion-start report)
  uint32_t lastMotionPublishMs = 0;
  for (;;) {
    pollSerial();
    maintainWifi();
    maintainTime();
    warnIfSensorStuck();
    servicePirTest();
    servicePirDebug();

    if (credential.isEmpty()) {
      if (wifiUp() && millis() - lastReminder > 30000) {
        lastReminder = millis();
        Serial.printf("[net] Not paired. In the web app: Devices > Add device, then type the code here. Device ID: %s\n", deviceId);
      }
    } else {
      connectMqtt();
      if (mqtt.connected()) {
        mqtt.loop();
        // A change of the local gas warning is published at once, so the backend's alert does not wait for the next
        // regular report.
        // Motion starting is also published at once, so a Security Away alert reaches the dashboard immediately.
        const Snapshot now = sensorsSnapshot();
        const bool gasWarningNow = now.gasWarning;
        const bool motionStart = motionStartPublishDue(now.motionNow, motionBefore, millis(), lastMotionPublishMs,
                                                       MOTION_PUBLISH_MIN_GAP_MS);
        motionBefore = now.motionNow;
        if (motionStart) lastMotionPublishMs = millis() ? millis() : 1;
        if (needFirstPublish || motionStart || gasWarningNow != publishedGasWarning ||
            millis() - lastPublishMs >= PUBLISH_INTERVAL_MS) {
          needFirstPublish = false;
          publishedGasWarning = gasWarningNow;
          lastPublishMs = millis();
          publishTelemetry();
          stateDirty = true;   // refresh the reported state together with telemetry
          stateDueMs = 0;
        }
        maybePublishState();
      }
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
}

void networkBegin() {
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  snprintf(deviceId, sizeof deviceId, "esp32-%02x%02x%02x%02x%02x%02x", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
  snprintf(statusTopic, sizeof statusTopic, "%s%s/status", TOPIC_PREFIX, deviceId);
  snprintf(telemetryTopic, sizeof telemetryTopic, "%s%s/telemetry", TOPIC_PREFIX, deviceId);
  snprintf(commandsTopic, sizeof commandsTopic, "%s%s/commands", TOPIC_PREFIX, deviceId);
  snprintf(acksTopic, sizeof acksTopic, "%s%s/acks", TOPIC_PREFIX, deviceId);
  snprintf(stateTopic, sizeof stateTopic, "%s%s/state", TOPIC_PREFIX, deviceId);

  prefs.begin(NVS_NAMESPACE, false);
  credential = prefs.getString("cred", "");
  bootCount = prefs.getULong64("boots", 0) + 1;
  prefs.putULong64("boots", bootCount);

  mqtt.setServer(MQTT_HOST, MQTT_BROKER_PORT);
  mqtt.setCallback(onMqttMessage);
  mqtt.setKeepAlive(MQTT_KEEPALIVE_S);
  mqtt.setSocketTimeout(5);
  mqtt.setBufferSize(512);

  Serial.printf("\n[net] Device ID: %s  (%s)\n", deviceId, credential.length() ? "paired" : "NOT paired");
  if (credential.isEmpty()) printHelp();

  // Pinned to core 0; Arduino's loop() (sensors, LEDs) runs on core 1 and is never blocked by network calls.
  xTaskCreatePinnedToCore(networkTask, "net", 12288, nullptr, 1, nullptr, 0);
}
