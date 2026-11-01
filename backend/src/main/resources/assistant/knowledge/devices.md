# Devices, pairing and commands

## Pairing and ownership
Create a pairing code on the Devices page and enter it on the device. The code is valid for 10 minutes and works once. The device receives its own credential once; the backend keeps only a hash. A device belongs to exactly one account. Disowning a device revokes its credential, removes it from the MQTT broker and deletes its readings, commands, rules, models, anomalies and anything the assistant has indexed about it.

## Remote LED commands
You can set the PIR LED to auto, on or off and the gas LED to auto or on from the Devices page. The backend checks that you own the device, sends the command over MQTT and waits for the device to confirm. Its status moves from pending to sent to acknowledged, or ends as failed, expired or unknown if no confirmation arrives. Commands are only possible to a device that is online.

## Troubleshooting
If a device shows offline, check its power and Wi-Fi and that the broker is running. If readings look stale the device has not been heard from within the stale window. A device that never connected shows never connected. If sensors show no value, check the wiring of the DHT22; the system reports missing values and never replaces them with zero.
