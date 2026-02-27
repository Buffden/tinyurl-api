# Use Cases

> Summary of user interaction scenarios. For detailed individual specifications, see [`../use-cases/`](../use-cases/).

---

## Use Case Index

| ID | Name | Version | Actor | Description |
| --- | --- | --- | --- | --- |
| UC-US-001 | Shorten URL | v1 | User | Submit a long URL and receive a short URL |
| UC-US-002 | Redirect URL | v1 | User | Access a short URL and get redirected to the original |
| UC-US-003 | Expire URL | v1 | System | Handle expiration of short URLs |
| UC-US-004 | Custom Alias | v2 | User | Create a short URL with a custom alias |

---

## UC-US-001: Shorten URL

- **Actor**: User (anonymous)
- **Preconditions**: None
- **Trigger**: User submits a long URL via `POST /api/urls`
- **Main Flow**:
  1. User sends a valid long URL with optional expiry.
  2. System validates the URL format and length (max 2,048 chars).
  3. System generates a unique short code (Base62 encoded).
  4. System stores the mapping in the database.
  5. System returns the short URL to the user.
- **Postconditions**: A new short URL mapping exists in the database.
- **Error Conditions**:
  - Invalid URL format: HTTP 400
  - URL exceeds max length: HTTP 400
  - Rate limit exceeded: HTTP 429

---

## UC-US-002: Redirect URL

- **Actor**: User (anonymous)
- **Preconditions**: A valid, non-expired short URL exists.
- **Trigger**: User accesses `GET /{code}`
- **Main Flow**:
  1. System looks up the short code.
  2. System verifies the link is not expired and not deleted.
  3. System redirects the user to the original URL.
- **Postconditions**: User is redirected to the original URL.
- **Error Conditions**:
  - Short code not found: HTTP 404
  - Short code expired: HTTP 410
  - Short code soft-deleted (v2): HTTP 410

---

## UC-US-003: Expire URL

- **Actor**: System (automated)
- **Preconditions**: A short URL exists with `expires_at` set.
- **Trigger**: Current time exceeds `expires_at`
- **Main Flow**:
  1. On redirect request, system checks `expires_at`.
  2. If expired, system returns HTTP 410 instead of redirecting.
  3. Optional cleanup job archives or removes expired records.
- **Note**: Cleanup is not required for correctness; the redirect path enforces expiry on every request.
- **Postconditions**: Expired link is no longer accessible via redirect.

---

## UC-US-004: Custom Alias (v2)

- **Actor**: User (anonymous)
- **Preconditions**: Custom alias feature flag is enabled.
- **Trigger**: User submits `POST /api/urls` with an `alias` field.
- **Main Flow**:
  1. System validates alias format (Base62, 4-32 chars).
  2. System checks alias uniqueness.
  3. System stores the custom alias as the short code.
  4. System returns the short URL with the custom alias.
- **Postconditions**: A new mapping exists with the user-provided alias.
- **Error Conditions**:
  - Invalid alias format: HTTP 400
  - Alias already taken: HTTP 409
  - Rate limit exceeded: HTTP 429
