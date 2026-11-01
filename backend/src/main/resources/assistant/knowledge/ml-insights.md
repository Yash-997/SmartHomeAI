# ML insights: occupancy prediction and anomalies

## Occupancy prediction
A logistic regression model is trained in Java on the stored motion history of one device. Time is cut into 15 minute slots and a slot counts as occupied if the PIR fired at least once. The model learns the pattern by time of day and day of week. Slots with too few readings are ignored rather than counted as empty. Training happens only when you press Train now or about once a day when enough new data has arrived, never on each reading.

## Readiness and accuracy
The model needs enough usable slots over enough different days and both occupied and empty examples. Until then the state is insufficient data and no accuracy is shown. When trained, it is scored on the most recent slots it did not learn from and compared with the baseline of always guessing the average. If it does not beat that baseline its state is low quality and its predictions are marked as such.

## Anomaly detection
Anomalies are found with explainable statistics. A reading is compared with the device's own recent normal for that hour using the median and the median absolute deviation (a robust z-score). A warning is raised at 4 robust deviations and critical at 6. Impossible values, a sensor repeating the same value many times, missing sensor values and a silent device are reported too. Each anomaly keeps its severity, time, explanation and status: open, acknowledged, resolved, dismissed or auto resolved.

## Limits
Occupancy is a motion proxy, not a head count. Results from little or simulated data say little about real behaviour. ML insights never control a device.
