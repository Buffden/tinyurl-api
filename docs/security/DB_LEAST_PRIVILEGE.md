# Database Least Privilege — App User Separation

**Goal:** The Spring Boot application runs with a DB user that can only do what it needs at runtime (DML). The master user is reserved for admin and migrations (DDL).
**Effort:** ~30 minutes
**Risk if skipped:** If the application is ever compromised, an attacker has full DDL access — they can `DROP TABLE`, `CREATE` backdoor tables, or destroy the database entirely.

---

## The Problem With Using the Master User for the App

The master RDS user (`tinyurl`) is a superuser. It can:

```sql
DROP TABLE url_mappings;        -- destroy all data
TRUNCATE url_mappings;          -- wipe all rows
ALTER TABLE url_mappings ...;   -- modify schema
CREATE TABLE exfil ...;         -- create new tables
GRANT ... TO ...;               -- escalate privileges
```

The Spring Boot app at runtime needs **none of these**. It only reads and writes rows.
If a SQL injection or dependency vulnerability ever allowed an attacker to execute arbitrary SQL,
the master user gives them the keys to everything. A least-privilege user limits the blast radius
to what the app legitimately does anyway.

---

## What the App Actually Needs

Based on the schema in `V1__init_schema.sql`:

| Object | Permissions needed | Why |
|---|---|---|
| `public` schema | `USAGE` | Required to access any object in the schema |
| `url_mappings` table | `SELECT, INSERT, UPDATE, DELETE` | Normal CRUD operations |
| `url_seq` sequence | `USAGE, SELECT` | `nextval('url_seq')` called on every URL creation |

**Does NOT need:** `CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `REFERENCES`, `TRIGGER`

---

## Why Flyway Needs to Stay on the Master User

Flyway runs database migrations — `CREATE TABLE`, `CREATE SEQUENCE`, `ALTER TABLE`. These are DDL
operations. The least-privilege app user cannot run them.

The solution is to split credentials:

```
Master user (tinyurl)
    └── Used by: Flyway only (runs at startup to apply migrations)
    └── Permissions: full DDL + DML

App user (tinyurl_appuser)
    └── Used by: Spring Boot datasource at runtime
    └── Permissions: DML only on specific tables
```

Spring Boot supports this natively — `spring.flyway.user` overrides the datasource user for
migrations only.

---

## Step 1 — Connect to RDS as Master User

From your local machine (requires RDS to be temporarily accessible, or use EC2 via SSM):

```bash
# Option A — via EC2 SSM Session Manager (recommended, no public exposure needed)
# In AWS Console → EC2 → tinyurl-prod → Connect → Session Manager

# Then on EC2:
psql -h <RDS_ENDPOINT> -U tinyurl -d tinyurl_production_db
```

```bash
# Option B — temporarily allow your IP in the RDS security group, then:
psql -h <RDS_ENDPOINT> -U tinyurl -d tinyurl_production_db
```

---

## Step 2 — Create the Least-Privilege App User

Run this SQL once, connected as the master user:

```sql
-- Create the app user with a strong password
CREATE USER tinyurl_appuser WITH PASSWORD '<generate-a-strong-password>';

-- Allow the user to connect to the database
GRANT CONNECT ON DATABASE tinyurl_production_db TO tinyurl_appuser;

-- Allow the user to see objects in the public schema
GRANT USAGE ON SCHEMA public TO tinyurl_appuser;

-- Grant DML on the app table only
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE url_mappings TO tinyurl_appuser;

-- Grant sequence access (required for nextval in JpaUrlRepository)
GRANT USAGE, SELECT ON SEQUENCE url_seq TO tinyurl_appuser;

-- Future tables/sequences created by Flyway migrations are automatically
-- granted to tinyurl_appuser via default privileges
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tinyurl_appuser;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO tinyurl_appuser;
```

> The `ALTER DEFAULT PRIVILEGES` lines are critical — without them, every new table added
> by a future Flyway migration would require a manual `GRANT` to `tinyurl_appuser`.

---

## Step 3 — Verify the App User's Permissions

Still connected as master, confirm `tinyurl_appuser` has exactly what it needs and nothing more:

```sql
-- Check table privileges
SELECT grantee, table_name, privilege_type
FROM information_schema.role_table_grants
WHERE grantee = 'tinyurl_appuser';

-- Expected output:
-- tinyurl_appuser | url_mappings | SELECT
-- tinyurl_appuser | url_mappings | INSERT
-- tinyurl_appuser | url_mappings | UPDATE
-- tinyurl_appuser | url_mappings | DELETE

-- Check sequence privileges
SELECT grantee, object_name, privilege_type
FROM information_schema.usage_privileges
WHERE grantee = 'tinyurl_appuser';

