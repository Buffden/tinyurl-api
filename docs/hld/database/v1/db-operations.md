# Database Operations (v1)

> ID generation, connection pooling, and operational guidance for the v1 baseline.

---

## 1) ID Generation and Write Contention

The v1 approach uses `BIGSERIAL` (a single Postgres sequence) for ID generation, which is then Base62-encoded to produce `short_code`. At the expected v1 write rate, this is simple and sufficient.

### Mitigation Options (if v1 create QPS grows unexpectedly)

| Option | How | When to use |
| --- | --- | --- |
| **Sequence cache** | `ALTER SEQUENCE url_mappings_id_seq CACHE 100;` — each connection pre-allocates 100 IDs locally | First line of defence if create traffic grows |
| **Pre-allocated ID ranges** | Each app instance fetches a range (e.g., 10,000 IDs) from the sequence on startup and issues IDs locally until exhausted | Only if sustained create QPS becomes high |
| **Snowflake-style IDs** | `timestamp (ms) + instance_id + local_counter` — fully removes DB sequence dependency | Out of scope for v1 |

For v1, the default sequence is adequate. `CACHE 100` is a safe tuning lever if load testing justifies it.

```sql
ALTER SEQUENCE url_mappings_id_seq CACHE 100;
```

---

## 2) Connection Management (PgBouncer)

In v1 there is no cache tier, so the database directly serves redirect lookups. PostgreSQL still has a practical connection limit, and connection pooling becomes useful if load testing shows concurrency pressure.

**PgBouncer** is recommended if the deployed workload approaches the v1 peak redirect target:

| Setting | Value | Reason |
| --- | --- | --- |
| Pool mode | `transaction` | URL shortener queries are short, single-statement transactions |
| `max_client_conn` | 500–1000 | Based on app concurrency and instance count |
| `default_pool_size` | 20–40 | Tune using load tests and `pg_stat_activity` |
| `server_idle_timeout` | 60s | Release idle server connections promptly |

If PgBouncer is introduced, the app should connect to PgBouncer rather than directly to Postgres. Postgres `max_connections` can remain conservative.

---

## 3) Autovacuum and Maintenance

The v1 table has a simple write-once/read-many pattern. It does not yet include soft deletes or batch cleanup, so the default PostgreSQL autovacuum settings are usually sufficient.

- Monitor table growth and dead tuples during load testing.
- Defer per-table autovacuum tuning until v2 introduces soft deletes and cleanup jobs.

Useful monitoring query:

```sql
SELECT n_dead_tup, n_live_tup, last_vacuum, last_autovacuum
FROM pg_stat_user_tables
WHERE relname = 'url_mappings';
```

---

## 4) Expiration and Cleanup

- No cleanup job. Expired records remain; filtered on read via `expires_at` check in app logic.
- Acceptable because table size is small at the projected v1 scale.

---

## 5) Future Evolution

v2 introduces the first meaningful schema evolution items:

- `url_hash` for lookup and analysis
- `is_deleted` and `deleted_at` for soft delete
- `created_ip` and `user_agent` for abuse investigation
- Additional indexes and per-table autovacuum tuning

Partitioning remains a future concern beyond v2.
