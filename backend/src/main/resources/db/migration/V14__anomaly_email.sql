-- Email delivery for anomalies. One anomaly row is one ongoing problem, so it gets at most one email.
-- email_status: NULL = no email due (below the email severity, or alerts off), PENDING, SENT, FAILED,
-- SKIPPED (mail not configured, no recipient, or the same problem was emailed within the cooldown).
ALTER TABLE ml_anomalies ADD COLUMN email_status VARCHAR(8);
ALTER TABLE ml_anomalies ADD COLUMN email_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ml_anomalies ADD COLUMN email_error VARCHAR(200);

CREATE INDEX idx_ml_anomalies_email ON ml_anomalies (device_uuid, kind, scope, email_status, email_at);
