# Phase 3: Observability

## Objective

Add production-grade logging, metrics, and health visibility to make runtime behavior measurable and debuggable.

## Depends On

- [Phase 2 Core API (v1)](PHASE_2_CORE_API_V1.md)

## Source References

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [LLD C4 Diagrams](../lld/c4-diagrams.md)
- [HLD System Design](../hld/system-design/system-design.md)
- [Threat Model](../security/threat-model.md)

## In Scope

- structured logs with request correlation
- metrics for request rate, latency, and error rate
- readiness/liveness validation on health endpoint

## Out of Scope

- full external dashboard platform rollout if not needed yet
- distributed tracing stack (defer unless required)

## Execution Steps

### Step 1: Structured logging baseline

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)
- [Threat Model](../security/threat-model.md)

Tasks:

- Use JSON logs with stable field names
- Ensure request correlation id propagation
- Mask or avoid sensitive values in logs

### Step 2: Metrics instrumentation

References:

- [LLD C4 Diagrams](../lld/c4-diagrams.md)

Tasks:

- Add counters for request/response classes
- Add timers/histograms for endpoint latencies
- Add db pool metrics where available

### Step 3: Health model hardening

References:

- [LLD API Contract](../lld/api-contract.md)

Tasks:

- Confirm liveness/readiness checks report dependencies correctly
- Ensure degraded dependency behavior is visible

### Step 4: Alert and threshold baseline

References:

- [Non-Functional Requirements](../requirements/non-functional-requirements.md)

Tasks:

- Define target alert thresholds (error and latency)
- Document triage paths for common failures

## Deliverables

- Structured logs enabled and validated
- Metrics emitted for key API paths
- Readiness and liveness checks verified

## Acceptance Criteria

- Logs include timestamp, level, correlation id, and request metadata
- Metrics can identify latency and error spikes
- Health endpoint reflects component-level status reliably
