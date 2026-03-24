# Database Schema (v1)

> Table definition, column rationale, indexing strategy, and constraints for the v1 baseline.

---

## 1) Database Choice

**PostgreSQL** — chosen for:

- ACID compliance and data durability (0% data loss tolerance).
- Mature indexing and predictable performance at the projected v1 scale.
- Sequence support for ID generation (ADR-001).
- Operational familiarity and ecosystem tooling.
- Native `TIMESTAMPTZ` for timezone-safe timestamps.

---

## 2) Schema Definition

### Table: `url_mappings`

```sql
CREATE TABLE url_mappings (
    id            BIGSERIAL        NOT NULL,
    short_code    VARCHAR(32)      NOT NULL,
    original_url  TEXT             NOT NULL,
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ      NULL,

    CONSTRAINT pk_url_mappings PRIMARY KEY (id),
    CONSTRAINT uq_short_code UNIQUE (short_code),
    CONSTRAINT chk_original_url_length CHECK (length(original_url) <= 2048),
    CONSTRAINT chk_short_code_format   CHECK (short_code ~ '^[0-9a-zA-Z_-]{4,32}$')
) WITH (fillfactor = 90);
```

### Column Details

| Column | Type | Nullable | Default | Purpose |
| --- | --- | --- | --- | --- |
| `id` | BIGSERIAL | No | Auto-increment | Internal numeric ID used for Base62 short code generation |
| `short_code` | VARCHAR(32) | No | — | The short code appearing in the URL. Unique across all rows. |
| `original_url` | TEXT | No | — | Destination URL. Max 2,048 chars enforced at both app and DB layer. |
| `created_at` | TIMESTAMPTZ | No | `NOW()` | Record creation time (timezone-aware). |
| `expires_at` | TIMESTAMPTZ | Yes | NULL | Expiration time. NULL = system default of 180 days applied at app layer. |

### Why `TIMESTAMPTZ` over `TIMESTAMP`

`TIMESTAMP` stores no timezone metadata. If the app server, DB server, or team operates across timezones, `TIMESTAMP` silently produces incorrect comparisons for expiration checks. `TIMESTAMPTZ` stores UTC internally and converts on read — always correct regardless of server locale.

---

## 3) Indexing Strategy

### Index Summary

| Index | Type | Columns | Condition | Purpose |
| --- | --- | --- | --- | --- |
| `pk_url_mappings` | B-tree (PK) | `id` | — | Row identity. Implicit from `PRIMARY KEY`. |
| `uq_short_code` | B-tree (Unique) | `short_code` | — | Redirect lookup path and short-code uniqueness. Sufficient for v1 traffic. |

### Indexes to Create Explicitly

None. `pk_url_mappings` and `uq_short_code` are created implicitly by the v1 table DDL.

---

## 4) Check Constraint Summary

All constraints are DB-enforced — the app layer validates first, but the DB is the last line of defence:

| Constraint | Rule |
| --- | --- |
| `uq_short_code` | `short_code` globally unique |
| `chk_original_url_length` | `original_url` <= 2,048 characters |
| `chk_short_code_format` | `short_code` matches `^[0-9a-zA-Z_-]{4,32}$` |
