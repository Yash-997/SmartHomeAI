-- AI assistant (RAG chat). The vector column itself is added by the vendor-specific V8 script
-- (db/vendor/postgresql uses pgvector, db/vendor/h2 is a plain text column used only by tests).

-- Searchable text chunks. owner_user_id NULL = shared product help text shipped with the app.
-- Every user-specific chunk carries its owner, and every search filters on it.
CREATE TABLE rag_chunks (
    id              UUID                     PRIMARY KEY,
    owner_user_id   UUID                     REFERENCES users (id) ON DELETE CASCADE,
    device_uuid     UUID                     REFERENCES devices (id) ON DELETE CASCADE,
    -- KNOWLEDGE, DEVICE_STATUS, SENSOR_HISTORY, ANOMALY, AUTOMATION, ML_MODEL, DOCUMENT
    source_type     VARCHAR(20)              NOT NULL,
    -- Stable key of the thing the chunk came from (a document, a device, a rule...), unique per owner.
    source_key      VARCHAR(120)             NOT NULL,
    chunk_index     INTEGER                  NOT NULL,
    title           VARCHAR(160)             NOT NULL,
    content         VARCHAR(4000)            NOT NULL,
    -- SHA-256 of the content: unchanged text is not embedded again.
    content_hash    VARCHAR(64)              NOT NULL,
    embedding_model VARCHAR(80)              NOT NULL,
    embedding_dims  INTEGER                  NOT NULL,
    -- When the facts in the chunk were true (live data) or the document was added.
    as_of           TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_rag_chunks_owner_type ON rag_chunks (owner_user_id, source_type);
CREATE INDEX idx_rag_chunks_owner_key ON rag_chunks (owner_user_id, source_key);
CREATE INDEX idx_rag_chunks_device ON rag_chunks (device_uuid);

CREATE TABLE chat_conversations (
    id            UUID                     PRIMARY KEY,
    owner_user_id UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         VARCHAR(80)              NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_chat_conversations_owner ON chat_conversations (owner_user_id, updated_at);

CREATE TABLE chat_messages (
    id              UUID                     PRIMARY KEY,
    conversation_id UUID                     NOT NULL REFERENCES chat_conversations (id) ON DELETE CASCADE,
    role            VARCHAR(10)              NOT NULL,
    content         VARCHAR(8000)            NOT NULL,
    -- Assistant messages only: ANSWERED, NO_CONTEXT, NOT_CONFIGURED, LLM_UNAVAILABLE, RETRIEVAL_UNAVAILABLE
    answer_status   VARCHAR(24),
    -- JSON snapshots of the references shown to the user and of any grounding warnings.
    sources_json    VARCHAR(8000),
    warnings_json   VARCHAR(2000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_chat_messages_conversation ON chat_messages (conversation_id, created_at);

-- Automation rules proposed by the assistant. Nothing here is a rule yet: it becomes one only when the owner approves.
CREATE TABLE assistant_suggestions (
    id              UUID                     PRIMARY KEY,
    owner_user_id   UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id UUID                     REFERENCES chat_conversations (id) ON DELETE CASCADE,
    message_id      UUID,
    summary         VARCHAR(200)             NOT NULL,
    rationale       VARCHAR(1000),
    -- The validated rule request, as JSON.
    rule_json       VARCHAR(4000)            NOT NULL,
    -- PENDING, APPROVED, REJECTED, EXPIRED
    status          VARCHAR(10)              NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at      TIMESTAMP WITH TIME ZONE,
    created_rule_id UUID
);

CREATE INDEX idx_assistant_suggestions_owner ON assistant_suggestions (owner_user_id, status, created_at);
CREATE INDEX idx_assistant_suggestions_message ON assistant_suggestions (message_id);
