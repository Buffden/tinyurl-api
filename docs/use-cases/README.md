# Use Cases

> Detailed use case specifications organized by version. Each version folder contains self-contained, implementation-ready use cases with no cross-version mixing.

---

## Structure

```
use-cases/
├── v1/                              # Baseline — implement first
│   ├── UC-US-001-shorten-url.md     # Shorten a long URL
│   ├── UC-US-002-redirect-url.md    # Redirect via short URL
│   └── UC-US-003-expire-url.md      # Passive expiry (read-time check)
│
└── v2/                              # Enhancements — implement after v1 is stable
    ├── UC-US-001-shorten-url.md     # Adds: rate limiting, cache warming, url_hash, audit fields
    ├── UC-US-002-redirect-url.md    # Adds: Redis cache-aside, negative caching, soft-delete check
    ├── UC-US-003-expire-url.md      # Adds: scheduled cleanup job, soft-delete, cache invalidation
    └── UC-US-004-custom-alias.md    # New: custom alias creation (feature-flagged)
```

---

## Use Case Index

| ID | Name | v1 | v2 |
|----|------|----|----|
| UC-US-001 | Shorten URL | [v1](v1/UC-US-001-shorten-url.md) | [v2 additions](v2/UC-US-001-shorten-url.md) |
| UC-US-002 | Redirect URL | [v1](v1/UC-US-002-redirect-url.md) | [v2 additions](v2/UC-US-002-redirect-url.md) |
| UC-US-003 | Expire URL | [v1](v1/UC-US-003-expire-url.md) | [v2 additions](v2/UC-US-003-expire-url.md) |
| UC-US-004 | Custom Alias | — | [v2](v2/UC-US-004-custom-alias.md) |

---

## How to Read

- **Building v1?** Read only the `v1/` files. They are complete and self-contained — no v2 references.
- **Building v2?** Read the `v1/` file first, then the corresponding `v2/` file. The v2 file documents only what changes or gets added. It references the v1 file and uses a comparison table to highlight differences.
