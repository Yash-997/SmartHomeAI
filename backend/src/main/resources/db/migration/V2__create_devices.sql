-- One row per physical device (device_id is its hardware identity, e.g. chip ID / MAC).
-- A disowned device keeps its row; it is re-activated by a fresh pairing.
CREATE TABLE devices (
    id              UUID                     PRIMARY KEY,
    device_id       VARCHAR(64)              NOT NULL UNIQUE,
    owner_user_id   UUID                     REFERENCES users (id),
    name            VARCHAR(60)              NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    registered_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    disowned_at     TIMESTAMP WITH TIME ZONE,
    -- SHA-256 hex of the device credential; NULL once revoked. The credential itself is never stored.
    credential_hash VARCHAR(64)
);

CREATE INDEX idx_devices_owner ON devices (owner_user_id);

-- Short-lived, single-use pairing codes created by a signed-in user. Only a hash of the code is stored.
CREATE TABLE pairing_codes (
    id          UUID                     PRIMARY KEY,
    user_id     UUID                     NOT NULL REFERENCES users (id),
    code_hash   VARCHAR(64)              NOT NULL UNIQUE,
    device_name VARCHAR(60)              NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_pairing_codes_user ON pairing_codes (user_id);
