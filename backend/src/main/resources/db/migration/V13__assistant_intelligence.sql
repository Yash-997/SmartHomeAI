-- Smart-home assistant: tool results shown with an answer, conversation focus for follow-up questions, and reports.

-- Charts, tables, follow-up questions and proposed device actions attached to an assistant message (JSON).
ALTER TABLE chat_messages ADD COLUMN extras_json VARCHAR(60000);

-- What the conversation was last about (metric, period, device), so "compare it with yesterday" can be resolved (JSON).
ALTER TABLE chat_conversations ADD COLUMN context_json VARCHAR(2000);

-- Generated reports. The content is a JSON snapshot computed from the database at generation time; the PDF is rendered
-- from that snapshot, so a downloaded report always matches what was previewed.
CREATE TABLE reports (
    id            UUID                     PRIMARY KEY,
    owner_user_id UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- DAILY, WEEKLY, MONTHLY, CUSTOM
    kind          VARCHAR(10)              NOT NULL,
    title         VARCHAR(160)             NOT NULL,
    period_from   TIMESTAMP WITH TIME ZONE NOT NULL,
    period_to     TIMESTAMP WITH TIME ZONE NOT NULL,
    time_zone     VARCHAR(50)              NOT NULL,
    -- Null = all of the owner's devices.
    device_uuid   UUID,
    summary       VARCHAR(2000)            NOT NULL,
    content_json  VARCHAR(1000000)         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reports_owner ON reports (owner_user_id, created_at);
