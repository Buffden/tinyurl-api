# Database Design

> Versioned database design documentation for TinyURL.

---

## Overview

The database design is split by version to match the rest of the architecture documentation:

- **v1**: production-minimal baseline with a single `url_mappings` table and no cache-specific or abuse-control schema additions.
- **v2**: evolved schema with `url_hash`, soft delete, abuse tracking fields, and supporting indexes for higher scale and operational safety.

---

## Versions

### v1

| File | Contents |
| --- | --- |
| [v1/database-design.md](v1/database-design.md) | Quick reference and design decisions for v1 |
| [v1/db-schema.md](v1/db-schema.md) | v1 table DDL, column rationale, indexes, check constraints |
| [v1/db-data-flow.md](v1/db-data-flow.md) | v1 write path and redirect query flow |
| [v1/db-operations.md](v1/db-operations.md) | v1 ID generation, pooling, and operational guidance |
| [v1/db-durability.md](v1/db-durability.md) | v1 durability guarantees, storage estimate, and migration path to v2 |
| [v1/db-design.excalidraw](v1/db-design.excalidraw) | v1 database design diagram |
| [v1/er-db.excalidraw](v1/er-db.excalidraw) | v1 ER-style table diagram |

### v2

| File | Contents |
| --- | --- |
| [v2/database-design.md](v2/database-design.md) | Quick reference and design decisions for v2 |
| [v2/db-schema.md](v2/db-schema.md) | v2 table DDL, column rationale, indexes, check constraints |
| [v2/db-data-flow.md](v2/db-data-flow.md) | v2 read, write, and soft-delete query flow |
| [v2/db-operations.md](v2/db-operations.md) | v2 ID generation, pooling, autovacuum, cleanup, partitioning |
| [v2/db-durability.md](v2/db-durability.md) | v2 durability guarantees, storage estimate, and migration path |
| [v2/db-design.excalidraw](v2/db-design.excalidraw) | v2 database design diagram |
| [v2/er-db.excalidraw](v2/er-db.excalidraw) | v2 ER-style table diagram |

---

## Guidance

- Start with [v1/database-design.md](v1/database-design.md) for the minimal production baseline.
- Use [v2/database-design.md](v2/database-design.md) when reviewing the scaled design with cache-aware and abuse-control additions.
- When comparing versions, treat v2 as an evolution of v1 rather than an unrelated redesign.
