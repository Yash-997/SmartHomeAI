# Predictive Occupancy and Context-Aware Lighting: Demonstration Guide

> **Say this up front:** occupancy ML uses **synthetic demonstration data only**. Its accuracy figures are not real-world accuracy. Live mode uses real ESP32 readings and real acknowledgements, but its predictions are **experimental and not validated on real data**.

## 1. Startup

From the project root, with `.env` filled in (see `README.md`):

```powershell
docker compose up -d --build
```

Wait until `docker ps` shows the containers as **healthy**. With the current local port mapping, open `http://localhost:5174` (backend health: `http://localhost:8082/api/health`). Use your own ports if `.env` differs.

## 2. Synthetic dataset generation

Automatic: on first start the backend generates 30 days and saves them. It reuses them after restarts.

| Item | Value in the current database |
|---|---|
| Period | 29 Oct to 27 Nov 2026 (Asia/Kolkata), 30 days |
| Readings | 42,852 one-minute rows (348 simulated offline minutes are missing) |
| Events | 25 departures and 25 arrivals |
| Seed | 42 (`app.predictive.seed`) |

Each reading has temperature, humidity, MQ-2 raw, PIR motion, timestamp and an occupancy ground-truth label. The simulated resident wakes about 06:00, leaves about 09:30 and returns about 19:00, with weekends, early and late days, days at home, missed routines and unusual early activity. PIR triggers irregularly, misses people who are still, and sometimes fires when nobody is home.

Tables: `synthetic_datasets` (the row with `is_primary = true`, `data_source = SYNTHETIC`), `synthetic_readings`, `synthetic_events`, `synthetic_connectivity_events`. Code: `predict/DemoData.java` and `ml/SyntheticGenerator.java`.

**To regenerate:** ML Insights → **Predictive model** → **Regenerate 30-day dataset**. The dataset always ends yesterday. The same seed on the same date gives the same data. Regenerating on a different date shifts the weekdays, so the figures in this guide will change. **Do not regenerate before your presentation.**

## 3. ML training

- Training happens automatically after generation (about 2 seconds here). The model is saved in `predictive_models`; a restart loads it without retraining.
- **Retrain model** (ML Insights) creates the next version from the same synthetic data.
- Targets: *home now*, and *return within 15, 30, 60 and 120 minutes* (only while away).
- Features: hour, weekday, weekend, PIR counts over 5/15/30 minutes (and 3 hours), time since motion, recency-weighted historical patterns (half-life 14 days), recent temperature, humidity and gas changes, and missing-data share.
- Logistic regression, random forest and gradient boosting (plain Java) are compared. The lowest **validation** log loss wins per target.

| Target | Chosen | Validation log loss: LR / RF / GB |
|---|---|---|
| Home now | Logistic regression | 0.025 / 0.039 / 0.033 |
| Return ≤15 min | Random forest | 0.105 / 0.074 / 0.088 |
| Return ≤30 min | Random forest | 0.168 / 0.112 / 0.154 |
| Return ≤60 min | Gradient boosting | 0.218 / 0.184 / 0.162 |
| Return ≤120 min | Gradient boosting | 0.236 / 0.248 / 0.216 |

## 4. Open the Predictive Demo

Sign in and choose **Predictive Demo** in the sidebar (phone: top-right menu). It needs no device. The banner shows **SIMULATION MODE** and **DATA SOURCE: SYNTHETIC DEMONSTRATION DATA**.

**Start** advances 5 simulated minutes per second. Choosing a scenario resets it, including settings. All figures come from model inference. The values below were recorded from the running app on 28 Nov 2026 with model v1.

## 5. Normal weekday (scenario 1)

At 08:00: probability home 100%, LIGHT ON because of real (simulated) PIR motion. Learned from motion: wake 05:57, departure 09:22, return window 18:25–19:20, weekend wake 07:21. Show the weekday and weekend charts and the predicted-versus-actual chart.

**Unusual wake-up (scenario 4):** at 07:30 there is no morning activity: deviation **ISOLATED**, learned wake unchanged. Press **+15 min** three times to see the resident get up. Then set **Previous weekdays same as today** to 4 and press **Apply to today**: **SUSTAINED**, 5 days, learned wake only moves to 06:12. A much longer run (15 days) is eventually learned.

At 07:30 the model still reads 77% home: with no motion, someone asleep and someone away look similar, and at that hour history says home.

## 6. Predictive arrival (scenario 2, 18:30)

- Home probability 9%: LIKELY EMPTY.
- Window 18:58–19:13, from 15 later returns among 22 comparable weekdays.
- Return within 15/30/60/120 min: 9 / 43 / 91 / 96%. Confidence for 60 min: 91%.
- Decision: **LIGHT ON**, pre-arrival. The card shows the 45-minute timeout running.

