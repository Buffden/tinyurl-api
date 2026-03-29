#!/bin/bash
# Creates the least-privilege application user for local development.
# Reads SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD from
# the environment (injected by docker-compose from .env).
# Runs automatically on first container initialisation.

set -e

# Escape single quotes in credentials to prevent SQL injection
APP_USER="${SPRING_DATASOURCE_USERNAME//\'/\'\'}"
APP_PASS="${SPRING_DATASOURCE_PASSWORD//\'/\'\'}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER "$APP_USER" WITH PASSWORD '$APP_PASS';
    GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO "$APP_USER";
    GRANT USAGE ON SCHEMA public TO "$APP_USER";
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE url_mappings TO "$APP_USER";
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "$APP_USER";
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "$APP_USER";
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO "$APP_USER";
EOSQL
