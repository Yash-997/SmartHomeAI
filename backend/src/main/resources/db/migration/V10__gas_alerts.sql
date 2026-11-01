-- Gas/smoke incidents (MQ-2) and the delivery status of the emails sent for them.
-- One row per incident: ACTIVE from the first confirmed over-threshold reading until readings return to normal.
CREATE TABLE gas_alerts (
    id                      UUID                     PRIMARY KEY,
    owner_user_id           UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_uuid             UUID                     NOT NULL,
    device_name             VARCHAR(100)             NOT NULL,
    -- Raw MQ-2 ADC values (0-4095), never ppm.
    threshold               INTEGER                  NOT NULL,
    recovery_threshold      INTEGER                  NOT NULL,
    trigger_gas_raw         INTEGER                  NOT NULL,
    peak_gas_raw            INTEGER                  NOT NULL,
    last_gas_raw            INTEGER                  NOT NULL,
    -- ACTIVE, RECOVERED
    state                   VARCHAR(10)              NOT NULL,
    started_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    recovered_at            TIMESTAMP WITH TIME ZONE,
    -- Email delivery: PENDING, SENT, FAILED, SKIPPED (mail not configured). The recovery_* columns stay NULL until recovery.
    alert_email_status      VARCHAR(8)               NOT NULL,
    alert_email_sent_at     TIMESTAMP WITH TIME ZONE,
    alert_email_error       VARCHAR(200),
    recovery_email_status   VARCHAR(8),
    recovery_email_sent_at  TIMESTAMP WITH TIME ZONE,
    recovery_email_error    VARCHAR(200)
);

CREATE INDEX idx_gas_alerts_owner ON gas_alerts (owner_user_id, started_at);
CREATE INDEX idx_gas_alerts_device_state ON gas_alerts (device_uuid, state);
