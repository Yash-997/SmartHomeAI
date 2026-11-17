# Assistant and reports

## What the assistant can answer
The assistant reads your own stored data through database queries. It can give the latest temperature, humidity, gas (raw MQ-2 value) and motion of each device; the average, minimum and maximum of any sensor over a period such as today, yesterday, the last 24 hours, this week, last week, the last 7 or 30 days, this month, last month or chosen dates; hourly or daily trends with a chart; comparisons between two periods with the difference already computed; motion and occupancy activity including the busiest hour of the day; device connectivity and how often a device went offline; anomalies; automation history; security events and gas incidents; and the overall system health.

## Follow-up questions
The assistant remembers what a conversation is about: the sensor, the period and the device. After asking about humidity today you can ask "compare it with yesterday", "and last week?" or "what about temperature?" and it reuses the rest. Start a new conversation to change the subject completely.

## How numbers are produced
Statistics are computed by the database from the stored readings, never estimated by the AI. A sensor that sent nothing in a period is reported as having no values, never as zero. Minimum and maximum times are given to the 15-minute window. Every answer lists its sources, and numbers that do not appear in any source are flagged.

## Device commands from the assistant
If you ask the assistant to switch an indicator LED, it only prepares the command. A card asks you to review and confirm it, and the command is sent through the normal command system only after you confirm. The gas warning LED can never be switched off.

## Reports
Open Reports in the menu, choose daily, weekly, monthly or a custom date range, optionally one device, and press Generate report. You can also ask the assistant to "generate a weekly report". A report contains an executive summary, a statistics table, environmental analysis with trend charts, occupancy and security, anomalies, devices and automation, and recommendations that quote the numbers they are based on. Reports are saved in the history and can be downloaded as PDF. Daily covers today so far or one chosen day, weekly the last 7 days, monthly the last 30 days, and custom up to 366 days.

## Gas safety
Gas alerts are handled by the alert system and the device itself, not by the assistant. While a gas incident is active every assistant answer shows a safety notice. Gas values are raw sensor readings, not ppm, and the assistant can never say the air is safe.
