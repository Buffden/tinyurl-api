# Flyway Checksum and Migration Lifecycle

This note explains Flyway checksum behavior and migration lifecycle in a generic, reusable way.

---

## 1) What is a Flyway checksum?

A Flyway checksum is an integer hash calculated from the SQL content of a migration file.

- When Flyway applies a versioned migration (for example, `V1__init_schema.sql`) it stores metadata in `flyway_schema_history`.
- The metadata includes migration version, script name, execution state, and checksum.
- On later runs, Flyway recalculates checksum from the current migration file and compares it with the stored checksum.

If stored checksum does not match current checksum, Flyway validation fails.

Why this matters:

- Prevents schema drift across environments.
- Detects accidental edits to already-applied migrations.
- Preserves reproducibility and auditability of database evolution.

---

## 2) Migration concept (versioned history)

Flyway migrations are an ordered, append-only history of schema changes.

- `V1__...` is the first baseline schema migration.
- `V2__...`, `V3__...` and later files represent incremental changes.

Core rule:

- Treat applied versioned migrations as immutable.
- Add a new versioned migration for every schema change.

Examples:

- Need a new column: add `V2__add_new_column.sql`.
- Need a new index: add `V3__add_index.sql`.

---

## 3) Typical migration lifecycle stages

### Stage A: Initial bootstrap (fresh database)

1. Database starts with no migration history.
2. Application/migration runner starts.
3. Flyway creates `flyway_schema_history`.
4. Flyway applies pending migrations in order.
5. Success entries are written to history.

Outcome: baseline schema is created and tracked.

### Stage B: Normal startup (existing database)

1. Database already contains `flyway_schema_history`.
2. Flyway validates applied migrations and checksums.
3. If no pending versions, startup continues.

Outcome: "schema is up to date".

### Stage C: Schema change stage

1. New migration file is added with next version.
2. Flyway validates existing history.
3. Flyway applies the new migration.
4. New history row is recorded.

Outcome: deterministic forward schema evolution.

### Stage D: Runtime data stage (ingestion/read workloads)

- Application writes and reads data using the schema created by migrations.
- Migrations do not run per request; they run at startup or deployment time.

Outcome: schema governance and data operations remain clearly separated.

### Stage E: Drift/failure stage

Trigger:

- A previously applied migration file is edited.

Result:

- Flyway validation fails before normal startup.

Common message:

- "Migration checksum mismatch for version X"

---

## 4) Handling checksum mismatch

### Production-safe approach

- Restore the original applied migration content.
- Put required schema updates into a new migration version.

### Local-development options

- Repair migration metadata if schema is correct and change was intentional.
- Recreate the local database from scratch if data can be discarded.

Example checks:

```sql
SELECT installed_rank, version, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

---

## 5) Practical best practices

1. Never rewrite applied versioned migrations in shared environments.
2. Add new forward-only migrations for every schema update.
3. Keep migrations small and reviewable.
4. Run migration validation in CI.
5. Test migrations against clean and non-clean databases.

---

## 6) Quick mental model

- Checksum: integrity guard for migration files.
- Versioned migration: immutable historical record.
- New schema requirement: new migration file.
- Checksum mismatch: safety stop, not a Flyway bug.
