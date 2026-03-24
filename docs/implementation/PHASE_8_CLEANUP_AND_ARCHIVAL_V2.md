# Phase 8: Cleanup and Archival (v2)

## Objective

Implement scheduled expiration cleanup and archival support without affecting serving traffic.

## MVP-Only Execution Rule

- For the current timeline, implement only content before `## Optional (Post-MVP)`.
- Treat `## Optional (Post-MVP)` items as backlog and do not block release.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 5 Cache Layer (v2)](PHASE_5_CACHE_LAYER_V2.md)

## Source References

- [Use Cases v2](../use-cases/v2/)
- [HLD Database v2 Operations](../hld/database/v2/db-operations.md)
- [HLD Database v2 Durability](../hld/database/v2/db-durability.md)
- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

## In Scope

- scheduled cleanup job
- soft-delete and grace-period handling
- cache invalidation for cleaned records
- archival policy execution path

## Out of Scope

- full historical analytics platform
- cold storage query APIs

## Execution Steps

### Step 1: Cleanup scheduler baseline

References:

- [Use Case: Expire URL v2](../use-cases/v2/UC-US-003-expire-url.md)

Tasks:

- Add scheduled job with configurable interval
- Define bounded batch size and transaction strategy

### Step 2: Soft-delete lifecycle implementation

References:

- [HLD Database v2 Operations](../hld/database/v2/db-operations.md)

Tasks:

- Mark eligible records as soft-deleted after grace period
- Preserve auditability fields for operations review

### Step 3: Cache invalidation integration

References:

- [LLD Cache Module](../lld/cache-module.md)

Tasks:

- Remove or invalidate affected cache entries after cleanup
- Ensure stale redirects are not served from cache

### Step 4: Archival and safety checks

References:

- [HLD Database v2 Durability](../hld/database/v2/db-durability.md)

Tasks:

- Define archival trigger and retention policy
- Add cleanup metrics and failure alerts

## Deliverables

- periodic cleanup job in place
- soft-delete and cache invalidation behavior validated
- archival path documented and tested for safety

## Acceptance Criteria

- Cleanup does not degrade online request path stability
- Expired records are processed according to grace policy
- Failures are observable and recoverable

---

## Part 2: Implementation Deep Dive

### Job Scheduling Framework Architecture

**Framework Comparison:**

| Framework | Pros | Cons | Recommendation |
| --- | --- | --- | --- |
| **Spring @Scheduled** | Simple, no external dependencies, good for single-instance | No clustering support, basic retry logic | ✅ For v2 baseline (single instance) |
| **Quartz** | Clustering support, persistent job store, complex scheduling | Heavy, overkill for simple jobs | Use if multi-instance needed |
| **Spring Batch** | Large-scale dataset processing, skip/retry logic built-in | Heavyweight framework overhead | Future scaling beyond v2 |

**Recommended: Spring @Scheduled with Redis-based distributed locking** (prevents concurrent execution in scaled scenarios).

**Configuration:**

**Spring Boot Scheduler Configuration:**
Enable scheduling globally with `@EnableScheduling` on the main application class. Configure a dedicated thread pool for scheduled tasks in `application.yaml` under `spring.task.scheduling` with a pool size of 2 and a thread name prefix of `cleanup-`. Set the cleanup cron expression as an environment-driven property (default: `0 0 2 * * *` for 2 AM UTC daily) so it can be adjusted per environment without a code change.

---

### Soft-Delete Lifecycle Implementation

**Database Schema Enhancement:**

**Soft-Delete Entity:**
Add `deleted_at` (nullable timestamp) and `archived_at` (nullable timestamp) columns to `url_mappings` via a Flyway migration. Update the JPA entity to include these fields. A record is considered soft-deleted when `deleted_at` is set and the grace period has passed. Redirect lookups must filter out soft-deleted records (`WHERE deleted_at IS NULL`) to prevent serving expired URLs.

---

### Cleanup Job Implementation

**Main Cleanup Service with Distributed Locking:**
The cleanup service acquires a Redis-based distributed lock (e.g., via `Redisson` or a simple `SET NX PX` command) before running to prevent concurrent execution across multiple app instances. If the lock cannot be acquired, the job logs a warning and exits immediately. The job processes expired records in batches (configurable size, default 500) within a transaction, sets `deleted_at` for each, evicts their cache entries, then releases the lock. Metrics are recorded for records processed, errors, and job duration.

---

### Archive Service & Cache Invalidation

**Archive Service:**
After soft-deletion, records older than the configured retention threshold (default 30 days post-expiry) are eligible for archival. The archive step copies these rows to a separate `url_mappings_archive` table (same schema, partitioned by year) and then hard-deletes them from the primary table. This keeps the primary table lean for query performance while preserving audit history.

**Cache Invalidation Service:**
After each batch of soft-deletes, collect the affected `short_code` values and delete their corresponding Redis cache keys. Use a Redis pipeline to batch the `DEL` commands in a single round-trip. If the cache eviction fails, log a warning but do not fail the cleanup job — stale cache entries will expire naturally via their TTL.

---

### Monitoring & Observability

**Prometheus Alerts:**
Define two alert rules: one that fires `WARNING` if the cleanup job has not run successfully in the last 25 hours (missed execution), and one that fires `CRITICAL` if the cleanup job error count exceeds zero in the last run (partial failure). Track job execution via a `cleanup.job.last_success_timestamp` gauge that is updated at the end of each successful run.

---

## Optional (Post-MVP)

- Move archival to dedicated cold storage pipeline for long-retention analytics.
- Add multi-stage archival tiers and compression policies.
- Add replay/recovery tooling for archived records.
- Add cleanup orchestration via Quartz/Spring Batch if job complexity grows.

## Updated Acceptance Criteria

- ✅ Cleanup job scheduled and runs on configured cron (2 AM UTC)
- ✅ Expired URLs past grace period are soft-deleted deterministically
- ✅ Cache entries invalidated immediately after soft-delete
- ✅ Soft-deleted URLs archived after threshold to archive table
- ✅ Archive table partitioned by year for efficient queries
- ✅ Cleanup does not degrade online request latency
- ✅ Distributed lock prevents concurrent cleanup execution
- ✅ Cleanup failures trigger alerts and are recoverable
