# RDS Database Size Analysis — tinyurl-prod

**Created:** 2026-04-10
**Instance:** `tinyurl-prod` — db.t4g.micro — PostgreSQL 17.4 — us-east-1a
**Status:** Active (running Mon–Fri 7 AM – 11 PM CDT, stopped overnight/weekends)

---

## TL;DR

| | Value |
| --- | --- |
| Allocated storage | 20 GiB gp3 |
| Auto-scale threshold | 25 GiB |
| Alert status | WARNING — emitting "approaching threshold" every 2 hours since Apr 3 |
| Backup window | 09:14–09:44 UTC ← **conflicts with nightly stop at 04:00 UTC** |
| Backup retention | 7 days |
| Automated snapshots | 8 (all 20 GB each, incremental) |
| Manual snapshots | **0** |
| Estimated monthly storage cost | ~$2.30/month (20 GB × $0.115/GB) |

**Immediate actions needed:**
1. Fix the backup window — it fires during the stopped period
2. Investigate what is consuming the 20 GB before auto-scale triggers
3. Take a manual snapshot before any RDS deletion (Option B)

---

## Storage Alert Details

RDS has been emitting the following event every 2 hours since at least 2026-04-03:

```
Storage size 20 GiB is approaching the maximum storage threshold 25 GiB.
Increase the maximum storage threshold.
```

This is a pre-scale warning. AWS will automatically expand the volume when storage usage
approaches the allocated size. Once expanded to 25 GiB, the allocation stays at 25 GiB
even if data is deleted — RDS does not shrink storage automatically.

**Cost impact of auto-scale triggering:**

| | Before | After auto-scale |
| --- | --- | --- |
| Allocated storage | 20 GiB | 25 GiB |
| Storage cost | $2.30/month | $2.88/month |
| Monthly delta | — | +$0.58/month |

Minor cost impact, but the real concern is that **you are running close to capacity on a
personal project**. If the DB keeps growing it will need another scale event.

---

## Backup Window Conflict with Night Scheduler

**This is the primary operational issue to fix.**

### Current configuration

| Setting | Value | Problem? |
| --- | --- | --- |
| Backup window | `09:14–09:44 UTC` | Yes — falls in stopped period |
| Scheduler stop | `04:00 UTC` daily | RDS stopped before backup window |
| Scheduler start | `12:00 UTC` Mon–Fri | RDS restarts after backup window |
| Weekend state | Stopped Fri 04:00 UTC → Mon 12:00 UTC | Backup pending ~56 hours |

The backup window (09:14 UTC) falls between the stop (04:00 UTC) and start (12:00 UTC).
Every day, the backup is scheduled during the stopped period.

### What AWS actually does

**On weekdays:** AWS defers the backup until the instance is running. Backups shift to the
next available window during the active period. Confirmed: after the scheduler was deployed,
weekday backups now happen at ~21:48 UTC (1:48 PM CDT) instead of 09:19 UTC.

**On weekends:** The instance stays stopped from Fri 04:00 UTC to Mon 12:00 UTC (~56 hours).
AWS will auto-restart the RDS to take the backup rather than miss the backup for more than
~12–18 hours.

### Observed weekend auto-restart (2026-04-04, Saturday)

```
2026-04-04T04:08:39 UTC  — DB instance stopped (by night scheduler)
2026-04-04T09:14 UTC     — backup window fires (deferred — instance stopped)
...
2026-04-04T21:43:29 UTC  — "Recovery of the DB instance has started" (AWS auto-restart)
2026-04-04T21:43:59 UTC  — "Recovery of the DB instance has started" (retry)
2026-04-04T21:44:29 UTC  — "Recovery of the DB instance has started" (retry)
2026-04-04T21:46:12 UTC  — DB instance restarted
2026-04-04T21:47:16 UTC  — DB instance started
2026-04-04T21:48:20 UTC  — Backing up DB instance
2026-04-04T21:50:30 UTC  — Finished DB Instance backup
2026-04-04T22:19:05 UTC  — DB instance stopped (by second stop attempt or manual)
```

