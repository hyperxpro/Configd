# ADR-0026: OpenTelemetry Interop Is a Stub for v0.1

## Status

Accepted (2026-04-17).

## Context

The production audit (PA-5012, gap O8) flagged that Configd emits its
own metrics format via `MetricsRegistry` + `PrometheusExporter` but does
not speak OpenTelemetry. Most operators expect to receive both metrics
and traces over OTLP into their existing collector (Tempo / Jaeger /
Grafana Cloud / Datadog).

A full OTel integration involves:

1. Pulling in `io.opentelemetry:opentelemetry-sdk` and OTLP exporters
   (≈ 20 transitive deps, including grpc-java and protobuf).
2. Threading a `Tracer` and `Meter` through every span boundary —
   currently zero spans exist.
3. Choosing a context-propagation strategy (W3C tracecontext vs.
   X-Cloud-Trace).
4. Deciding metric naming conventions (OTel uses dotted names, Prometheus
   prefers underscores) and whether to bridge the existing
   `MetricsRegistry` or replace it.

This is a multi-week effort. v0.1 ships without it.

## Decision

For v0.1 GA we ship:

- **Native Prometheus exposition** via `PrometheusExporter` (the
  histogram-type fix in O6 makes this aggregatable across instances).
- **A documented bridge contract** (this ADR) describing exactly how an
  operator who needs OTel can wire it up themselves: scrape the
  `/metrics` endpoint with the Prometheus receiver in their OTel
  collector, then forward via OTLP to their backend.

For traces, v0.1 ships **no traces**. Operators wanting distributed
traces must wait for v0.2.

## Bridge contract for operators wanting OTel

```yaml
# OTel collector config — scrape Configd /metrics into the OTel pipeline
receivers:
  prometheus:
    config:
      scrape_configs:
        - job_name: configd
          scrape_interval: 30s
          static_configs:
            - targets: ['configd-server:9090']

exporters:
  otlp:
    endpoint: <your-backend>

service:
  pipelines:
    metrics:
      receivers: [prometheus]
      exporters: [otlp]
```

This is a one-config-file integration. The histograms emit as proper
`histogram` type per O6 so they remain aggregatable through the OTel
pipeline.

## Consequences

- v0.1 GA can ship without taking on a 20-dep OTel hard dependency.
- Operators who only need metrics get a clean, low-friction integration.
- Operators wanting distributed traces are blocked until v0.2 — this is
  documented in `docs/progress.md` Phase 8 residuals.
- We retain the option to add a native OTel SDK in v0.2 without breaking
  the Prometheus path (both can coexist).

## Related

- O6 / PA-5008/15 — histogram type fix in `PrometheusExporter`
- ADR-0025 — on-call procurement separation (operator-side observability)

## Verification

- **Testable via:** the Prometheus exposition path is exercised by `configd-observability/src/test/java/io/configd/observability/PrometheusExporterTest.java`; histogram type emission is asserted there (the O6 fix). The "no OTel SDK on classpath" structural assertion is verifiable by `mvn dependency:tree` returning no `io.opentelemetry:*` artifacts.
- **Invalidated by:** introduction of `io.opentelemetry:opentelemetry-sdk` (or any OTLP exporter) into a production POM — that would silently change the operator integration model.
- **Operator check:** `curl -sf http://configd-server:9090/metrics | head` returns Prometheus-format histograms (`# TYPE ... histogram`); operator OTel collector scrapes that endpoint per the config snippet above. Distributed-trace spans are NOT YET WIRED in v0.1 (operator must wait for v0.2).
