# Phase 3 Log Aggregation Baseline

This document defines a minimal production-ready approach for collecting and querying structured application logs.

## Goal

Ensure logs from all services can be searched by time range, severity, endpoint, and correlation id.

## Required Log Fields

All application logs should include at least:

- `@timestamp`
- `level`
- `message`
- `service`
- `correlation_id`
- `logger_name`
- request metadata fields when available (`method`, `route`, `status`, `duration_ms`)

## Recommended Pipeline (Baseline)

1. App emits JSON logs to stdout.
2. Container runtime captures stdout/stderr.
3. Log shipper (CloudWatch Agent, Fluent Bit, Filebeat, or Vector) forwards logs.
4. Central store indexes logs (CloudWatch Logs, ELK/OpenSearch, Grafana Loki).
5. Dashboards and alerts query centralized logs.

## Minimum Alerts for Logs

1. Error volume spike
- Trigger when ERROR logs exceed baseline over 5 minutes.

2. Correlation-id missing rate
- Trigger when logs without `correlation_id` exceed 1%.

3. Exception signature surge
- Trigger on sudden spikes for repeated exception signatures.

## Triage Query Examples

1. Correlate one failing request
- Filter by `correlation_id` and inspect all matching events.

2. Endpoint failure analysis
- Filter by `route` and `status>=500`, group by exception/error code.

3. Slow request analysis
- Filter by high `duration_ms`, group by route and time window.

## Security and Privacy Notes

- Never log credentials, tokens, or full secrets.
- Mask sensitive fields before logging.
- Keep retention and access controls aligned with security policy.

## Rollout Checklist

- [ ] JSON logs enabled in all environments
- [ ] Log shipping configured for app containers
- [ ] Correlation id searchable in central logs
- [ ] Error and latency dashboards created
- [ ] Alert rules validated with test events
