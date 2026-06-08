---
title: "Cutting My AWS Bill by 36% on a Portfolio Project — Every Decision I Made"
subtitle: "From $81/month to $52/month on a production-grade TinyURL service, and what's still on the table"
type: medium-post
date: 2026-04-22
tags: [AWS, DevOps, Cloud Cost Optimization, Side Projects, Infrastructure]
---

# Cutting My AWS Bill by 36% on a Portfolio Project — Every Decision I Made

My TinyURL side project is built to production standards — proper CI/CD, layered security, Cloudflare + CloudFront + ALB + Nginx in front, Spring Boot on EC2, PostgreSQL on RDS. It's a portfolio piece, but I run it like it's real.

In March 2026, the first full month of the deployment, it cost $44.16. By April, projected to $81/month if left untouched. That's over $970/year for a side project.

Here's the full audit of what I spent, every optimization I made, and the ones still in progress.

---

## The Full Infrastructure Inventory

Before cutting anything, I audited every line item:

| Service | Resource | Cost/Month |
|---|---|---|
| EC2 | `ems-prod-app` (t2.micro) — 4 services in Docker | $8.35 |
| EC2 | `tinyurl-prod` (t3.small → t3.micro) | $14.98 → $7.49 |
| RDS | `tinyurl-prod` (db.t4g.micro, PostgreSQL 17.4, 20 GB gp3) | $13.70 |
| ALB | `tinyurl-alb` | $14.01 |
| Elastic IPs | 2x ALB EIPs + 1 idle (released) + EC2 EIPs | ~$11/month peak |
| Route 53 | `buffden.com` hosted zone | $0.54 |
| EBS | 8 GB gp3 + 20 GB gp2 | $1.50 |
| S3 + CloudFront | Static Angular SPA | Negligible |
| SSM Parameter Store | 37 params, all Standard tier | Free |
| CloudWatch Logs | 30-day retention | Minimal |

**Baseline projection: ~$81/month**

---

## The Architecture Decisions That Saved Money Before Day One

The biggest cost wins weren't reactive — they were baked into the design.

### 1. Static Angular SPA on S3 + CloudFront

The frontend is a pure static build deployed to S3 and served through CloudFront. The alternative — running a Node server or serving assets from the Spring Boot host — would mean the EC2 instance handles every page load, increases coupling, and adds cost at scale. S3 + CloudFront charges are usage-based and at this traffic level, essentially zero.

CloudFront handles routing too: `/api/*` and `/{shortCode}` go to the ALB; everything else serves `index.html` from S3 for SPA routing. 403/404 errors from S3 are remapped to `index.html` so browser-side routing works correctly.

### 2. Cloudflare as the First Layer

Before traffic ever reaches an AWS service, it hits Cloudflare. The DNS record for `tinyurl.buffden.com` is orange-cloud (proxied), meaning DNS resolves to Cloudflare's anycast edge — not CloudFront directly.

The flow: `User → Cloudflare edge → CloudFront → S3 / ALB`

This matters financially because DDoS and bot traffic gets absorbed by Cloudflare before it reaches AWS metered services. Cloudflare WAF, bot protection, and DDoS mitigation run at the edge. If a scraper hammers the redirect endpoint, Cloudflare eats it — not my ALB or EC2.

### 3. Docker Compose on EC2 Instead of ECS/Fargate

The `ems-prod-app` instance runs four services inside Docker Compose: Spring Boot, PostgreSQL, Redis, and Nginx. Total cost: $8.35/month on a t2.micro.

ECS Fargate for the equivalent would be meaningfully more expensive, adds operational complexity, and is unnecessary at this scale. The precedent this set matters: TinyURL's own EC2 host follows the same model — Nginx + Spring Boot in Docker, using RDS for the database (for now).

### 4. No NAT Gateway

EC2 instances are in public subnets and reach the internet directly through the Internet Gateway. A NAT Gateway would add ~$32/month in baseline charges plus data processing fees. For instances that only need outbound access to pull container images and send CloudWatch logs, a NAT Gateway is over-engineered. RDS is in a private subnet (no internet access needed).

### 5. SSM Session Manager Instead of a Bastion Host

