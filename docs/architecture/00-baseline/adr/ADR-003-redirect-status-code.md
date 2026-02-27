# ADR-003: Redirect Status Code

**Status**: Accepted
**Date**: February 2026
**Deciders**: Architecture review

---

## Context

When a short URL is accessed, the system must issue an HTTP redirect. The choice of status code determines caching behaviour:

- **301 (Permanent)**: browsers and proxies cache the redirect; subsequent requests never reach the server.
- **302 (Temporary)**: every request hits the server; no client-side caching.

Relevant constraints:
- v1 has no analytics or click tracking — missing redirect observations is acceptable.
- Some links carry an explicit expiry; others do not.
- Links are immutable after creation (destination never changes).

---

## Decision

Use **HTTP 301** when the link was created **without an explicit expiry** (user expressed no intent for the link to be temporary).

Use **HTTP 302** when the link has an **explicit expiry or is marked temporary** (the server must validate on every request to enforce the expiry).

Note: the system applies a 180-day default retention TTL to all links regardless. The 301/302 choice reflects **user intent**, not the internal cleanup window.

---

## Consequences

- The redirect handler must read `expires_at` on every request to select the correct status code.
- 301-cached links cannot be revoked at the browser level. Acceptable because links are immutable in v1.
- If click tracking is added in a future version, the default will likely shift to 302-only under a new ADR.

---

## Alternatives Considered

| Option | Why Rejected |
| --- | --- |
| Always 301 | Cannot enforce expiration on links with an explicit TTL — expired links would still redirect from browser cache |
| Always 302 | Every redirect hits the server; eliminates caching benefit for the majority of links that have no expiry |
