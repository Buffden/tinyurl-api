# High-Level Design

> Architecture and database design documentation for TinyURL (`tinyurl.buffden.com`).
> Single-region deployment, evolving from v1 (DB-only) → v2 (Cache + Abuse Safety + Reliability).

---

## Contents

### System Design

| File | What's inside |
| --- | --- |
| [system-design/system-design.md](system-design/system-design.md) | Component architecture, read/write data flow, failure domains, scaling strategy, diagram references |

### Database Design

| File | What's inside |
| --- | --- |
| [database/database-design.md](database/database-design.md) | Index + key decisions quick reference |
| [database/db-schema.md](database/db-schema.md) | Table DDL, column rationale, indexes, check constraints |
| [database/db-data-flow.md](database/db-data-flow.md) | Read, write, and soft-delete SQL query paths |
| [database/db-operations.md](database/db-operations.md) | ID generation, PgBouncer, autovacuum tuning, cleanup job, partitioning |
| [database/db-durability.md](database/db-durability.md) | `synchronous_commit`, PITR backup policy, storage estimates, migration path |

---

## Where to Start

- **Understanding the overall system** → [system-design.md](system-design/system-design.md)
- **Understanding the table structure** → [db-schema.md](database/db-schema.md)
- **Understanding how queries work** → [db-data-flow.md](database/db-data-flow.md)
- **Understanding operational concerns** → [db-operations.md](database/db-operations.md)
- **Understanding durability and backup** → [db-durability.md](database/db-durability.md)

---

## Related

- [Requirements](../requirements/README.md)
- [Architecture Baseline & ADRs](../architecture/00-baseline/README.md)
- [Use Cases](../use-cases/)
