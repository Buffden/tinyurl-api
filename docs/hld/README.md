# High-Level Design

> Architecture and database design documentation for TinyURL (`go.buffden.com`).
> Single-region deployment, evolving from v1 (DB-only) → v2 (Cache + Abuse Safety + Reliability).

Runtime model note: Docker Compose is the backend orchestrator for local and single-host EC2 runtime; frontend delivery is handled separately via CloudFront + S3.

---

## Contents

### System Design

| File | What's inside |
| --- | --- |
| [system-design/system-design.md](system-design/system-design.md) | Component architecture, read/write data flow, failure domains, scaling strategy, diagram references |

### Database Design

| File | What's inside |
| --- | --- |
| [database/README.md](database/README.md) | Versioned database design overview and links to v1 and v2 |
| [database/v1/database-design.md](database/v1/database-design.md) | v1 database quick reference and schema set |
| [database/v2/database-design.md](database/v2/database-design.md) | v2 database quick reference and schema set |

---

## Where to Start

- **Understanding the overall system** → [system-design.md](system-design/system-design.md)
- **Understanding the version split first** → [database/README.md](database/README.md)
- **Understanding the v1 table structure** → [database/v1/db-schema.md](database/v1/db-schema.md)
- **Understanding the v2 table structure** → [database/v2/db-schema.md](database/v2/db-schema.md)
- **Understanding v2 operational concerns** → [database/v2/db-operations.md](database/v2/db-operations.md)

---

## Related

- [Requirements](../requirements/README.md)
- [Architecture Baseline & ADRs](../architecture/00-baseline/README.md)
- [Use Cases](../use-cases/)