-- Confirm tinyurl_appuser CANNOT do DDL (this should fail):
-- GRANT CONNECT ON DATABASE tinyurl_production_db TO tinyurl_appuser; -- run as tinyurl_appuser, should fail
```

---

## Step 4 — Update AWS Parameter Store

Add two new parameters and update the existing username:

| Parameter | Type | Value |
|---|---|---|
| `/tinyurl/prod/spring/datasource/username` | String | `tinyurl_appuser` |
| `/tinyurl/prod/spring/datasource/password` | SecureString | `<tinyurl_appuser password>` |
| `/tinyurl/prod/spring/flyway/user` | String | `tinyurl` (master) |
| `/tinyurl/prod/spring/flyway/password` | SecureString | `<master password>` |

> The `spring/flyway/user` and `spring/flyway/password` entries tell Spring Boot to use
> the master credentials for Flyway migrations only. The datasource credentials
> (used for all runtime queries) are `tinyurl_appuser`.

---

## Step 5 — Update Local `.env`

```bash
# .env
POSTGRES_USER=tinyurl
POSTGRES_PASSWORD=<master-password>

SPRING_DATASOURCE_USERNAME=tinyurl_appuser
SPRING_DATASOURCE_PASSWORD=<tinyurl_appuser-password>

# Flyway uses master user for local dev migrations
SPRING_FLYWAY_USER=tinyurl
SPRING_FLYWAY_PASSWORD=<master-password>
```

---

## Step 6 — Create the App User in Local Postgres

For local dev, the Postgres container also needs the `tinyurl_appuser` user.
This is handled automatically by `infra/postgres/init/002_app_user.sh` — a shell init script
that Docker Postgres runs on first container initialization.

The script reads `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` from the
environment (injected by docker-compose from `.env`) so no credentials are hardcoded in the repo.

Key points about the grants in that script:

- Grants on `TABLE url_mappings` only — **not** `ALL TABLES` (which would include `flyway_schema_history`)
- `ALTER DEFAULT PRIVILEGES` covers any future tables added by Flyway migrations automatically
- `set -e` + `ON_ERROR_STOP=1` — container init fails loudly if the script errors

**Fresh setup (no existing volume):** just run `docker compose up -d` — the script runs automatically.

**If the container already exists with data**, the init script will not re-run. Apply the grants
manually by running the same SQL the script would have executed:

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
    CREATE USER $SPRING_DATASOURCE_USERNAME WITH PASSWORD '$SPRING_DATASOURCE_PASSWORD';
    GRANT CONNECT ON DATABASE $POSTGRES_DB TO $SPRING_DATASOURCE_USERNAME;
    GRANT USAGE ON SCHEMA public TO $SPRING_DATASOURCE_USERNAME;
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE url_mappings TO $SPRING_DATASOURCE_USERNAME;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $SPRING_DATASOURCE_USERNAME;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $SPRING_DATASOURCE_USERNAME;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO $SPRING_DATASOURCE_USERNAME;
"
```

> The env vars (`$POSTGRES_USER`, `$SPRING_DATASOURCE_USERNAME`, etc.) are read from your shell,
> so source your `.env` first: `set -a && source .env && set +a`

To verify the grants are scoped correctly (url_mappings only, not flyway_schema_history):

```bash
docker compose exec postgres psql -U tinyurl -d tinyurl -c \
  "SELECT grantee, table_name, privilege_type FROM information_schema.role_table_grants WHERE grantee = 'tinyurl_appuser';"
```

Expected: exactly 4 rows — SELECT, INSERT, UPDATE, DELETE on `url_mappings`.

---

## Step 7 — Verify End-to-End

After deploying (or restarting locally):

```bash
# 1. Create a short URL — exercises INSERT + nextval
curl -X POST https://go.buffden.com/api/urls \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'

# 2. Use the short URL — exercises SELECT
curl -I https://go.buffden.com/<short-code>

# 3. Check health
curl https://go.buffden.com/actuator/health
```

If any of these fail with a DB error, check:
- Parameter Store values are correct
- Spring Boot restarted and picked up new config
- `tinyurl_appuser` grants were applied before the app started

---

## What This Protects Against

| Attack scenario | Before (master user) | After (app user) |
|---|---|---|
| SQL injection in URL input | Attacker can `DROP TABLE`, wipe DB | Attacker can only read/write rows — schema intact |
| Compromised dependency (supply chain) | Full DB access | DML only, no DDL |
| App misconfiguration runs bad migration | Could destroy schema | Cannot — no DDL permissions |
| Attacker reads app credentials from EC2 | Gets superuser access | Gets DML-only access |

---

## Summary

The master user (`tinyurl`) keeps full DDL privileges and is only used by Flyway at startup.
The app user (`tinyurl_appuser`) has DML-only access to exactly the tables and sequences the
application needs. An attacker who steals the app's DB credentials cannot structurally
damage the database.
