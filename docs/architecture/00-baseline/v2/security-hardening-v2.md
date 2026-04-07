# Security Hardening — v2 Backlog

> Items deferred from v1. Each is justified: either requires new infrastructure (Redis, frontend changes), or the v1 controls already reduce risk to an acceptable level for launch.

---

## 1. Cloudflare Turnstile CAPTCHA

**Why deferred:** Requires Angular frontend changes (Turnstile widget), a new backend validation call to Cloudflare's `/siteverify` API, and a new SSM secret (`/tinyurl/app/turnstile-secret-key`). The v1 dual-layer rate limiting (nginx 40r/m + Bucket4j 20/hour per IP) is sufficient to block single-IP and slow-drip abuse for launch traffic.

**Why it matters in v2:** Turnstile is the only control that stops a distributed botnet (many IPs, each staying under per-IP rate limits) from mass-creating spam/phishing URLs.

**Implementation plan:**
- Add `ngx-turnstile` (or equivalent) to the Angular form. On submit, pass `cf-turnstile-response` token alongside the URL.
- Add `captchaToken` field to `CreateUrlRequest` DTO (required in prod profile, optional in dev).
- Create `CaptchaVerificationService` — calls `https://challenges.cloudflare.com/turnstile/v0/siteverify` with the secret key and token. Use `RestClient` (Spring Boot 3.2+).
- Call `captchaVerificationService.verify(token)` in `UrlServiceImpl.shortenUrl()` before any DB work. Throw `CaptchaException` → 400 if invalid.
- Load secret key from SSM (`/tinyurl/app/turnstile-secret-key`) via existing AWS Parameter Store config.
- Gate the requirement on a `tinyurl.captcha.enabled` property (false in dev, true in prod).

**Note on SSRF:** The backend calls a fixed Cloudflare endpoint, not a user-supplied URL — no new SSRF surface.

---

## 2. Distributed Rate Limiting (Bucket4j + Redis)

**Why deferred:** v1 runs on a single EC2 instance. `IpRateLimitFilter` uses Caffeine (in-process) to store per-IP token buckets. This is correct and sufficient for a single instance.

**Why it matters in v2:** v2 introduces autoscaling (multiple app instances). Per-IP state stored in-process is not shared across instances — a bot can hit 3 instances and create 3× the intended quota. Redis-backed Bucket4j solves this with atomic bucket operations via Redis scripts.

**Implementation plan:**
- Add `com.bucket4j:bucket4j-redis` dependency alongside the existing `bucket4j-core`.
- Replace `Caffeine.newBuilder().build(key -> newBucket())` in `IpRateLimitFilter` with a `ProxyManager` backed by `RedisBasedProxyManager` (using the same Redis instance added for the cache layer).
- No change to the bucket policy (20 creations/hour/IP) or filter logic.
- The existing `IpRateLimitFilter` structure is designed for this migration: the only change is the backing store.

---

## 3. CloudWatch Log Retention Policy

**Why deferred:** No IaC (Terraform/CloudFormation) exists in the repository to set the CloudWatch log group retention period. Logs default to indefinite retention, which is a cost and compliance concern but not a security vulnerability.

**Why it matters in v2:** Unbounded log retention accumulates cost and may conflict with data retention policies.

**Implementation plan:**
- When IaC is introduced in v2, set `retention_in_days = 90` on the CloudWatch log group for the application and nginx logs.
- If IaC is not added before v2, add a one-time CLI step to the deployment runbook:
  ```
  aws logs put-retention-policy \
    --log-group-name /tinyurl/api \
    --retention-in-days 90
  ```

---

## 4. CloudWatch Anomaly Alerting (4xx / 5xx Rate Spikes)

**Why deferred:** CloudWatch alarms for infrastructure health exist (CPU, ALB 5xx). Application-level alerting on anomalous request patterns (sudden spike in 400s suggesting scanning, or 429s suggesting a rate-limit bypass attempt) requires a CloudWatch Metric Filter on the structured log stream and an alarm with a meaningful threshold.

**Why it matters in v2:** v1 has observability (structured logs, Prometheus metrics) but no automated paging when attack patterns emerge. Without this, an ongoing abuse incident may go unnoticed until it impacts latency or DB load.

**Implementation plan:**
- Add a CloudWatch Metric Filter on the application log group for `status_class=4xx` and `status_class=5xx`.
- Create a CloudWatch alarm: alert if `4xx rate > 15%` of total requests over a 5-minute window.
- Create a CloudWatch alarm: alert if `5xx rate > 1%` over a 5-minute window.
- Route alarms to an SNS topic → email or Slack webhook.
- This integrates with the broader v2 observability work (Micrometer + Grafana).

---

## Status in OWASP Compliance

| Item | OWASP Ref | Deferred Until |
|---|---|---|
| CAPTCHA (Turnstile) | API6 | v2 |
| Distributed rate limiting | API6 | v2 (requires autoscaling) |
| CloudWatch log retention | A09 | v2 (requires IaC) |
| CloudWatch anomaly alerting | A09 | v2 (observability milestone) |
