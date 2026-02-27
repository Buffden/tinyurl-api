# Database Design

> Schema definition, indexing strategy, and storage estimation for TinyURL.

---

## 1) Database Choice

**PostgreSQL** — chosen for:

- ACID compliance and data durability (0% data loss tolerance).
- Mature indexing (B-tree, partial indexes).
- Sequence support for ID generation (ADR-001).
- Operational familiarity and ecosystem tooling.

### Durability Guarantee

The NFR requires **0% data loss tolerance** — losing a mapping breaks a published link permanently with no recovery path.

PostgreSQL's default `synchronous_commit` setting must remain `on`:

```sql
SET synchronous_commit = on;
```

- **What it does**: Forces PostgreSQL to wait for the WAL record to be flushed to disk before acknowledging a `COMMIT` to the client. Without this, the DB can confirm a write that has not yet reached durable storage, creating a window where a crash loses committed transactions.
- **Why it matters here**: Every `INSERT` on the create path is a permanent, user-visible mapping. An acknowledged short URL that silently disappears on crash violates the 0% data loss NFR.
- **Performance trade-off**: `synchronous_commit = on` adds ~1–10 ms per write (one WAL fsync). At 5–10 create QPS this is negligible and is not on the critical path (the redirect path is read-only and unaffected).
- **Do not set `synchronous_commit = off`**: This trades durability for write throughput — appropriate for metrics or log tables, not for the URL mapping table.

---

## 2) Schema Definition

### Table: `url_mappings`

```sql
CREATE TABLE url_mappings (
    id            BIGSERIAL       PRIMARY KEY,
    short_code    VARCHAR(32)     NOT NULL,
    original_url  TEXT            NOT NULL,
    created_at    TIMESTAMP       NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP       NULL,
    is_deleted    BOOLEAN         NOT NULL DEFAULT FALSE,
    deleted_at    TIMESTAMP       NULL,
    created_ip    INET            NULL,

    CONSTRAINT uq_short_code UNIQUE (short_code)
);
```

### Column Details

| Column | Type | Nullable | Default | Purpose |
| --- | --- | --- | --- | --- |
| `id` | BIGSERIAL | No | Auto-increment | Internal numeric ID for Base62 encoding |
| `short_code` | VARCHAR(32) | No | — | The short code appearing in the URL. PK for lookups. Supports custom aliases (v2, up to 32 chars). |
| `original_url` | TEXT | No | — | The destination URL (max 2,048 chars enforced at app layer) |
| `created_at` | TIMESTAMP | No | `NOW()` | Record creation time |
| `expires_at` | TIMESTAMP | Yes | NULL | Expiration time. NULL = system default (180 days applied at app layer) |
| `is_deleted` | BOOLEAN | No | `FALSE` | Soft delete flag (v2; included in v1 schema for forward compatibility) |
| `deleted_at` | TIMESTAMP | Yes | NULL | When the soft delete occurred |
| `created_ip` | INET | Yes | NULL | Client IP for abuse auditing (v2 operational field) |

---

## 3) Indexing Strategy

### Primary Index

```sql
-- Implicit from PRIMARY KEY on id
```

### Namespace Integrity Constraint

```sql
-- Defined inline in CREATE TABLE (see schema above):
-- CONSTRAINT uq_short_code UNIQUE (short_code)
```

- **Purpose**: Enforces uniqueness across ALL rows — including soft-deleted ones.
- **Why a named constraint over a raw index**: The constraint is tied to the table definition, making schema intent explicit. Integrity is DB-enforced, not index-enforced. PostgreSQL manages the underlying B-tree index implicitly.
- **Why it covers deleted rows**: Deleted aliases are permanently reserved (namespace reservation — see UC-US-004). The same `short_code` can never be reissued, even after soft delete.

### Redirect Path Index (Active Rows Only, v2)

```sql
CREATE UNIQUE INDEX idx_short_code_active
    ON url_mappings (short_code)
    WHERE is_deleted = FALSE;
```

- **Purpose**: Optimises the redirect query path. The planner uses this partial index when the query includes `AND is_deleted = FALSE`, skipping the deleted-row portion of the heap entirely.
- **Why UNIQUE**: Ensures no two active rows share a `short_code`. Redundant with `uq_short_code` for hard uniqueness, but allows PostgreSQL to satisfy insert uniqueness checks using only the partial index when `is_deleted = FALSE`.

### Expiration Index

