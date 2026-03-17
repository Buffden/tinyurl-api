CREATE SEQUENCE IF NOT EXISTS url_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS url_mappings (
    id BIGINT PRIMARY KEY,
    short_code VARCHAR(32) NOT NULL UNIQUE,
    original_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    has_explicit_expiry BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_url_mappings_short_code ON url_mappings(short_code);
