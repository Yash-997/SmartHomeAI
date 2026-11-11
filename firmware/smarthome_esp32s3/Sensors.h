#pragma once

#include <Arduino.h>

#include "Logic.h"

/** A consistent copy of the latest sensor and output state, safe to take from any task. */
struct Snapshot {
  bool tempValid = false;     // false = no recent DHT22 reading (sensor failed or not read yet)
  uint32_t dhtOk = 0, dhtFail = 0;    // DHT22 read results since boot (diagnostics)
  DhtResult dhtLast = DhtResult::Timeout;
  float temperatureC = 0;
  bool humValid = false;
  float humidityPct = 0;
  bool motionNow = false;     // filtered PIR state right now (debounced, held, ignored during PIR warm-up)
  bool motionSinceLast = false;  // motion at any time since the last publish (so short motion isn't missed)
  bool pirRaw = false;        // the PIR pin level right now (diagnostics only)
  bool pirWarmingUp = false;
  bool pirStuckHigh = false;  // PIR output HIGH without a break for PIR_STUCK_HIGH_MS (likely a wiring/power fault)
  uint32_t pirRises = 0;      // raw LOW->HIGH transitions of the PIR pin since boot (diagnostics)
  uint32_t pirHighMs = 0;     // total time the raw PIR pin has been HIGH since boot (diagnostics)
  uint32_t pirRawHighForMs = 0;  // how long the raw pin has been HIGH without a break (0 when LOW)
  uint32_t pirLastRiseMs = 0; // millis() of the last raw LOW->HIGH transition (0 = none yet)
  bool pirStable = false;     // MotionFilter's debounced level
  bool pirCandidate = false;  // MotionFilter's pending level (differs from pirStable while debouncing)
  bool pirLedPinHigh = false; // GPIO12 as actually driven, read back from the GPIO output register
  uint32_t pirLedHoldLeftMs = 0;  // Auto mode: time left on the PIR LED's hold timer (0 = off)
  bool gasValid = false;      // a real, recent, post-warm-up MQ-2 sample exists (otherwise nothing is published)
  int gasRaw = 0;             // raw 12-bit ADC counts (0-4095). NOT ppm.
  bool gasHaveSample = false; // at least one MQ-2 sample since boot (warm-up included)
  bool gasWarning = false;    // local safety logic's verdict; remote commands cannot change it
  bool gasWarmingUp = true;
  bool gasStuckZero = false; // reading pinned at 0 after warm-up: AO not reaching the ADC (wiring fault)
  uint32_t gasSampleSeq = 0;  // increases with every MQ-2 sample taken
  int gasBurstMin = 0;        // spread of the conversions in the last real sample (diagnostics)
  int gasBurstMax = 0;
  bool gasLedTest = false;    // the SIMULATED LED self-test is running: gasSimRaw drives the warning, nothing is published
  int gasSimRaw = 0;

  // Outputs: what the LEDs are actually doing, and the remote mode in effect.
  LedMode pirMode = LedMode::Auto;
  bool pirLedOn = false;
  LedMode gasMode = LedMode::Auto;
  bool gasLedOn = false;
};

void sensorsBegin();

/** Call from loop(). Never blocks for long and never touches the network, so LEDs and the gas warning keep working
 *  with Wi-Fi/MQTT/backend down. */
void sensorsUpdate();

Snapshot sensorsSnapshot();

/** Called by the publisher after it has sent `motionSinceLast`. */
void sensorsClearMotionLatch();

/** Serial "ledtest": for LEDTEST_HIGH_MS feeds a SIMULATED reading of LEDTEST_HIGH_RAW through the real gas warning logic
 *  (the red LED must light), then LEDTEST_LOW_RAW for LEDTEST_LOW_MS (it must go off). Real readings are not used and
 *  no gas value is published while it runs; the warning starts from "off" again afterwards. */
void sensorsStartLedTest();

/** Remote overrides for the indicator LEDs. They only change how the LEDs are driven; sensing and the gas warning
 *  logic are untouched. Overrides return to Auto by themselves after OVERRIDE_MAX_MS and on every reboot. */
/** A remote "on" does not override the automatic LED while motion (or its hold timer) already lights it, and never
 *  extends an active "on" (see applyRemotePirMode in Logic.h). */
RemotePirResult sensorsSetPirLedMode(LedMode mode);
/** Off is not accepted for the gas LED (it is treated as Auto): the gas warning must stay visible. */
void sensorsSetGasLedMode(LedMode mode);
