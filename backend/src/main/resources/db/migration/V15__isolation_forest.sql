-- Isolation Forest anomaly model (hand-written Java, see com.smarthome.ml.IsolationForest).

-- Every anomaly event carries the measured value and the range that would have been normal, as numbers (the
-- explanation and details JSON already say it in words). NULL when the detector has no such number (e.g. a silent
-- device). Older rows stay NULL.
ALTER TABLE ml_anomalies ADD COLUMN detected_value DOUBLE PRECISION;
ALTER TABLE ml_anomalies ADD COLUMN expected_low DOUBLE PRECISION;
ALTER TABLE ml_anomalies ADD COLUMN expected_high DOUBLE PRECISION;
-- kind now also includes MULTIVARIATE_OUTLIER (Isolation Forest).

-- The trained anomaly model (forests + preprocessing + threshold) as JSON. Model types ANOMALY_IF (real telemetry)
-- and ANOMALY_IF_SYNTH (synthetic demonstration model, never used for live detection).
ALTER TABLE ml_models ADD COLUMN artifact_json TEXT;

-- Evaluations of the anomaly model (per detector, per anomaly type) do not fit the old limits.
ALTER TABLE ml_models ALTER COLUMN metrics_json SET DATA TYPE TEXT;
ALTER TABLE ml_training_runs ALTER COLUMN metrics_json SET DATA TYPE TEXT;
ALTER TABLE synthetic_datasets ALTER COLUMN evaluation_json SET DATA TYPE TEXT;
