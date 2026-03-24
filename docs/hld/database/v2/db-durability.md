# Database Durability, Storage, and Migration (v2)

> Durability guarantees, backup strategy, storage projections, and schema migration path for v2.

---

## 1) Durability Guarantee

The NFR requires **0% data loss tolerance** — losing a mapping breaks a published link permanently with no recovery path. This requires two independent guarantees:

### 1a) Write Durability — `synchronous_commit = on`

```sql
SET synchronous_commit = on;
```

- Forces PostgreSQL to wait for the WAL record to be flushed to disk before acknowledging a `COMMIT`.
- Prevents committed transactions from being lost on crash.
- Adds ~1–10 ms per write (one WAL fsync). At 5–10 create QPS this is negligible and not on the critical path.
- **Do not set `synchronous_commit = off`** — appropriate for metrics/log tables, not URL mappings.

### 1b) Disaster Recovery — PITR (Point-in-Time Recovery)

`synchronous_commit` only guarantees writes survive a process crash. If the disk fails, you need WAL archiving + base backups:

| Component | Policy |
| --- | --- |
| WAL archiving | Continuous, shipped to S3 (or equivalent) |
| Base backup | Daily full backup via `pg_basebackup` |
| Retention | 30-day WAL + backup retention |
| RTO target | < 1 hour |
| RPO target | < 5 minutes (bounded by WAL shipping lag) |

**These two together satisfy the 0% data loss NFR.** `synchronous_commit` alone does not.

---

## 2) Storage Estimation

### Per-Row Size

| Field | Estimated Size |
| --- | --- |
| `id` (BIGINT) | 8 bytes |
| `short_code` (avg 7 chars) | ~10 bytes |
| `original_url` (avg 100 chars) | ~104 bytes |
| `url_hash` (CHAR 64) | 66 bytes |
| `created_at` (TIMESTAMPTZ) | 8 bytes |
| `expires_at` (TIMESTAMPTZ) | 8 bytes |
| `is_deleted` (BOOLEAN) | 1 byte |
| `deleted_at` (TIMESTAMPTZ) | 8 bytes |
| `created_ip` (INET) | 7 bytes (IPv4) |
| `user_agent` (avg 80 chars) | ~84 bytes |
| Row overhead | ~23 bytes |
| **Total per row** | **~327 bytes** |

> **TOAST note**: `original_url` values > 2KB and `user_agent` values exceeding Postgres's inline threshold (~2KB per row total) are stored in a TOAST table transparently. At avg 100-char URLs this is not a concern, but pathological 2,048-char URLs will be TOASTed, adding a secondary heap read on cache miss.

### Projected Storage

| Timeframe | Rows | Raw Data | With Indexes (~2x) |
| --- | --- | --- | --- |
| Year 1 | 1 million | ~312 MB | ~624 MB |
| Year 3 | 10 million | ~3.1 GB | ~6.2 GB |
| Year 5 | 20 million | ~6.2 GB | ~12.4 GB |

**Conclusion**: Comfortably fits in a single PostgreSQL instance through year 5. No sharding or partitioning required at this scale; add partitioning only if cleanup job or vacuum performance degrades.

---

## 3) Migration Path

| Version | Change | Method |
| --- | --- | --- |
| v1 | Initial schema with `is_deleted`, `deleted_at` (defaulted, unused) | `CREATE TABLE` |
| v2 | Add `created_ip`, `user_agent`, `url_hash` columns | `ALTER TABLE ADD COLUMN` — non-blocking in PostgreSQL for nullable/defaulted columns |
| v2 | Add `idx_url_hash` non-unique index | `CREATE INDEX CONCURRENTLY idx_url_hash ON url_mappings (url_hash)` — zero downtime |
| v2 | Add per-table autovacuum settings | `ALTER TABLE SET (...)` — online, no lock |
| v3+ | Partitioning | Requires table rebuild — plan for a maintenance window or use `pg_partman` for online migration |
