# Functional Requirements

> Defines what the system must do. Each requirement is tagged with the version in which it is introduced.

---

## FR-001: Shorten URL

- **Version**: v1
- **Description**: Users can submit a long URL and receive a unique short URL.
- **Input**: Original URL (max 2,048 characters).
- **Output**: Short URL in the format `https://tinyurl.buffden.com/<short_code>`.
- **Constraints**:
  - Short codes are 6-8 characters, Base62 encoded.
  - Each long URL may produce a different short code on each submission (no deduplication in v1).

---

## FR-002: Redirect to Original URL

- **Version**: v1
- **Description**: Accessing a short URL redirects the user to the original long URL.
- **Behavior**:
  - If the short code exists and is not expired: redirect to the original URL.
  - If the short code does not exist: return HTTP 404.
  - If the short code is expired: return HTTP 410.
  - If the short code is soft-deleted (v2): return HTTP 410.

---

## FR-003: Support Expiration

- **Version**: v1
- **Description**: Short URLs support an optional expiration time.
- **Behavior**:
  - If the client provides an expiry: use the provided value.
  - If no expiry is provided: default to 180 days from creation.
  - Expired links are treated as non-existent on the redirect path.

---

## FR-004: Redirect Status Code Selection

- **Version**: v1
- **Description**: The system uses appropriate HTTP redirect status codes.
- **Behavior**:
  - Links without explicit expiry: HTTP 301 (permanent redirect).
  - Links with explicit expiry or marked temporary: HTTP 302 (temporary redirect).
- **Clarification**: "Without explicit expiry" means the user did not provide an expiry at creation time. The system still applies a 180-day default retention TTL (FR-003) for operational cleanup, but the redirect status code is 301 because the user expressed no intent for the link to be temporary. The 301 reflects user intent; the 180-day TTL is an internal retention constraint.

---

## FR-005: Immutable Short URLs

- **Version**: v1
- **Description**: Short URLs are immutable after creation. The destination URL cannot be changed.

---

## FR-006: Custom Aliases

- **Version**: v2 (feature-flagged)
- **Description**: Users can optionally provide a custom alias instead of a system-generated code.
- **Constraints**:
  - Allowed characters: Base62 (`[0-9a-zA-Z]`).
  - Length: 4-32 characters.
  - Must be unique (conflict returns HTTP 409).
  - Rate-limited more strictly than normal creates.

---

## FR-007: Soft Delete

- **Version**: v2
- **Description**: Administrators can soft-delete a link to block access without losing the record.
- **Behavior**:
  - Soft-deleted links return HTTP 410 on redirect.
  - The mapping record is preserved for audit purposes.
  - `is_deleted` flag and `deleted_at` timestamp are set.

---

## FR-008: Basic Per-IP Rate Limiting on Create

- **Version**: v1
- **Description**: The system applies coarse per-IP throttling on the URL creation endpoint to prevent abuse.
- **Behavior**:
  - Requests exceeding the threshold return HTTP 429 (Too Many Requests).
  - Implementation may be handled at the Nginx/reverse-proxy layer; no application-level store required in v1.
- **Constraints**:
  - Applies to `POST /api/urls` only.
  - Specific threshold (e.g., requests per minute per IP) is an operational configuration, not a hard-coded value.

---

## Assumptions

The following assumptions underpin the requirements above. If any change, requirements must be revisited.

- Single-region deployment for all versions in scope.
- No user authentication — all endpoints are public and anonymous.
- No URL deduplication — the same long URL may produce multiple distinct short codes.
- No analytics or click tracking in v1.
- Clients are trusted to supply well-formed input; server-side validation guards against malformed requests only.

---

## Non-Goals (Explicitly Excluded)

The following are intentionally excluded from all current versions:

- User accounts and authentication
- Analytics dashboards and click tracking
- Editable links (changing destination after creation)
- Multi-region deployment
- Malware/phishing URL scanning
