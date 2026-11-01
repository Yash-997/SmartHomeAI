#pragma once

#include <Arduino.h>

#include "Logic.h"

/** A consistent copy of the latest sensor and output state, safe to take from any task. */
struct Snapshot {
  bool tempValid = false;     // false = no recent DHT22 reading (sensor failed or not read yet)
  float temperatureC = 0;
  bool humValid = false;
  float humidityPct = 0;
  bool motionNow = false;     // PIR output right now
  bool motionSinceLast = false;  // PIR was high at any time since the last publish (so short motion isn't missed)
  bool gasValid = false;      // false while the MQ-2 is warming up
  int gasRaw = 0;             // raw 12-bit ADC counts (0-4095). NOT ppm.
  bool gasWarning = false;    // local safety logic's verdict; remote commands cannot change it
  bool gasWarmingUp = true;

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

/** Remote overrides for the indicator LEDs. They only change how the LEDs are driven; sensing and the gas warning
 *  logic are untouched. Overrides return to Auto by themselves after OVERRIDE_MAX_MS and on every reboot. */
void sensorsSetPirLedMode(LedMode mode);
/** Off is not accepted for the gas LED (it is treated as Auto): the gas warning must stay visible. */
void sensorsSetGasLedMode(LedMode mode);
