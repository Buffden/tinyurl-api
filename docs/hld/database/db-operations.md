# Database Operations

> ID generation, connection pooling, autovacuum tuning, expiration cleanup, and partitioning strategy.

---

## 1) ID Generation and Write Contention

The current approach uses `BIGSERIAL` (a single Postgres sequence) for ID generation, which is then Base62-encoded to produce `short_code`. This works but has a **write contention ceiling**: a single Postgres sequence can become a bottleneck at high create QPS because all app instances contend for the same sequence lock.

### Mitigation Options (in order of complexity)

| Option | How | When to use |
| --- | --- | --- |
| **Sequence cache** | `ALTER SEQUENCE url_mappings_id_seq CACHE 100;` — each connection pre-allocates 100 IDs locally | First line of defence; handles moderate write QPS |
| **Pre-allocated ID ranges** | Each app instance fetches a range (e.g., 10,000 IDs) from the sequence on startup and issues IDs locally until exhausted | High create QPS (> 500/sec) |
| **Snowflake-style IDs** | `timestamp (ms) + instance_id + local_counter` — fully removes DB sequence dependency | Very high create QPS or multi-region |

For v2 (single-region, low create QPS), **sequence cache of 100** is sufficient. Revisit if create QPS exceeds 200/sec.

```sql
ALTER SEQUENCE url_mappings_id_seq CACHE 100;
```

---

## 2) Connection Management (PgBouncer)

At 5K redirect QPS with a 90% cache hit ratio, ~500 QPS hits the DB. Each query opens or reuses a connection. PostgreSQL has a hard connection limit (typically 100–200 before memory pressure degrades performance). Without pooling, connection exhaustion under load is a real failure mode.

**PgBouncer** must sit between the app and PostgreSQL:

| Setting | Value | Reason |
| --- | --- | --- |
| Pool mode | `transaction` | URL shortener queries are short, single-statement transactions — transaction pooling gives maximum reuse |
| `max_client_conn` | 1000 | App instances × goroutines/threads per instance |
| `default_pool_size` | 20–40 | Tune based on `pg_stat_activity` under load |
| `server_idle_timeout` | 60s | Release idle server connections promptly |

App connects to PgBouncer, not Postgres directly. Postgres `max_connections` can be kept at 100.

---

## 3) Autovacuum Tuning

The `url_mappings` table has two update patterns that generate dead tuples:

- Soft deletes (`UPDATE SET is_deleted = TRUE, deleted_at = NOW()`)
- Cleanup job hard deletes (`DELETE FROM url_mappings WHERE ...`)

The default autovacuum thresholds (20% of table) are too coarse for large tables. Tune per-table:

```sql
ALTER TABLE url_mappings SET (
    autovacuum_vacuum_scale_factor    = 0.01,  -- vacuum after 1% dead tuples (not 20%)
    autovacuum_analyze_scale_factor   = 0.005, -- analyze after 0.5% changes
    autovacuum_vacuum_cost_delay      = 2      -- less aggressive I/O throttling than default 20ms
);
```

Monitor with:

```sql
SELECT n_dead_tup, n_live_tup, last_vacuum, last_autovacuum
FROM pg_stat_user_tables
WHERE relname = 'url_mappings';
```

---

## 4) Expiration and Cleanup

### v1 Strategy

- No cleanup job. Expired records remain; filtered on read via `expires_at` check in app logic.
- Acceptable because table size is small (< 5 GB at year 3).

### v2 Strategy

Periodic cleanup job (run off-peak, e.g., nightly):

```sql
-- Batch hard-delete rows expired more than 30 days ago
WITH to_delete AS (
    SELECT id FROM url_mappings
    WHERE expires_at < NOW() - INTERVAL '30 days'
    LIMIT 1000  -- batch limit to avoid long locks
)
DELETE FROM url_mappings
WHERE id IN (SELECT id FROM to_delete);
```

- Use `LIMIT`-based batching to avoid long-running deletes that block reads.
- Run in a loop with a short sleep between batches.
- Consider archiving to a cold table before delete if audit retention is required.

---

## 5) Partitioning (Future — v3+)

At 20M rows (year 5), a single table is still manageable. But if growth accelerates or cleanup job performance degrades, range partition by `created_at`:

```sql
CREATE TABLE url_mappings (
    ...
) PARTITION BY RANGE (created_at);

CREATE TABLE url_mappings_2026 PARTITION OF url_mappings
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

CREATE TABLE url_mappings_2027 PARTITION OF url_mappings
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
```

Benefits:

- Cleanup jobs drop entire partitions (instant) instead of row-by-row deletes.
- Vacuum and autovacuum operate per partition.
- Partition pruning improves query performance for time-bounded scans.

**Do not add this prematurely.** Introduce only when cleanup job performance or table bloat becomes measurable. When the time comes, use `pg_partman` for online migration to avoid a maintenance window.
