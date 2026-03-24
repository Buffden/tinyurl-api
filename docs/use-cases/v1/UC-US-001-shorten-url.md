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

---

## 4) Triggers

- End user sends a `POST /api/urls` request with a JSON body containing the target URL.

---

## 5) Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `POST /api/urls` with `{ "url": "<long_url>" }`. |
| 2 | System | Validates URL format (must be valid HTTP/HTTPS, max 2048 chars). |
| 3 | System | Generates next value from the DB sequence. |
| 4 | System | Encodes the sequence value using Base62 to produce the short code. |
| 5 | System | Computes `expires_at` (default: 180 days from now). |
| 6 | System | Inserts a new row into `url_mappings`. |
| 7 | System | Returns `201 Created` with the short URL, short code, and expiry timestamp. |

---

## 6) Alternative Flows

### 6a) Custom Expiry

| Step | Actor | Action |
|---|---|---|
| 1a | End User | Includes `"expiresInDays": <N>` in the request body. |
| 5a | System | Uses the provided value instead of the 180-day default. |

### 6b) Duplicate URL Submission

| Step | Actor | Action |
|---|---|---|
| 1b | End User | Submits a URL that was previously shortened. |
| 3b | System | Generates a new, independent short code. No deduplication. |

> **Non-idempotency note**: This operation is non-idempotent. Repeated identical requests produce distinct short codes. There is no deduplication or "find existing" behaviour.

---

## 7) Error Flows

### 7a) Invalid URL

| Step | Actor | Action |
|---|---|---|
| 2a | System | URL fails validation (malformed, unsupported scheme, too long). |
| 2b | System | Returns `400 Bad Request` with error code `INVALID_URL`. |

### 7b) Invalid Expiry

| Step | Actor | Action |
|---|---|---|
| 5a | System | `expiresInDays` is not a positive integer or exceeds 3650 (10 years). |
| 5b | System | Returns `400 Bad Request` with error code `INVALID_EXPIRY`. |

### 7c) Database Failure

| Step | Actor | Action |
|---|---|---|
| 6a | System | Insert fails due to DB error (connection, constraint, timeout). |
| 6b | System | Returns `500 Internal Server Error` with error code `INTERNAL_ERROR`. |

---

## 8) Postconditions

### Success

- A new row exists in `url_mappings` with the generated short code and original URL.
- The short code is globally unique (enforced by DB `UNIQUE` constraint).
- `expires_at` is set to the requested value or default (180 days).

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
