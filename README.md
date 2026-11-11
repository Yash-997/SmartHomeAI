# AI-Powered Smart Home Automation

College mega project.

- **Chunk 1**: project skeleton, database, MQTT broker, health check.
- **Chunk 2**: authentication (register, login, JWT, protected dashboard).
- **Chunk 3**: ESP32 device registration and ownership (pairing codes, rename, disown).
- **Chunk 4**: MQTT telemetry, device presence, live readings in the UI.
- **Chunk 5**: secure remote commands for the two indicator LEDs (command API, acknowledgements, reported state, history).
- **Chunk 6**: rule-based automation engine (sensor and schedule rules, priorities, cooldowns, audit history).
- **Chunk 7**: ML intelligence (occupancy prediction with a Java logistic-regression model, explainable anomaly detection, ML Insights page). See [ML Intelligence](#ml-intelligence).
- **Chunk 9**: security modes (Away/Night), device digital twin, approval-gated recommendations and an authenticated system status on the dashboard. See [Security and intelligence](#security-modes-digital-twin-recommendations-and-system-status).
- **Chunk 8**: AI assistant with RAG (PostgreSQL + pgvector retrieval over your own devices, readings, anomalies and rules; approval-gated rule suggestions). See [AI Assistant](#ai-assistant-rag).
- **Firmware**: classic ESP32 DevKit sketch (Wi-Fi, pairing, authenticated MQTT, telemetry); see [ESP32 firmware](#esp32-firmware). **It compiles but has not been run on real hardware yet.**

Not implemented yet: chatbot, RAG, embeddings, ML, energy features.

## Structure

| Path | Purpose |
|---|---|
| `backend/` | Spring Boot 4 (Java 21, Maven) REST API, MQTT ingest, Flyway migrations |
| `firmware/smarthome_esp32s3/` | ESP32 (classic DevKit) Arduino sketch; the folder name is historical (`firmware/tests/` has host-side unit tests) |
| `frontend/` | React + Vite + TypeScript + Tailwind CSS |
| `docker-compose.yml` | PostgreSQL (pgvector) and Eclipse Mosquitto (+ a one-shot broker init) |
| `infra/mosquitto/mosquitto.conf` | Broker config: no anonymous access, Dynamic Security plugin |
| `infra/postgres/init/` | SQL run on first DB start (enables `vector`) |
| `.env.example` | Template for local settings; copy to `.env` |

## Prerequisites

- JDK 21 or newer, Node.js 20+, Docker Desktop (running). Maven is **not** required (`mvnw` is bundled).

## Setup

```powershell
copy .env.example .env
```

Edit `.env` and set real values for `POSTGRES_PASSWORD`, `JWT_SECRET` (32+ chars), `MQTT_ADMIN_PASSWORD` and `MQTT_BACKEND_PASSWORD`. Generate a random secret with:

```powershell
[Convert]::ToBase64String((1..48 | % { Get-Random -Max 256 }))
```

The backend reads `../.env` automatically when started from `backend/`. Never commit `.env`.

**If a port is already taken** on your PC (a local PostgreSQL on 5432 and another app on 8080/5173 are common), change it in `.env`: `POSTGRES_PORT`, `MQTT_PORT`, `SERVER_PORT`. If you change `SERVER_PORT`, start the frontend with `VITE_API_TARGET`, e.g. `$env:VITE_API_TARGET="http://localhost:8081"; npm run dev`, and add the frontend's origin to `CORS_ALLOWED_ORIGINS`.

## Run

**1. Database and broker**
```powershell
docker compose up -d
docker compose ps -a        # postgres and mosquitto "Up"; mosquitto-init "Exited (0)" is normal
```

**2. Backend**
```powershell
cd backend
.\mvnw.cmd spring-boot:run        # macOS/Linux: ./mvnw spring-boot:run
```
On first start it applies the database migrations, creates the broker roles and its own broker account, then connects. Look for `MQTT ingest connected` in the log. If the broker is down the API still works and the backend keeps retrying.

**3. Frontend**
```powershell
cd frontend
npm install
npm run dev
```

Open the URL Vite prints (normally http://localhost:5173), register, and use **Devices** in the header.

## How devices work

1. **Pair (in the web app).** Devices, Add device, give it a name. You get a one-time code like `ABCD-2345`, valid 10 minutes.
2. **Provision (the device).** The ESP32 sends the code and its hardware ID to the backend:
   ```
   POST /api/device/pair      {"pairingCode":"ABCD-2345","deviceId":"esp32-a1b2c3d4e5f6"}
   -> {"deviceId":"esp32-a1b2c3d4e5f6","name":"...","credential":"<43 chars, shown once>"}
   ```
   The device must store the credential. Knowing a hardware ID (or MAC) alone can never claim a device. The owner is whoever created the code.
3. **Connect (the device).** It connects to the MQTT broker with **username = its device ID** and **password = the credential**. The broker lets it publish only under `home/devices/<its own ID>/...`.
4. **Disown (in the web app).** The credential is revoked, the broker drops the device's live connection and deletes its account, and its stored readings are deleted. It must be paired again with a new code.

Device authentication is separate from user login: user JWTs never work on `/api/device/**`, and device credentials never work on user endpoints.

## MQTT contract

| Topic | Direction | Purpose |
|---|---|---|
| `home/devices/{deviceId}/telemetry` | device to backend | sensor readings (QoS 0 or 1; **not** retained) |
| `home/devices/{deviceId}/status` | device to backend | `{"state":"online"}` after connecting (retained, QoS 1) |
| `home/devices/{deviceId}/commands` | backend to device | one remote command (QoS 1, **never** retained). Only the backend can publish here; a device can subscribe only to its own |
| `home/devices/{deviceId}/acks` | device to backend | the device's answer to a command |
| `home/devices/{deviceId}/state` | device to backend | what the LEDs are actually doing (not retained) |

The device should also set an MQTT **Last Will** on the status topic: `{"state":"offline"}`, retained, QoS 1, so the broker announces unexpected disconnects.

Telemetry payload (JSON object, max 1024 bytes; every reading is optional but at least one is required):

```json
{ "seq": 42, "ts": 1792830977, "temperature": 24.5, "humidity": 55.2, "motion": true, "gasRaw": 1834 }
```

| Field | Rule |
|---|---|
| `seq` | **required**, integer >= 0, increases with each message (also keeps identical readings distinct) |
| `ts` | optional epoch **seconds**; if sent it must be after Nov 2023 and not more than 5 min ahead, otherwise the message is rejected. Omit it if the clock isn't synced |
| `temperature` | optional, number, -40..125 (degrees C). Omit if the sensor read failed |
| `humidity` | optional, number, 0..100 (%). Omit if the sensor read failed |
| `motion` | optional, boolean (`true`/`false`, not 0/1) from the PIR |
| `gasRaw` | optional, integer 0..4095, the **raw MQ-2 ADC value**. It is never converted to ppm (that needs calibration) |
| `deviceId` | optional; if present it must equal the topic's device ID or the message is rejected. Identity always comes from the topic and the authenticated broker connection, never the payload |

Rules the backend applies: unknown, disowned or invalid devices are ignored; malformed or out-of-range messages are dropped and logged by reason only (never the payload); byte-identical messages within 10 minutes are stored once (QoS 1 redelivery); retained telemetry is ignored.

### Remote commands

Supported outputs (the allow-list in `CommandActions.java`; the firmware has the same list):

| `action` | allowed `value` |
|---|---|
| `set_pir_led` | `auto`, `on`, `off` |
| `set_gas_led` | `auto`, `on` (**never `off`**) |

`auto` = the local logic decides (the PIR LED follows motion; the gas LED shows the gas warning). Remote commands only change how the two LEDs are driven. They cannot touch sensing, the gas warning logic or publishing, and the **gas warning always lights the gas LED**, whatever mode is set. A forced mode returns to `auto` after 1 hour and on reboot.

Command (backend to device, validated by both sides):

```json
{ "id": "3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b", "action": "set_pir_led", "value": "on", "expiresAt": 1792836030 }
```

`id` is a UUID, `expiresAt` is absolute epoch seconds (30 s after creation by default; the device refuses anything already expired, further than 10 minutes away, or any time its clock is not set). Acknowledgement (device to backend):

```json
{ "id": "3f2b8c1e-5d4a-4e7b-9a10-0c6d7e8f9a2b", "status": "applied", "detail": "" }
```

`status` is `applied`, `rejected` or `expired`; `detail` is a short reason (the backend strips control characters and caps it at 120 characters). A device can only resolve **its own** commands. Reported state:

```json
{ "pirLed": { "mode": "on", "on": true }, "gasLed": { "mode": "auto", "on": false }, "gasWarning": false, "lastCommandId": "3f2b8c1e-..." }
```

The device reports on connect, whenever a mode or the gas warning changes, and every telemetry interval. `lastCommandId` lets the backend confirm a command whose acknowledgement was lost.

Command lifecycle:

| Status | Meaning |
|---|---|
| `PENDING` | saved, not yet handed to the broker |
| `SENT` | handed to the broker, waiting for the device |
| `ACKNOWLEDGED` | the device applied it (by acknowledgement, or confirmed by its reported state) |
| `FAILED` | the device rejected it, or the backend could not deliver it |
| `EXPIRED` | it expired before it could be sent, or the device said it arrived too late. It definitely did not run |
| `UNKNOWN` | sent, but no acknowledgement arrived by expiry plus 10 s. It may or may not have run; a late acknowledgement or state report still resolves it |

Rules: the owner always comes from the JWT; a device that is not yours, is disowned or does not exist is "not found"; commands are refused with **409** unless the device is currently `ONLINE` (nothing is queued for later, so nothing surprising happens when it reconnects); an identical command already in flight is returned instead of sent twice; at most 5 commands may wait per device (429). Disowning deletes the device's command history and reported state.

**Online/offline** is derived, never assumed. A device is `ONLINE` only if it reported online **and** the backend received a live message within `TELEMETRY_STALE_AFTER_SECONDS` (default 90). It is `NEVER_CONNECTED` until the first live message, and `OFFLINE` if it said so or went silent. The UI marks readings older than that threshold as **STALE**, and sensors that sent nothing show "no reading" instead of 0.

## Automation

Rules that control the two indicator LEDs automatically, from sensor readings or a schedule. Open **Automation** in the web app, or use the API below. A rule is plain data: a trigger, a flat list of simple comparisons, and one allow-listed action. Nothing a user sends is ever executed as code, SQL or an expression.

### How it works

1. **Telemetry rules** are evaluated whenever a new valid, non-duplicate reading arrives from the rule's device (after it has been saved). **Schedule rules** are evaluated once at a local time (see below).
2. If the conditions are true, the engine asks the **existing command service** to send the action, with the rule owner's identity. There is only one place that publishes commands, so everything that protects manual commands protects automations too: ownership, the device must be **online**, the action allow-list, command expiry, acknowledgements and history. The resulting command shows "Automation: *rule name*" next to it in the Devices page.
3. Every evaluation that matched, or was deliberately held back, is written to an audit trail with the sensor values seen, the per-condition results, the outcome, the reason and the command ID.

### Rule JSON

Create a rule (`POST /api/automations`). `deviceId` is the device whose readings are watched; `targetDeviceId` (optional, default the same device) is the device that receives the action. You must own both.

```json
{
  "name": "Warm room indicator",
  "deviceId": "7d2c1f0e-5b1a-4c58-9b0e-2f3a6a9d1c11",
  "trigger": { "type": "TELEMETRY" },
  "logic": "AND",
  "conditions": [
    { "sensor": "temperature", "operator": ">", "value": 30 },
    { "sensor": "motion", "operator": "==", "value": true }
  ],
  "action": { "action": "set_pir_led", "value": "on" },
  "priority": 20,
  "cooldownSeconds": 60,
  "enabled": true
}
```

A scheduled rule (every weekday at 22:30 India time, only if the room is quiet):

```json
{
  "name": "Night mode",
  "deviceId": "7d2c1f0e-5b1a-4c58-9b0e-2f3a6a9d1c11",
  "trigger": { "type": "SCHEDULE", "time": "22:30", "days": ["MON","TUE","WED","THU","FRI"], "timeZone": "Asia/Kolkata" },
  "conditions": [ { "sensor": "motion", "operator": "==", "value": false } ],
  "action": { "action": "set_pir_led", "value": "off" },
  "cooldownSeconds": 60
}
```

| Field | Rules |
|---|---|
| `name` | 1-60 characters |
| `trigger.type` | `TELEMETRY` or `SCHEDULE` |
| `trigger.time` | `HH:mm`, 24-hour (schedule only) |
| `trigger.days` | any of `MON`..`SUN`; omit for every day (schedule only) |
| `trigger.timeZone` | an IANA name such as `Asia/Kolkata`, `Europe/London`, `UTC` (schedule only; offsets like `+05:30` are refused) |
| `logic` | `AND` (default, all conditions) or `OR` (any condition). One mode per rule; mixed groups are not supported |
| `conditions[].sensor` | `temperature` (-40..125), `humidity` (0..100), `motion` (true/false), `gas` (whole number 0..4095, the **raw** MQ-2 reading, never ppm) |
| `conditions[].operator` | `>`, `>=`, `<`, `<=`, `==`, `!=` (motion allows only `==` and `!=`). Up to 10 conditions; a telemetry rule needs at least one, a schedule rule may have none |
| `action` | `set_pir_led` with `auto`/`on`/`off`, or `set_gas_led` with `auto`/`on`. **`set_gas_led` + `off` is always refused** |
| `priority` | 0-100, default 10; higher wins when rules compete for the same output |
| `cooldownSeconds` | 5-86400, default 60 |
| `enabled` | default `true` |

Errors are JSON with per-field messages, for example `"fieldErrors": {"conditions[0].value": "Must be between -40 and 125 for temperature"}`. Unknown JSON fields are ignored; the owner always comes from your login, never from the request.

### Endpoints (all need a user JWT)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/automations` | create (201). At most 50 rules per user |
| GET | `/api/automations` | your rules |
| GET | `/api/automations/{id}` | one rule (404 if it is not yours) |
| PUT | `/api/automations/{id}` | replace the whole rule |
| PATCH | `/api/automations/{id}/enabled` | `{ "enabled": true }` |
| DELETE | `/api/automations/{id}` | delete the rule and its history (204) |
| GET | `/api/automations/{id}/executions?limit=50` | that rule's audit trail, newest first |
| GET | `/api/automations/executions?limit=50` | audit trail across all your rules |

Ownership is checked in the service layer on every call, for the rule **and** for each device it mentions. Someone else's rule or device is "not found". Disowning a device deletes every rule that watches or acts on it.

### Behaviour you can rely on

**Missing or stale data is never zero.** Each comparison is true, false or *unknown*. A reading that is absent (DHT22 failed, MQ-2 still warming up) makes it unknown, never 0 or false. A rule only acts when its result is true: `temperature < 5 AND motion == true` with no temperature never fires, while `temperature < 5 OR motion == true` still fires on motion alone. Unknown results are recorded as "No data" so you can see why a rule did not act. Scheduled rules with conditions use the latest stored reading only if it is at most 90 seconds old (`TELEMETRY_STALE_AFTER_SECONDS`); otherwise they are skipped as "Data too old" (or "No data" if the device never sent one).

**Cooldown.** After a rule acts it cannot act again until its cooldown has passed. The cooldown is claimed atomically in the database, so even simultaneous evaluations cannot run a rule twice. A run that did not actually happen (device offline, broker down) does **not** use up the cooldown. Repeated identical skips (for example "cooling down") are folded into one history row with a repeat count (×N), so a rule that stays true all night does not flood the history. The last 200 entries per rule are kept.

**Offline devices.** Automations act only on a device that is `ONLINE` right now (same rule as manual commands). Otherwise the execution is recorded as "Device offline" and nothing is queued, so nothing surprising happens when it reconnects.

**Priorities and conflicts.** Rules for the same output of the same device compete; the winner is the highest priority, then the oldest rule, then the lowest id, so the same inputs always give the same winner. The losers are recorded as "Overridden". This is decided among all rules whose conditions are true, **before** cooldowns: a high-priority rule in cooldown still owns its output while its conditions hold, so a lower-priority rule cannot take over. Rules for different outputs do not compete. (Conflicts are resolved within one telemetry message or one schedule tick; rules fired by different messages simply run in order.)

**Already set.** If the device's fresh state report already shows the requested mode, no command is sent ("Already set").

**Schedules and time zones.** A schedule is local wall-clock time in the rule's own IANA time zone, evaluated every 15 seconds. It runs **once per local day**, from the scheduled time until 5 minutes later. If the backend was not running in that window, the day is recorded as "Missed" instead of running hours late. Daylight saving is handled by the JDK rules: a time that does not exist (spring forward, for example 02:30 on 2027-03-14 in New York) runs at the first valid moment after the gap; a time that happens twice (fall back) runs once. Different zones are independent: 22:30 `Asia/Kolkata` is 17:00 UTC.

**Loop and overload protection.** Only incoming telemetry triggers rules (never command results, acknowledgements or state reports), so an action cannot re-trigger itself. Additionally: a cooldown of at least 5 s, a per-rule lock against overlapping runs, at most 6 automation commands per device per minute, at most 10 actions per message or tick, a bounded evaluation queue, and a limit of 50 rules per user. Tunables live under `app.automation.*` (see `AutomationProperties.java`).

**One failing rule never stops the others.** Each rule is evaluated and executed in its own error boundary; a failure is recorded against that rule only.

### Safety rules

- Automation can **never** turn off the gas warning LED. `set_gas_led` + `off` is refused when a rule is saved, refused again by the command service, and refused a third time by the engine at run time (recorded as "Refused (unsafe)") even if a rule row were tampered with. On the device itself the gas warning also always lights the LED, whatever mode is set (firmware, Chunk 5).
- Automations act with their owner's rights only, through the same command service as people. They cannot touch devices of other users.
- Rules contain only fixed vocabulary (sensor names, six operators, numbers within sensor ranges). There are no scripts, expressions or SQL.

### Using the Automation page

**New rule** opens the builder: choose the device to watch and the device to act on; choose *when* (new readings, or a time, days and time zone); add conditions and choose **All of these** (AND) or **Any of these** (OR); choose the output and value (the gas LED has no "off"); set priority and cooldown. Each rule card has an enabled switch, Edit, History (that rule's audit trail) and Delete (with confirmation). **Recent activity** lists what all your rules did, with the sensor values seen, why anything was skipped, and the current status of the command each run created. The page shows loading, empty, validation and error states.

### Manual verification (with the backend, broker and a paired, online device)

1. Create a rule: temperature `>` 30, then `set_pir_led` `on`, cooldown 20. Publish a reading with `temperature` 22 (nothing happens), then 33 (a command appears on the device's `.../commands` topic and the rule shows "Sent"), then 34 two seconds later ("Cooling down").
2. Publish `{"seq":N,"motion":true}` with no temperature: the history shows "No data", not a trigger.
3. Try to save a rule with the gas LED set to `off`: the form and the API refuse it.
4. Create a schedule for the next minute in UTC: it runs once within about 15 seconds of that minute and never again the same day.
5. Disown the device: its rules and history disappear.

Hardware: nothing in this chunk changed the firmware, and none of it was run on a physical board. Commands from automations arrive exactly like manual ones, so the firmware checklist (steps 10-14 above) still applies, and you should repeat the LED checks once with a rule driving them.

### Limitations

- Conditions are one flat list with one AND/OR mode; there are no nested groups.
- A rule reads one device's sensors; conditions combining several devices are not supported (a rule may act on a different device you own).
- Conflicts are resolved per telemetry message or schedule tick, not across rules fired by different messages.
- Equality on measured values (`==` on temperature) rarely matches a real sensor; prefer `>`/`<` ranges.
- Only the two indicator LEDs can be automated.

## ML Intelligence

Two features, both learned from **your own device's stored readings** (no cloud service, no pre-trained model, no invented data). Everything is advisory: ML never sends a command, never touches automation rules and has no influence on the firmware's own gas warning, which keeps working with the backend and ML switched off.

### Occupancy prediction

- **What "occupied" means**: the PIR motion sensor fired at least once in a 15-minute slot. It is an activity proxy; a person sitting still may not trigger it.
- **Data**: persisted `motion` readings, bucketed into 15-minute slots. A slot with fewer than 10 readings is dropped, never labelled "empty" (a silent device is not an empty room).
- **Model**: logistic regression written in Java (Newton/IRLS with L2 ridge, backtracking line search, deterministic). Features: 6 sine/cosine harmonics of time of day, weekday dummies, and weekend x time-of-day interactions (30 features). The time zone is chosen at training (default: your browser's).
- **Evaluation**: chronological hold-out (fit on the earliest 80% of slots, score the latest 20%), then the stored model is refitted on all slots. Reported: accuracy, precision, recall, F1, AUC, log loss, Brier score, and the baseline "always guess the training average" for accuracy and log loss. Measures that are undefined (for example precision with no predicted positives) are `null`, not 0.
- **Status**: `READY`; `LOW_QUALITY` (not better than the baseline, or AUC below 0.6; predictions still marked as such); `INSUFFICIENT_DATA` (no model, no metrics, plus the reason and what is missing); `NOT_TRAINED`. A failed retrain never overwrites a working model.
- **Confidence**: the probability of the predicted class, plus the number of similar past hour-of-week slots (`support`) and `reliable=false` when that is small.
- **When it trains**: only on `POST .../occupancy/train` (rate limited, one run at a time) or the hourly check, which retrains when at least 24 h and 1500 new samples have passed. Never per telemetry message.

### Anomaly detection

- **Statistical outliers** (temperature, humidity, gasRaw): robust z-score, `(x - median) / (1.4826 x MAD)` with a minimum spread per sensor, against the device's last 7 days (excluding the last 30 minutes), using the same hour of day (UTC, +/-1 h) when it has at least 30 samples, otherwise all hours. No baseline under 200 samples. WARNING at |z| >= 4, CRITICAL at >= 6. Each finding stores the numbers behind it.
- **Out of range**: DHT22 outside -40..80 C (CRITICAL); gas ADC at saturation 4095 (WARNING). Values the ingest rejects are also recorded (CRITICAL after 5).
- **Stuck**: 60 identical temperature/humidity readings (INFO) or 20 identical gas readings (WARNING).
- **Missing**: 10 messages in a row without temperature/humidity, or 20 without gas (WARNING). A missing value is never treated as 0.
- **Stale**: no message for 10 minutes (given up after 7 days).
- **History**: one row per ongoing problem (with an occurrence count). States `OPEN`, `ACKNOWLEDGED`, `RESOLVED`, `DISMISSED` (suppressed for 60 minutes), `AUTO_RESOLVED` (condition ended). Gas findings carry an advisory note and are never a replacement for the physical alarm.
- **Runs**: about once a minute for online devices, and on demand with "Check now".

### Endpoints (user JWT; 404 if the device is not yours)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/devices/{id}/ml/status` | readiness, data counts, metrics, active anomaly count |
| POST | `/api/devices/{id}/ml/occupancy/train?timeZone=` | train now; 429 if repeated within 30 s, 409 if already running |
| GET | `/api/devices/{id}/ml/occupancy/predict?at=` | one prediction (default now); 409 when no usable model |
| GET | `/api/devices/{id}/ml/occupancy/forecast?hours=&stepMinutes=&from=` | forecast points |
| GET | `/api/devices/{id}/ml/anomalies?status=active\|all&limit=` | anomaly history |
| PATCH | `/api/devices/{id}/ml/anomalies/{anomalyId}` | `{status: ACKNOWLEDGED\|RESOLVED\|DISMISSED, note}`; 409 if already closed |
| POST | `/api/devices/{id}/ml/anomalies/scan` | check now (rate limited) |

Migration `V6__ml.sql` adds `ml_models` and `ml_anomalies` (cascade-deleted with the device). Settings live under `app.ml.*` in `application.properties`.

### Manual verification

1. Pair a device and let it report (or, for a dry run only, load clearly labelled simulated history, see below).
2. Open **ML Insights**: with little data you see "Insufficient data", the progress bars toward the minimum, and no accuracy figures.
3. Press **Train now**. With enough history you see the metrics next to the baseline, a "motion likely / quiet likely" line and a 24-hour forecast.
4. Publish an out-of-range or frozen reading, press **Check now**: an anomaly appears with its explanation and "Show the numbers behind this". Acknowledge, resolve or dismiss it.
5. Disown the device: its model and anomalies are deleted.

### Limitations

- Occupancy is a PIR-activity proxy, not a person count; one time zone is fixed at training; the baseline for anomalies uses UTC hours.
- Features are time-of-day and weekday only; there is no weather, holiday or cross-device input. Two weeks of history is the realistic minimum for a meaningful weekly pattern.
- All ML tests use **simulated** data. Their accuracy (including a perfect score on the simulated pattern in the PostgreSQL check) says the code works, not how well it would predict your home.
- No physical ESP32 or sensor was used to test this chunk.

## AI Assistant (RAG)

A chat page (**Assistant**) answers questions about *your own* devices, sensor history, anomalies, automation rules and ML models, and can **propose** automation rules. It is retrieval-augmented generation: the answer is written by a language model from records retrieved out of the database, and every answer lists the records it used.

### Architecture

```
question ─► validate + rate limit ─► refresh your facts ─► embed question ─► pgvector search ─► prompt ─► LLM ─► checks ─► answer + sources
                                      (from the DB,        (same model      (YOUR chunks +    (data in   (OpenAI-    (citations,
                                       hash-skipped)        as the index)    shared help)      a fenced   compatible  numbers,
                                                                                                block)     or Claude)  rule parser)
```

| Part | What it does |
|---|---|
| `LiveFacts` | Turns real records into text facts through the same owner-scoped services the REST API uses: device status and latest reading, per-day and last-24h sensor summaries (min/avg/max of values that exist; a missing sensor is "no value", never 0), anomalies, automation rules and recent runs, ML model state and hold-out metrics. |
| `RagIndexer` + `Chunker` | Splits text into ~600-character overlapping chunks, embeds them and syncs the store: unchanged text (SHA-256) is not embedded again, facts whose source is gone are deleted. Also indexes the bundled help text (`resources/assistant/knowledge/*.md`, shared by all users) and notes users add. |
| `RagChunkStore` | `rag_chunks` table. On PostgreSQL the vector is a pgvector `vector` column and search is exact cosine distance (`ORDER BY embedding <=> ?`); the H2 test database stores text vectors and computes the same cosine in Java. **Every search is restricted to `owner_user_id = caller OR owner_user_id IS NULL`**, and there is no search method without a caller. |
| `EmbeddingClient` | `local` (default): a built-in feature-hashing embedder (256 dims, word stems, small synonym table, sublinear term weights) that needs no network or key. `openai`: any OpenAI-compatible `/embeddings` endpoint (OpenAI, Ollama, LM Studio...). Vectors of different models are never compared. |
| `LlmClient` | `openai` (OpenAI chat-completions format: OpenAI, Ollama, LM Studio, OpenRouter...) or `anthropic` (Messages API), over the JDK `HttpClient` (no new dependencies). `none` if unset. |
| `PromptBuilder` | Fixed rules in the system message; all retrieved text goes in a numbered `<<<CONTEXT token>>>` block in the user message, with a random per-request token, after `<<<` and `>>>` have been defanged so stored text cannot close the block or fake a rule-suggestion block. |
| `GroundingCheck` | Removes citations to sources that do not exist and reports any number in the answer that appears in none of the sources (allowing for rounding), plus answers with numbers and no citation. |
| `SuggestionParser` + `AssistantService` | Extracts an optional rule proposal, validates it with the real `AutomationValidator` and ownership checks, and stores it as a `PENDING` suggestion. It becomes a rule only through **Approve**. |

Migrations: `V7__assistant.sql` (tables `rag_chunks`, `chat_conversations`, `chat_messages`, `assistant_suggestions`) and the vendor-specific `V8__assistant_vectors.sql` (`db/vendor/postgresql`: `CREATE EXTENSION vector` + `embedding vector`; `db/vendor/h2`: a text column for tests), chosen by Flyway's `{vendor}` location. The pgvector extension is also created by `infra/postgres/init/01-pgvector.sql` on a fresh Docker volume.

### Environment variables (all optional; put them in `.env`, never in code)

| Variable | Meaning |
|---|---|
| `LLM_PROVIDER` | `openai` (any OpenAI-compatible server), `anthropic`, or unset/`none` |
| `LLM_BASE_URL` | e.g. `http://localhost:11434/v1` for Ollama. Blank = the provider's own address |
| `LLM_API_KEY` | not needed for a local server; required for OpenAI/Anthropic. Only sent in a request header, never logged or returned |
| `LLM_MODEL` | model name (required for `openai`; Anthropic defaults to `claude-sonnet-5-5`) |
| `LLM_TIMEOUT_SECONDS` | default 30 |
| `EMBEDDING_PROVIDER` | `local` (default) or `openai` |
| `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL` | for `openai` embeddings; the two address/key values default to the `LLM_` ones |

Tuning (retrieval `topK`, `minScore`, chunk size, limits) lives under `app.assistant.*` in `application.properties`. If you change the embedding model, the next chat re-embeds everything (chunks record their model).

### Ingestion process

1. **Help text** (shared): indexed on the first chat after start-up (and again if the embedding model changes).
2. **Your live facts**: rebuilt from the database before a question is answered, at most every 10 seconds per user. Only changed text is re-embedded. Disowning a device deletes its chunks immediately; deleted rules, anomalies and old days drop out on the next refresh.
3. **Your notes**: *Assistant → Your notes → Add note* (`POST /api/assistant/documents`, 20 to 20,000 characters, up to 20 notes). They are chunked and embedded immediately, and nothing is stored if embedding fails. Only you can retrieve them.

### Endpoints (user JWT; the owner always comes from the token)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/assistant/status` | model/embedding configuration (never a key), index size, limits |
| POST | `/api/assistant/chat` | `{message, conversationId?}`. 200 with `assistantMessage{content, status, sources[], warnings[], suggestions[]}`; 400 invalid, 404 not your conversation, 429 too fast (15/min), 503 data store unavailable |
| GET | `/api/assistant/conversations`, `/conversations/{id}/messages` | your history |
| DELETE | `/api/assistant/conversations/{id}` | |
| GET, POST | `/api/assistant/documents` | your notes |
| DELETE | `/api/assistant/documents/{id}` | |
| GET | `/api/assistant/suggestions` | your pending rule suggestions |
| POST | `/api/assistant/suggestions/{id}/approve` | **the only way a suggestion becomes a rule**; runs `AutomationService.create`, so all rule checks apply again. 409 if already decided or expired |
| POST | `/api/assistant/suggestions/{id}/reject` | |

Answer `status`: `ANSWERED`; `NO_CONTEXT` (nothing relevant found, the model is not called); `NOT_CONFIGURED` (no language model: you still get the matching records); `LLM_UNAVAILABLE` (model timed out, rejected the key, rate limited...: records are still shown); `RETRIEVAL_UNAVAILABLE` (embedding service down).

### Safety rules

- **Ownership**: facts are built from owner-scoped services, searches are filtered by owner in SQL, results are re-checked in Java, and conversations, notes and suggestions are looked up by `(id, owner)`; someone else's id is a 404.
- **Prompt injection**: stored text (device names, notes, rule names, anomaly notes) is *data*: fenced, defanged, never in the system message, and the model is told not to follow it. Even if a model is fooled, it has no tool to act: replies are text only, and any proposed rule must pass the normal validator (so `set_gas_led off` is refused) and the owner's approval.
- **No commands**: the assistant has no code path to the command service or MQTT. The device's own gas warning in the firmware does not depend on the backend, ML or the assistant.
- **No invention**: nothing found means no model call; numbers not present in the sources are flagged in the reply; the page shows the sources for every answer.
- **Secrets**: keys and tokens are never put in prompts, responses, logs or error messages (client errors are generic: "rejected the configured API key", "could not be reached").

### Using the Assistant page

Ask a question or click an example. Under each answer, **Sources** lists the records used (type, text, time, match score; cited ones are highlighted); amber "Check this" lines are grounding warnings. A **Suggested automation rule** card shows the rule in words with **Approve and create rule** / **Reject**. Loading, empty, no-model, error and expired states are shown in place.

### Manual verification

1. Start the stack (Postgres, Mosquitto, backend, frontend). Without any `LLM_*` variable, open **Assistant**: a banner says no AI model is set up. Ask "what is the temperature of my device"; you get the matching stored records as sources and no generated answer.
2. Set `LLM_PROVIDER`/`LLM_MODEL` (and `LLM_BASE_URL`/`LLM_API_KEY` as needed) in `.env`, restart the backend, repeat: you get a written answer with `[S1]`-style citations.
3. Ask for a rule ("turn the PIR LED on when motion is detected after 22:00"): a suggestion card appears and **Automation** still shows no new rule until you press Approve.
4. Ask for the gas LED to be turned off: no suggestion is created and a warning says it was discarded.
5. Add a note, ask about it, then delete the note and ask again.
6. Log in as a second user: they never see your devices, notes or conversations.

### Limitations

- A language model can still be wrong or incomplete. The checks only catch numbers missing from the sources and citations to nothing; they cannot prove an answer is true.
- The default `local` embedder matches wording, not meaning ("it is boiling in here" will not find "temperature"). Use `EMBEDDING_PROVIDER=openai` with a real embedding model for semantic search. Generic questions ("how are my things?") may not retrieve the right records; naming a device or sensor helps.
- Sensor history is summarised per UTC day and for the last 24 h (not raw readings); only the last 7 days are indexed. Live facts can be up to ~10 s old.
- Search is exact cosine over a user's chunks, which is right for hundreds to a few thousand chunks per user; there is no approximate index.
- Only OpenAI-compatible and Anthropic APIs are supported; no streaming, no tool calling.
- Tests use a scripted fake model and a local stub server, so they prove the application's handling of model output, not the quality of any real model. A live call to a hosted model or a pulled Ollama model was **not** made in this environment (no key configured, no model downloaded).
- No physical hardware was used.

## Security modes, digital twin, recommendations and system status

All four appear on the **Dashboard** and need a user login. Everything is scoped to the token's user.

### Security modes (Home / Away / Night)

- **Home**: nothing is watched (motion still drives the PIR LED locally on the device, but no alert is recorded). **Away** and **Night** each have their own settings: record motion alerts (yes/no), ignore a new episode within N seconds of the previous alert (10 to 3600, a guard against a flickering sensor), and optionally also ask the PIR LED to switch `on` once per episode (given back to `auto` when the mode ends, or when the option is turned off).
- **Motion episodes**: one episode = one `MOTION_ALERT`, however long the motion lasts. An episode ends when the device reports `motion: false` (a whole report interval without motion) or sends no motion report for 90 s; the next motion after that is a new episode. Switching mode starts afresh. The device sends a report the moment motion starts, so the alert appears within seconds; the dashboard shows it under **Open alerts** (titled "MOTION DETECTED WHILE AWAY") until it is acknowledged, however much newer history there is, and it stays in the history afterwards.
- **What is recorded** (the security event history): mode and settings changes, motion alerts (WARNING), a device going offline while armed (WARNING, once per outage), and the LED action taken or failed. Alerts can be acknowledged one by one or all at once. Telemetry older than two minutes (replays) never raises an alert.
- **How it reacts**: each stored telemetry message with `motion=true` is handed, after its transaction commits, to a dedicated worker thread (the same pattern as the automation engine), so alerts never run on the MQTT thread and the cooldown is race-free. Offline detection runs every 30 s (`app.security.sweep-ms`).
- **The LED action goes through `CommandService`** (origin `security:away` / `security:night` / `security:restore`), so it has the same ownership, online and validation checks as a person's command. On leaving the mode, the PIR LED is only restored if the newest PIR command is the one security sent: a value the owner set afterwards is left alone.
- **Gas**: the only command security can build is `set_pir_led`. It never sends a gas-LED command, a mode change on its own sends nothing, and the firmware's gas warning does not depend on any of this. A test drives every mode with a very high raw gas value and checks that no `set_gas_led` command exists.
- Endpoints: `GET /api/security/settings`, `PUT /api/security/mode` (`{mode}`), `PUT /api/security/config` (`{away?, night?}`), `GET /api/security/events?limit=&kind=&unacknowledged=`, `POST /api/security/events/{id}/acknowledge`, `POST /api/security/events/acknowledge-all`. Migration `V9__security_and_recommendations.sql`.

### Device digital twin

`GET /api/twins` and `GET /api/devices/{id}/twin` (404 unless you own the device). Computed from the device record, its state report and its command history; nothing is stored. Per output (PIR LED, gas LED): the last **confirmed** request (newest acknowledged command, and who asked: `user`, `automation:<rule>`, `security:away`...), a command still in flight, what the device is **expected** to be doing now, what it **reports**, and a state. The expected mode follows the firmware's own rules, so normal device behaviour is not shown as drift: a PIR `on` acknowledged as "kept auto" (motion had already lit the LED) is expected to stay `auto`; a PIR `on` ends after 2 minutes; any forced mode ends after 1 hour. The device sends a state report whenever an LED switches on or off, so "lit" is current. States: `IN_SYNC`, `PENDING`, `DRIFT` (reports something else, for example after a restart), `UNREPORTED` (no recent state report), `UNKNOWN` (a later command was never acknowledged) or `NO_DESIRED` (nothing confirmed yet, so nothing is compared). Also: connection, last-seen and last-reading ages, pending commands, command problems in 24 h, and the gas warning *as the device last reported it*.

### Recommendations (approval required)

`GET /api/recommendations` (regenerated at most every 5 minutes), `POST /api/recommendations/refresh`, `POST /api/recommendations/{id}/approve`, `POST .../dismiss`. Each one stores its **evidence** (the real numbers) and exactly what approval will do. Approving runs `AutomationService.create` (or switches a rule off), so ownership, validation and the gas-LED rule are enforced again; a failed approval leaves it pending. Dismissed ideas are not offered again for 14 days, applied ones for 30, and ideas that stop being valid are marked stale. Three generators, all deterministic:

| Generator | Needs | Suggests |
|---|---|---|
| Quiet hours | an occupancy model in state READY; a run of at least 4 hours where the forecast chance of motion is below 10% and each hour is backed by enough similar slots | two schedule rules: PIR LED `off` at the start and `auto` at the end (skipped if you already have one at that time) |
| Temperature indicator | at least 3 unusual temperature readings in 7 days (from the anomaly detector) and at least 200 stored temperature values | `temperature > p95 + 2 C` turns the PIR LED on, cooldown 5 min (skipped if you already have a high-temperature rule on that device) |
| Failing rules | an enabled rule with at least 5 runs in 7 days of which at least 70% failed or found the target offline | switch that rule off (it is kept) |

When something cannot be suggested, the reason comes back in `notes` (model not trained, too little data...) instead of a made-up suggestion. Gas readings are never used as a trigger and gas is never a target. Only the PIR LED can be set.

### System status

`GET /api/system/status` (the public `GET /api/health` liveness check is unchanged). One call that reports, for the caller's own devices: database, MQTT link (`CONNECTED`, `DISCONNECTED` = DOWN, `DISABLED`), device availability, command results in the last 24 h (counts by status, failure rate, recent problems), per-sensor freshness (`FRESH`/`STALE`/`NEVER` against `app.health.sensor-stale-seconds`, default 300; only for devices that are online), ML readiness and active anomalies, and the assistant (model configured, search index readable). Every finding is an `issue` with a level; the overall level is the worst one. A check that fails is reported as a problem instead of failing the call. The language model is **not** called to check it, so "configured" does not mean "reachable".

### Manual verification

1. Dashboard: **System status** shows OK when the broker is up and your devices are online and sending.
2. **Security**: choose Away, walk in front of the PIR sensor: a motion alert appears; walking again within the interval adds nothing. Turn on "switch the PIR LED on" and repeat: the LED lights; choose Home and it returns to auto.
3. Unplug a device while Away: a "went offline" event appears within about 30 s and the status shows it offline.
4. **Device twins**: send a PIR LED command from the Devices page: it shows "waiting for device", then "in sync" once the device acknowledges and reports. Restart the board: it shows "differs".
5. **Recommendations** (after a trained model or a few unusual temperature readings): read the numbers, approve one, find the rule on the Automation page; dismiss another.

### Limitations

- Security modes watch the PIR sensor of every device you own; there is no per-device or per-room selection, no schedule that arms Night automatically, and no siren or phone notification: alerts are history entries on the page. It is not a certified alarm system.
- The offline-while-armed memory is kept in memory, so a backend restart may report an ongoing outage once more.
- "Occupied" means the PIR fired; quiet-hours suggestions are guesses about activity, based on the next 24 hours of the forecast, and weekday and weekend patterns differ.
- The temperature suggestion uses the PIR LED as an indicator because it is the only output rules can switch back; there is no other actuator in this system.
- The digital twin compares the last *acknowledged* request with the *reported* state; it cannot know about changes made on the board that were never reported.
- Sensor freshness uses one threshold for all sensors; a sensor that legitimately reports rarely will show as stale.
- None of this was tested on physical hardware: the live checks used readings, acknowledgements and state reports published over real MQTT by the test script, not by a board.

## Gas/smoke email alerts

When a paired device reports an MQ-2 `gasRaw` at or above a threshold, the owner's account email gets one emergency email. When readings return to normal they get one recovery email. Everything is recorded in PostgreSQL (`gas_alerts`, migration `V10__gas_alerts.sql`) together with whether each email was delivered, and shown in the **Gas & smoke alerts** panel on the dashboard.

### How an incident works

- **Start:** `ALERT_GAS_CONFIRM_READINGS` (default 2) consecutive readings with `gasRaw >= ALERT_GAS_THRESHOLD` (default 2500). One noisy sample does nothing.
- **One alert per incident:** while the incident is `ACTIVE`, further high readings only update its peak and last value. No more emails.
- **Recovery:** the same number of consecutive readings below `threshold x (1 - ALERT_GAS_RECOVERY_MARGIN_PERCENT/100)` (default 2125). Readings between 2125 and 2500 keep the incident open, so a value hovering near the threshold cannot flood the inbox. The recovery email is sent once and the incident becomes `RECOVERED`; a later rise starts a new incident.
- **Restarts:** the incident row is the source of truth, so restarting the backend while gas is high does not send a second alert. Only the consecutive-reading counters are in memory.
- **Values are raw ADC (0-4095), not ppm**, and the MQ-2 on a hobby board is not a certified alarm.
- A device with no owner (unpaired) has nobody to warn, so no incident is created for it.

### Gmail setup

1. Turn on 2-Step Verification for the Gmail account, then create an **App Password** (Google Account > Security > App passwords).
2. Put these in `.env` (never in code or git):

   ```
   MAIL_USERNAME=you@gmail.com
   MAIL_PASSWORD=<the 16-character app password>
   ```
3. `docker compose up -d backend` to restart it with the new values.

SMTP is `smtp.gmail.com:587` with STARTTLS required (`MAIL_HOST` / `MAIL_PORT` can override for another provider), with 10 s timeouts. The email goes to the email address the device owner registered with. Gmail sends only as the logged-in account, so `ALERT_MAIL_FROM` normally stays blank.

| Variable | Default | Meaning |
|---|---|---|
| `MAIL_USERNAME`, `MAIL_PASSWORD` | blank | Gmail address and Google App Password. Blank = incidents are recorded with delivery `SKIPPED`, nothing is mailed |
| `ALERT_GAS_ENABLED` | `true` | Master switch |
| `ALERT_GAS_THRESHOLD` | `2500` | Raw MQ-2 value that starts an incident |
| `ALERT_GAS_RECOVERY_MARGIN_PERCENT` | `15` | Hysteresis below the threshold |
| `ALERT_GAS_CONFIRM_READINGS` | `2` | Consecutive readings to start or end an incident |
| `ALERT_MAIL_FROM` | blank | Sender; blank = `MAIL_USERNAME` |

### Delivery status

Each email is `SENT`, `FAILED` (for example a rejected app password; a short safe reason is stored, never the password or the raw server reply), or `SKIPPED` (mail not set up, or the owner has no email). A failed email is **not retried automatically**: that would risk repeats, and the incident stays visible on the dashboard with the failure. Fix the setting and the next incident will mail normally.

### Endpoints (user JWT; scoped to the caller)

| Method and path | Purpose |
|---|---|
| `GET /api/alerts/gas/config` | Thresholds, whether Gmail is configured, and the masked recipient (`y***@gmail.com`). Never returns credentials |
| `GET /api/alerts/gas?limit=20` | The caller's incidents, newest first, with delivery status of both emails |

### Verify without hardware

With the stack running and a paired device (see "Try the whole pipeline without any hardware"), publish two telemetry messages with `"gasRaw": 3000`, then two with `"gasRaw": 500`. You should get one emergency email, one recovery email, and one `RECOVERED` row on the dashboard. The automated tests (`GasAlertTests`) cover this with a recording fake mailer; they do not contact Gmail, so the real SMTP login is only verified by doing the above once.

## API summary

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | none | returns a JWT |
| GET | `/api/auth/me` | user JWT | profile |
| POST | `/api/devices/pairing-codes` | user JWT | `{name}`, returns the one-time code |
| GET | `/api/devices` | user JWT | your devices with connection state and latest reading |
| GET | `/api/devices/{id}` | user JWT | one device (404 if not yours) |
| GET | `/api/devices/{id}/telemetry?limit=50` | user JWT | recent readings, newest first (max 200) |
| POST | `/api/devices/{id}/commands` | user JWT | `{action, value}`; 202 with the command, 400 invalid, 409 device not online, 429 too many waiting, 503 broker down |
| GET | `/api/devices/{id}/commands?limit=20` | user JWT | command history, newest first |
| GET | `/api/devices/{id}/commands/{commandId}` | user JWT | one command (poll its status) |
| PATCH | `/api/devices/{id}` | user JWT | rename `{name}` |
| POST, GET, PUT, PATCH, DELETE | `/api/automations...` | user JWT | automation rules and their history, see [Automation](#automation) |
| GET, POST, PATCH | `/api/devices/{id}/ml/...` | user JWT | occupancy model and anomalies, see [ML Intelligence](#ml-intelligence) |
| GET, PUT, POST | `/api/security/...` | user JWT | security mode, settings, event history, acknowledge |
| GET | `/api/alerts/gas`, `/api/alerts/gas/config` | user JWT | gas/smoke incidents with email delivery status; thresholds and masked recipient |
| GET | `/api/twins`, `/api/devices/{id}/twin` | user JWT | reported versus confirmed state |
| GET, POST | `/api/recommendations...` | user JWT | list, refresh, approve, dismiss |
| GET | `/api/system/status` | user JWT | database, MQTT, devices, commands, sensors, ML, assistant |
| GET, POST, DELETE | `/api/assistant/...` | user JWT | chat, history, notes and approval-gated rule suggestions, see [AI Assistant](#ai-assistant-rag) |
| DELETE | `/api/devices/{id}` | user JWT | disown (503 and nothing changed if the broker is unreachable) |
| POST | `/api/device/pair` | pairing code | device-side pairing |
| GET | `/api/device/me` | `X-Device-Id` + `X-Device-Key` | device checks its credential |

Errors are JSON `{status, error, message, fieldErrors?}`.

## Test

```powershell
cd backend;  .\mvnw.cmd verify      # backend build + 372 tests (in-memory H2, a fake broker and a scripted fake language model; no Docker or API key needed)
cd frontend; npm run build; npm run lint
```

### Latest verified results (Chunk 9)

| Check | Result |
|---|---|
| Backend `mvnw verify` | **372 tests, 0 failures** (314 from Chunks 1 to 8, plus 58 new: security modes 20, digital twin 11, recommendations 14, system status 13) |
| Frontend `npm run build` / `npm run lint` | build passes; lint shows only the old cosmetic warning in `auth.tsx` |
| Real PostgreSQL 17 + Mosquitto | V9 applied and the schema validated. Over **real MQTT** (readings, acknowledgements and state reports published with each paired device's own credential): system status reported database and broker OK, the device ONLINE and all four sensors FRESH; in Away mode a motion message produced one alert and a second one inside the cooldown produced none; with the LED option the device itself received `set_pir_led on` on its command topic (recorded as origin `security:away`); the twin went PENDING, then IN_SYNC after the device's ack and state report, then DRIFT when the device reported `auto`; leaving Away delivered `set_pir_led auto`; 0 `set_gas_led` commands ever existed; another user got 404 on the twin and on acknowledging the alert, and saw no events or recommendations. With a **simulated** 21-day motion history (labelled `SIMULATED-`, removed afterwards) and a model really trained on it (hold-out accuracy 0.99 against a 0.645 baseline), the quiet-hours recommendations (23:00 to 06:00, highest motion chance 6.3%) appeared; approving one created the rule, a second approval returned 409, a dismissed one was not offered again, the 15 s refresh throttle returned 429. Stopping the Mosquitto container turned the status DOWN (`not connected`) and it returned to OK after the container restarted. Backend log: 0 occurrences of the DB, JWT or MQTT secrets |
| Physical ESP32 hardware | **not tested** (the "device" in the live check was the test script) |

<details><summary>Earlier result (Chunk 8)</summary>

| Check | Result |
|---|---|
| Backend `mvnw verify` | **314 tests, 0 failures** (216 from Chunks 1 to 7, plus 98 new for the assistant: unit 55 = chunker 7, local embedder 6, grounding check 10, prompt builder 8, suggestion parser 7, rerank 3, rate limiter 2, HTTP model clients 12 against a local stub server; integration 43 = retrieval/ownership/documents 17, chat/injection/failure/suggestions 26) |
| Repeat runs | the assistant tests were run 4 times in a row after fixing two flaky tests (random ids affecting word-embedding ranks; a timestamp fraction confirming an invented number): 62/62 each time |
| Frontend `npm run build` / `npm run lint` | build passes; lint shows only the old cosmetic warning in `auth.tsx` |
| Real PostgreSQL 17 (pgvector) + Mosquitto | V7 and V8 applied, Hibernate schema validated. Two users and two devices paired for real; readings published over real MQTT (plus SIMULATED history rows labelled `SIMULATED-`, removed afterwards). 256-dimension `vector` rows written; native `<=>` search returns the status chunk itself at 1.000. Owner A got a cited answer with the stored 23.4 C; owner B's questions about A's device sent nothing about it to the model; a suggestion created no rule until approved (second approve 409, other user 404); a gas-LED-off proposal was discarded; a note was found by its owner; model HTTP 500 and 401 degraded to references with a plain reason; disowning removed all 8 chunks of the device. Backend log: 0 occurrences of the DB, JWT, MQTT or LLM key secrets |
| Language model in that run | **a local stub server speaking the OpenAI chat format, not a real model**. No hosted-model call and no Ollama model were used (no key, no model downloaded) |
| Physical ESP32 hardware | **not tested** |

</details>

<details><summary>Earlier result (Chunk 7)</summary>

| Check | Result |
|---|---|
| Backend `mvnw verify` | 216 tests, 0 failures (79 new for ML: 47 unit = regression 8, metrics 6, trainer 15, anomaly detectors 18; 32 integration = API 13, anomalies 19) |
| Frontend `npm run build` / `npm run lint` | build passes; lint shows one old cosmetic warning in `auth.tsx` |
| Firmware host tests | unchanged by this chunk |
| Real PostgreSQL 17 + Mosquitto | V6 applied and Hibernate schema validated at start-up. With **simulated** history (20,160 rows labelled `SIMULATED-`, deleted afterwards): insufficient-data state with no metrics, training (READY, hold-out metrics beat the baseline), predict/forecast, 401/404 ownership checks, injected outlier/saturated/frozen gas anomalies detected, acknowledge/resolve/409/400 handling, disown cleanup (0 rows left). Backend log: 0 occurrences of the DB, JWT or MQTT secrets |
| Simulated vs real | every ML figure above comes from simulated data; the simulated pattern is easy, so near-perfect scores there are not an accuracy claim |
| Physical ESP32 hardware | **not tested** |

</details>

### Try the whole pipeline without any hardware

Pair a device through the web app (or the API), then play the ESP32 yourself with `mosquitto_pub` inside the broker container. Replace `DEVICE_ID` and `CREDENTIAL` with what pairing returned:

```powershell
# 1. device says it is online
docker exec smarthome-mosquitto mosquitto_pub -q 1 -r -u DEVICE_ID -P CREDENTIAL -t home/devices/DEVICE_ID/status -m '{\"state\":\"online\"}'
# 2. a reading
docker exec smarthome-mosquitto mosquitto_pub -q 1 -u DEVICE_ID -P CREDENTIAL -t home/devices/DEVICE_ID/telemetry -m '{\"seq\":1,\"temperature\":24.5,\"humidity\":55.2,\"motion\":true,\"gasRaw\":1834}'
```

The Devices page should show **Online** and the values within 5 seconds. Then check the guard rails (nothing should be stored for any of these):

```powershell
# wrong password -> "Connection Refused: not authorised"
docker exec smarthome-mosquitto mosquitto_pub -u DEVICE_ID -P wrong -t home/devices/DEVICE_ID/telemetry -m '{\"seq\":2,\"temperature\":20}'
# publishing as another device -> silently blocked by the broker
docker exec smarthome-mosquitto mosquitto_pub -u DEVICE_ID -P CREDENTIAL -t home/devices/some-other-device/telemetry -m '{\"seq\":3,\"temperature\":99}'
# out-of-range value -> dropped by the backend
docker exec smarthome-mosquitto mosquitto_pub -u DEVICE_ID -P CREDENTIAL -t home/devices/DEVICE_ID/telemetry -m '{\"seq\":4,\"temperature\":900}'
```

To try commands without hardware, pretend to be the device. In one terminal listen on its command topic, in the web app press a LED button, then answer with the command's `id`:

```powershell
docker exec smarthome-mosquitto mosquitto_sub -v -u DEVICE_ID -P CREDENTIAL -t home/devices/DEVICE_ID/commands
docker exec smarthome-mosquitto mosquitto_pub -q 1 -u DEVICE_ID -P CREDENTIAL -t home/devices/DEVICE_ID/acks -m '{\"id\":\"COMMAND_ID\",\"status\":\"applied\"}'
docker exec smarthome-mosquitto mosquitto_pub -q 1 -u DEVICE_ID -P CREDENTIAL -t home/devices/DEVICE_ID/state -m '{\"pirLed\":{\"mode\":\"on\",\"on\":true},\"gasLed\":{\"mode\":\"auto\",\"on\":false},\"gasWarning\":false}'
```

The command goes SENT, then ACKNOWLEDGED, and the card shows what the device reports. If you do not answer, it becomes UNKNOWN about 40 s later (30 s lifetime plus 10 s grace). The device must be online first (publish a telemetry message as above).

Stop publishing for 90 seconds and the card flips to **Offline** and the readings show **STALE**. Disown the device and its credential stops working immediately. Inspect stored data:

```powershell
docker exec smarthome-postgres psql -U smarthome -d smarthome -c "SELECT d.device_id, r.received_at, r.temperature_c, r.humidity_pct, r.motion, r.gas_raw FROM telemetry_readings r JOIN devices d ON d.id = r.device_uuid ORDER BY r.id DESC LIMIT 10;"
```

## ESP32 firmware

Sketch: `firmware/smarthome_esp32s3/` (C++, Arduino framework, classic ESP32 DevKit, board "ESP32 Dev Module"). It reads the sensors locally and, once paired, publishes them over authenticated MQTT and accepts LED commands, using exactly the topics and JSON described above.

**Status:** it compiles, its payload builders, pairing-code parser, back-off, gas-warning and command-validation logic are unit-tested on a PC, and its payloads and command handling were replayed through the real broker and backend. **It has not yet been verified end to end on the physical board**, so everything in the [hardware verification checklist](#hardware-verification-checklist) is still for you to confirm.

### Pins

| Function | GPIO | Note |
|---|---|---|
| DHT22 data | 14 | as originally specified |
| PIR output | 26 | as originally specified |
| PIR LED | 12 | as originally specified |
| MQ-2 analog (AO) | 35 | as originally specified (ADC1 channel 7) |
| MQ-2 LED | 13 | as originally specified |

These are the original wiring of the classic ESP32 DevKit. GPIO35 is on **ADC1**, which keeps working while Wi-Fi is on, so the MQ-2 is read continuously and the Wi-Fi radio is never stopped for a gas reading. (ADC2 cannot be read while Wi-Fi runs; the build refuses an ADC2 pin for the MQ-2.) All pins are in `config.h`, and the build fails with an explanation if a pin is unsuitable. An optional ESP32-S3 build still exists (`--fqbn esp32:esp32:esp32s3`), with PIR on GPIO5 and MQ-2 on GPIO4, because GPIO26/35 cannot be used on the S3.

Wiring notes:
- **MQ-2 AO can reach 5 V** when the module is powered from 5 V, which can damage the ESP32 (its pins are 3.3 V only). Power the module from 3.3 V, or put a voltage divider (for example 10 k + 20 k) between AO and GPIO35.
- Use a series resistor (about 220-330 ohm) for each LED. A bare DHT22 needs a pull-up (about 10 k) on its data line.
- The PIR output is read as a digital input (with a pull-down); check that its output level is 3.3 V.
- GPIO12 (PIR LED) is a boot-strapping pin on the classic ESP32: it must be LOW at reset. An LED with a resistor to GND is fine; do not add a pull-up on it.

### Behaviour

- **Local, always on** (works with no Wi-Fi, MQTT or backend): the PIR LED turns on as soon as motion is detected and stays on for 10 s after it stops (new motion restarts the 10 s). This timer is entirely local and the same in every security mode. The MQ-2 is sampled every 250 ms and averaged, and the gas LED turns on when the averaged **raw** reading stays at or above `GAS_WARN_RAW` for 2 samples, and off again below `GAS_CLEAR_RAW` (hysteresis). During the first 60 s the MQ-2 heater is warming up: no warning, and no gas value is published.
- **Networking** runs on a separate core, so slow or failed connections never stall the sensors or LEDs. Wi-Fi retries back off from 1 s up to 60 s, MQTT from 2 s up to 120 s (each with 20% jitter, reset on success).
- **Published every 30 s** to `home/devices/<deviceId>/telemetry`: `seq` (always increases, also across reboots), `ts` (only once NTP has set the clock), `temperature` and `humidity` (omitted if the DHT22 read failed or is older than 15 s), `motion` (true if motion occurred at any time since the last report), `gasRaw` (raw ADC counts, **never ppm**; omitted while warming up).
- **Presence:** it connects with a Last Will `{"state":"offline"}` (retained) on its status topic and publishes `{"state":"online"}` (retained) after connecting.
- **Remote LED commands:** it subscribes only to its own `.../commands` topic, and validates every command (UUID id, allow-listed action and value, clock set, not expired, expiry no more than 10 minutes away) before changing anything. It answers each one on `.../acks` (`applied`, `rejected` or `expired` with a reason), reports the resulting LED state on `.../state`, and remembers the last 16 command ids so a redelivered command is answered again but **never applied twice**. Remote commands can only change how the two LEDs are driven: the gas warning logic never reads them, the gas LED is always on during a gas warning (even if the PIR/gas mode says otherwise), `set_gas_led off` is refused, and a forced mode returns to `auto` after 1 hour or on reboot.
- **Gas thresholds** are `GAS_WARN_RAW = 600` (on) and `GAS_CLEAR_RAW = 510` (off) in `config.h`. They are raw ADC counts that depend on your sensor, supply voltage and warm-up age: measure your own clean-air and gas readings and set them (see the checklist). The original firmware was not available, so this LED and gas-warning behaviour was written from scratch; please compare it with what you expect and tell me what differs.

### Pairing and credentials

The device follows the backend's existing design; there is no other pairing protocol.

1. Its **device ID** is `esp32-` plus its Wi-Fi MAC (for example `esp32-a1b2c3d4e5f6`). That only names the device. **A MAC or ID alone never claims anything.**
2. You create a one-time pairing code in the web app (Devices, Add device).
3. You type the code into the Serial Monitor. The device sends `POST /api/device/pair` with the code and its ID and receives its **MQTT credential**, which it stores in flash (NVS). The credential is never printed, logged, or put in source code.
4. It then connects to the broker with username = device ID and password = that credential.

Serial commands (local USB only): the pairing code itself, `status` (now also shows the LED modes and last applied command), `reset` (forget the credential; use it after disowning), and `help`.

Flash storage is not encrypted, so anyone with physical access to the board can read the credential. For more than a college demo, enable ESP32 flash encryption.

### Build and flash

One-time toolchain setup. Install Arduino CLI from https://arduino.github.io/arduino-cli/latest/installation/ (Arduino IDE 2 works the same, board "ESP32 Dev Module"). The ESP32 core is large: allow about 5 GB of free disk, and keep the install path short on Windows (very long paths break extraction). It was built here with `esp32:esp32` 3.3.12, DHT sensor library 1.4.7, Adafruit Unified Sensor 1.1.15, PubSubClient 2.8 and ArduinoJson 7.4.3.

```powershell
arduino-cli config init
arduino-cli config add board_manager.additional_urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core update-index
arduino-cli core install esp32:esp32
arduino-cli lib install "DHT sensor library" "Adafruit Unified Sensor" "PubSubClient" "ArduinoJson"
```

Create your private settings (git-ignored):

```powershell
cd firmware\smarthome_esp32s3
copy secrets.example.h secrets.h
notepad secrets.h        # Wi-Fi name and password, your PC's LAN IP for API_BASE_URL and MQTT_HOST
```

`API_BASE_URL` and `MQTT_HOST` must be your PC's **LAN IP** (`ipconfig`), not `localhost`, with your `SERVER_PORT` (8080 by default) and `MQTT_PORT`. Windows Firewall must allow inbound TCP on both ports. The ESP32 only supports 2.4 GHz Wi-Fi.

Compile and flash (replace `COM5` with your port from `arduino-cli board list`):

```powershell
cd ..\..
arduino-cli compile --fqbn esp32:esp32:esp32 firmware\smarthome_esp32s3
arduino-cli upload  --fqbn esp32:esp32:esp32 -p COM5 firmware\smarthome_esp32s3
arduino-cli monitor -p COM5 -c baudrate=115200
```

The classic DevKit has one USB port (CP210x or CH340 USB-UART; install its driver if no COM port appears). If upload fails, hold BOOT while pressing RESET and retry. In the Serial Monitor set the line ending to "Newline".

Unit tests for the logic (no hardware, needs a C++ compiler):

```powershell
cd firmware\tests
g++ -std=c++17 -I..\smarthome_esp32s3 logic_test.cpp -o logic_test.exe ; .\logic_test.exe
# command_test also parses the backend's wire format with ArduinoJson (header-only, from your Arduino libraries folder)
g++ -std=c++17 -I..\smarthome_esp32s3 -I<path to>\libraries\ArduinoJson\src command_test.cpp -o command_test.exe ; .\command_test.exe
```

### Hardware verification checklist

Do these in order; each is something only the real board can confirm. Start the stack first (`docker compose up -d`, the backend, the frontend).

1. **Senses with no network.** Flash with a wrong Wi-Fi password on purpose. The Serial log shows Wi-Fi retries with growing delays while `status` still shows sensor values. Wave a hand in front of the PIR: the PIR LED (GPIO12) lights immediately and goes out 10 s after motion stops.
2. **DHT22.** `status` shows plausible temperature and humidity. Breathe on it and both should rise. Unplug its data wire: after about 15 s they show `n/a`.
3. **MQ-2 and the gas LED.** After the 60 s warm-up `status` no longer says "warming up". Note `gasRaw` in clean air. Briefly hold a gas source near it (a lighter with the gas released but **not lit**, a few cm away, in a ventilated place): `gasRaw` should rise. Set `GAS_WARN_RAW` clearly above clean air and below your gas peak, `GAS_CLEAR_RAW` a bit lower, reflash, and confirm the gas LED (GPIO13) lights with the gas and goes out after it clears, and stays off in clean air.
4. **Pairing.** Put the correct Wi-Fi in `secrets.h` and reflash. Serial prints the device ID and "Not paired". In the web app choose Devices, Add device, then type the code in the Serial Monitor. Expect `[pair] Success` and then `MQTT connected; announced online.`. The web card should become **Online** with live values within about 30 s.
5. **Data is real.** Compare the web readings with `status`; motion and `gasRaw` should react as in steps 1 and 3. `gasRaw` must be a raw number (0-4095), never ppm.
6. **Offline behaviour.** Turn off the router or stop Docker. The LEDs and the gas warning keep working, and Serial shows reconnect attempts with growing delays. After 90 s the card shows **Offline** and the readings **STALE**. Restore the network: the device reconnects by itself and the card returns to **Online**.
7. **Last Will.** Unplug the ESP32. Within about 45 s the card should flip to **Offline**.
8. **Reboot.** Press RESET. It reconnects without asking for a code (the credential is kept), and `seq` keeps increasing.
9. **Disown.** Disown the device in the web app. The broker drops it; Serial reports the rejected credential and, after 3 attempts, tells you to type `reset` and pair again with a new code. Pair it again before the next steps.
10. **PIR LED command.** With the device online, press **On** for the PIR indicator LED. Within about a second the physical LED (GPIO12) should light with no motion, the command should show **Confirmed by device**, and the card should show "Device reports: ON (on)". Press **Off**, then wave a hand at the PIR: the LED must stay off. Press **Auto**: the LED follows motion again. Serial prints a `[cmd]` line for each.
11. **Gas LED command.** Press **On** for the gas warning LED: the LED (GPIO13) lights and the card reports it. Press **Auto**: it goes out in clean air. Confirm there is no **Off** button for this LED.
12. **Gas warning beats commands.** Set the gas LED to **On**, then **Auto**; now expose the sensor to gas so the warning triggers. The gas LED must light and stay lit while the warning is active, the card must show the gas warning banner, and nothing you press may turn the LED off while the warning lasts. Do the same with the PIR LED set to **Off** to confirm it does not affect the gas LED.
13. **Offline and expiry.** Stop the router: the buttons become disabled ("needs the device to be online") once the card shows Offline, and a command sent in the last seconds before that ends up Unconfirmed rather than Confirmed. Restore the network and confirm an old command is not executed late.
14. **Local logic survives.** While a forced mode is active, confirm sensing, telemetry and the gas warning continue normally. After 1 hour a forced LED returns to Auto by itself (or press RESET: modes always start as Auto).

## Security notes

- Broker: anonymous access is off; each device has its own account confined to its own topics. Traffic is plain MQTT without TLS, which is acceptable only on a trusted home network. Do not expose the broker port to the internet.
- Broker accounts live in the `mosquitto_data` Docker volume. If you delete that volume (`docker compose down -v`) devices can no longer connect: disown and pair them again.
- Changing `MQTT_ADMIN_PASSWORD` after the first start has no effect on an existing volume; reset the volume to apply it.
- Commands: only the backend account may publish to `.../commands`; a device may subscribe only to its own commands topic and publish only its own telemetry, status, acks and state (verified on the real broker: wildcard and other-device subscriptions are refused, and forged commands or acknowledgements never arrive). The gas warning LED can never be turned off remotely, by the API, the firmware, or the LED logic.
- Pairing is rate limited per client address; device credentials and pairing codes are stored only as SHA-256 hashes and never logged.
- A hardware ID is not a secret. A disowned device's ID could be re-registered by anyone holding a valid pairing code (it would only get a credential that the real device does not have). Proving possession of the physical device needs a per-device secret burned in at manufacture, which is out of scope here.

## Troubleshooting

- Tests or the build crash with "insufficient memory ... malloc/mmap failed" (JVM crash logs): the machine is out of RAM or virtual memory (close heavy apps, free disk space on the drive holding the page file). The test JVM is already kept small (`pom.xml`, surefire `argLine`). Crash logs are written under `backend/target/`.

- Backend log `MQTT not ready ... retrying`: the broker isn't up or `MQTT_*_PASSWORD` in `.env` doesn't match the broker's volume. Check `docker compose ps -a` and `docker logs smarthome-mosquitto`.
- Pairing returns 503: the broker is unreachable; nothing was changed, retry with the same code.
- `password authentication failed` from the backend even though Docker is up: another PostgreSQL (e.g. a Windows service) is using the same port. Change `POSTGRES_PORT`.
- Device shows **Never connected**: it has not published a valid message yet. Check the backend log for `Rejected MQTT message` lines (they state the reason).
- Changed DB credentials after first start: `docker compose down -v` deletes all data.
- If `mvnw` fails while extracting Maven, install Maven 3.9+ and use `mvn` instead.
