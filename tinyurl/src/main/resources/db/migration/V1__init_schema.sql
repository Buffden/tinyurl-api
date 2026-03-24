CREATE SEQUENCE url_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE url_mappings (
    id                  BIGINT      NOT NULL,
    short_code          VARCHAR(32) NOT NULL,
    original_url        TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ NOT NULL,
    has_explicit_expiry BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_url_mappings PRIMARY KEY (id),
    CONSTRAINT uq_short_code UNIQUE (short_code),
    CONSTRAINT chk_original_url_length CHECK (length(original_url) <= 2048),
    CONSTRAINT chk_short_code_format CHECK (short_code ~ '^[0-9a-zA-Z_-]{4,32}$')
) WITH (fillfactor = 90);

CREATE INDEX idx_url_mappings_short_code ON url_mappings(short_code);