No SSH on port 22. No bastion EC2 instance. All shell access goes through AWS Systems Manager Session Manager. This means:
- No additional EC2 instance ($8–15/month saved)
- No security group rule opening port 22 to the internet
- All session activity logged to CloudWatch
- IAM-controlled access instead of SSH key management

### 6. OIDC for CI/CD — No IAM Access Keys

The GitHub Actions deploy pipeline authenticates to AWS using OIDC federation. GitHub's identity provider issues a short-lived token that AWS trusts; no long-lived access key is created or stored. Benefits: zero credential rotation overhead, no secret leakage risk, no IAM user to manage.

### 7. GHCR for Container Images

Container images are pushed to GitHub Container Registry (GHCR). For public repositories, GHCR is free with no egress charges for pulls. Using AWS ECR would add per-GB storage and data transfer charges — modest, but unnecessary.

### 8. SSM Parameter Store Standard Tier for Secrets

37 parameters (database credentials, app config, feature flags) are stored in SSM Parameter Store as Standard-tier SecureString entries encrypted with KMS. Standard tier is free for up to 10,000 parameters. AWS Secrets Manager would cost $0.40/secret/month — that adds up with multiple services.

---

## The Three Reactive Optimizations (After the Bill Arrived)

### Action 1: Release the Idle Elastic IP — Saves $3.65/month

An EIP from a previous experiment was still allocated but not associated with any resource. AWS charges $3.65/month for any unassociated EIP. Released on 2026-04-02.

This is the easiest win in any AWS cost audit: search for unassociated Elastic IPs and release them immediately.

### Action 2: Downsize EC2 from t3.small → t3.micro — Saves $7.49/month

The `tinyurl-prod` EC2 instance was originally provisioned as a t3.small at $14.98/month. After measuring actual utilization — memory at 45–57%, CPU rarely above 10% — there was no justification for the larger instance. Downsized to t3.micro ($7.49/month) on 2026-04-02.

The EMS instance (`ems-prod-app`) couldn't be downsized — it's in availability zone us-east-1e, which only supports the t2 instance family, and the smallest available t2 is t2.micro (already in use).

**The lesson:** Right-size based on measured utilization, not anticipated load. You can always scale up. You won't always remember to scale down.

### Action 3: Night Scheduler — Saves ~$18/month

This is the most impactful single change. The architecture:

- **EventBridge** fires two cron rules: stop at 04:00 UTC (11 PM CDT), start at 12:00 UTC (7 AM CDT) weekdays
- **Lambda** executes EC2 `StopInstances` / `StartInstances` and RDS `StopDBInstance` / `StartDBInstance`
- **SQS Dead Letter Queue** captures any Lambda failures for retry/alerting
- **SNS** sends email notification on start/stop so I know when the environment is available
- **CloudWatch Alarm** monitors Lambda errors

Total cost: $0/month. All components stay within AWS free tier.

Uptime breakdown:
- Weekdays: ~11 hours on, ~13 hours off per day
- Weekends: fully off from Friday 11 PM to Monday 7 AM (60 hours)
- Effective uptime: ~48% of the week

Savings: ~52% of EC2 + RDS costs that are time-based. On $34.68/month of stoppable compute and database charges, that's ~$18/month.

**The caveat:** This works because it's a portfolio project. I don't need the service up at 2 AM. If this were serving real users, a different approach would be needed — autoscaling to zero on ECS Fargate or a similar demand-based shutdown strategy.

---

## What's Still on the Table

The optimizations above took the projected bill from $81 → $52/month (36% reduction). Two larger options are still being evaluated:

### Option A: Remove the ALB + Release 2 EIPs — Saves $17.66/month

The Application Load Balancer costs $14.01/month. Combined with the two EIPs attached to it ($7.30/month), removing it saves $21.31/month — minus one new EIP for the EC2 instance ($3.65/month) = **$17.66/month net savings**.

The trade-off: TLS termination moves from the ALB to Nginx on EC2 directly. The EC2 security group needs rules updated to accept HTTPS traffic from CloudFront. ALB health checks are replaced by CloudFront origin health checks or a simple Route 53 health check.

