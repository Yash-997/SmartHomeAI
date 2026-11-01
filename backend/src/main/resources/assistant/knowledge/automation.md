# Automation rules

## Anatomy of a rule
A rule has a trigger, optional conditions and one action. The trigger is either every new reading from a device (TELEMETRY) or a time of day on chosen days in a chosen time zone (SCHEDULE). Conditions compare a sensor with a number using greater than, at least, less than, at most, equals or not equals. Sensors are temperature, humidity, motion (true or false) and gas (raw value). Several conditions are joined with AND or OR. A condition on a sensor that has no value in the reading is not met; it is never treated as zero.

## Actions
Only the two indicator LEDs can be automated. set_pir_led accepts auto, on or off. set_gas_led accepts auto or on, never off. A rule trying to switch the gas LED off is refused when saved and again when it would run.

## Priority and cooldown
Priority is 0 to 100 and a higher number wins when two rules want the same output at the same moment. The cooldown is the minimum number of seconds between two runs of one rule, so a fast stream of readings cannot flood a device with commands.

## History
Every run is recorded with the values it saw and its outcome: sent, skipped because the condition was not met, skipped during cooldown, skipped because there was no data, refused as unsafe, or failed.

## Suggestions from the assistant
Ask the assistant for a rule in plain words, for example turn the PIR LED on when motion is detected after 22:00. It replies with a proposal that shows the trigger, conditions and action. Nothing is created until you approve it, and an invalid or unsafe proposal is discarded with an explanation.
