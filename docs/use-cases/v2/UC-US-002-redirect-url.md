# UC-US-002: Redirect via Short URL — v2 Additions

> v2 changes to [v1/UC-US-002](../v1/UC-US-002-redirect-url.md). Read the v1 use case first.

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-002 |
| Version | v2 |
| Priority | Critical |
| Status | Approved |
| Related FR | FR-002, FR-004 |

---

## What Changes in v2

| Area | v1 Behaviour | v2 Behaviour |
|------|-------------|-------------|
| Cache lookup | None — always hits DB | Redis cache-aside: check cache first, fall back to DB on miss |
| Cache populate | None | On cache miss + DB hit, populate Redis with the mapping |
| Negative caching | None | On DB miss (404), store a tombstone in Redis to prevent repeated DB misses |
| Soft delete check | Not checked (no `is_deleted` column in v1) | Check `is_deleted = true` → return `410 Gone` |

---

## Updated Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `GET /<short_code>`. |
| 2 | System | **Checks Redis cache** for key `url:<short_code>`. |
| 3 | System | **Cache hit** → Skip to step 5. **Cache miss** → Query PostgreSQL. |
| 4 | System | **On DB hit, populate Redis cache** (`url:<short_code>` → JSON, TTL from config). |
| 5 | System | Validates: `is_deleted = false` and (`expires_at` is null or in the future). |
| 6 | System | Determines redirect status code: **301** if no explicit expiry, **302** if expiring (per ADR-003). |
| 7 | System | Returns redirect response with `Location: <original_url>` header. |

Steps in **bold** are new or changed in v2.

---

## New Alternative Flow

### Cache Hit

| Step | Actor | Action |
|---|---|---|
| 2a | System | Key `url:<short_code>` found in Redis. |
| 2b | System | Deserialize JSON → `UrlMapping`. Skip DB query. Proceed to validation (step 5). |

---

## Updated Error Flows

### Short Code Not Found (with negative caching)

| Step | Actor | Action |
|---|---|---|
| 3a | System | No row found in DB. |
| 3b | System | **Sets negative cache entry** in Redis (`url:<short_code>` → tombstone, TTL: 60 seconds). |
| 3c | System | Returns `404 Not Found` with error code `NOT_FOUND`. |

### Soft-Deleted Link (new in v2)

| Step | Actor | Action |
|---|---|---|
| 5a | System | `is_deleted = true`. |
| 5b | System | Returns `410 Gone` with error code `GONE`. |

### Cache Failure (circuit breaker)

| Step | Actor | Action |
|---|---|---|
| 2a | System | Redis is unreachable or circuit breaker is open. |
| 2b | System | Skips cache, falls back to DB query (step 3). Redirect is not degraded. |

---

## Updated Postconditions

### Success (additions to v1)

- Redis cache is populated with the mapping (on cache miss + DB hit).
- Subsequent requests for the same short code are served from cache (P95 < 10 ms).

### Failure — 404 (additions to v1)

- Negative cache entry prevents repeated DB misses for the same non-existent code.

---

## Updated NFRs

| NFR | v1 Target | v2 Target |
|------|-----------|-----------|
| Response time (DB) | P95 < 100 ms | P95 < 100 ms |
| Response time (cache hit) | N/A | P95 < 10 ms |
| Cache hit ratio (steady state) | N/A | > 90% |