```sql
CREATE INDEX idx_expires_at ON url_mappings (expires_at)
    WHERE expires_at IS NOT NULL;
```

- **Purpose**: Supports cleanup jobs that scan for expired records.
- **Partial index**: Only indexes rows with a non-null expiry, keeping the index compact.

---

## 4) Data Flow

### Write (Create)

1. App calls `nextval('url_mappings_id_seq')` to get the next ID.
2. App encodes the ID to Base62 to produce `short_code`.
3. App inserts the row with `short_code`, `original_url`, `created_at`, and `expires_at`.

### Read (Redirect)

1. App queries:

   ```sql
   SELECT original_url, expires_at
   FROM url_mappings
   WHERE short_code = $1
     AND is_deleted = FALSE;
   ```

   In v2 this query uses `idx_short_code_active` (partial index on active rows), skipping deleted rows at the index level.

2. App checks `expires_at` in application logic.
3. **If found and not expired**: redirect (301 or 302 per ADR-003).
4. **If found but expired**: return `410 Gone`.
5. **If not found** (code unknown or deleted): return `410 Gone` for deleted codes (secondary check if needed), `404 Not Found` for unknown codes.

> **Note on deleted vs. unknown**: The partial-index query cannot distinguish "never existed" from "soft-deleted". If 404 vs. 410 precision is required for deleted codes, a secondary `SELECT 1 FROM url_mappings WHERE short_code = $1 AND is_deleted = TRUE` can be issued on miss. This is rare and adds negligible overhead since soft deletes are infrequent.

---

## 5) Storage Estimation

### Per-Row Size

| Field | Estimated Size |
| --- | --- |
| `id` (BIGINT) | 8 bytes |
| `short_code` (avg 7 chars) | ~10 bytes |
| `original_url` (avg 100 chars) | ~104 bytes |
| `created_at` (TIMESTAMP) | 8 bytes |
| `expires_at` (TIMESTAMP) | 8 bytes |
| `is_deleted` (BOOLEAN) | 1 byte |
| `deleted_at` (TIMESTAMP) | 8 bytes |
| `created_ip` (INET) | 7 bytes (IPv4) |
| Row overhead | ~23 bytes |
| **Total per row** | **~177 bytes** |

### Projected Storage

| Timeframe | Rows | Raw Data | With Indexes (~2x) |
| --- | --- | --- | --- |
| Year 1 | 1 million | ~170 MB | ~340 MB |
| Year 3 | 10 million | ~1.7 GB | ~3.4 GB |
| Year 5 | 20 million | ~3.4 GB | ~6.8 GB |

**Conclusion**: Comfortably fits in a single PostgreSQL instance. No sharding needed at this scale.

---

## 6) Expiration, Cleanup, and Operational Tuning

### v1 Strategy

- **No cleanup job**: Expired records remain in the table. Filtered on read.
- **Acceptable because**: Table size is small (< 5 GB at year 3).
- **Autovacuum note**: As expired rows accumulate in v1, autovacuum pressure increases. Dead tuple bloat from future soft-delete updates (v2) may require tuning `autovacuum_vacuum_scale_factor` and `autovacuum_vacuum_cost_delay` for this table specifically. Monitor bloat with `pg_stat_user_tables.n_dead_tup`.

### v2 Strategy

- **Periodic cleanup job**: Archive or hard-delete expired records older than a retention window (e.g., 30 days past expiry).
- **Benefits**: Reduces table bloat, reclaims index space, lowers vacuum pressure.
- **Implementation**: Batch `DELETE` or `INSERT INTO archive ... SELECT ... DELETE` with row limits to avoid long locks.

### Fillfactor

```sql
ALTER TABLE url_mappings SET (fillfactor = 90);
```

- **Why**: At a 99:1 read/write ratio this table is append-heavy, but soft deletes in v2 trigger in-place updates (HOT updates for `is_deleted`, `deleted_at`). A `fillfactor` of 90 reserves 10% of each page for updates, reducing page splits and keeping HOT update chains short.
- **Effect**: Minor write overhead in exchange for reduced index bloat during soft-delete operations.

---

## 7) Migration Path

- v1 schema includes `is_deleted` and `deleted_at` columns (defaulted, unused) for forward compatibility.
- v2 adds `created_ip` column via `ALTER TABLE ADD COLUMN` (non-blocking in PostgreSQL for nullable columns).
- Future versions may add columns for analytics (`click_count`, `last_accessed_at`) without schema-breaking changes.
