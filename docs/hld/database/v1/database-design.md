# Database Design (v1)

> Production-minimal database design for TinyURL.

---

## Files

| File | Contents |
| --- | --- |
| [db-schema.md](db-schema.md) | v1 table DDL, column rationale, indexes, check constraints |
| [db-data-flow.md](db-data-flow.md) | v1 read path and write path SQL queries |
| [db-operations.md](db-operations.md) | v1 ID generation, connection pooling, and operational guidance |
| [db-durability.md](db-durability.md) | v1 durability guarantees, storage estimate, and migration path to v2 |

---

## Quick Reference

### Technology

**PostgreSQL** with optional **PgBouncer** in front of the primary database. Single-region, single primary instance.

### Core Table

One table: `url_mappings` — maps `short_code → original_url` with creation time and optional expiry.

### Key Design Decisions

| Decision | Choice | Reason |
| --- | --- | --- |
| Timestamps | `TIMESTAMPTZ` | Timezone-safe; avoids silent expiry comparison bugs |
| URL deduplication | None | Repeated identical URLs can produce distinct short codes |
| Schema shape | Minimal single table | Keep v1 operationally simple and easy to ship |
| Namespace reservation | `uq_short_code` | Prevents short-code collisions |
| URL length cap | `CHECK (length <= 2048)` at DB layer | App layer validates first; DB is the last line of defence |
| Connection pooling | PgBouncer if load testing shows connection pressure | Useful at peak read volume, but not a schema dependency |
| Durability | `synchronous_commit = on` + PITR | Write durability + disaster recovery together satisfy 0% data loss NFR |
| Sequence contention | Default sequence is sufficient in v1 | Create QPS remains low in the baseline |
