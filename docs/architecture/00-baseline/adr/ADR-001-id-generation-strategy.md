# ADR-001: ID Generation Strategy

**Status**: Accepted
**Date**: February 2026
**Deciders**: Architecture review

---

## Context

Each short URL requires a unique, compact identifier that appears directly in the user-facing URL (`tinyurl.buffden.com/{code}`). The identifier must be:

- Unique with no collision risk
- 6–8 characters (Base62 alphabet)
- Generatable without external coordination in a single-region, single-primary-DB deployment

---

## Decision

Use a **database auto-increment sequence** as the source of truth for uniqueness, then **encode the numeric ID in Base62** to produce the short code.

---

## Consequences

- Application must implement Base62 encoding/decoding.
- ID generation is coupled to the primary DB sequence — acceptable at v1 write volumes (~5–10 create QPS).
- Create QPS (~5–10) is low enough that DB sequence contention is not a bottleneck.
- Short codes are sequential and therefore **enumerable**. No private content exists in v1, so enumeration is an accepted risk. If enumeration resistance is required in v2+, randomise the ID space or add obfuscation under a new ADR.
- Short code length grows logarithmically with URL count (10M URLs ≈ 4–5 Base62 characters — well within the 6–8 target).

---

## Alternatives Considered

| Option | Why Rejected |
| --- | --- |
| Raw auto-increment integer | Human-readable but purely numeric; not a "short code" |
| UUID (v4) | 36 characters — defeats the purpose of a short URL |
| SHA-256 (truncated) | Collision probability requires resolution logic; adds complexity with no benefit over a sequence |