You may see a brief spike in the occupancy line near 17:30: a simulated PIR false trigger. This is deliberate sensor noise.

## 7. Night Mode (scenario 3, 02:00)

Press **Trigger PIR motion**. The motion appears on the chart and the prediction is recalculated (it is already 100% home, so the rounded value does not change). Result: **SUPPRESSED NIGHT MODE**, LED OFF, "Motion detected, but lighting suppressed because Night Mode is active."

Night Mode is 22:00–06:00 by default, crosses midnight, and is editable in **Decision settings**. Sensors and predictions continue; only automatic lighting is suppressed.

## 8. Delayed return and pre-arrival timeout (scenario 5)

Today's return is 20:00. The model is not told. Press **Start** and pause near 20:05.

| Time | Home | 60-min return | Confidence | Window | Decision |
|---|---|---|---|---|---|
| 18:45 | 9% | 93% | 93% | 18:58–19:13 | LIGHT ON (pre-arrival) |
| 19:00 | 23% | 93% | 81% | 19:13–19:25 | Already ON, no duplicate |
| 19:15 | 46% | 92% | 23% | 19:25–20:06 | SUPPRESSED LOW CONFIDENCE |
| 19:30 | 45% | 92% | 11% | 20:06 | **LIGHT OFF: pre-arrival timeout** |
| 19:45 | 42% | 95% | 12% | 20:06 | Stays off |
| 20:05 | 99% | motion seen at 20:00 | | | Home; light ON |

**Explain honestly:** as the resident stays away, fewer past days support an arrival, so confidence collapses even though the raw probability stays high. The occupancy value also becomes UNCERTAIN, because historically the resident is home by now. After the timeout, the outdated prediction cannot switch the light on again; only real motion resets it. The timeout never overrides a manual choice or Night Mode, and appears in the decision log.

**Missed routine (scenario 7):** the resident stays home all day, so motion continues and no pre-arrival logic is used. Compare its activity chart with a normal day. **Delay arrival by 1 hour** and **Missed routine** buttons change today's behaviour; the time fields change wake-up, departure and return.

## 9. Manual override (scenario 6, 02:00)

Trigger PIR motion, then **Manual ON**: LED ON, and the reason states that the override is respected and Night Mode suppression remains active. **Automatic** returns it to OFF.

## 10. Live ESP32 mode

Firmware, GPIO pins, thresholds, MQTT topics and message formats are unchanged. Only the PIR LED is controlled, through the existing `set_pir_led` command. No relays.

**Prepare:** power the board and confirm it is ONLINE on **Devices** (this is judged from its latest real message, not a stored flag). Disable automation rules or Security "light on motion" that use the same LED. Rehearse before the meeting.

1. **Send one simulated decision to hardware:** in scenario 2, **Try this simulated decision on a real ESP32** → choose the board → **Send "on" to the device**. Show `SENT` becoming `ACKNOWLEDGED`. `FAILED`, `EXPIRED` or `UNKNOWN` means no confirmation; say so.
2. **Live ESP32** tab: choose the board. It starts as **preview only**. When PIR data is fresh, press **Start controlling the real LED**. Decisions are evaluated every 5 seconds; commands show their real status and decision changes are listed.
3. **Night Mode in daytime:** set night hours to include now. The backend sends `off` (needed because in `auto` the firmware lights the LED itself). Wave at the PIR: the LED stays off.
4. **Manual ON / OFF / Automatic**, then **Stop controlling the LED** (sends `auto`).
5. **Disconnect test:** unplug the board. Once reported offline, the decision is NO ACTION and nothing is sent.

Firmware limits: a remote `on` lasts at most 2 minutes (the backend renews it while still wanted) and `off` at most 1 hour, so a short automatic light is possible at renewal. The board may acknowledge `on` without changing mode if motion already lit the LED. Armed state, live settings and live event history are lost if the backend restarts (control becomes disarmed).

Live readings are stored for the live dashboard only. They never enter the synthetic dataset, training or the demonstration charts.

## 11. Explaining the architecture and evaluation

```text
Synthetic generator → synthetic_* tables → training → saved model ─┐
                                                                   ├→ probabilities → LightingDecisionEngine → command
ESP32 → MQTT → Spring Boot → telemetry_readings (live only) ───────┘                       ↓
                                                                   simulation: virtual LED · live: ESP32 + acknowledgement
```

The model has no access to any device. Engine priority: offline → manual override → Night Mode → safe fallback (failed, expired or stale) → actual motion → pre-arrival timeout → prediction with hysteresis (ON 0.75, OFF 0.35), confidence 0.6, arrival 0.65 and a 60 s cooldown. Commands that match the current state are not sent.

Confidence: for occupancy `max(p, 1−p)`; for arrival the same multiplied by historical support (full at 8 or more later returns). It is a heuristic, not a statistical confidence interval.

### Measured results: SYNTHETIC DATA EVALUATION