For a single-instance, single-region deployment with CloudFront in front, an ALB is over-specified. CloudFront handles the load distribution to the origin; the ALB's only real job here is TLS termination and health checking.

Risk: Medium. The Nginx TLS configuration must be correct, and CloudFront → EC2 connectivity needs validation before cutting over.

### Option B: Migrate RDS to Docker PostgreSQL on EC2 — Saves $13.70/month

The TinyURL RDS instance (db.t4g.micro) costs $13.70/month. The `ems-prod-app` EC2 already runs PostgreSQL inside Docker Compose alongside Spring Boot and Redis. TinyURL's EC2 could do the same.

Current `tinyurl-prod` memory utilization: 45–57%. Adding a Docker PostgreSQL container (similar to EMS) would push it to ~62–82% — comparable to how EMS runs today on the same instance size.

The precedent is solid: EMS has run this configuration stably. The risk is the operational difference — RDS provides automated backups, Multi-AZ failover options, and managed minor version updates. Moving to Docker PostgreSQL means managing backups manually (or via pg_dump cron), no built-in HA, and manual version updates.

For a side project where the data can be reconstructed and downtime is acceptable: the risk is manageable. For a production system with real user data: RDS earns its cost.

**Combined potential: -$60.64/month from baseline (-64% total reduction)**

---

## The Backup Window Bug I Found

While analyzing the RDS setup, I found a conflict between the night scheduler and the RDS maintenance window.

The automated backup window was set to 09:14–09:44 UTC. The night scheduler stops the RDS instance at 04:00 UTC and restarts it at 12:00 UTC weekdays, and the instance is completely off on weekends.

AWS behavior: if a scheduled backup window falls while an RDS instance is stopped, AWS auto-restarts the instance to take the backup, then stops it again. On weekends, this was adding ~36 minutes of unexpected RDS runtime — at odd hours, with an SNS alert triggering unnecessarily.

Fix: Move the backup window to 18:00–18:30 UTC (1:00–1:30 PM CDT) — squarely inside the active window.

**The lesson:** Any time scheduler changes interact with managed service maintenance windows, audit them for conflicts.

---

## Current State: April 2026

| Action | Savings/Month | Status |
|---|---|---|
| Release idle EIP | $3.65 | Done |
| Downsize EC2 t3.small → t3.micro | $7.49 | Done |
| Night scheduler | ~$18.00 | Done |
| **Total achieved** | **~$29/month (36%)** | |
| Remove ALB + 2 EIPs | $17.66 | Evaluating |
| Migrate RDS → Docker PostgreSQL | $13.70 | Evaluating |
| **Total potential** | **~$60/month (64%)** | |

**Projected final bill if all options complete: ~$21/month**

---

## What I'd Do Differently From the Start

1. **Provision t3.micro from day one** — I over-provisioned out of habit. One month of metrics would have confirmed the smaller instance was fine.

2. **Set the backup window explicitly during provisioning** — RDS default backup windows are assigned randomly. When you layer a scheduler on top, you need to own that window.

3. **Audit EIPs immediately after decommissioning any resource** — Detach → release is one step. Detach alone isn't.

4. **Night scheduler as a default for portfolio projects** — Not something to add after costs spike. Should be infrastructure-as-code from day one.

The architecture itself I'd keep largely the same. Cloudflare in front, CloudFront for the SPA, Docker Compose on EC2, SSM for secrets and access — these all held up well. The costs were controllable from the start; I just didn't control them immediately.

---

## Summary

A production-grade portfolio project doesn't have to cost $80+/month. The combination of upfront architectural choices (CDN-hosted SPA, Cloudflare edge, no NAT gateway, SSM over bastion) and reactive optimizations (right-sizing, idle resource cleanup, scheduled downtime) get you most of the way there.

The remaining decisions — ALB removal and RDS migration — are data migration and operational risk decisions, not cost knowledge gaps. The cost information was always available. The work is in the execution.

Total time spent on the scheduler build + optimizations: roughly a weekend. Monthly return: $29/month and counting.

---

*The project is live at [tinyurl.buffden.com](https://tinyurl.buffden.com). Architecture docs, ADRs, and the full system design are in the repo at [github.com/buffden/tinyurl](https://github.com/buffden/tinyurl).*
