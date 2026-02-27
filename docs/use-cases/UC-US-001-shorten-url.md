# UC-US-001: Shorten a Long URL

---

## Metadata

| Field | Value |
|---|---|
| Use Case ID | UC-US-001 |
| Version | v1 |
| Priority | Critical |
| Status | Approved |
| Related FR | FR-001, FR-005 |

---

## 1) Summary

An end user submits a long URL to the system and receives a shortened URL that can be used as a permanent redirect.

---

## 2) Actors

| Actor | Role |
|---|---|
| End User | Initiates the request with a long URL. |
| System | Validates, generates a short code, persists the mapping, and returns the short URL. |

---

## 3) Preconditions

1. The system is operational and accepting requests.
2. The user has network access to the API endpoint.
3. The user has not exceeded their rate limit.

---

## 4) Triggers

- End user sends a `POST /api/urls` request with a JSON body containing the target URL.

---

## 5) Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `POST /api/urls` with `{ "url": "<long_url>" }`. |
| 2 | System | Validates URL format (must be valid HTTP/HTTPS, max 2048 chars). |
| 3 | System | Checks per-IP rate limit. |
| 4 | System | Generates next value from the DB sequence. |
| 5 | System | Encodes the sequence value using Base62 to produce the short code. |
| 6 | System | Computes `expires_at` (default: 180 days from now). |
| 7 | System | Inserts a new row into `url_mappings`. |
| 8 | System | Returns `201 Created` with the short URL, short code, and expiry timestamp. |

---

## 6) Alternative Flows

### 6a) Custom Expiry

| Step | Actor | Action |
|---|---|---|
| 1a | End User | Includes `"expires_in_days": <N>` in the request body. |
| 6a | System | Uses the provided value instead of the 180-day default. |

### 6b) Duplicate URL Submission

| Step | Actor | Action |
|---|---|---|
| 1b | End User | Submits a URL that was previously shortened. |
| 4b | System | Generates a new, independent short code. No deduplication. |

> **Non-idempotency note**: This operation is non-idempotent. Repeated identical requests produce distinct short codes. There is no deduplication or "find existing" behaviour.

---

## 7) Error Flows

### 7a) Invalid URL

| Step | Actor | Action |
|---|---|---|
| 2a | System | URL fails validation (malformed, unsupported scheme, too long). |
| 2b | System | Returns `400 Bad Request` with error code `INVALID_URL`. |

### 7b) Rate Limit Exceeded

| Step | Actor | Action |
|---|---|---|
| 3a | System | Per-IP token bucket is empty. |
| 3b | System | Returns `429 Too Many Requests` with `Retry-After` header. |

### 7c) Database Failure

| Step | Actor | Action |
|---|---|---|
| 7a | System | Insert fails due to DB error (connection, constraint, timeout). |
| 7b | System | Returns `500 Internal Server Error`. |

---

## 8) Postconditions

### Success

- A new row exists in `url_mappings` with the generated short code and original URL.
- The short code is globally unique.
- The cache is warmed with the new mapping (v2).

### Failure

- No row is inserted.
- No short code is consumed from the sequence (sequence values are never reused, but gaps are acceptable).

---

## 9) Non-Functional Requirements

| NFR | Target |
|---|---|
| Response time | P95 < 100 ms |
| Availability | 99.9% |
| Durability | Write is committed to PostgreSQL (fsync). |

---

## 10) Sequence Diagram

See [shorten-url-flow.puml](../diagrams/sequence/shorten-url-flow.puml).
