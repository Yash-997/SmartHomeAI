-- Idempotent: databases created by Chunk 2 (Hibernate ddl-auto) already have this table.
CREATE TABLE IF NOT EXISTS users (
    id            UUID                     PRIMARY KEY,
    username      VARCHAR(30)              NOT NULL UNIQUE,
    email         VARCHAR(254)             NOT NULL UNIQUE,
    password_hash VARCHAR(255)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
