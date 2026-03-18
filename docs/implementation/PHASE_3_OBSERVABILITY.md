# Phase 3: Observability

## Objective

Add production-grade logging, metrics, and health visibility to make runtime behavior measurable and debuggable. Includes structured JSON logging with correlation IDs, Prometheus-ready metrics instrumentation, health endpoint hardening, and operational runbooks for alerting and log aggregation.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)

## Source References

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [LLD C4 Diagrams](../lld/c4-diagrams.md)
- [HLD System Design](../hld/system-design/system-design.md)
- [Threat Model](../security/threat-model.md)

## In Scope

- Structured JSON logging with request correlation IDs
- Prometheus metrics for request rate, latency, and error rate
- Health endpoint hardening with readiness/liveness groups
- Alert baseline thresholds and triage procedures
- Log aggregation architecture (Loki + Promtail + Grafana)
- Production-grade operational documentation

## Out of Scope

- Distributed tracing (defer unless required)
- Multi-region log replication
- Advanced dashboard customization

---

## Part 1: Implementation Details

### What Was Implemented

#### 1) Structured JSON Logging

**Files:**
- `tinyurl/src/main/resources/logback-spring.xml`
- `tinyurl/src/main/java/com/tinyurl/observability/RequestObservabilityFilter.java`

**Changes:**
- Logging output is now JSON using logstash-logback-encoder
- Correlation ID is propagated via `X-Correlation-Id` header
- Correlation ID is added to MDC as `correlation_id`
- Request metadata logged: method, route, status, duration, client_ip, user_agent

**Why:** Makes logs machine-parseable, searchable, and enables request-level traceability across services and failures.

#### 2) Metrics Instrumentation

**Files:**
- `tinyurl/build.gradle.kts` (Prometheus registry dependency)
- `tinyurl/src/main/java/com/tinyurl/observability/RequestObservabilityFilter.java`
- `tinyurl/src/main/java/com/tinyurl/controller/GlobalExceptionHandler.java`
- `tinyurl/src/main/resources/application.yaml`

**Metrics added:**
- `tinyurl.http.server.requests.total` - counter with tags: method, route, status_class, outcome
- `tinyurl.http.server.request.duration` - timer with tags: method, route, status
- `tinyurl.http.server.errors.total` - counter with tags: status, error_code (normalized to bounded set)
- HikariCP connection pool metrics (automatic)
- Percentiles at 95th and 99th for request duration

**Why:** Supports request rate, latency, and error-rate monitoring; enables baseline alert conditions.

#### 3) Health Model Hardening

**Files:**
- `tinyurl/src/main/resources/application.yaml`
- `tinyurl/src/main/resources/application-prod.yaml`

**Changes:**
- Component-level health details set to `when_authorized` (security baseline)
- Health groups defined:
  - `readiness`: includes readiness state, db, diskSpace, ping
  - `liveness`: includes liveness state, ping
- Default (dev): metrics/prometheus endpoints exposed
- Production: only health endpoint exposed (via `application-prod.yaml`)

**Why:** Improves dependency-aware readiness behavior, makes startup/degraded states visible, and prevents operational data leaks in production.

### Verification Steps

1. **Unit/integration tests**
   - Run tests for app context, service logic
   - Expected: all passing

2. **Runtime verification**
   - Bring up the full stack with `docker compose up -d --build`.
   - Hit `/actuator/health/readiness` and `/actuator/health/liveness` and confirm both return `UP`.
   - Hit `/actuator/prometheus` (dev profile only) and verify `tinyurl_http_server_requests_total` and HikariCP metrics appear in the output.

3. **Error metric trigger**
   - Send invalid request
   - Confirm `tinyurl_http_server_errors_total` increments

---

## Part 2: Alert Baseline

### SLO-aligned Starter Alerts

#### 1. Error Rate Alert
- **Condition**: 5xx rate > 1% over 5 minutes
- **Signal**: `tinyurl.http.server.requests.total{status_class="5xx"}`
- **Action**: Check recent deploy, DB state, app logs by `correlation_id`

#### 2. Latency Alert
- **Condition**: P99 latency > 500ms over 10 minutes
- **Signal**: `http.server.requests` percentile metrics
- **Action**: Inspect slow endpoints, DB pool pressure, host CPU/memory

#### 3. Readiness Degradation
- **Condition**: `/actuator/health/readiness` not `UP` for 2+ consecutive checks
- **Signal**: Readiness endpoint status
- **Action**: Inspect DB connectivity, Flyway migration state

