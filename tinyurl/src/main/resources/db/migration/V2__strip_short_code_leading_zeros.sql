-- Remove leading zeros from existing short codes, relax the minimum length constraint,
-- and add creator tracking columns.

-- Drop the old constraint first (required min 4 chars) so the UPDATE below can produce shorter codes
ALTER TABLE url_mappings
    DROP CONSTRAINT chk_short_code_format;

-- Strip leading zeros from all existing short codes
UPDATE url_mappings
SET short_code = LTRIM(short_code, '0')
WHERE short_code ~ '^0+.+$';

-- Update the format constraint to allow codes as short as 1 character
ALTER TABLE url_mappings
    ADD CONSTRAINT chk_short_code_format CHECK (short_code ~ '^[0-9a-zA-Z_-]{1,32}$');

-- Add creator tracking columns
-- creator_ip: IPv4 (max 15 chars) or IPv6 (max 45 chars) of the requester
-- creator_user_agent: browser, device, or app that made the request
-- referer: page the user was on when they created the short URL
-- click_count: incremented on every successful redirect
ALTER TABLE url_mappings
    ADD COLUMN creator_ip         VARCHAR(45)   NULL,
    ADD COLUMN creator_user_agent VARCHAR(512)  NULL,
    ADD COLUMN referer            VARCHAR(2048) NULL,
    ADD COLUMN click_count        BIGINT        NOT NULL DEFAULT 0;