Split by day: 18 train, 6 validation, 6 test. The test days are scored once, with the chosen algorithm. Baseline: recency-weighted historical share for that quarter hour, with no ML. Threshold 0.5.

| Target | Predictor | Precision | Recall | F1 | ROC-AUC | Brier (lower is better) | TP / FP / FN / TN |
|---|---|---|---|---|---|---|---|
| Home now | Model | 0.996 | 0.997 | **0.996** | 1.000 | **0.004** | 1170 / 5 / 4 / 542 |
| | Baseline | 0.997 | 0.964 | 0.981 | 0.999 | 0.041 | |
| Return ≤15 | Model | none predicted | 0.000 | – | 0.956 | **0.017** | 0 / 0 / 15 / 532 |
| | Baseline | 0.727 | 0.533 | **0.615** | 0.960 | 0.030 | |
| Return ≤30 | Model | 0.929 | 0.433 | 0.591 | 0.970 | **0.025** | 13 / 1 / 17 / 516 |
| | Baseline | 0.864 | 0.633 | **0.731** | **0.978** | 0.037 | |
| Return ≤60 | Model | 1.000 | 0.550 | 0.710 | 0.966 | **0.040** | 33 / 0 / 27 / 487 |
| | Baseline | 0.935 | 0.717 | **0.811** | **0.988** | 0.045 | |
| Return ≤120 | Model | 0.949 | 0.775 | 0.853 | 0.962 | 0.051 | 93 / 5 / 27 / 422 |
| | Baseline | 0.972 | 0.858 | **0.912** | **0.993** | **0.046** | |

For "home now", FP = 5 false "occupied" and FN = 4 false "empty". Arrival time judged at 16:00 on 5 test days: the median estimate was off by 21 minutes on average; the actual return fell in the window on 3 of 5.

Calibration and all values: ML Insights → Predictive model → **Show evaluation**.

**What you may claim**

- "Home now" beat the simple baseline on this synthetic test. Both are very high because a generated resident is much more regular than a person.
- **Arrival ML did not beat the simple baseline overall.** F1 and ROC-AUC are worse in all four windows. The model's probability error (Brier) is lower at 15, 30 and 60 minutes, and worse at 120. At 15 minutes it never crosses 0.5, so it predicts no returns at that threshold.
- Algorithms were chosen on only 6 validation days, and there are only 6 test days with strongly correlated samples. Small differences are not meaningful.
- A good next step is more data and testing whether the historical baseline should serve arrivals.

## 12. Known limitations

- One synthetic resident. No holidays or visitors. No real-world accuracy has been measured.
- The model does not learn your household from live readings. This is deliberate.
- PIR cannot distinguish a still or sleeping person from an empty home.
- Live features use only today's readings; motion before midnight is not carried over.
- Routine and deviation logic assume one main daytime absence. Deviation reporting covers wake-up time.
- "Previous weekdays same as today" adapts historical features and the learned routine in your session without retraining the classifiers.
- The synthetic dataset does not move forward each day; regenerate to update it.
- Simulator and live-control state are in memory (single backend instance). Regenerating or retraining affects all users.
- Other features (assistant, automations, Security) can command the same LED; the engine does not arbitrate between them.
- Removed with the old real-data pipeline: quiet-hours rule suggestions and "motion at a normally quiet time" anomalies. Isolation Forest training on live readings is off by default. Rule-based anomaly checks remain.
- Previously saved PDF reports, chat history and automation execution history are user records and may still quote old readings.

## 5–7 minute demonstration script

| Time | Show | Say |
|---|---|---|
| 0:00–0:50 | ML Insights: label and model card | "All occupancy learning uses 30 days of labelled synthetic data, because the real readings were not good enough." |
| 0:50–1:40 | Scenario 1 and charts | "The routine is learned from motion. Weekdays and weekends differ." |
| 1:40–2:40 | Scenario 2; optionally send to the board | "At 18:30 it estimates 91% for a return within an hour. A separate engine decides to light up. This acknowledgement is from the real board." |
| 2:40–3:25 | Scenario 3 | "Sensing continues at night; only automatic lighting is suppressed." |
| 3:25–3:55 | Scenario 6 | "A person's choice wins, and the screen says why." |
| 3:55–4:40 | Scenario 4, then 4 sustained days | "One late morning does not rewrite the routine." |
| 4:40–5:50 | Scenario 5 | "The resident is late. Confidence collapses and the light times out instead of staying on." |
| 5:50–7:00 | Evaluation and limitations | "Presence beat the baseline on synthetic data. Arrival did not, overall. I report that, and real labelled data is the next step." |

If the board is offline, say so and skip the hardware step. The simulation never shows an acknowledgement.

## Tests

```powershell
cd backend;  .\mvnw.cmd test
cd ..\frontend;  npm test;  npm run build
```