#### 4. Liveness Instability
- **Condition**: Frequent restarts or liveness failures in 10 minutes
- **Signal**: Container restart count + `/actuator/health/liveness`
- **Action**: Inspect fatal exceptions, memory pressure, runtime mismatches

### Triage Flow

1. Confirm user impact from error and latency graphs
2. Filter logs by `correlation_id` and endpoint path
3. Validate dependency health (readiness group)
4. Roll back recent deploy if regression confirmed
5. Add post-incident action item for metric threshold adjustment

---

## Part 3: Log Aggregation Baseline

### Goal

Ensure logs from all services are searchable by time range, severity, endpoint, and correlation ID.

### Required Log Fields

All application logs must include:
- `@timestamp` - ISO-8601 log timestamp
- `level` - log level (INFO, WARN, ERROR)
- `message` - log message
- `service` - service name (tinyurl)
- `correlation_id` - request correlation ID
- `logger_name` - Java logger name
- `method`, `route`, `status`, `duration_ms` - request metadata (when applicable)

### Recommended Pipeline (Baseline)

1. App emits JSON logs to stdout
2. Container runtime captures stdout/stderr
3. Log shipper (CloudWatch Agent, Fluent Bit, Filebeat, Vector) forwards logs
4. Central store indexes logs (CloudWatch Logs, ELK/OpenSearch, Loki)
5. Dashboards and alerts query centralized logs

Logical flow: `App -> stdout -> Shipper -> Loki -> Grafana`

### Minimum Log-Based Alerts

1. **Error volume spike**: ERROR logs exceed baseline over 5 minutes
2. **Missing correlation-id rate**: Logs without `correlation_id` exceed 1%
3. **Exception signature surge**: Sudden spike in repeated exception signatures

### Triage Query Examples

1. **Correlate one failing request**
   - Filter by `correlation_id` and inspect all matching events

2. **Endpoint failure analysis**
   - Filter by `route` and `status>=500`, group by exception/error code

3. **Slow request analysis**
   - Filter by high `duration_ms`, group by route and time window

### Security and Privacy Notes

- Never log credentials, tokens, or full secrets
- Mask sensitive fields before logging
- Keep retention and access controls aligned with security policy

### Rollout Checklist

- [ ] JSON logs enabled in all environments
- [ ] Log shipping configured for app containers
- [ ] Correlation ID searchable in central logs
- [ ] Error and latency dashboards created
- [ ] Alert rules validated with test events

---

## Part 4: Log Storage Migration (Loki + Promtail + Grafana)

This is the recommended free stack for v1/v2 scale because it is easiest to operate and secure on single-host or small-cluster deployment.

### Why This Stack?

**Loki + Promtail + Grafana (recommended)**
- Pros: low-cost indexing, simple operations, good Docker support, strong Grafana integration
- Cons: log query language differs from Elasticsearch

**Alternatives considered:**
- OpenSearch + Fluent Bit: heavier, higher ops complexity
- ELK: most resource-heavy for small teams

### Target Production Architecture

1. TinyURL app writes JSON to stdout
2. Container runtime writes to local log files
3. Promtail tails container logs, ships to Loki over private network
4. Loki stores log streams on encrypted disk with retention policy
5. Grafana queries Loki, provides dashboards/alerts

### Security Controls (Minimum Baseline)

1. **Network isolation** - Run Loki and Promtail on private network only. Never expose Loki directly to public internet.

2. **Transport security** - Use TLS for Grafana access. Use TLS/mTLS between Promtail and Loki if remote.

3. **Authentication and authorization** - Enable Grafana login (no anonymous access in production). Use strong admin password and role-based access. Restrict datasource edit rights to admins only.

4. **Data at rest** - Store Loki data on encrypted volume. Restrict filesystem permissions for log directories.

5. **Retention and lifecycle** - Set finite retention (14-30 days recommended). Enforce deletion and compaction to control risk/cost.

6. **Sensitive data handling** - Never log secrets, tokens, or passwords. Use Promtail pipeline stages to drop/mask sensitive patterns if needed.

### Migration Strategy (Phased)

**Phase A: Prepare**
1. Confirm JSON logging is enabled in application ✓ (done in Phase 3)
2. Define required labels: `service`, `env`, `level`, `correlation_id`
3. Define retention target and incident triage queries

**Phase B: Deploy Logging Stack**
1. Deploy Loki with persistent encrypted storage
2. Deploy Promtail on same host(s) as app containers
3. Deploy Grafana and add Loki datasource