AWS waited ~17.5 hours after the backup window before auto-restarting. The backup took
~2 minutes, then the instance was stopped again. This adds ~36 minutes of RDS runtime on
weekends with no value.

### Fix

Move the backup window to the middle of the active running period so it never conflicts:

```bash
aws rds modify-db-instance \
  --db-instance-identifier tinyurl-prod \
  --preferred-backup-window "18:00-18:30" \
  --apply-immediately
```

`18:00–18:30 UTC` = 1:00–1:30 PM CDT. This is the midpoint of the Mon–Fri running window
(12:00–04:00 UTC). Backups will reliably run at 1 PM CDT on weekdays.

On **weekends**, the instance is stopped the whole time, so AWS will still auto-restart it
once to take a backup. This is unavoidable without disabling automated backups entirely.
The auto-restart will happen in the afternoon CDT (matching the backup window schedule) and
only lasts ~5 minutes.

---

## Automated Snapshot Inventory

8 automated snapshots exist as of 2026-04-10. All are 20 GB each (nominal allocation;
actual incremental disk usage is much smaller after the first snapshot).

| Snapshot ID | Created (UTC) | Size | Type |
| --- | --- | --- | --- |
| `rds:tinyurl-prod-2026-03-29-09-19` | 2026-03-29 09:19 | 20 GB | automated |
| `rds:tinyurl-prod-2026-03-30-09-19` | 2026-03-30 09:19 | 20 GB | automated |
| `rds:tinyurl-prod-2026-03-31-09-19` | 2026-03-31 09:19 | 20 GB | automated |
| `rds:tinyurl-prod-2026-04-01-09-19` | 2026-04-01 09:19 | 20 GB | automated |
| `rds:tinyurl-prod-2026-04-02-09-19` | 2026-04-02 09:19 | 20 GB | automated |
| `rds:tinyurl-prod-2026-04-04-21-48` | 2026-04-04 21:48 | 20 GB | automated |
| `rds:tinyurl-prod-2026-04-06-21-48` | 2026-04-06 21:48 | 20 GB | automated |
| `rds:tinyurl-prod-2026-04-08-21-48` | 2026-04-08 21:48 | 20 GB | automated |

**Note on the Apr 4 and later backups:** After the scheduler was deployed (Apr 3), the
backup window conflict caused the backup to be deferred and taken at 21:48 UTC instead of
09:19 UTC. The backup time shift from morning to evening is visible in the snapshot IDs.

### Snapshot storage cost

AWS includes automated backup storage **equal to 100% of your DB's allocated storage** for
free. For a 20 GB DB, the first 20 GB of snapshot storage is free.

Automated snapshots are block-level incremental after the first full snapshot, so 8 snapshots
of a 20 GB DB does not cost 160 GB — it costs 20 GB (initial) + incremental changes only.
For a low-write portfolio DB, the incremental changes are likely under 1–2 GB total.

**Current estimated snapshot storage cost: ~$0/month** (within free tier).

### No manual snapshots exist

Zero manual snapshots. This is a risk: if the RDS instance is deleted (Option B), automated
snapshots are deleted with it unless a final snapshot is explicitly requested.

---

## DB Size Investigation

To understand what is consuming the 20 GB, connect via SSM + psql and run:

```bash
# Start SSM session to the tinyurl EC2 instance
aws ssm start-session --target i-0bfdc622bac423b96

# Inside the session — connect to RDS (get endpoint from SSM param)
RDS_HOST=$(aws ssm get-parameter --name /tinyurl/prod/spring/datasource/url --query Parameter.Value --output text | grep -oP '(?<=//)[^:/]+')
psql -h $RDS_HOST -U <db-user> -d <db-name>
```

Once connected, run these queries:

