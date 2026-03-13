# Phase 7: Cleanup and Archival (v2)

## Objective

Implement scheduled expiration cleanup and archival support without affecting serving traffic.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)
- [Phase 4 Cache Layer (v2)](PHASE_4_CACHE_LAYER_V2.md)

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
