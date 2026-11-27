# ML insights: occupancy prediction and anomalies

## Occupancy prediction
Predictive occupancy is trained only on a synthetic 30 day demonstration dataset, never on readings from your devices. Java classifiers (logistic regression, random forest and gradient boosting) are compared on a chronological validation split and the best one is chosen for each target. One estimates whether the simulated resident is home now; four estimate whether an absent resident returns within 15, 30, 60 or 120 minutes. Recent days count more than older ones. The model only predicts: a separate decision engine, with Night Mode, manual override and safety checks, decides what the light does.

## Evaluation
The latest synthetic days are held out and compared with a simple historical-share baseline. These figures describe synthetic data only. Accuracy in a real home has not been measured. Live ESP32 mode applies the synthetic model to real readings as an experiment.

## Anomaly detection
Anomalies are found with explainable statistics. A reading is compared with the device's own recent normal for that hour using the median and the median absolute deviation (a robust z-score). A warning is raised at 4 robust deviations and critical at 6. Impossible values, a sensor repeating the same value many times, missing sensor values and a silent device are reported too. Each anomaly keeps its severity, time, explanation and status: open, acknowledged, resolved, dismissed or auto resolved.

## Limits
The occupancy model describes a simulated resident, not your household. A motion sensor cannot count people. ML insights never control a device.
