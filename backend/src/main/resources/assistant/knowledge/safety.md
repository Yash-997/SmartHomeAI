# Gas safety and what the assistant can and cannot do

## The gas warning works on the device itself
The ESP32 firmware watches the gas sensor on its own. When the gas value crosses the warning threshold the device lights its gas warning LED, whatever LED mode is set, whether or not Wi-Fi, the backend, automations or this assistant are working. Nothing in the backend can switch that warning off. The gas LED can be set to auto or on, never to off.

## The assistant is advisory
This assistant explains data and suggests automation rules. It cannot send commands to devices, it cannot change or delete rules by itself, and it cannot change the gas warning. A rule it suggests is only a proposal: it becomes a real rule only after you press Approve, and it goes through the same checks as a rule you create by hand.

## It is not a safety system
Do not rely on this software, the web page or the assistant to protect against gas leaks, smoke or fire. Use certified detectors for that. If a gas warning is active or you smell gas, leave the area and follow the local emergency guidance.

## Why an answer can be limited
The assistant answers only from your own devices, readings, anomalies and automation rules plus the help text. If the stored data does not contain the answer it says so instead of guessing. Numbers it quotes should appear in the sources shown under the answer.
