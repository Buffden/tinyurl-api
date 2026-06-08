# TinyURL — Funny Findings Log

A running log of suspicious, hilarious, or otherwise interesting things discovered in production.

---

## #1 — The Mystery URL from Patna

**Date discovered:** 2026-04-10
**DB Row ID:** 1033
**Short code:** `0000Gf` (becomes `Gf` after the leading-zero fix)

### The URL

```
https://harshlatrinemehai.com
```

A 10-year short URL. For a domain whose name translates roughly to something unprintable in polite company. Set to expire **April 5, 2036**.

### Full DB Record

| Field | Value |
|---|---|
| `id` | 1033 |
| `short_code` | `0000Gf` |
| `original_url` | `https://harshlatrinemehai.com` |
| `created_at` | `2026-04-08 23:51:46 UTC` |
| `expires_at` | `2036-04-05 00:00:00 UTC` |
| `has_explicit_expiry` | `true` |

### Who Did This

Traced via CloudWatch access logs (`/tinyurl/prod`):

```
49.47.134.46 - - [08/Apr/2026:23:51:46 +0000]
"POST /api/urls HTTP/1.1" 201 167
"https://tinyurl.buffden.com/"
"Mozilla/5.0 (iPhone; CPU iPhone OS 18_6_2 like Mac OS X)
 AppleWebKit/605.1.15 (KHTML, like Gecko)
 Mobile/15E148 [LinkedInApp]/9.31.9671"
```

| Field | Value |
|---|---|
| **IP** | `49.47.134.46` |
| **City** | Patna, Bihar, India |
| **ISP** | Reliance Jio Infocomm Limited (AS55836) |
| **Device** | iPhone (iOS 18.6.2) |
| **App** | LinkedIn app (opened tinyurl.buffden.com from within LinkedIn) |
| **Time (local)** | 5:21 AM IST, April 9 2026 |
| **Coordinates** | 25.5941°N, 85.1356°E |

### Timeline

- **5:21 AM IST** — Someone in Patna, Bihar is awake, on LinkedIn, on their iPhone, on Jio mobile data
- They somehow land on `tinyurl.buffden.com`
- They deliberately type `harshlatrinemehai.com` as the URL to shorten
- They select a **10-year expiry** — this was not an accident
- They hit submit
- `go.buffden.com/0000Gf` now redirects to a toilet-named domain until 2036

### Notes

- Dynamic Jio IP — cannot narrow down to a specific individual
- The domain name appears to be a Hindi insult/joke
- Whoever this is, they planned ahead (10 years)
- The app had no authentication at this time — open to anyone with the link
- Prior to this successful creation, IP `64.189.4.32` (MacBook, Chrome) had attempted ~15 failed POSTs and hit the rate limiter at 16:29 UTC — those were legitimate test attempts by the app owner

---

*Add new findings below as they are discovered.*
