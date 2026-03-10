# Database Schema

> Table definition, column rationale, indexing strategy, and constraints for TinyURL.

---

## 1) Database Choice

**PostgreSQL** — chosen for:

- ACID compliance and data durability (0% data loss tolerance).
- Mature indexing (B-tree, partial indexes, hash indexes).
- Sequence support for ID generation (ADR-001).
- Operational familiarity and ecosystem tooling.
- Native `INET` type for IP storage and `TIMESTAMPTZ` for timezone-safe timestamps.

---

## 2) Schema Definition

### Table: `url_mappings`

```sql
CREATE TABLE url_mappings (
    id            BIGSERIAL        NOT NULL,
    short_code    VARCHAR(32)      NOT NULL,
    original_url  TEXT             NOT NULL,
    url_hash      CHAR(64)         NOT NULL,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ      NULL,
    is_deleted    BOOLEAN          NOT NULL DEFAULT FALSE,
    deleted_at    TIMESTAMPTZ      NULL,
    created_ip    INET             NULL,
    user_agent    TEXT             NULL,

    CONSTRAINT pk_url_mappings PRIMARY KEY (id),
    CONSTRAINT uq_short_code UNIQUE (short_code),
    CONSTRAINT chk_original_url_length CHECK (length(original_url) <= 2048),
    CONSTRAINT chk_short_code_format   CHECK (short_code ~ '^[0-9a-zA-Z_-]{4,32}$'),
    CONSTRAINT chk_deleted_consistency CHECK (
        (is_deleted = FALSE AND deleted_at IS NULL) OR
        (is_deleted = TRUE  AND deleted_at IS NOT NULL)
    )
) WITH (fillfactor = 90);
```

### Column Details

| Column | Type | Nullable | Default | Purpose |
| --- | --- | --- | --- | --- |
| `id` | BIGSERIAL | No | Auto-increment | Internal numeric ID used for Base62 short code generation |
| `short_code` | VARCHAR(32) | No | — | The short code appearing in the URL. Unique across all rows including deleted. Supports custom aliases up to 32 chars. |
| `original_url` | TEXT | No | — | Destination URL. Max 2,048 chars enforced at both app and DB layer. |
| `url_hash` | CHAR(64) | No | — | SHA-256 hex digest of `original_url`. Used to detect and deduplicate identical URLs before insert. |
| `created_at` | TIMESTAMPTZ | No | `NOW()` | Record creation time (timezone-aware). |
| `expires_at` | TIMESTAMPTZ | Yes | NULL | Expiration time. NULL = system default of 180 days applied at app layer. |
| `is_deleted` | BOOLEAN | No | `FALSE` | Soft delete flag for abuse blocking without losing auditability. |
| `deleted_at` | TIMESTAMPTZ | Yes | NULL | When the soft delete occurred. Must be set iff `is_deleted = TRUE` (enforced by check constraint). |
| `created_ip` | INET | Yes | NULL | Client IP for abuse auditing and rate limit enforcement. |
| `user_agent` | TEXT | Yes | NULL | Client User-Agent for abuse detection. IP alone is insufficient (VPNs, IPv6 rotation). |

### Why `TIMESTAMPTZ` over `TIMESTAMP`

`TIMESTAMP` stores no timezone metadata. If the app server, DB server, or team operates across timezones, `TIMESTAMP` silently produces incorrect comparisons for expiration checks. `TIMESTAMPTZ` stores UTC internally and converts on read — always correct regardless of server locale.

### Why `url_hash` for Deduplication

Without deduplication, the same URL can be shortened 1M times producing 1M rows — wasted storage and a poor user experience. `url_hash` (SHA-256 of the original URL) lets the app do a fast lookup before insert and return the existing short URL if it already exists. `uq_url_hash` prevents race conditions when two identical URLs are submitted concurrently.

See the full query in [db-data-flow.md — Write Path](db-data-flow.md#1-write-path-create).

---

## 3) Indexing Strategy

### Index Summary

| Index | Type | Columns | Condition | Purpose |
| --- | --- | --- | --- | --- |
| `pk_url_mappings` | B-tree (PK) | `id` | — | Row identity. Implicit from `PRIMARY KEY`. |
| `uq_short_code` | B-tree (Unique) | `short_code` | — | Global uniqueness including deleted rows. Deleted codes are permanently reserved and never reissued (see UC-US-004). |
| `idx_short_code_active` | B-tree (Partial, Covering) | `short_code` INCLUDE `original_url, expires_at` | `WHERE is_deleted = FALSE` | Redirect path — index-only scan, no heap fetch needed. `uq_short_code` enforces hard uniqueness; this index exists for query speed only. |
| `uq_url_hash_active` | B-tree (Partial, Unique) | `url_hash` | `WHERE is_deleted = FALSE` | Deduplication lookup before insert. Partial so soft-deleted URLs can be re-shortened. |
| `idx_expires_at` | B-tree (Partial) | `expires_at` | `WHERE expires_at IS NOT NULL` | Cleanup job scan only — excludes permanent links, keeping the index compact. Not on any hot path. |

### Indexes to Create Explicitly

`pk_url_mappings` and `uq_short_code` are created implicitly by the `CREATE TABLE` DDL above. The three partial indexes must be created separately:

```sql
-- Covering index: satisfies the redirect query with an index-only scan (no heap fetch)
CREATE INDEX idx_short_code_active
    ON url_mappings (short_code)
    INCLUDE (original_url, expires_at)
    WHERE is_deleted = FALSE;

-- Partial unique index: allows soft-deleted URLs to be re-shortened
CREATE UNIQUE INDEX uq_url_hash_active
    ON url_mappings (url_hash)
    WHERE is_deleted = FALSE;

-- Cleanup job scan: excludes permanent links, keeps the index compact
CREATE INDEX idx_expires_at
    ON url_mappings (expires_at)
    WHERE expires_at IS NOT NULL;
```

---

## 4) Check Constraint Summary

All constraints are DB-enforced — the app layer validates first, but the DB is the last line of defence:

| Constraint | Rule |
| --- | --- |
| `uq_short_code` | `short_code` globally unique (including deleted rows) |
| `chk_original_url_length` | `original_url` ≤ 2,048 characters |
| `chk_short_code_format` | `short_code` matches `^[0-9a-zA-Z_-]{4,32}$` |
| `chk_deleted_consistency` | `deleted_at` is set iff `is_deleted = TRUE` |
