# Phase 3 Log Storage Migration Plan (Free): Loki + Promtail + Grafana

This document provides a production-oriented migration plan for storing logs securely at zero license cost using the recommended stack:

- Grafana Loki (log store)
- Promtail (log shipper)
- Grafana (query, dashboards, alerting)

## 1) Why this stack

### Free options considered

1. Loki + Promtail + Grafana (recommended)
- Pros: low-cost indexing model, simple operations, good Docker support, strong Grafana integration
- Cons: log query language is different from Elasticsearch ecosystem

2. OpenSearch + Fluent Bit/Filebeat
- Pros: powerful full-text search, mature ecosystem
- Cons: heavier memory/storage footprint and higher ops complexity

3. ELK (Elasticsearch + Logstash + Kibana)
- Pros: mature and widely known
- Cons: most resource-heavy for small teams

### Recommendation

Use Loki + Promtail + Grafana for v1/v2 scale because it is easiest to operate and secure on a single-host or small-cluster deployment.

---

## 2) Target architecture (production)

1. TinyURL app writes structured JSON logs to stdout.
2. Container runtime writes stdout/stderr to local log files.
3. Promtail tails container logs and ships to Loki over private network.
4. Loki stores log streams on encrypted disk.
5. Grafana queries Loki and provides dashboards/alerts.

Logical flow:

`App -> stdout -> Promtail -> Loki -> Grafana`

---

## 3) Security controls (minimum baseline)

1. Network isolation
- Run Loki and Promtail on private network only.
- Do not expose Loki directly to public internet.

2. Transport security
- Use TLS for Grafana access.
- If Loki is remote, use TLS/mTLS between Promtail and Loki.

3. Authentication and authorization
- Enable Grafana login (no anonymous access in production).
- Use strong admin password and role-based access.
- Restrict datasource edit rights to admins only.

4. Data at rest
- Store Loki data on encrypted volume.
- Restrict filesystem permissions for log directories.

5. Retention and lifecycle
- Set finite retention (for example 14-30 days to start).
- Enforce deletion and compaction to control risk/cost.

6. Sensitive data handling
- Never log secrets, tokens, or passwords.
- Use Promtail pipeline stages to drop/mask sensitive patterns if needed.

---

## 4) Migration strategy (phased)

### Phase A: Prepare

1. Confirm JSON logging is enabled in application.
2. Define required labels: `service`, `env`, `level`, `correlation_id`.
3. Define retention target and incident triage queries.

### Phase B: Deploy logging stack

1. Deploy Loki with persistent encrypted storage.
2. Deploy Promtail on same host(s) as app containers.
3. Deploy Grafana and add Loki datasource.

### Phase C: Connect TinyURL logs

1. Configure Promtail scrape job for container logs.
2. Parse JSON fields from app log lines.
3. Map key labels (`service=tinyurl`, `env=prod`, `level`, optional `route`).

### Phase D: Validate

1. Generate synthetic requests and errors.
2. Search by `correlation_id` end-to-end.
3. Verify alert rules trigger for error spikes.

### Phase E: Harden

1. Enable TLS and auth on Grafana endpoint.
2. Restrict network ingress to admin CIDRs/VPN.
3. Tune retention and label cardinality.

---

## 5) Example production Compose skeleton

Use this as a conceptual baseline and adapt to your deployment model.

```yaml
services:
  loki:
    image: grafana/loki:3.0.0
    command: -config.file=/etc/loki/config.yaml
    volumes:
      - ./observability/loki/config.yaml:/etc/loki/config.yaml:ro
      - loki-data:/loki
    networks: [observability]
    restart: unless-stopped

  promtail:
    image: grafana/promtail:3.0.0
    command: -config.file=/etc/promtail/config.yaml
    volumes:
      - ./observability/promtail/config.yaml:/etc/promtail/config.yaml:ro
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
    networks: [observability]
    restart: unless-stopped

  grafana:
    image: grafana/grafana:11.0.0
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}
      - GF_AUTH_ANONYMOUS_ENABLED=false
    volumes:
      - grafana-data:/var/lib/grafana
    ports:
      - "3000:3000"
    networks: [observability]
    restart: unless-stopped

volumes:
  loki-data:
  grafana-data:

networks:
  observability:
    internal: true
```

Notes:

- Prefer not to publish Loki port publicly.
- Publish Grafana through secure reverse proxy with TLS.

---

## 6) Promtail parsing recommendations

1. Use docker/container scrape configs.
2. Apply JSON pipeline stage to extract:
- `level`
- `correlation_id`
- `logger_name`
- `message`

3. Keep label cardinality low:
- Good labels: service, env, level
- Avoid high-cardinality labels: user_id, request_id as labels
- Keep high-cardinality fields in log body, not labels

---

## 7) Dashboard and alert baseline

Create Grafana panels for:

1. Error volume by level and route
2. Top exception signatures
3. Missing-correlation-id count
4. Slow-request logs (`duration_ms` threshold)

Starter alerts:

1. Error log rate spike in 5 minutes
2. Repeated exception signature spike
3. Promtail ingestion failures
4. Loki disk usage threshold breach

---

## 8) Operational runbook (minimum)

1. Incident lookup flow
- Start with alert time window
- Filter `service=tinyurl`
- Pivot by `correlation_id`
- Correlate with metrics and health endpoints

2. Capacity checks
- Loki disk growth/day
- Query latency in Grafana Explore
- Promtail backlog/retry behavior

3. Backup/restore
- Backup Loki persistent volume snapshots
- Test restore at least once per quarter

---

## 9) Acceptance criteria for migration completion

1. Logs searchable in Grafana for all app instances.
2. Correlation-id trace works for one request across full flow.
3. Security controls enabled (auth, TLS, network restrictions).
4. Retention policy active and verified.
5. At least three log-based alerts configured and tested.

---

## 10) Future upgrades (optional)

1. Move from Promtail to Grafana Alloy when standardizing telemetry agents.
2. Add long-term object storage backend for Loki if retention grows.
3. Add SSO for Grafana access control.
4. Add trace correlation once distributed tracing is introduced.
