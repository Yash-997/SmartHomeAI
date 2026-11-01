-- Machine-learning features. Everything here is derived from telemetry_readings; nothing is invented.

-- One current model per device and type. A retrain replaces the row.
CREATE TABLE ml_models (
    id               UUID                     PRIMARY KEY,
    device_uuid      UUID                     NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    model_type       VARCHAR(20)              NOT NULL,
    -- READY | LOW_QUALITY | INSUFFICIENT_DATA (see OccupancyTrainer). INSUFFICIENT_DATA rows hold no model.
    status           VARCHAR(20)              NOT NULL,
    reason           VARCHAR(400),
    time_zone        VARCHAR(50)              NOT NULL,
    bucket_minutes   INTEGER                  NOT NULL,
    trained_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    data_from        TIMESTAMP WITH TIME ZONE,
    data_to          TIMESTAMP WITH TIME ZONE,
    samples          INTEGER                  NOT NULL,
    labeled_buckets  INTEGER                  NOT NULL,
    positive_buckets INTEGER                  NOT NULL,
    distinct_days    INTEGER                  NOT NULL,
    -- JSON: weights and per-hour lookup tables (null when there is no model).
    model_json       VARCHAR(8000),
    -- JSON: evaluation on a held-out, later slice of the history (null when not evaluated).
    metrics_json     VARCHAR(2000),
    CONSTRAINT uq_ml_models_device_type UNIQUE (device_uuid, model_type)
);

-- Detected sensor anomalies. Repeats of the same problem are folded into one row (occurrences).
CREATE TABLE ml_anomalies (
    id              UUID                     PRIMARY KEY,
    device_uuid     UUID                     NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    -- STATISTICAL_OUTLIER | OUT_OF_RANGE | STUCK_VALUE | MISSING_DATA | STALE_DATA
    kind            VARCHAR(20)              NOT NULL,
    -- temperature | humidity | gasRaw | device
    scope           VARCHAR(12)              NOT NULL,
    severity        VARCHAR(10)              NOT NULL,
    -- OPEN | ACKNOWLEDGED | RESOLVED | DISMISSED | AUTO_RESOLVED
    status          VARCHAR(15)              NOT NULL,
    explanation     VARCHAR(600)             NOT NULL,
    -- JSON with the numbers behind the explanation (value, median, spread, score, thresholds...).
    details         VARCHAR(1500),
    detected_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    occurrences     INTEGER                  NOT NULL,
    reading_at      TIMESTAMP WITH TIME ZONE,
    resolved_at     TIMESTAMP WITH TIME ZONE,
    resolution_note VARCHAR(200)
);

CREATE INDEX idx_ml_anomalies_device_status ON ml_anomalies (device_uuid, status, last_seen_at);
CREATE INDEX idx_ml_anomalies_key ON ml_anomalies (device_uuid, kind, scope, status);
