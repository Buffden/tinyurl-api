-- Local bootstrap SQL for Postgres container initialization.
-- Flyway migrations in tinyurl/src/main/resources/db/migration remain the source of truth for schema.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
