# Sensors and readings

## What each device measures
A paired ESP32 device reports four values: temperature in degrees Celsius and relative humidity in percent from a DHT22 sensor, motion from a PIR sensor (true when movement was seen), and a raw gas value from an MQ-2 sensor. Any value can be missing from a reading, for example when the DHT22 fails to respond. A missing value is stored as missing, never as zero.

## Gas readings are raw values, not ppm
The MQ-2 value is the raw reading of the analog-to-digital converter (0 to 4095). It is not converted to parts per million because that needs calibration this system does not have. A higher raw value means more gas or smoke detected, but the number alone does not say how dangerous the air is. A raw value of exactly 4095 means the converter is saturated: the sensor may be disconnected, faulty, or exposed to a very high level.

## Valid ranges
The DHT22 temperature range accepted by the backend is minus 40 to 80 degrees Celsius and humidity is 0 to 100 percent. Values outside these ranges are rejected and are recorded as an out-of-range anomaly. The gas raw value must be between 0 and 4095.

## Online, offline and stale
The device publishes about every 30 seconds. A device counts as online only if it said it is online and the backend heard from it within the stale window (90 seconds by default). A reading older than that is marked stale and should not be treated as the current state of the room.

## Motion means PIR activity
Motion true means the PIR sensor fired. A person sitting still may not trigger it, so no motion does not prove that a room is empty.
