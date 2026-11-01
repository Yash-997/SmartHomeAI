-- Security modes (Home / Away / Night), their event history, and the recommendations the system proposes.
-- The digital twin and the system status are computed from existing tables and need no storage.

-- One row per user; a user without a row is in HOME mode with the default behaviour.
CREATE TABLE security_settings (
    owner_user_id        UUID                     PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    mode                 VARCHAR(8)               NOT NULL,
    mode_changed_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Behaviour while in AWAY / NIGHT: record motion alerts, how often per device, and whether to light the PIR LED.
    away_motion_alerts   BOOLEAN                  NOT NULL,
    away_cooldown_s      INTEGER                  NOT NULL,
    away_light_on_motion BOOLEAN                  NOT NULL,
    night_motion_alerts  BOOLEAN                  NOT NULL,
    night_cooldown_s     INTEGER                  NOT NULL,
    night_light_on_motion BOOLEAN                 NOT NULL,
    -- True while a security action has forced the PIR LED on, so it can be given back to AUTO afterwards.
    pir_led_forced       BOOLEAN                  NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE security_events (
    id              UUID                     PRIMARY KEY,
    owner_user_id   UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_uuid     UUID,
    -- MODE_CHANGED, SETTINGS_CHANGED, MOTION_ALERT, DEVICE_OFFLINE, ACTION_TAKEN, ACTION_FAILED
    kind            VARCHAR(20)              NOT NULL,
    -- INFO, WARNING, CRITICAL
    severity        VARCHAR(10)              NOT NULL,
    mode            VARCHAR(8)               NOT NULL,
    message         VARCHAR(300)             NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_security_events_owner ON security_events (owner_user_id, created_at);
CREATE INDEX idx_security_events_device ON security_events (device_uuid, kind, created_at);

-- A proposed change. Nothing is applied until the owner approves it.
CREATE TABLE recommendations (
    id            UUID                     PRIMARY KEY,
    owner_user_id UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_uuid   UUID,
    -- CREATE_RULE or DISABLE_RULE
    action_type   VARCHAR(12)              NOT NULL,
    -- Stable identity of the idea (for example "quiet-hours:<device>:22"), so regenerating never duplicates it.
    fingerprint   VARCHAR(120)             NOT NULL,
    title         VARCHAR(160)             NOT NULL,
    rationale     VARCHAR(1000)            NOT NULL,
    -- The real numbers the suggestion rests on, as JSON text.
    evidence      VARCHAR(2000)            NOT NULL,
    -- CREATE_RULE: the rule request as JSON. DISABLE_RULE: {"ruleId": "..."}.
    payload       VARCHAR(4000)            NOT NULL,
    -- PENDING, APPLIED, DISMISSED, STALE
    status        VARCHAR(10)              NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at    TIMESTAMP WITH TIME ZONE,
    created_rule_id UUID
);

CREATE INDEX idx_recommendations_owner ON recommendations (owner_user_id, status, created_at);
CREATE INDEX idx_recommendations_fingerprint ON recommendations (owner_user_id, fingerprint, created_at);
