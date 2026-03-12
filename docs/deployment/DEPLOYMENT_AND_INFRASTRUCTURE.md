# TinyURL — Deployment & Infrastructure Strategy

> Comprehensive guide covering Docker, RDS, Redis, pricing, capacity planning, failure modes, and scalability decisions for TinyURL v1–v3.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Component Decisions](#component-decisions)
3. [Cost Breakdown](#cost-breakdown)
4. [Data Durability & Persistence](#data-durability--persistence)
5. [Capacity Planning & Projections](#capacity-planning--projections)
6. [Failure Modes & Recovery](#failure-modes--recovery)
7. [Scaling Path (v1 → v2 → v3)](#scaling-path-v1--v2--v3)
8. [Infrastructure Configuration](#infrastructure-configuration)
9. [Operational Runbooks](#operational-runbooks)

---

## Architecture Overview

### Current Decision: Hybrid Cloud-Native

**v1–v2 Topology:**

```
Internet (HTTPS)
    │
    ▼
┌────────────────────────────────────────────────────────────┐
│  EC2 Instance (t3.small) — ap-south-1                      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Docker Compose (tinyurl-net bridge network)         │  │
│  │                                                      │  │
│  │  ├─ Nginx:1.25-alpine        (ports 80, 443)        │  │
│  │  │  ├─ TLS termination                              │  │
│  │  │  ├─ Rate limiting: 40 r/m per IP                │  │
│  │  │  └─ Reverse proxy to :8080                       │  │
│  │  │                                                   │  │
│  │  ├─ Spring Boot App (Java 21)                        │  │
│  │  │  ├─ Stateless; ports 8080 (internal)             │  │
│  │  │  ├─ HikariCP connection pool → RDS               │  │
│  │  │  ├─ Redis cache (v2 only) → cache-aside          │  │
│  │  │  ├─ Rate limiter (Redis token bucket, v2)        │  │
│  │  │  ├─ IAM role for SSM secrets fetch               │  │
│  │  │  └─ Prometheus metrics :8080/metrics             │  │
│  │  │                                                   │  │
│  │  ├─ Redis:7-alpine (v2 only)                         │  │
│  │  │  ├─ Ports 6379 (internal only)                   │  │
│  │  │  ├─ AOF persistence (appendonly.aof)             │  │
│  │  │  ├─ Max memory: 512 MB                           │  │
│  │  │  ├─ Eviction policy: allkeys-lru                 │  │
│  │  │  └─ Data: cached URLs + rate limit tokens        │  │
│  │  │                                                   │  │
│  │  ├─ Prometheus:latest                                │  │
│  │  │  ├─ Metrics scrape from app                      │  │
│  │  │  └─ Time-series storage (local, 1 GB)            │  │
│  │  │                                                   │  │
│  │  └─ Flyway:10-alpine (run-once on startup)           │  │
│  │     └─ DDL migrations against RDS                   │  │
│  │                                                      │  │
│  │  Network: tinyurl-net (bridge)                       │  │
│  │  All inter-service communication is internal        │  │
│  │  No ports exposed except Nginx → host               │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  EBS Root Volume: 30 GB gp3                               │
│  - Container images, logs, Prometheus data               │
└─────────────────┬───────────────────────────────────────────┘
                  │
        ┌─────────┴──────────┬──────────────────┐
        ▼                    ▼                  ▼
┌──────────────────┐  ┌────────────────────┐  ┌──────────────┐
│  RDS PostgreSQL  │  │  SSM Parameter     │  │  CloudWatch  │
│  16 — db.t3.micro│  │  Store             │  │  (free)      │
│  ├─ 5 GB gp3     │  │  ├─ DB password    │  │              │
│  ├─ Automated    │  │  ├─ RDS endpoint   │  │  Alarms for: │
│  │  backups (7d) │  │  ├─ Redis password │  │  ├─ CPU      │
│  ├─ Encryption   │  │  └─ Redis host     │  │  ├─ Disk     │
│  │  (KMS)        │  │  (v2 only)         │  │  └─ Conn     │
│  ├─ Multi-AZ:NO  │  │                    │  │              │
│  │  (v1, add in  │  │  SecureString +    │  │              │
│  │   v2.5)       │  │  KMS encrypted     │  │              │
│  └─ PITR enabled │  │                    │  │              │
│  (35-day window) │  └────────────────────┘  └──────────────┘
│                  │
│  Source of truth │
│  for URL data    │
└──────────────────┘
```

### Why This Hybrid Approach?

| Component | Choice | Rationale |
|---|---|---|
| **Compute** | EC2 t3.small | Cost-effective ($8–12/month); sufficient for v1–v2 load |
| **Database** | RDS PostgreSQL db.t3.micro | Managed backups, encryption, HA path (removes DB ops burden) |
| **Cache** | Docker Redis on EC2 (v1: none, v2+) | $0 extra cost; easily upgradeable to ElastiCache when scaling |
| **Secrets** | AWS SSM Parameter Store | No credentials in code/env; IAM-based access |
| **Observability** | Prometheus (local) + CloudWatch | Free metrics; alerting without third-party services |

---

## Component Decisions

### 1. Docker on EC2 (App Tier)

#### What We're Doing

Running **all stateless services in Docker Compose** on a single EC2 instance:
- Nginx (reverse proxy + TLS termination)
- Spring Boot app (stateless)
- Redis (v2 only, ephemeral cache)
- Prometheus (time-series storage)
- Flyway (migrations, runs once)

#### Why Docker?

✅ **Benefits:**
- Consistency: local dev environment mirrors production exactly.
- Simplicity: `docker compose up` brings the entire stack up.
- Isolation: services don't compete for system resources directly.
- Easy upgrades: just change image tags in compose file.

⚠️ **Tradeoffs:**
- Single point of failure: if EC2 dies, everything goes down (acceptable for v1).
- No automatic self-healing: you must manually restart.
- Memory limited to EC2 RAM (2 GB on t3.small is comfortable but not unlimited).

#### Docker Compose Structure

**File:** `docker-compose.yml` (in repository root)

```yaml
version: '3.8'
services:
  nginx:
    image: nginx:1.25-alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./certs:/etc/nginx/certs:ro
    depends_on:
      app:
        condition: service_healthy
    networks:
      - tinyurl-net
    restart: unless-stopped

  app:
    image: ghcr.io/buffden/tinyurl-app:latest
    environment:
      SPRING_CLOUD_AWS_REGION: ap-south-1
      SPRING_CONFIG_IMPORT: aws-parameterstore:/tinyurl/prod/
      LOGGING_LEVEL_ROOT: INFO
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: "6379"
      # Database connection pooling
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "10"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE: "2"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 3
    depends_on:
      - redis  # v2 only; v1 omit this
    networks:
      - tinyurl-net
    restart: unless-stopped
    deploy:
      resources:
        limits:
          cpus: '0.8'
          memory: 900M
        reservations:
          cpus: '0.4'
          memory: 512M

  redis:
    # v2 ONLY — entire service omitted in v1
    image: redis:7-alpine
    volumes:
      - redis-data:/data
    command: >
      redis-server
      --appendonly yes
      --maxmemory 512mb
      --maxmemory-policy allkeys-lru
      --requirepass ${REDIS_PASSWORD}
      --loglevel notice
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 10s
      timeout: 5s
      retries: 3
    networks:
      - tinyurl-net
    restart: unless-stopped
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 512M

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    networks:
      - tinyurl-net
    restart: unless-stopped

  flyway:
    image: flyway/flyway:10-alpine
    environment:
      FLYWAY_USER: postgres
      FLYWAY_PASSWORD: ${DB_PASSWORD}
      FLYWAY_URL: ${FLYWAY_URL}  # jdbc:postgresql://rds-endpoint:5432/tinyurl
    volumes:
      - ./migrations:/flyway/sql:ro
    depends_on:
      - app
    networks:
      - tinyurl-net
    restart: on-failure
    
volumes:
  redis-data:
    driver: local
  prometheus-data:
    driver: local

networks:
  tinyurl-net:
    driver: bridge
```

**Environment variables (`.env` file, NOT committed):**
```
REDIS_PASSWORD=your-secure-redis-password-here
DB_PASSWORD=your-rds-password-here
FLYWAY_URL=jdbc:postgresql://tinyurl-db.c123abc.ap-south-1.rds.amazonaws.com:5432/tinyurl
```

---

### 2. PostgreSQL on RDS (Data Tier)

#### What We're Doing

**PostgreSQL 16** runs as **AWS RDS managed service** instead of Docker container.

#### Why RDS Instead of Docker PostgreSQL?

| Concern | Docker PostgreSQL | RDS |
|---|---|---|
| **Backups** | You script `pg_basebackup` + S3 | AWS automates daily snapshots |
| **PITR** | You manage WAL archiving | 35-day PITR window automatic |
| **Encryption at rest** | You set up EBS KMS | One checkbox at creation |
| **High availability** | Manual; no failover | Multi-AZ replication (optional, cheap) |
| **Patching** | You do it manually | AWS patches with maintenance windows |
| **Updates** | Manual version upgrades | AWS manages minor versions |
| **Monitoring** | You scrape `pg_stat_*` views | Free CloudWatch metrics |
| **Cost (v1)** | ~$3–5 (on EC2 bill) | $15/month |
| **Cost (v2, if you need HA)** | $3–5 | $30–50 (Multi-AZ) |

**Key insight:** RDS $15/month **removes all database operational burden**. You get professional backups, encryption, and monitoring for cheap.

#### RDS Configuration (v1)

**Specification:**
- **Instance:** `db.t3.micro` (burstable, 1 vCPU, 1 GB RAM)
- **Storage:** 5 GB gp3 (can grow auto-scaling, optional)
- **Backup retention:** 7 days (default; can extend to 35)
- **PITR:** Enabled (automatic)
- **Multi-AZ:** Disabled in v1 (single AZ; upgrade in v2.5 if needed)
- **Encryption:** KMS enabled at creation
- **Public:** No (private subnet, VPC access only)
- **Password:** Fetched from SSM Parameter Store at app startup

#### RDS Upgrade Path

| Phase | Config | Cost | Trigger |
|---|---|---|---|
| v1 | db.t3.micro, single-AZ | $15 | Baseline |
| v2 | db.t3.micro, single-AZ | $15 | Same as v1; no upgrade needed |
| v2.5 | db.t3.small, Multi-AZ | $50 | Traffic exceeds 5K avg QPS, latency increases |
| v3 | db.t3.medium, Multi-AZ + read replica | $120+ | >10K QPS with geographic distribution |

---

### 3. Redis Cache (v2+ Only)

#### What We're Doing

**Redis 7** runs as a **Docker container** on the same EC2 instance as the app.

#### Architecture: Cache-Aside Pattern

**Redirect flow (v2):**

```
User: GET /aB3xYz
  ↓
App: check Redis
  ├─ HIT (< 1 ms) → return cached UrlMapping
  │  └─ User redirected instantly
  ├─ MISS → query RDS
  │  ├─ RDS returns UrlMapping (5–10 ms)
  │  ├─ App caches it: Redis.set("url:aB3xYz", mapping, 1h)
  │  └─ User redirected
  └─ NEGATIVE HIT (code doesn't exist) → return 404 (60 ms max)
```

#### Why Cache is Critical at Scale

**Without cache (v1, at 5,000 QPS peak):**
```
5,000 requests/sec × 5 ms avg DB latency = 25 concurrent DB connections saturated
RDS connection pool exhausted
Response time: 500+ ms
User experience: slow
```

**With cache (v2, assuming 90% hit ratio):**
```
5,000 × 0.90 = 4,500 requests hit Redis (< 1 ms each)
5,000 × 0.10 = 500 requests hit RDS (5–10 ms)
DB connections needed: 2–5 (instead of 25)
Response time: < 80 ms
User experience: instant
```

**Impact:** Cache prevents a $50/month RDS upgrade (db.t3.micro → db.t3.small). **Cost savings: $35/month for $11 in Redis.**

#### Redis Memory & Persistence

**Memory allocation:**
- Max memory: `512 MB` (configured in docker-compose)
- Actual usage at v2 peak (5,000 QPS): ~28 MB
  - Cache entries: ~22 MB (10,000–100,000 URLs)
  - Rate limit tokens: ~5 MB (100,000 IPs)
  - Overhead: ~1 MB
- **Headroom:** 484 MB free (94% spare)

**Persistence strategy:**
- **Enabled via AOF** (Append-Only File): `--appendonly yes`
- Data survives container restarts ✅
- Cost: minimal disk I/O overhead
- Recovery time: ~10 seconds on startup

**Why persistence?**
- 🔓 **Cache data loss is tolerable** (source of truth is RDS; re-fetch on miss)
- ⚠️ **Rate limit data loss is risky** (attackers could immediately restart attacks)
- ✅ **AOF persistence survives both** without extra cost

#### TTL Strategy

| Data Type | TTL | Why | Actual Time in Memory |
|---|---|---|---|
| **Cached URL mapping** | 1h ± 10% | Balance: freshness + hit ratio. Most heavy traffic is within 1h of creation. | 54–66 minutes |
| **Negative cache (non-existent code)** | 60s ± 20% | Prevent bot attacks. Newly created codes become accessible within 1 min. | 48–72 seconds |
| **Rate limit token bucket** | 60s | Refill tokens every minute per IP. | 60 seconds |

**Eviction when full:**
- Policy: `allkeys-lru` (least-recently-used eviction)
- When Redis hits 512 MB, oldest unused entries are auto-deleted
- **You don't manage cleanup; Redis does it automatically**

#### EC2 Restart Impact on Cache

**Scenario: You deploy new code → EC2 restarts**

```
t0: EC2 shutdown
  ├─ Redis container stops
  ├─ Redis dumps AOF to disk
  └─ All in-memory cache is cleared

t0 + 30 sec: EC2 boots up
  └─ Docker containers start

t0 + 40 sec: Redis container online
  ├─ Reads AOF from appendonly.aof file
  ├─ Replays all cached entries from disk
  └─ Rate limit buckets restored

t0 + 50 sec: App container online
  └─ Starts serving requests

User impact:
├─ Users accessing at t0 + 30–50 sec: see "service unavailable"
├─ Users accessing at t0 + 50 sec onwards: normal operation
├─ Cache is warm (no cache misses for 50 sec post-deploy)
└─ Total downtime: ~50 seconds
```

**Distinction: Cache loss ≠ Data loss**
- Cache data (Redis): **Lost on restart**, but source of truth (RDS) is unaffected
- Real data (RDS): **Survives**, safely encrypted
- User experience: Slight latency increase during warm-up (5–10 seconds), then normal

---

### 4. Rate Limiting (Nginx + Redis)

#### Two-Layer Defense

**Layer 1: Nginx (Cheap, Stateless)**

```nginx
# In nginx.conf
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=40r/m;
limit_req_status 429;

server {
    location /api/urls {
        # Create endpoint: 40 req/min per IP
        limit_req zone=api_limit burst=5 nodelay;
        proxy_pass http://app:8080;
    }
}
```

- Per-IP rate limiting
- Tracked in Nginx in-memory zone (10 MB zone, ~160K IPs)
- Returns 429 (Too Many Requests) if limit exceeded
- **Cost:** Zero extra (pure Nginx config)
- **Downside:** Only works per Nginx instance; doesn't coordinate if you scale

**Layer 2: Redis Token Bucket (Sophisticated, v2)**

From [docs/lld/rate-limiting-module.md](../lld/rate-limiting-module.md):

```java
@PostMapping("/api/urls")
public ResponseEntity<UrlResponse> shortenUrl(
    @RequestBody UrlRequest request,
    HttpServletRequest httpRequest) {
    
    String clientIp = httpRequest.getHeader("X-Forwarded-For");
    
    // Redis token bucket: 40 tokens/minute per IP
    boolean allowed = rateLimiter.allowRequest(clientIp);
    
    if (!allowed) {
        return ResponseEntity
            .status(429)
            .header("Retry-After", "60")
            .build();
    }
    
    // ... create short URL ...
}
```

**How it works:**
```
Key: rate-limit:192.168.1.100
Value: {"tokens": 40, "refillTime": 1234567890, "ttl": 60s}

On request from 192.168.1.100:
├─ Check Redis key
├─ If expired: reset to 40 tokens
├─ If tokens > 0: consume 1, return true
└─ If tokens == 0: return false (429 Too Many Requests)

Tokens refill at 40 per minute (sliding window)
```

**Benefits:**
- Distributed: works across multiple app instances
- Flexible: can vary limits by endpoint or user tier
- Sophisticated: token bucket is industry standard for rate limiting

**Cost:** Uses Redis memory (~5 MB for 100K IPs), no extra cost

#### Defense Against Attacks

**Bot attacking `/api/urls` at 100 req/min:**

```
Request 1–40: Allowed (tokens consumed)
Request 41: Denied (429 response)
Attacker gives up or backs off
```

**On EC2 restart (rate limit data lost):**
```
Rate limit tokens cleared from Redis
Attacker restarts flooding immediately
├─ First 40 requests allowed
├─ Next 1,000 requests blocked
└─ Defense is reset but effective quickly
```

**Mitigation: Enable AOF persistence** (already configured in docker-compose):
```yaml
redis:
  command: redis-server --appendonly yes
```

---

## Cost Breakdown

### Monthly Infrastructure Costs

#### v1 Baseline (No Cache)

| Component | Instance | Rate | Monthly | Notes |
|---|---|---|---|---|
| **EC2** | t3.small | $0.0277/hr × 730 hrs | **$20.21** | 1 vCPU, 2 GB RAM; includes boot volume |
| **EBS** | 30 GB gp3 | $0.08/GB | **$2.40** | Root volume for OS + Docker images |
| **RDS** | db.t3.micro | $0.015 × 730 hrs | **$10.95** | PostgreSQL 16; includes 5 GB storage + backups |
| **SSM** | Parameter Store | $0.04/req × 100 req/day | **$1.20** | Secrets fetch at app boot + runtime |
| **CloudWatch** | Alarms (3) | $0.10 each | **$0.30** | CPU, disk, connections monitoring |
| | | | |
| **v1 Total** | | | **~$35.06** | Rounded to **$35/month** |

#### v2 With Cache

| Component | Instance | Rate | Monthly | Change | Notes |
|---|---|---|---|---|---|
| **EC2** | t3.small | $0.0277/hr × 730 hrs | **$20.21** | — | Same; Redis runs in Docker |
| **EBS** | 30 GB gp3 | $0.08/GB | **$2.40** | — | Same |
| **RDS** | db.t3.micro | $0.015 × 730 hrs | **$10.95** | — | Same capacity; lower load from cache |
| **Redis** | Docker | $0 | **$0** | +$0 | Runs on EC2; cost is EC2 CPU share |
| **SSM** | Parameter Store | $0.04/req × 100 req/day | **$1.20** | — | Same |
| **CloudWatch** | Alarms (5) | $0.10 each | **$0.50** | +$0.20 | +2 alarms for cache metrics |
| | | | |
| **v2 Total** | | | **~$35.26** | +$0.20 | Rounded to **$35/month** |

#### v2 With ElastiCache (Optional Upgrade)

| Component | Instance | Rate | Monthly | Change | Notes |
|---|---|---|---|---|---|
| All v2 components | — | — | $35.26 | — | Same as above |
| **ElastiCache** | cache.t3.micro | $0.015/hr | **$10.95** | +$10.95 | AWS-managed Redis; better SLA |
| | | | |
| **v2 + ElastiCache** | | | **~$46.21** | +$10.95 | More expensive, more reliable |

#### v2.5 Scaled (Post-Viral Growth)

| Component | Instance | Rate | Monthly | Notes |
|---|---|---|---|---|
| **EC2** | t3.medium (2vCPU) | $0.0554/hr × 730 | **$40.42** | Upgrade if EC2 CPU hits 80% |
| **EBS** | 50 GB gp3 | $0.08/GB | **$4.00** | More space for Prometheus |
| **RDS** | db.t3.small (2 vCPU) | $0.030/hr × 730 | **$21.90** | Upgrade if DB connection pool saturates |
| **RDS Multi-AZ** | Replication | +100% | **+$21.90** | HA upgrade if traffic justifies SLA |
| **ElastiCache** | cache.t3.small | $0.030/hr | **$21.90** | If traffic supports higher throughput |
| | | | |
| **v2.5 Total (scaled)** | | | **~$110/month** | With Multi-AZ + upgraded tier |

### 3-Year Cost Projection

| Phase | Timeline | Monthly | Annual | 3-Year Total | Notes |
|---|---|---|---|---|---|
| v1 | Months 1–3 | $35 | $420 | **$1,260** | Baseline |
| v1 (steady) | Months 4–12 | $35 | $420 | | Same |
| v2 | Months 13–18 | $35 | $420 | ~$2,520 | Cache adds minimal cost |
| v2 + ElastiCache | Months 19–24 | $46 | $552 | | Upgrade if reliability matters |
| v2.5 + HA | Months 25–36 | $110 | $1,320 | | Scales for viral growth |
| | | | | |
| **3-Year Total (conservative)** | | — | — | **~$3,780** | v1→v2 path without ElastiCache |
| **3-Year Total (with HA)** | | — | — | **~$5,400** | includes Multi-AZ + ElastiCache |

### Cost Comparison: This vs. Alternatives

| Platform | Setup | Monthly | 3-Year Total | Trade-offs |
|---|---|---|---|---|
| **Your design (EC2 + RDS + Docker Redis)** | DIY | $35–50 | $3,800–5,400 | Full control; some ops burden |
| **Heroku** | Deploy → managed | $150–300 | $5,400–10,800 | Simple; expensive; less control |
| **AWS Amplify + DynamoDB** | Fully managed | $50–100 | $1,800–3,600 | Simple; limited customization |
| **DigitalOcean App Platform** | Managed | $80–120 | $2,880–4,320 | Cheaper than Heroku; less mature |
| **Self-hosted bare metal** | Buy hardware | $1,500 upfront | $1,500–2,000 | Cheapest long-term; high effort |

**Conclusion:** Your hybrid approach is **cost-optimal for the scope** — cheaper than Heroku, more managed than bare metal.

---

## Data Durability & Persistence

### PostgreSQL (RDS) — Source of Truth

#### Durability Guarantees

| Layer | Mechanism | Impact |
|---|---|---|
| **Transaction durability** | `synchronous_commit = on` (RDS default) | Write must be flushed to disk before `COMMIT` returns. ~1–10 ms per write. |
| **Backups** | Automated daily snapshots | 7-day retention; can restore to any point in that window |
| **PITR** | Point-in-Time Recovery | 35-day WAL retention; restore to any second in the window |
| **Encryption** | KMS at-rest (RDS default) | All data on disk is encrypted with customer-managed or AWS-managed keys |
| **Multi-AZ** | Synchronous replication (optional v2.5+) | Standby replica in different AZ; automatic failover on primary failure |

#### Recovery Time Objectives

| Failure Scenario | RTO (Time to Recover) | RPO (Data Loss) |
|---|---|---|
| **Container crash** | Automatic recovery by Docker (< 30 sec) | Zero (data on RDS) |
| **EBS volume failure** | 5–10 minutes (RDS snapshot restore) | < 5 minutes (based on backup frequency) |
| **AZ failure** | < 2 minutes (Multi-AZ automatic failover) | Zero (synchronous replication) |
| **Regional disaster** | Manual restore to different region (30+ min) | Depends on backup strategy |

#### Backup Data

```
Retention: 7 days by default
├─ Daily snapshot: 1 GB × 7 = 7 GB max stored
├─ S3 storage cost: ~$0.23/month
├─ Can extend to 35 days: slight cost increase
└─ RDS manages storage automatically
```

**Data safety:** Losing RDS data requires **simultaneous failure of all snapshots + WAL archive**, which is virtually impossible.

---

### Redis (Cache) — Ephemeral, Not Critical

#### Persistence (v2)

**Mechanism: AOF (Append-Only File)**

```bash
redis-server --appendonly yes
```

- Every write command is appended to `appendonly.aof` file
- On restart, Redis reads the file and replays commands
- **Data survives:** container restarts, EC2 brief crashes
- **Data lost:** EC2 permanent failure (unless you have EBS snapshots)

#### Recovery Behavior

| Failure | Cache Data | User Impact | Recovery |
|---|---|---|---|
| Container restart | Restored from AOF (10 sec) | Latency spike (first 100 requests); then normal | Automatic |
| EC2 reboot | Restored from AOF (same) | Same as above | Automatic |
| EC2 EBS failure | Lost | Temporary cache miss; queries hit RDS (slower) | Automatic (RDS provides fallback) |
| Redis corrupts AOF | Lost (can't parse) | Cache acts empty; all queries → RDS | Wipe cache, restart |

#### Why Cache Data Loss Is Acceptable

> **RDS is the source of truth. Redis is a performance layer, not a data layer.**

Example flow:
```
Cache entry for url:amazon123abc lost
↓
User clicks link immediately after
↓
App queries Redis → MISS
↓
App queries RDS → HIT → returns mapping
↓
App re-caches for 1 hour
↓
Next user gets fast redirect (cached)
```

**No data loss from a user's perspective. Just slower latency on one redirect.**

---

### Secrets Management (SSM Parameter Store)

#### How It Works

1. **At EC2 instance launch:** IAM role is attached that grants `ssm:GetParameters` + `ssm:GetParameter`.
2. **App startup:** Spring Cloud AWS reads `spring.config.import: aws-parameterstore:/tinyurl/prod/`
3. **Parameters resolved:**
   - `/tinyurl/prod/spring.datasource.password` → RDS password
   - `/tinyurl/prod/spring.redis.password` → Redis password (v2)
   - `/tinyurl/prod/server.ssl.key-store-password` → TLS cert password
4. **No credentials in code/env/docker-compose.yml** ✅

#### Security Properties

| Property | Status | Details |
|---|---|---|
| **Encrypted at rest** | ✅ Yes | KMS encryption; only IAM role can decrypt |
| **Encrypted in transit** | ✅ Yes | All SSM API calls are over HTTPS |
| **Audit trail** | ✅ Yes | CloudTrail logs all parameter access |
| **Access control** | ✅ Yes | IAM role = only this EC2 instance can fetch |
| **Secrets in code** | ✅ No | Never; always resolved at runtime |

#### Cost of SSM

```
Standard API calls: $0.04 per 10,000 requests
At app startup: ~10 parameters fetched = 1 request
Per day: 24 startups (estimated) = 24 requests
Per month: 24 × 30 = 720 requests
Per month cost: 720 / 10,000 × $0.04 = $0.003 (included in $1.20 estimate)
```

**Essentially free.**

---

## Capacity Planning & Projections

### Data Growth (RDS)

#### Per-Row Size

| Field | Size |
|---|---|
| `id` (BIGINT) | 8 bytes |
| `short_code` (avg 7 chars) | 10 bytes |
| `original_url` (avg 100 chars) | 104 bytes |
| `created_at` (TIMESTAMPTZ) | 8 bytes |
| `expires_at` (TIMESTAMPTZ) | 8 bytes |
| Row overhead | 23 bytes |
| **Total** | **~161 bytes** |

#### Projections

| Timeframe | Annual URLs Created | Total Rows | Raw Data | With Indexes (2×) | RDS Sufficient? |
|---|---|---|---|---|---|
| Year 1 | 1 million | 1M | 154 MB | ~308 MB | ✅ 5 GB easily |
| Year 2 | 5 million | 6M | 924 MB | ~1.8 GB | ✅ 5 GB comfortable |
| Year 3 | 4 million | 10M | 1.5 GB | ~3.0 GB | ✅ 5 GB adequate |
| Year 4 | 10 million | 20M | 3.0 GB | ~6.0 GB | ⚠️ Resize to 10 GB |

**Conclusion:** RDS 5 GB default is **sufficient through year 3.5**. By year 4, resize to 10 GB (online, zero downtime on RDS).

---

### Redis Memory Usage (v2)

#### Worst-Case Scenario — Peak Traffic

**Scenario: 20K QPS peak (v2 end state), 1 hour sustained**

```
Traffic per hour: 20K QPS × 3,600 sec = 72 million hits
Unique URLs accessed: ~100K (assume 80:20 pareto distribution)
Cache size: 100K entries × 220 bytes = 22 MB

Rate limit data:
Assume 1 million concurrent IPs (unrealistic, but worst case)
But realistically: ~100K concurrent IPs from same region
Rate limit size: 100K × 50 bytes = 5 MB

Prometheus metrics: ~2 MB
─────────────────
Total: ~29 MB at absolute peak
```

**Headroom in 512 MB allocation:** 483 MB free (94% spare).

#### Growth Curve

| Phase | Peak QPS | Cache Size | Headroom | Status |
|---|---|---|---|---|
| v1 | 1K | < 5 MB | 507 MB | ✅ No issue |
| v2 early | 2K | ~8 MB | 504 MB | ✅ No issue |
| v2 mid | 5K | ~15 MB | 497 MB | ✅ No issue |
| v2 late | 10K | ~22 MB | 490 MB | ✅ No issue |
| v2.5 peak | 20K | ~28 MB | 484 MB | ✅ Still OK |
| v3 (multiple EC2) | >50K | — | — | ⚠️ Upgrade to ElastiCache cluster |

**Conclusion:** Docker Redis on EC2 **never reaches memory limits** for v1–v2.5 scope.

---

### EC2 Disk Space (EBS)

#### Breakdown

```
30 GB gp3 allocation:

├─ OS (Amazon Linux 2): 2.0 GB
├─ Docker daemon: 0.5 GB
│
├─ Container images (cached):
│  ├─ nginx:1.25-alpine: 30 MB
│  ├─ openjdk:21-slim: 300 MB
│  ├─ redis:7-alpine: 30 MB
│  ├─ prometheus:latest: 200 MB
│  ├─ other (base, flyway): 200 MB
│  └─ Subtotal: 760 MB
│
├─ Application data:
│  ├─ Prometheus time-series data: 1.0 GB/month growth
│  ├─ Logs (Docker JSON): ~100 MB/month
│  ├─ Redis AOF: ~22 MB (cache contents, ephemeral)
│  └─ Subtotal: ~1.1 GB/month
│
├─ Available for growth: 30 GB - 3.3 GB existing = 26.7 GB
│
└─ Growth rate:
   Year 1: 1.1 GB/month × 12 = 13.2 GB
   Year 2: 1.1 GB/month × 12 = 13.2 GB (total 26.4 GB)
   Year 3: 1.1 GB/month × 12 = 13.2 GB (hits 30 GB limit)
```

#### Action Required by Year 3

**At 26 GB usage (month 36), you'll need to:**

1. **Archive old Prometheus data** (easy — compress local data to S3)
2. **Rotate logs** (already done by Docker; old logs evicted)
3. **Upgrade EBS volume to 50 GB** (online resize, zero downtime)

**Cost of upgrade:** 50 GB gp3 = $4.00/month (increase from $2.40 = +$1.60/month).

#### Sudden Traffic Surge Impact on Disk

**Question:** If traffic spikes 10×, does disk fill up?

**Answer:** No, because:
- Traffic spikes don't write to disk (only to Redis in-memory).
- Prometheus writes time-series data, but that grows over days/weeks, not hours.
- Only persistent growth is Prometheus metrics + logs, which grow slowly.

**Disk is not a bottleneck during traffic surges.**

---

## Failure Modes & Recovery

### Failure Scenario Matrix

#### Scenario 1: Redis Container Crashes

```
t0: Redis process OOMKilled (out of memory)
  └─ Docker automatically restarts it (restart policy: unless-stopped)

t0 + 30 sec: Redis online again
  ├─ Reads appendonly.aof file
  ├─ Replays all commands to restore state
  └─ Ready to serve cache requests

Cache during downtime (t0 → t0 + 30 sec):
├─ App can't reach Redis
├─ Circuit breaker opens (after 5 attempts)
├─ App treats all Redis operations as cache misses
├─ All requests hit RDS (slower, but survive)

User impact:
├─ Latency spike to ~50–100 ms (1 request)
├─ Auto-recovery within 30 seconds
├─ No data loss

Recovery: AUTOMATIC
```

---

#### Scenario 2: EC2 Instance Reboots (Deployment)

```
t0: You trigger deployment
  ├─ GitHub Actions builds Docker image
  ├─ Pushes to GHCR
  └─ Runs: ssh ec2-instance "docker-compose pull && docker-compose up -d"

t0 + 10 sec: All containers stop gracefully
  ├─ Nginx: closes connections
  ├─ Spring Boot: triggers shutdown hooks
  ├─ Redis: flushes appendonly.aof to disk
  └─ All pending transactions complete

t0 + 20 sec: EC2 pulls new images and starts them
  ├─ Nginx comes online: listens on :80, :443
  ├─ Redis comes online: reads AOF, replays state
  ├─ Spring Boot starts: connects to RDS, reads SSM secrets
  └─ Flyway runs: applies any pending migrations
  
t0 + 40 sec: All services fully online
  └─ Ready to serve traffic

User impact:
├─ t0–t0 + 40 sec: Connection refused (HTTP 503)
├─ t0 + 40 sec onward: Normal operation
├─ First 50 requests post-deploy: cache misses (slightly slower)
├─ Total downtime: 40 seconds
├─ No data loss

Recovery: AUTOMATIC
Downtime: ~40 seconds (acceptable for non-SLA services)
```

---

#### Scenario 3: RDS Master Failure (Single-AZ, v1)

```
t0: RDS storage failure in AZ
  └─ AWS detects failure via heartbeat loss

t0 + 2 min: Automatic failover to recent snapshot
  ├─ New DB instance spins up from backup
  ├─ Latest backup is < 5 minutes old
  └─ Data loss: < 5 minutes of transactions

Connection recovery:
├─ App connection pool times out (30 sec)
├─ Connection pool reconnects to new RDS endpoint
├─ Springs Boot auto-detects and retries
├─ App resumes serving requests

User impact:
├─ Latency spike to ~1–2 sec per request (while RDS recovering)
├─ ~5 minutes of transactions may be replayed (< 100 URLs in worst case)
├─ Service unavailable: ~3–5 minutes

Recovery time: 3–5 minutes
Data loss: < 5 minutes (tolerable for URL shortener)
Frequency: < 0.1% per month (AWS SLA)

NOTE: In v1 (single-AZ), this is manual. Upgrade to Multi-AZ (v2.5) for automatic recovery.
```

---

#### Scenario 4: EC2 Permanent Failure (Rare)

```
t0: EC2 hardware failure
  └─ AWS replaces the host

t0 + 1 min: EC2 is unreachable

Recovery:
├─ You must launch a new EC2 instance
├─ Or: AWS can retry on different hardware (bounced instance)
├─ Attach same EBS volume or use AMI snapshot

t0 + 5–10 min: New EC2 online
  ├─ Docker pulls images
  ├─ Services start
  ├─ RDS data is untouched (separate service)
  └─ Service resumes

Data safety:
├─ PostgreSQL (RDS): Fully intact ✅
├─ Redis cache: Lost (but ephemeral)
├─ EBS volume: Lost (but can restore from snapshot)

User impact:
├─ t0–t0 + 10 min: Complete unavailability
├─ t0 + 10 min onward: Service resumes
└─ Total downtime: ~10 minutes

Recovery time: 10 minutes (manual intervention required)
Frequency: 0.01 per month (per AWS stats)

Mitigation (v2+): Use Auto Scaling Group + stateless app for automatic recovery.
```

---

#### Scenario 5: DDoS / Rate Limit Breach

```
t0: Attacker sends 1,000 requests/sec to /api/urls create endpoint

Layer 1 (Nginx):
├─ Rate limit zone: 40 req/min per IP
├─ Burst: 5 requests allowed
├─ Attack request 1–40: passed to app
├─ Attack request 41+: return 429 immediately (< 1 ms)
├─ Database saved from any requests beyond first 40

Layer 2 (Redis, v2):
├─ Token bucket for same IP: starts with 40 tokens
├─ Request 1–40: consumed from bucket
├─ Request 41+: return 429 from app-level check
├─ Defense-in-depth: if attacker bypasses Nginx, Redis still protects

Impact:
├─ App receives maybe 40–50 requests from attacker per minute
├─ RDS receives maybe 40–50 inserts per minute (negligible)
├─ Other users unaffected (different IPs are rate-limited separately)
├─ Bandwidth used: 40 × 100 bytes = 4 KB/min (trivial)

Recovery: AUTOMATIC
Network bill impact: Zero (AWS doesn't charge for DDoS within reason)
```

---

### Runbook: Recovery Procedures

#### Recovery: Redis OOMKilled (Out of Memory)

**Symptom:** Redis is unreachable; app logs show `ConnectionRefused` on cache operations.

**Recovery:**
```bash
# SSH into EC2
ssh -i /path/to/key ec2-user@your-ec2-ip

# Check Docker logs
docker logs tinyurl-redis

# Restart Redis
docker restart tinyurl-redis

# Verify it's healthy
docker ps | grep redis
# Status should say "Up" and healthcheck "healthy"

# Check RDS to ensure no data loss
# (RDS is unaffected; this is just cache)
```

**Time to fix:** 30 seconds (automatic restart)

---

#### Recovery: RDS Connection Timeout

**Symptom:** App logs show `SocketTimeoutException` on database queries.

**Recovery:**
```bash
# SSH into EC2
ssh ec2-user@your-ec2-ip

# Check app logs for more details
docker logs tinyurl-app | tail -50

# Verify RDS is reachable
# Check Security Group: RDS security group must allow EC2 security group on port 5432
# Check VPC subnet: EC2 and RDS must be in same VPC

# If RDS is unreachable:
# 1. Verify RDS is running (AWS Console → RDS Instances)
# 2. Check its security group allows EC2
# 3. Restart RDS (AWS Console → Instances → Reboot)

# If RDS is reachable but connection pool is exhausted:
docker exec tinyurl-app curl -s http://localhost:8080/actuator/health/db

# If it shows "DOWN", increase HikariCP pool size:
# Edit docker-compose.yml:
#   SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "20" (increase from 10)

docker-compose up -d
```

**Time to fix:** 5–10 minutes

---

#### Recovery: EC2 Disk Full

**Symptom:** Docker can't start new containers; Prometheus can't write metrics.

**Recovery:**
```bash
# Check disk usage
df -h /

# If > 90%, clean up old Docker images/containers
docker system prune -a  # WARNING: removes all unused images

# If still full, archive Prometheus data to S3
docker exec tinyurl-prometheus tar czf /prometheus/backup.tar.gz /prometheus/data

aws s3 cp backup.tar.gz s3://your-backup-bucket/prometheus-backup-$(date +%s).tar.gz

# Clear Prometheus data
docker rm tinyurl-prometheus
rm -rf /var/lib/docker/volumes/prometheus-data/_data

# Restart Prometheus
docker-compose up -d prometheus

# Resize EBS volume (online, no downtime)
# AWS Console → Volumes → Select volume → Modify
# Increase size to 50 GB
# Then on EC2: sudo growpart /dev/xvda 1 && sudo resize2fs /dev/xvda1
```

**Time to fix:** 15–20 minutes

---

#### Recovery: EC2 Needs Manual Restart

**Symptom:** Services are unresponsive; logs are lagging.

**Recovery:**
```bash
# **WARNING: This causes ~40 sec downtime**

ssh ec2-user@your-ec2-ip

# Graceful shutdown
docker-compose down

# Bring everything back up
docker-compose up -d

# Verify all services are healthy
docker-compose ps

# Status should show:
# nginx: Up, health: healthy
# app: Up, health: healthy
# redis: Up, health: healthy
# (v2 only)

# Tail logs to confirm startup
docker-compose logs -f
```

**Time to fix:** 1 minute (includes downtime)

---

### Monitoring & Alerting

#### CloudWatch Alarms (Recommended)

Create these alarms to catch issues early:

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name tinyurl-ec2-cpu-high \
  --alarm-description "EC2 CPU exceeds 80%" \
  --metric-name CPUUtilization \
  --namespace AWS/EC2 \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:ap-south-1:ACCOUNT:email-topic

aws cloudwatch put-metric-alarm \
  --alarm-name tinyurl-ebs-disk-high \
  --alarm-description "EBS disk usage exceeds 75%" \
  --metric-name DiskSpaceUsage \
  --namespace CWAgent \
  --statistic Average \
  --threshold 75 \
  --comparison-operator GreaterThanThreshold

aws cloudwatch put-metric-alarm \
  --alarm-name tinyurl-rds-connections \
  --alarm-description "RDS connections exceeds 150" \
  --metric-name DatabaseConnections \
  --namespace AWS/RDS \
  --threshold 150 \
  --comparison-operator GreaterThanThreshold
```

#### Prometheus Dashboards (Local)

Access at `http://localhost:9090` from EC2:

```promql
# Cache hit ratio (v2+)
cache_requests_total{result="hit"} / cache_requests_total

# Database query latency
rate(response_time_seconds_sum{endpoint="/api/urls"}[5m]) / rate(response_time_seconds_count{endpoint="/api/urls"}[5m])

# Redis memory usage
redis_memory_used_bytes / redis_maxmemory_bytes
```

---

## Scaling Path (v1 → v2 → v3)

### Phase 1: v1 Foundation (Months 1–3)

**Scope:**
- EC2 t3.small + RDS db.t3.micro
- No cache
- Nginx rate limiting only
- Single-AZ

**Traffic targets:**
- Avg: 100–500 QPS
- Peak: 1,000 QPS

**Cost:** $35/month

**Metrics to monitor:**
- RDS CPU: should stay < 20%
- EC2 CPU: should stay < 30%
- Redirect P95 latency: target < 100 ms
- Create endpoint P95 latency: target < 200 ms

**Upgrade trigger:** If RDS CPU > 60% or P95 latency > 150 ms

---

### Phase 2: v2 Cache Layer (Months 4–6)

**Changes:**
- Add Docker Redis (v2 only)
- Implement cache-aside for redirect path
- Add Redis-backed rate limiting for /api/urls
- Aim for 90% cache hit ratio

**New costs:**
- +$0 (Redis runs on existing EC2)
- Optionally add CloudWatch alarms: +$0.20

**Traffic targets:**
- Avg: 1,000 QPS
- Peak: 5,000 QPS

**Cost:** $35.20/month (essentially unchanged)

**Validation:**
- Cache hit ratio: > 90%
- P95 latency with cache: < 80 ms
- RDS query rate: < 10% of redirect traffic

**Upgrade trigger:** If cache hit ratio < 80% or traffic exceeds 5K avg QPS

---

### Phase 2.5: ElastiCache Migration (Optional, Months 12–18)

**Decision point:** "Is Redis reliability worth $11/month?"

**Triggers for upgrade to ElastiCache:**
- Data loss events from Redis becoming a problem
- Need for encrypted backups + PITR
- Want AWS-managed HA

**Changes:**
- Migrate from Docker Redis → ElastiCache cache.t3.micro
- App connection string changes (environment variable)
- Zero code changes required

**New cost:** +$11/month (total $46)

**Benefits:**
- AWS SLA: 99.95% uptime
- Automatic failover + replication
- Encrypted at rest + in transit
- Daily automated snapshots

---

### Phase 2.5+: RDS Upgrade (If Traffic Exceeds 5K QPS Avg)

**Trigger:** RDS CPU > 70% or connection pool > 80% utilized

**Changes:**
- RDS: db.t3.micro → db.t3.small (2 vCPU, 2 GB RAM)
- Enables Multi-AZ sync replica for HA

**New cost:** +$20/month (total $55–65)

**Benefits:**
- 2× database processing power
- Multi-AZ automatic failover (< 2 min RTO)
- Sync replication: 0 data loss

---

### Phase 3: Horizontal Scaling (Year 2, if traffic grows > 10K avg QPS)

**Major architecture change:**

```
User traffic
    │
    ▼
┌──────────────────────┐
│  Application Load    │
│  Balancer (ALB)      │
└─────────┬────────────┘
          │
    ┌─────┼─────┐
    ▼     ▼     ▼
  ┌─────────────────┐
  │  Auto-Scaling   │
  │  Group (ASG)    │
  │                 │
  │  EC2 t3.small   │ (multiple instances)
  │  ├─ Docker      │
  │  │  ├─ Nginx    │
  │  │  └─ App      │
  │  │              │
  │  └─ (no Redis)  │ (shared ElastiCache)
  └────────┬────────┘
           │
      ┌────┼─────┐
      ▼    ▼     ▼
  External services
  ├─ RDS Multi-AZ (db.t3.medium)
  ├─ ElastiCache Cluster (cache.t3.small)
  └─ CloudFront (optional, for static redirects)
```

**Changes:**
- EC2 → ASG: 3+ app instances with auto-scaling based on CPU/ALB target group health
- RDS: db.t3.small → db.t3.medium + Multi-AZ enabled
- ElastiCache: cache.t3.micro → cache.t3.small (or cluster mode)
- Add ALB for load balancing ($0.022/hour = ~$16/month)
- Add CloudFront for redirect caching at edge (optional)

**New cost:** ~$200–250/month (10× increase)

**Benefits:**
- Handles 50K+ QPS
- Automatic scaling: adds instances on demand
- No single point of failure
- Geographic distribution possible (CloudFront)

---

### Cost Comparison: Growth Path

| Phase | Timeline | Config | Monthly Cost | Cumulative |
|---|---|---|---|---|
| v1 | Months 1–3 | EC2 + RDS only | $35 | $105 |
| v1 → v2 | Months 4–6 | Add Docker Redis | $35 | $210 |
| v2 → v2.5 | Months 13–18 | RDS upgrade + ElastiCache | $55 | $495 |
| v2.5 → v3 | Year 2+ | ALB + ASG + upgraded DB | $220 | $2,640+ |

---

## Infrastructure Configuration

### EC2 Instance Setup

#### Terraform Configuration

```hcl
# main.tf
terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "ap-south-1"
}

# VPC and networking
resource "aws_vpc" "tinyurl" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  
  tags = {
    Name = "tinyurl-vpc"
  }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.tinyurl.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "ap-south-1a"
  map_public_ip_on_launch = true
  
  tags = {
    Name = "tinyurl-public-subnet"
  }
}

resource "aws_subnet" "private" {
  vpc_id            = aws_vpc.tinyurl.id
  cidr_block        = "10.0.2.0/24"
  availability_zone = "ap-south-1a"
  
  tags = {
    Name = "tinyurl-private-subnet"
  }
}

# Internet Gateway
resource "aws_internet_gateway" "tinyurl" {
  vpc_id = aws_vpc.tinyurl.id
  
  tags = {
    Name = "tinyurl-igw"
  }
}

# Route table for public subnet
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.tinyurl.id
  
  route {
    cidr_block      = "0.0.0.0/0"
    gateway_id      = aws_internet_gateway.tinyurl.id
  }
  
  tags = {
    Name = "tinyurl-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# EC2 Instance
resource "aws_instance" "tinyurl" {
  ami                         = data.aws_ami.amazon_linux_2.id
  instance_type               = "t3.small"
  subnet_id                   = aws_subnet.public.id
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.tinyurl.name
  
  root_block_device {
    volume_type           = "gp3"
    volume_size           = 30
    delete_on_termination = true
    encrypted             = true
  }
  
  tags = {
    Name = "tinyurl-app"
  }
  
  user_data = base64encode(file("${path.module}/user-data.sh"))
}

# IAM Role for EC2 (SSM Parameter access)
resource "aws_iam_role" "tinyurl" {
  name = "tinyurl-app-role"
  
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy" "ssm_access" {
  name = "ssm-parameter-access"
  role = aws_iam_role.tinyurl.id
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParameters",
          "ssm:GetParametersByPath"
        ]
        Resource = "arn:aws:ssm:ap-south-1:*:parameter/tinyurl/prod/*"
      },
      {
        Effect = "Allow"
        Action = "kms:Decrypt"
        Resource = "arn:aws:kms:ap-south-1:*:key/*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "tinyurl" {
  name = "tinyurl-app-profile"
  role = aws_iam_role.tinyurl.name
}

# RDS PostgreSQL
resource "aws_db_instance" "tinyurl" {
  identifier          = "tinyurl-db"
  engine              = "postgres"
  engine_version      = "16.2"
  instance_class      = "db.t3.micro"
  allocated_storage   = 5
  storage_type        = "gp3"
  storage_encrypted   = true
  
  db_name  = "tinyurl"
  username = "postgres"
  password = random_password.db_password.result
  
  db_subnet_group_name   = aws_db_subnet_group.tinyurl.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  
  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "mon:04:00-mon:05:00"
  
  skip_final_snapshot       = false
  final_snapshot_identifier = "tinyurl-final-snapshot-${formatdate("YYYY-MM-DD-hhmm", timestamp())}"
  
  enable_cloudwatch_logs_exports = ["postgresql"]
  
  tags = {
    Name = "tinyurl-postgres"
  }
}

resource "random_password" "db_password" {
  length  = 16
  special = true
}

# Store RDS password in SSM
resource "aws_ssm_parameter" "db_password" {
  name = "/tinyurl/prod/spring.datasource.password"
  type = "SecureString"
  value = random_password.db_password.result
}

# Security Groups
resource "aws_security_group" "ec2" {
  name   = "tinyurl-ec2-sg"
  vpc_id = aws_vpc.tinyurl.id
  
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "rds" {
  name   = "tinyurl-rds-sg"
  vpc_id = aws_vpc.tinyurl.id
  
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }
  
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Outputs
output "ec2_public_ip" {
  value = aws_instance.tinyurl.public_ip
}

output "rds_endpoint" {
  value = aws_db_instance.tinyurl.endpoint
}
```

#### User Data Script

```bash
#!/bin/bash
set -e

# Install Docker
amazon-linux-extras install docker -y
systemctl start docker
systemctl enable docker

# Install Docker Compose
curl -L "https://github.com/docker/compose/releases/download/v2.20.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose

# Create app directory
mkdir -p /opt/tinyurl
cd /opt/tinyurl

# Clone repository (or use CodeDeploy)
git clone https://github.com/buffden/tinyurl.git .

# Copy .env secrets from S3 (secure way)
aws s3 cp s3://your-config-bucket/tinyurl/.env .env

# Start services
docker-compose up -d

# Set up CloudWatch agent for disk monitoring
wget https://s3.amazonaws.com/amazoncloudwatch-agent/amazon_linux/amd64/latest/amazon-cloudwatch-agent.rpm
rpm -U ./amazon-cloudwatch-agent.rpm

cat > /opt/aws/amazon-cloudwatch-agent/etc/config.json <<EOF
{
  "metrics": {
    "namespace": "CWAgent",
    "metrics_collected": {
      "disk": {
        "measurement": [
          {
            "name": "used_percent",
            "rename": "DiskSpaceUsage"
          }
        ],
        "metrics_collection_interval": 300,
        "resources": ["/"]
      }
    }
  }
}
EOF

/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a query -m ec2 -c file:/opt/aws/amazon-cloudwatch-agent/etc/config.json -s
```

---

### RDS Database Setup

#### Migration Scripts (Flyway)

**File:** `migrations/V1__create_url_mappings_table.sql`

```sql
CREATE TABLE url_mappings (
  id BIGSERIAL PRIMARY KEY,
  short_code VARCHAR(10) NOT NULL UNIQUE,
  original_url TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL,
  created_ip INET,
  user_agent VARCHAR(255)
);

CREATE INDEX idx_short_code ON url_mappings(short_code);
CREATE INDEX idx_expires_at ON url_mappings(expires_at) WHERE expires_at IS NOT NULL;

-- Set synchronous_commit for durability (v1)
-- NOTE: This is per-database, set at instance level in RDS console
SET synchronous_commit = on;
```

**File:** `migrations/V2__add_soft_delete_columns.sql`

```sql
-- v2 additions
ALTER TABLE url_mappings ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
ALTER TABLE url_mappings ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_short_code_active ON url_mappings(short_code) INCLUDE (original_url, expires_at) WHERE is_deleted = FALSE;
```

---

### Docker Compose Detailed Config

See [Full Production docker-compose.yml](#docker-compose-structure) section above.

---

## Operational Runbooks

### Deployment Checklist

**Before deploying to production:**

```
□ Code reviewed and test passing
□ Load test completed (> 5K QPS on staging)
□ Database migrations tested on RDS staging
□ Environment secrets verified in SSM Parameter Store
□ Backup of current RDS snapshot created
□ Incident response plan reviewed
□ Team notified of deployment window

Deployment steps:
□ Pull latest code from main branch
□ Build Docker image: docker build -t ghcr.io/buffden/tinyurl-app:vX.Y.Z .
□ Push to GHCR: docker push ghcr.io/buffden/tinyurl-app:vX.Y.Z
□ SSH into EC2
□ cd /opt/tinyurl && git pull origin main
□ docker-compose pull
□ docker-compose up -d
□ Verify health: curl http://localhost:8080/actuator/health
□ Monitor logs: docker-compose logs -f app
□ Test redirect: curl -L http://localhost/test-code
```

---

### Incident Response

#### Alert: Redis Memory Full

```
1. Check memory: docker exec tinyurl-redis redis-cli INFO memory
2. If used_memory > maxmemory:
   a. Check eviction: redis-cli --stat
   b. If evictions are high (> 1/sec), increase maxmemory or upgrade to ElastiCache
3. No action needed if evictions < 1/sec (normal LRU behavior)
```

#### Alert: RDS CPU High

```
1. Check RDS metrics: AWS Console → RDS Instances → Metrics
2. If CPU > 80% for > 5 min:
   a. Scale RDS: db.t3.micro → db.t3.small (15–20 min downtime)
   b. Or: increase Read Replicas to offload read traffic
3. Check app logs for slow queries: docker logs tinyurl-app | grep SLOW_QUERY
```

#### Alert: Disk Nearly Full

```
1. Check disk: df -h /
2. If > 85%:
   a. Archive Prometheus data: docker exec prometheus tar czf /prometheus/archive.tar.gz /prometheus/data
   b. Upload to S3: aws s3 cp /var/lib/docker/volumes/prometheus-data/_data/archive.tar.gz s3://bucket/
   c. Wipe local: rm -rf /var/lib/docker/volumes/prometheus-data/_data/*
   d. Resize EBS: Grow from 30GB to 50GB (online, no downtime)
3. Monitor: du -sh /* to find large directories
```

---

## Summary & Decision Matrix

| Question | Answer | Phase |
|---|---|---|
| **Where does app run?** | Docker Compose on EC2 t3.small ($8–12/month) | v1+ |
| **Where does database run?** | AWS RDS PostgreSQL db.t3.micro ($15/month) | v1+ |
| **Where does cache run?** | Docker Redis on same EC2 (v2); ElastiCache optional ($11/month, v2.5+) | v2+ |
| **How are secrets managed?** | AWS SSM Parameter Store (encrypted, IAM-based) | v1+ |
| **How long does cache persist?** | 1 hour (±10% jitter) for URLs; 60s for rate limits; ephemeral | v2+ |
| **What if EC2 crashes?** | Data on RDS survives; cache lost (ephemeral); users fall back to DB | all versions |
| **What if RDS crashes?** | Automatic failover to backup (single-AZ v1); < 2 min RTO | v1 (manual); v2.5+ (automatic Multi-AZ) |
| **Total v1 cost?** | $35/month | v1 |
| **Total v2 cost?** | $35–50/month (minimal increase) | v2 |
| **At what scale do we upgrade?** | RDS: 5K+ avg QPS; EC2: 10K peak QPS; Redis: never (512 MB sufficient) | v2.5+ |
| **What's the path to HA?** | v1 → v2 (cache) → v2.5 (RDS Multi-AZ + ElastiCache) → v3 (ALB + ASG + multiple EC2) | Gradual |

---

## Conclusion

This hybrid architecture (EC2 + RDS + Docker Redis) provides:

✅ **Cost-optimal for v1–v2:** ~$35–50/month for the entire system  
✅ **Professional durability:** RDS managed backups + encryption  
✅ **Clear upgrade path:** Add cache → add Multi-AZ → add ASG  
✅ **Observability:** CloudWatch + Prometheus local metrics  
✅ **Security:** No secrets in code; IAM-based access; encrypted at rest  
✅ **Scalability:** Can handle 5K+ QPS with cache; path to 50K+ QPS with v3  

It's not a SaaS platform like Heroku ($150+/month), but it's professional, maintainable, and cost-conscious for a bootstrapped project.

---

**Document Version:** 1.0  
**Last Updated:** March 12, 2026  
**Maintainer:** Harsh Wardhan Patil
