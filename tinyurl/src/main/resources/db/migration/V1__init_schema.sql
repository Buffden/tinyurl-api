CREATE TABLE IF NOT EXISTS url_mappings (
    id            BIGSERIAL   NOT NULL,
    short_code    VARCHAR(32) NOT NULL,
    original_url  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ NULL,

    CONSTRAINT pk_url_mappings PRIMARY KEY (id),
    CONSTRAINT uq_short_code UNIQUE (short_code),
    CONSTRAINT chk_original_url_length CHECK (length(original_url) <= 2048),
    CONSTRAINT chk_short_code_format CHECK (short_code ~ '^[0-9a-zA-Z_-]{4,32}$')
) WITH (fillfactor = 90);