**Phase C: Connect TinyURL**
1. Configure Promtail scrape job for container logs
2. Parse JSON fields from app log lines
3. Map key labels: `service=tinyurl`, `env=prod`, `level`, `route` (low cardinality)

**Phase D: Validate**
1. Generate synthetic requests and errors
2. Search by `correlation_id` end-to-end
3. Verify alert rules trigger for error spikes

**Phase E: Harden**
1. Enable TLS and auth on Grafana endpoint
2. Restrict network ingress to admin CIDRs/VPN
3. Tune retention and label cardinality

### Example Production Docker Compose Skeleton

Define three services — `loki`, `promtail`, and `grafana` — on a shared internal Docker network named `observability`. Loki should mount a local config file and a named volume for persistent log storage. Promtail should mount the Docker socket and container log directory in read-only mode so it can tail container stdout. Grafana should expose port 3000, disable anonymous access via environment variable, and mount a named volume for dashboard persistence. Neither Loki nor Promtail should publish ports to the host; only Grafana should be reachable, and only via a TLS-terminating reverse proxy in production.

### Promtail Parsing Recommendations

1. Use docker/container scrape configs
2. Apply JSON pipeline stage to extract: `level`, `correlation_id`, `logger_name`, `message`
3. Keep label cardinality low:
   - **Good labels**: service, env, level
   - **Avoid**: user_id, request_id as labels
   - **Store high-cardinality fields** in log body, not labels

### Grafana Dashboard and Alert Baseline

**Panels to create:**
1. Error volume by level and route
2. Top exception signatures (last 4 hours)
3. Missing-correlation-id count
4. Slow-request logs (`duration_ms` > threshold)

**Starter alerts:**
1. Error log rate spike in 5 minutes
2. Repeated exception signature spike
3. Promtail ingestion failures
4. Loki disk usage > 80% threshold

### Operational Runbook (Minimum)

**Incident lookup flow:**
1. Start with alert time window
2. Filter `service=tinyurl`
3. Pivot by `correlation_id`
4. Correlate with metrics and health endpoints

**Capacity checks:**
- Loki disk growth/day
- Query latency in Grafana Explore
- Promtail backlog/retry behavior

**Backup/restore:**
- Backup Loki persistent volume snapshots
- Test restore at least once per quarter

### Acceptance Criteria for Migration Completion

1. Logs searchable in Grafana for all app instances
2. Correlation-ID trace works for one request across full flow
3. Security controls enabled (auth, TLS, network restrictions)
4. Retention policy active and verified
5. At least three log-based alerts configured and tested

### Future Upgrades (Optional)

1. Move from Promtail to Grafana Alloy when standardizing telemetry agents
2. Add long-term object storage backend for Loki if retention grows
3. Add SSO for Grafana access control
4. Add trace correlation once distributed tracing is introduced

---

## Execution Steps

### Step 1: Structured logging baseline

Tasks:
- Use JSON logs with stable field names ✓
- Ensure request correlation id propagation ✓
- Mask or avoid sensitive values in logs ✓

### Step 2: Metrics instrumentation

Tasks:
- Add counters for request/response classes ✓
- Add timers/histograms for endpoint latencies ✓
- Add db pool metrics where available ✓

### Step 3: Health model hardening

Tasks:
- Confirm liveness/readiness checks report dependencies correctly ✓
- Ensure degraded dependency behavior is visible ✓

### Step 4: Alert and threshold baseline

Tasks:
- Define target alert thresholds (error and latency) ✓
- Document triage paths for common failures ✓

### Step 5: Log aggregation setup

Tasks:
- Document log aggregation pipeline requirements
- Provide Loki deployment skeleton and configuration  
- Create Grafana dashboard templates (post-phase)
- Document cardinality control best practices

## Deliverables

- [x] Structured JSON logging enabled and validated
- [x] Metrics emitted for all API paths (request count, duration, errors)
- [x] Readiness and liveness health checks verified against dependencies
- [x] Alert baseline thresholds documented and ready for metrics platform
- [x] Log aggregation architecture specified with operational runbook
- [x] Security controls checklist for production rollout

## Acceptance Criteria

- [x] Logs include timestamp, level, correlation_id, request metadata
- [x] Metrics can identify latency and error spikes (percentiles available)
- [x] Health endpoint reflects component status reliably
- [x] Alert rules can be configured per baseline definitions
- [x] Log aggregation pipeline documented and testable
- [x] All sensitive data handling guidelines documented
- [x] Production vs dev exposure differences documented and enforced