```sql
-- Total database size
SELECT pg_size_pretty(pg_database_size(current_database())) AS db_size;

-- Size by table (top 10)
SELECT
  schemaname,
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS total_size,
  pg_size_pretty(pg_relation_size(schemaname||'.'||tablename)) AS table_size,
  pg_size_pretty(pg_indexes_size(schemaname||'.'||tablename)) AS index_size
FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC
LIMIT 10;

-- Bloat check — tables with dead tuples
SELECT
  relname AS table,
  n_live_tup AS live_rows,
  n_dead_tup AS dead_rows,
  ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
  last_autovacuum,
  last_autoanalyze
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 10;

-- WAL and transaction log usage
SELECT
  pg_size_pretty(sum(size)) AS wal_size
FROM pg_ls_waldir();

-- Check for large TOAST values (overflow storage for large text/bytea)
SELECT
  c.relname AS table,
  t.relname AS toast_table,
  pg_size_pretty(pg_total_relation_size(t.oid)) AS toast_size
FROM pg_class c
JOIN pg_class t ON c.reltoastrelid = t.oid
WHERE pg_total_relation_size(t.oid) > 1024 * 1024
ORDER BY pg_total_relation_size(t.oid) DESC;
```

### Common causes in a Spring Boot + Flyway DB

| Cause | How to check | How to fix |
| --- | --- | --- |
| URL table has millions of rows | `SELECT count(*) FROM urls` | Archive old/unused rows |
| Dead tuples (table bloat) | Dead rows query above | `VACUUM FULL <table>` |
| Index bloat | `pg_indexes_size` in size query | `REINDEX TABLE <table>` |
| Flyway migration history | `SELECT count(*) FROM flyway_schema_history` | Delete old entries if not needed |
| WAL accumulation | `pg_ls_waldir()` query | Usually self-cleaning; check if replication slots stuck |
| pg_wal directory | N/A if no replicas | No replication slots = no WAL retention issue |

---

## Recommended Action Plan

### Immediate (this week)

1. **Fix backup window** — prevents weekend auto-restarts
   ```bash
   aws rds modify-db-instance \
     --db-instance-identifier tinyurl-prod \
     --preferred-backup-window "18:00-18:30" \
     --apply-immediately
   ```

2. **Reduce backup retention to 1 day** — no need for 7-day history on a personal project
   ```bash
   aws rds modify-db-instance \
     --db-instance-identifier tinyurl-prod \
     --backup-retention-period 1 \
     --apply-immediately
   ```

3. **Investigate DB size** — use SSM + psql queries above to find what is using the 20 GB

### Before option B (RDS deletion)

4. **Take a final manual snapshot**
   ```bash
   aws rds create-db-snapshot \
     --db-instance-identifier tinyurl-prod \
     --db-snapshot-identifier tinyurl-prod-final-before-docker-migration

   aws rds wait db-snapshot-completed \
     --db-snapshot-identifier tinyurl-prod-final-before-docker-migration
   ```

5. **Dump the DB to a file as a second safety net**
   ```bash
   pg_dump -h <rds-endpoint> -U <user> -d <dbname> \
     -F c -f ~/tinyurl_prod_$(date +%Y%m%d).dump
   ```

6. **Delete instance with skip-final-snapshot** (since you already have both above)
   ```bash
   aws rds delete-db-instance \
     --db-instance-identifier tinyurl-prod \
     --skip-final-snapshot
   ```

7. **After Docker Postgres is stable for 2 weeks**, delete the manual snapshot:
   ```bash
   aws rds delete-db-snapshot \
     --db-snapshot-identifier tinyurl-prod-final-before-docker-migration
   ```

---

## Storage Cost Reference

| Item | Rate | Current cost |
| --- | --- | --- |
| gp3 storage | $0.115/GiB/month | $2.30/month (20 GB) |
| gp3 IOPS above 3000 baseline | $0.02/IOPS/month | $0 (using baseline) |
| gp3 throughput above 125 MB/s | $0.04/MiB/month | $0 (using baseline) |
| Automated snapshot storage | Free up to DB size | $0 (within 20 GB free) |
| Manual snapshot storage | $0.095/GiB/month | $0 (none exist) |
| **Total storage** | | **~$2.30/month** |

If RDS is deleted (Option B), all of the above goes to $0 and is replaced by the existing
20 GB EBS volume on the EC2 (already paid for at $1.00/month for the 20 GB gp2 volume).
