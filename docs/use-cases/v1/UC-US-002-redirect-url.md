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

1. The system is operational.

---

## 4) Triggers

- End user sends a `GET /<short_code>` request.

---

## 5) Main Flow

| Step | Actor | Action |
|---|---|---|
| 1 | End User | Sends `GET /<short_code>`. |
| 2 | System | Queries PostgreSQL for the URL mapping by `short_code`. |
| 3 | System | Validates that `expires_at` is null or in the future. |
| 4 | System | Determines redirect status code: **301** if no explicit expiry, **302** if expiring (per ADR-003). |
| 5 | System | Returns redirect response with `Location: <original_url>` header. |

---

## 6) Alternative Flows

### 6a) Permanent Link (no explicit expiry)

| Step | Actor | Action |
|---|---|---|
| 4a | System | `expires_at` was not explicitly set by user (system default applied). |
| 4b | System | Returns `301 Moved Permanently`. Client may cache the redirect. |

### 6b) Expiring Link

| Step | Actor | Action |
|---|---|---|
| 4a | System | `expires_at` was explicitly set by user. |
| 4b | System | Returns `302 Found`. Client will not cache — server must validate expiry on every request. |

---

## 7) Error Flows

### 7a) Short Code Not Found

| Step | Actor | Action |
|---|---|---|
| 2a | System | No row found in DB for the given short code. |
| 2b | System | Returns `404 Not Found` with error code `NOT_FOUND`. |

### 7b) Expired Link

| Step | Actor | Action |
|---|---|---|
| 3a | System | `expires_at` is in the past. |
| 3b | System | Returns `410 Gone` with error code `GONE`. |

### 7c) Database Unavailable

| Step | Actor | Action |
|---|---|---|
| 2a | System | DB connection fails. |
| 2b | System | Returns `503 Service Unavailable` with `Retry-After: 30` header. |

---

## 8) Postconditions

### Success

- User's browser/client follows the redirect to the original URL.
- No persistent state is modified. The operation is a pure read.

### Failure (404/410)

- No redirect occurs. User receives an error response.

---

## 9) Non-Functional Requirements

| NFR | Target |
|---|---|
| Response time | P95 < 100 ms |
| Availability | 99.9% |
| Read-to-write ratio | 99:1 |
