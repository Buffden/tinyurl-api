# Phase 3 Alert Baseline

This baseline defines starter alerts for runtime reliability. Thresholds should be tuned with production traffic history.

## SLO-aligned Starter Alerts

1. Error rate alert
- Condition: 5xx rate > 1% over 5 minutes
- Signal: `tinyurl.http.server.requests.total` with `status_class=5xx`
- Action: check recent deploy, downstream DB state, and app logs by `correlation_id`

2. Latency alert
- Condition: P99 latency > 500 ms over 10 minutes
- Signal: `http.server.requests` percentile metrics from Actuator/Prometheus
- Action: inspect slow endpoints, DB pool pressure, and host CPU/memory

3. Readiness degradation
- Condition: `/actuator/health/readiness` not `UP` for 2+ checks
- Signal: readiness endpoint health status
- Action: inspect DB connectivity and Flyway startup validation state

4. Liveness instability
- Condition: frequent restarts or liveness failures in 10 minutes
- Signal: container restart count + `/actuator/health/liveness`
- Action: inspect fatal exceptions, memory pressure, and image/runtime mismatches

## Triage Flow

1. Confirm user impact from error and latency graphs.
2. Filter logs by `correlation_id` and endpoint path.
3. Validate dependency health (`db`, readiness group).
4. Roll back recent deploy if regression is confirmed.
5. Add post-incident action item with metric threshold adjustment if needed.
