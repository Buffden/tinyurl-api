# ADR-004: Expiration Policy

**Status**: Accepted
**Date**: February 2026
**Deciders**: Architecture review

---

## Context

Short URLs need a lifecycle policy. Without one, the database grows unbounded and dead links persist indefinitely. Relevant constraints:

- Links are immutable after creation — expiry cannot be changed once set.
- Clients may or may not provide an explicit expiry at creation time.
- Storage at target scale is manageable (10M rows ≈ 1.5–3 GB).

---

## Decision

Apply a **180-day default expiry** when the client provides none. Clients may supply a shorter or longer value at creation time.

On redirect:

- If `expires_at < now()` or `is_deleted = true`: return **HTTP 410 Gone**. The resource existed and is intentionally unavailable.
- If the short code is not found at all: return **HTTP 404 Not Found**.

No cleanup job in v1. Expired records remain in the DB, filtered on read. A periodic cleanup job is deferred to v2.

---

## Consequences

- `expires_at` column must allow NULL at the DB layer; the application writes `now() + 180 days` when the client omits the field.
- Expiry is enforced **on the redirect path only** — no background process is required for correctness.
- No renewal mechanism exists. Users who need a longer lifetime must create a new short URL.
- API documentation must communicate the 180-day default clearly to avoid user surprise.
- Index on `expires_at` required in v2 to support the cleanup job without a full table scan.

---

## Alternatives Considered

| Option | Why Rejected |
| --- | --- |
| No expiration | Unbounded DB growth; dead links accumulate forever |
| Mandatory fixed expiry for all links | Too restrictive — users with long-lived links cannot opt out |
| Return HTTP 404 for all cases (found, expired, unknown) | Loses HTTP semantic precision; clients cannot distinguish "never existed" from "existed and expired"; makes debugging harder |
