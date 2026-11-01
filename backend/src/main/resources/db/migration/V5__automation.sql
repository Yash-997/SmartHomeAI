-- Rule-based automation. Rules are plain data (no scripts or expressions): a trigger, a flat list of simple
-- comparisons joined by AND or OR, and one allow-listed LED action.
CREATE TABLE automation_rules (
    id                 UUID                     PRIMARY KEY,
    owner_user_id      UUID                     NOT NULL REFERENCES users (id),
    -- Device whose sensor readings are evaluated (and whose telemetry triggers TELEMETRY rules).
    device_uuid        UUID                     NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    -- Device the action is sent to (often the same device). The owner must own both.
    target_device_uuid UUID                     NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    name               VARCHAR(60)              NOT NULL,
    enabled            BOOLEAN                  NOT NULL,
    trigger_type       VARCHAR(10)              NOT NULL,
    condition_logic    VARCHAR(3)               NOT NULL,
    action             VARCHAR(30)              NOT NULL,
    action_value       VARCHAR(10)              NOT NULL,
    priority           INTEGER                  NOT NULL,
    cooldown_seconds   INTEGER                  NOT NULL,
    -- SCHEDULE rules only: local wall-clock time, comma separated days (MON,TUE,...) and an IANA time zone.
    schedule_time      TIME,
    schedule_days      VARCHAR(40),
    time_zone          VARCHAR(50),
    -- Atomic claims that stop a rule running twice (cooldown) or a schedule firing twice on the same day.
    last_executed_at   TIMESTAMP WITH TIME ZONE,
    last_scheduled_on  DATE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_automation_rules_owner ON automation_rules (owner_user_id);
CREATE INDEX idx_automation_rules_source ON automation_rules (device_uuid, trigger_type, enabled);
CREATE INDEX idx_automation_rules_target ON automation_rules (target_device_uuid);
CREATE INDEX idx_automation_rules_schedule ON automation_rules (trigger_type, enabled);

CREATE TABLE automation_conditions (
    rule_id       UUID             NOT NULL REFERENCES automation_rules (id) ON DELETE CASCADE,
    cond_order    INTEGER          NOT NULL,
    sensor        VARCHAR(12)      NOT NULL,
    comparison_op VARCHAR(3)       NOT NULL,
    -- Exactly one of these is set, depending on the sensor (motion is boolean, the rest are numbers).
    number_value  DOUBLE PRECISION,
    bool_value    BOOLEAN,
    PRIMARY KEY (rule_id, cond_order)
);

-- Audit trail. One row per evaluation that matched or was blocked; repeated identical skips are folded into one row
-- (repeat_count) so a rule that stays true does not flood the table.
CREATE TABLE automation_executions (
    id             UUID                     PRIMARY KEY,
    rule_id        UUID                     NOT NULL REFERENCES automation_rules (id) ON DELETE CASCADE,
    owner_user_id  UUID                     NOT NULL REFERENCES users (id),
    -- The device the action was meant for.
    device_uuid    UUID                     NOT NULL,
    rule_name      VARCHAR(60)              NOT NULL,
    trigger_type   VARCHAR(10)              NOT NULL,
    outcome        VARCHAR(30)              NOT NULL,
    reason         VARCHAR(200),
    action         VARCHAR(30)              NOT NULL,
    action_value   VARCHAR(10)              NOT NULL,
    -- JSON snapshots: the sensor values that triggered the evaluation and the per-condition results.
    trigger_values VARCHAR(1000),
    evaluation     VARCHAR(2000),
    command_id     UUID,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    repeat_count   INTEGER                  NOT NULL
);

CREATE INDEX idx_automation_exec_rule ON automation_executions (rule_id, last_at);
CREATE INDEX idx_automation_exec_owner ON automation_executions (owner_user_id, last_at);
CREATE INDEX idx_automation_exec_device_outcome ON automation_executions (device_uuid, outcome, created_at);

-- Which automation (if any) sent a command, shown next to the command in the UI.
ALTER TABLE device_commands ADD COLUMN origin VARCHAR(80);
