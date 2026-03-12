# UC-US-001: Shorten a Long URL — v2 Additions

> v2 changes to [v1/UC-US-001](../v1/UC-US-001-shorten-url.md). Read the v1 use case first.

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-001 |
| Version | v2 |
| Priority | Critical |
| Status | Approved |
| Related FR | FR-001, FR-005, FR-006 |

---

## What Changes in v2

| Area | v1 Behaviour | v2 Behaviour |
|------|-------------|-------------|
| Rate limiting | None | Per-IP token bucket (5 req/min). Returns `429` with `Retry-After` header. |
| Cache warming | None | After successful insert, populate Redis cache with the new mapping. |
| Custom alias | Not supported | Optional `alias` field in request body. See [UC-US-004](UC-US-004-custom-alias.md). |
| `url_hash` | Not stored | SHA-256 hash of `original_url` stored in `url_hash` column for operational analysis. |
| Audit fields | Not stored | `created_ip` (INET) and `user_agent` (TEXT) stored for abuse detection. |

---

## Updated Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `POST /api/urls` with `{ "url": "<long_url>" }`. |
| 2 | System | **Checks per-IP rate limit** (Bucket4j token bucket). If exhausted → `429`. |
| 3 | System | Validates URL format (must be valid HTTP/HTTPS, max 2048 chars). |
| 4 | System | Generates next value from the DB sequence. |
| 5 | System | Encodes the sequence value using Base62 to produce the short code. |
| 6 | System | Computes `expires_at` (default: 180 days from now). |
| 7 | System | **Computes `url_hash`** (SHA-256 of `original_url`). |
| 8 | System | Inserts a new row into `url_mappings` (including `url_hash`, `created_ip`, `user_agent`). |
| 9 | System | **Warms Redis cache** with the new mapping (`url:<short_code>` → JSON, TTL from config). |
| 10 | System | Returns `201 Created` with the short URL, short code, and expiry timestamp. |

Steps in **bold** are new in v2.

---

## New Error Flow

### Rate Limit Exceeded

| Step | Actor | Action |
|---|---|---|
| 2a | System | Per-IP token bucket is empty. |
| 2b | System | Returns `429 Too Many Requests` with `Retry-After` header and error code `RATE_LIMIT_EXCEEDED`. |

---

## Updated Postconditions

### Success (additions to v1)

- Redis cache is warmed with the new mapping.
- `url_hash`, `created_ip`, and `user_agent` are persisted for operational analysis.

---

## Updated NFRs

| NFR | v1 Target | v2 Target |
|------|-----------|-----------|
| Response time | P95 < 100 ms | P95 < 100 ms (unchanged — cache warm is async-safe) |
| Rate limit | None | 5 req/min per IP on create endpoint |
