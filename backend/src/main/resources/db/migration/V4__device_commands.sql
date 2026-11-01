-- Command history. The id is the command ID sent to the device and echoed in its acknowledgement.
-- Lifecycle: PENDING -> SENT -> ACKNOWLEDGED | FAILED | EXPIRED | UNKNOWN (see CommandStatus).
CREATE TABLE device_commands (
    id           UUID                     PRIMARY KEY,
    device_uuid  UUID                     NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    requested_by UUID                     NOT NULL REFERENCES users (id),
    action       VARCHAR(30)              NOT NULL,
    action_value VARCHAR(10)              NOT NULL,
    status       VARCHAR(20)              NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at      TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    -- Short human-readable reason (sanitized; may originate from the device).
    detail       VARCHAR(120)
);

CREATE INDEX idx_device_commands_device_created ON device_commands (device_uuid, created_at);
CREATE INDEX idx_device_commands_open ON device_commands (status, expires_at);

-- The latest state the device itself reported (one row per device). This is what the device says it is doing,
-- as opposed to what a command asked for.
CREATE TABLE device_reported_state (
    device_uuid     UUID                     PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    pir_led_mode    VARCHAR(10)              NOT NULL,
    pir_led_on      BOOLEAN                  NOT NULL,
    gas_led_mode    VARCHAR(10)              NOT NULL,
    gas_led_on      BOOLEAN                  NOT NULL,
    gas_warning     BOOLEAN                  NOT NULL,
    last_command_id UUID,
    reported_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
