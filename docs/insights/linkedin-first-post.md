TinyURL — Production-Grade URL Shortener
https://lnkd.in/g9fGGqYv

The goal wasn't to build a URL shortener. It was to treat a small system with the same rigour a production system deserves.

Built and deployed a URL shortener on Amazon Web Services (AWS) using production-grade technologies, fully documented, with ADRs before any code was written.

Request flow — six layers before the application code runs

Cloudflare — The origin is never directly reachable. The EC2 security group accepts inbound traffic only from Cloudflare's published IP ranges. DDoS mitigation, bot protection, and WAF rate limiting are all absorbed at the edge before they become an AWS bill.

CloudFront — Routes by path, forward to the ALB; everything else serves the Angular SPA from S3. Frontend and backend deploy independently.

ALB — Terminates TLS, redirects HTTP to HTTPS, and runs health checks. It detects failure immediately and stops forwarding traffic.

NGINX — Rate limiting across three independent zones with per-IP connection caps. Known vulnerability scanners are silently dropped at the TCP level.

Spring Boot — Stateless, input validated at the boundary. HTTP semantics chosen deliberately: 301 for permanent links, 302 for expiring ones, 410 Gone for expired — not 404. Each signals a different intent to browsers, crawlers, and downstream clients.

PostgreSQL — Two DB users by design. Flyway holds DDL rights for schema migrations. The application user has no ALTER or DROP privileges. A compromised dependency cannot touch the schema.

Secrets in SSM, never in config or env vars. Dependencies verified via SHA-256 checksums. Docker images cosign-signed against GitHub OIDC. Rate limiting at three independent layers — Cloudflare WAF, Nginx, and the application. Full OWASP Secure Headers enforced at Nginx.

CI/CD runs three gates: Testcontainers unit tests, a Docker Compose smoke test with ephemeral credentials, and then SSM deploy. All three must pass — smoke failure blocks deploy.

The full ADR breakdown is on Medium — seven decisions, why each alternative was rejected, and what I'd change. https://lnkd.in/g4aYJwuS

The following posts in this series will cover the AWS cost architecture, the security design in detail, and why Cloudflare was chosen over AWS WAF. If any of these are relevant to what you're building, follow along.

v2 — Enhancement Plan

Every v2 item has a specific trigger: Redis cache-aside when DB read-throughput is the bottleneck, distributed rate limiting when autoscaling makes per-process state insufficient, and Turnstile CAPTCHA when distributed bots bypass per-IP limits. CloudWatch anomaly alerting, soft delete, and custom aliases follow the same principle — each deferred until the constraint is measured, not assumed. The ADRs document why.

- Source: https://lnkd.in/g5jcF3Uv

#SystemDesign #AWS #Java #SpringBoot #Angular #Cloudflare #DevOps #PostgreSQL #OWASP