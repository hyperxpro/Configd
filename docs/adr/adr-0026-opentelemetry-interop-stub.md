# ADR-0026: OpenTelemetry Interop Is a Documented Bridge, Not a Native Integration

## Status

Accepted (2026-04-17).

## Context

Configd emits its own metrics format via `MetricsRegistry` +
`PrometheusExporter` but does not speak OpenTelemetry natively. Most
operators expect to receive both metrics and traces over OTLP into
their existing collector (Tempo / Jaeger / Grafana Cloud / Datadog).

A full OTel integration involves:

1. Pulling in `io.opentelemetry:opentelemetry-sdk` and OTLP exporters
   (~ 20 transitive deps, including grpc-java and protobuf).
2. Threading a `Tracer` and `Meter` through every span boundary -
   currently zero spans exist.
3. Choosing a context-propagation strategy (W3C tracecontext vs.
   X-Cloud-Trace).
4. Deciding metric naming conventions (OTel uses dotted names, Prometheus
   prefers underscores) and whether to bridge the existing
   `MetricsRegistry` or replace it.

That is a multi-week effort in its own right, and Configd does not take
it on.

## Decision

Configd ships:

- **Native Prometheus exposition** via `PrometheusExporter`, with
  histograms emitted as a proper `histogram` type so they aggregate
  correctly across instances.
- **A documented bridge contract** (this ADR) describing exactly how an
  operator who needs OTel can wire it up themselves: scrape the
  `/metrics` endpoint with the Prometheus receiver in their OTel
  collector, then forward via OTLP to their backend.

Configd emits **no traces**. There are currently zero spans in the
codebase; operators who need distributed tracing need a separate tool
for that today.

## Bridge contract for operators wanting OTel

```yaml
# OTel collector config - scrape Configd /metrics into the OTel pipeline
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

This is a one-config-file integration. The histograms emit as a proper
`histogram` type so they remain aggregatable through the OTel pipeline.

## Consequences

- Configd ships without taking on a ~20-dependency OTel SDK hard
  dependency.
- Operators who only need metrics get a clean, low-friction integration.
- Operators wanting distributed traces need a separate tool for that;
  Configd does not emit spans.
- Adding a native OTel SDK later would not need to break the Prometheus
  path - both could coexist.

## Related

- ADR-0025 - on-call procurement separation (operator-side observability)

## Verification

- **Testable via:** the Prometheus exposition path is exercised by `configd-observability/src/test/java/io/configd/observability/PrometheusExporterTest.java`; histogram type emission is asserted there. The "no OTel SDK on classpath" structural assertion is verifiable by `mvn dependency:tree` returning no `io.opentelemetry:*` artifacts.
- **Invalidated by:** introduction of `io.opentelemetry:opentelemetry-sdk` (or any OTLP exporter) into a production POM - that would silently change the operator integration model.
- **Operator check:** `curl -sf http://configd-server:9090/metrics | head` returns Prometheus-format histograms (`# TYPE ... histogram`); operator OTel collector scrapes that endpoint per the config snippet above. Configd does not emit distributed-trace spans - there is nothing to wire.
