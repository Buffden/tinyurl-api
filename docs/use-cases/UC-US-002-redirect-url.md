# UC-US-002: Redirect via Short URL

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-002 |
| Version | v1 |
| Priority | Critical |
| Status | Approved |
| Related FR | FR-002, FR-004 |

---

## 1) Summary

An end user accesses a short URL. The system resolves the short code to the original URL and issues an HTTP redirect.

---

## 2) Actors

| Actor | Role |
|---|---|
| End User | Navigates to the short URL via browser or HTTP client. |
| System | Looks up the short code, validates status, and returns a redirect response. |

---

## 3) Preconditions

1. The short code exists in the system.
2. The associated URL mapping has not expired or been soft-deleted.
3. The system is operational.

---

## 4) Triggers

- End user sends a `GET /<short_code>` request.

---

## 5) Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `GET /<short_code>`. |
| 2 | System | (v2) Checks Redis cache for the short code. |
| 3 | System | On cache miss, queries PostgreSQL for the URL mapping. |
| 4 | System | Validates that `is_deleted = false` and `expires_at > now()`. |
| 5 | System | Determines redirect status code (301 for permanent, 302 for expiring). |
| 6 | System | Returns redirect response with `Location: <original_url>` header. |
| 7 | System | (v2) Populates Redis cache if it was a cache miss. |

---

## 6) Alternative Flows

### 6a) Cache Hit (v2)

| Step | Actor | Action |
|---|---|---|
| 2a | System | Short code found in Redis cache. |
| 2b | System | Skips DB query, proceeds to validation at step 4. |

### 6b) Permanent Link (no expiry)

| Step | Actor | Action |
|---|---|---|
| 5a | System | `expires_at IS NULL`. |
| 5b | System | Returns `301 Moved Permanently`. Client may cache the redirect. |

---

## 7) Error Flows

### 7a) Short Code Not Found

| Step | Actor | Action |
|---|---|---|
| 3a | System | No row found in DB (and no cache entry). |
| 3b | System | (v2) Sets negative cache entry (TTL: 30-120 seconds). |
| 3c | System | Returns `404 Not Found`. |

### 7b) Expired Link

| Step | Actor | Action |
|---|---|---|
| 4a | System | `expires_at` is in the past. |
| 4b | System | Returns `410 Gone`. |

### 7c) Soft-Deleted Link

| Step | Actor | Action |
|---|---|---|
| 4a | System | `is_deleted = true`. |
| 4b | System | Returns `410 Gone`. |

### 7d) Database Unavailable

| Step | Actor | Action |
|---|---|---|
| 3a | System | DB connection fails. |
| 3b | System | Returns `503 Service Unavailable` with `Retry-After` header. |

---

## 8) Postconditions

### Success

- User's browser/client follows the redirect to the original URL.
- Cache is populated with the mapping (v2).
- Redirect does not modify any persistent state. The operation is a pure read.

### Failure (404/410)

- No redirect occurs. User receives an error response.

---

## 9) Non-Functional Requirements

| NFR | Target |
|---|---|
| Response time (v1) | P95 < 100 ms |
| Response time (v2 cache hit) | P95 < 10 ms |
| Availability | 99.9% |
| Read-to-write ratio | 99:1 |

---

## 10) Sequence Diagram

See [redirect-url-flow.puml](../diagrams/sequence/redirect-url-flow.puml).
