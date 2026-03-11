# Database Design (v2)

> Version 2 database design for TinyURL. Each topic is covered in a dedicated file.

---

## Files

| File | Contents |
| --- | --- |
| [db-schema.md](db-schema.md) | Database choice, table DDL, column rationale, indexing strategy, check constraints |
| [db-data-flow.md](db-data-flow.md) | Read path, write path, soft delete path — with SQL queries |
| [db-operations.md](db-operations.md) | ID generation, PgBouncer connection pooling, autovacuum tuning, expiration cleanup, partitioning |
| [db-durability.md](db-durability.md) | `synchronous_commit`, PITR backup strategy, storage estimation, migration path |

---

## Quick Reference

### Technology

**PostgreSQL** with **PgBouncer** (connection pooling). Single-region, single primary instance through v2.

### Core Table

One table: `url_mappings` — maps `short_code → original_url` with expiry, soft delete, and abuse tracking fields.

### Key Design Decisions

| Decision | Choice | Reason |
| --- | --- | --- |
| Timestamps | `TIMESTAMPTZ` | Timezone-safe; avoids silent expiry comparison bugs |
| URL hash lookup | SHA-256 hash (`url_hash`) | Speeds lookup and analysis for repeated destination URLs without preventing multiple short links |
| Soft delete | `is_deleted` + `deleted_at` | Blocks malicious links without losing auditability |
| Namespace reservation | `uq_short_code` covers deleted rows | Deleted codes are never reissued |
| URL length cap | `CHECK (length <= 2048)` at DB layer | App layer validates first; DB is the last line of defence |
| Connection pooling | PgBouncer in transaction mode | Prevents connection exhaustion at 5K redirect QPS |
| Durability | `synchronous_commit = on` + PITR | Write durability + disaster recovery together satisfy 0% data loss NFR |
| Sequence contention | Sequence cache of 100 | Sufficient for v2 create QPS; escalation path documented |
