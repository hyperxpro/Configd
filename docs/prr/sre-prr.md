# SRE Production Readiness Review -- Configd

**Date:** 2026-04-11
**Reviewer:** release-engineer / observability-auditor
**Verdict:** NOT READY -- critical gaps in alerting, runbooks, tracing, and logging

---

## 1. SLO/SLI Check

### 1.1 SLI Implementation

**File:** `configd-observability/src/main/java/io/configd/observability/SloTracker.java`

`SloTracker` provides a sliding-window compliance tracker. SLOs are defined programmatically via `defineSlo(name, target, window)`, and SLI values are computed as success/failure ratios over the window. The implementation uses `ConcurrentLinkedDeque` for lock-free event recording with monotonic `System.nanoTime()` timestamps and lazy eviction.

**Assessment:**

| Capability | Status | Notes |
|---|---|---|
| SLI recording (success/failure events) | PRESENT | `recordSuccess()` / `recordFailure()` per SLO |
| Compliance ratio computation | PRESENT | `compliance(sloName)` over sliding window |
| Breach detection | PRESENT | `isBreaching(sloName)` compares current vs target |
| Snapshot of all SLOs | PRESENT | `snapshot()` returns `Map<String, SloStatus>` |
| SLIs emitted as Micrometer metrics | MISSING | SloTracker is standalone; no bridge to MetricsRegistry or Micrometer |
| SLIs exported to external monitoring (Prometheus, Datadog) | MISSING | No metric export pipeline exists |

### 1.2 SLO Targets vs PROMPT.md Section 0.1

| Target (PROMPT.md) | SLO Defined in Code? | Notes |
|---|---|---|
| Write commit latency p99 < 150 ms cross-region | NO | No SLO defined for write latency |
| Read latency at edge p99 < 1 ms | NO | No SLO defined for read latency |
| Read latency at edge p999 < 5 ms | NO | No SLO defined for p999 |
| Propagation delay p99 < 500 ms global | NO | No SLO defined for propagation |
| Availability 99.999% control plane writes | NO | No SLO defined for write availability |
| Availability 99.9999% edge reads | NO | No SLO defined for edge read availability |
| Write QPS 10k/s baseline, 100k/s burst | NO | No throughput SLO defined |

**Finding: CRITICAL.** The `SloTracker` class is a generic framework only. None of the mandatory system targets from Section 0.1 are instantiated as SLO definitions. There is no code path that calls `defineSlo()` with the production targets.

### 1.3 Error Budget

| Capability | Status |
|---|---|
| Error budget computation (1 - target) * window | MISSING |
| Error budget consumption rate tracking | MISSING |
| Error budget exhaustion alerting | MISSING |
| Error budget policy (freeze deploys when exhausted) | MISSING |

**Finding: CRITICAL.** No error budget mechanism exists. The `SloTracker` can tell you if an SLO is breaching, but cannot track burn rate or remaining budget.

---

## 2. Alerting

### 2.1 Burn-Rate Alerts

| Alert Type | Status | Notes |
|---|---|---|
| Fast burn-rate alert (e.g., 14.4x over 1h) | MISSING | No alerting code exists anywhere |
| Slow burn-rate alert (e.g., 1x over 3d) | MISSING | No alerting code exists anywhere |
| Multi-window alerting (short + long window) | MISSING | |
| Alert routing (PagerDuty, OpsGenie, etc.) | MISSING | |

**Finding: CRITICAL.** Zero alerting infrastructure. No alert definitions, no alert routing, no notification channels. The `SloTracker.isBreaching()` method exists but nothing calls it periodically or routes the result anywhere.

### 2.2 Failure Mode Alerts

Based on documented failure modes in `docs/architecture.md` and ADRs:

| Failure Mode | Alert Defined? | Runbook Linked? |
|---|---|---|
| Region loss | NO | NO |
| Leader election failure / stuck | NO | NO |
| Split brain | NO | NO |
| Reconfiguration failure | NO | NO |
| Edge catch-up storm | NO | NO |
| Poison config propagation | NO | NO |
| Slow consumer disconnect | NO | NO |
| Version gap divergence | NO | NO |
| Certificate expiry | NO | NO |
| Invariant violation | PARTIAL (metric counter emitted) | NO |
| Rollout health gate failure | NO | NO |

**Finding: CRITICAL.** The only failure signal that produces a metric is invariant violations (via `InvariantMonitor` -> `MetricsRegistry`). No other failure mode has an associated alert.

---

## 3. Runbooks

### 3.1 Required Runbooks

Searched: `docs/wiki/`, `docs/`, all `.md` files, all source files.

| Runbook Topic | Exists? | Location |
|---|---|---|
| Region loss | NO | -- |
| Leader stuck / failed election | NO | -- |
| Reconfiguration rollback | NO | -- |
| Edge fleet catch-up storm | NO | -- |
| Poison config rollback | NO | -- |
| Cert rotation | NO | -- |
| Emergency write freeze | NO | -- |
| Version gap divergence | NO | -- |

**Finding: CRITICAL.** Zero runbooks exist. The `docs/wiki/` directory contains developer-oriented documentation (Getting Started, Architecture Overview, Integration Guide, Docker, Testing, Module Reference) but no operational runbooks.

---

## 4. Observability Stack

### 4.1 Metrics

**File:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java`

The `MetricsRegistry` provides three metric types:

| Type | Implementation | Thread Safety |
|---|---|---|
| Counter | `LongAdder`-backed (lock-free) | Yes -- high-throughput concurrent increments |
| Gauge | `LongSupplier` evaluated at snapshot time | Caller-dependent |
| Histogram | Ring-buffer (4096 slots), `AtomicLong` cursor | Yes -- lock-free record path |

**Metrics defined in code:**

| Metric Name | Type | Source |
|---|---|---|
| `invariant.violation.<name>` | Counter | `InvariantMonitor.recordViolation()` |

That is the only metric currently emitted by production code. No other module registers metrics.

**Cardinality Assessment:**

- The `MetricsRegistry` API takes a plain string name with no label/tag support. There are no unbounded cardinality labels because there are no labels at all.
- RISK: When labels are added (e.g., per-node, per-key, per-region), cardinality explosion is possible if not constrained. The current API has no guardrails.

**Hot-Path Lock-Free Counters:**

- `DefaultCounter` uses `LongAdder` -- GOOD, this is the correct choice for high-throughput counters.
- `DefaultHistogram.record()` uses `AtomicLong.getAndIncrement()` for cursor and `LongAdder.add()` for sum -- GOOD, lock-free on the recording path.
- `DefaultHistogram.updateMin()/updateMax()` uses `synchronized` for CAS emulation -- ACCEPTABLE for monitoring (rare contention after warmup), but should use `VarHandle` for production.

**Micrometer Integration:**

- `micrometer-core` is declared as a dependency in `configd-observability/pom.xml` but is NOT USED in any source file. The `MetricsRegistry` is a custom implementation that the Javadoc says is "designed to be replaced with Micrometer in production deployments." This replacement has not happened.

**Finding: MAJOR.** Only one metric (`invariant.violation.*`) is emitted. Critical operational metrics are missing: request rates, latency histograms, queue depths, replication lag, fan-out latency, election counts, snapshot transfer times, connection counts, error rates by type.

### 4.2 Tracing

| Capability | Status |
|---|---|
| OpenTelemetry dependency | NOT PRESENT |
| Trace instrumentation (spans) | NOT PRESENT |
| Sampled traces for write commit path | NOT PRESENT |
| Sampled traces for read path | NOT PRESENT |
| Sampled traces for fan-out/distribution | NOT PRESENT |
| Sampled traces for reconfiguration | NOT PRESENT |
| Trace context propagation across modules | NOT PRESENT |

**Finding: CRITICAL.** Zero tracing. No OpenTelemetry dependency, no span creation, no trace context propagation. This makes production debugging of latency issues impossible.

### 4.3 Logging

| Capability | Status | Notes |
|---|---|---|
| Logging framework (SLF4J, Logback, Log4j2) | NOT PRESENT | No logging dependency in any pom.xml |
| Structured logging (JSON) | NOT PRESENT | |
| Leveled logging | NOT PRESENT | |
| No INFO+ on hot path | N/A (no logging exists) | |
| MDC for request context | NOT PRESENT | |

Source files use `System.Logger` (JDK platform logger) in a few test/build files, but no production logging framework is configured.

**Finding: CRITICAL.** No production logging infrastructure. Operators will have zero visibility into system behavior beyond metrics (which are also largely absent).

### 4.4 Dashboards

| Capability | Status |
|---|---|
| Grafana dashboard JSON definitions | NOT PRESENT |
| Dashboard-as-code (Grafonnet, Jsonnet) | NOT PRESENT |
| Any dashboard definition files | NOT PRESENT |

**Finding: MAJOR.** No dashboards defined. Even with metrics, operators have no pre-built views.

---

## 5. Rollout & Rollback

### 5.1 Rollout Strategy

**File:** `configd-distribution-service/src/main/java/io/configd/distribution/RolloutController.java`

| Capability | Status | Notes |
|---|---|---|
| Canary stage (0.1% of nodes) | PRESENT | `Stage.CANARY(0.001)` |
| Progressive stages | PRESENT | CANARY -> 1% -> 10% -> 50% -> FULL |
| Configurable soak times | PRESENT | Default: 60s/120s/300s/600s per stage |
| Health-gated advancement | PRESENT | `canAdvance()` checks `healthPassing` and soak time |
| Emergency immediate override | PRESENT | `RolloutPolicy.IMMEDIATE` skips to FULL |
| Pause/Resume | PRESENT | `pause()` / `resume()` methods |
| Rollback | PRESENT | `rollback()` sets state to `ROLLED_BACK` |

**Assessment:** The `RolloutController` implements the staged rollout described in ADR-0008. The design is sound. However:

| Gap | Severity |
|---|---|
| Rollback is a state change only -- no code reverts the config version on edge nodes | MAJOR |
| `updateHealth()` is called externally but no health check integration exists | MAJOR |
| No automated rollback on health degradation (must be triggered manually) | MAJOR |
| No audit trail for IMMEDIATE overrides | MINOR |
| No integration tests for the full rollout lifecycle | MAJOR |

### 5.2 Feature Flags

| Capability | Status |
|---|---|
| Feature flag system | NOT PRESENT |
| Kill switches | NOT PRESENT |
| Runtime feature gating | NOT PRESENT |

**Finding: MINOR.** No feature flag system. For a config distribution system, this is less critical than for application services, but kill switches for new features would be valuable.

---

## 6. Invariant Monitoring

**File:** `configd-observability/src/main/java/io/configd/observability/InvariantMonitor.java`

| Capability | Status | Notes |
|---|---|---|
| TLA+ invariant -> Java assertion bridge | PRESENT | `register()` + `checkAll()` |
| Test mode (AssertionError on violation) | PRESENT | |
| Production mode (metric increment) | PRESENT | Increments `invariant.violation.<name>` counter |
| Violation count tracking | PRESENT | `violations()` returns map |

**Assessment:** This is one of the stronger components. The design correctly bridges formal verification to runtime, matching PROMPT.md Section 5 Rule 13. However, the invariants need to be actually registered by the runtime modules -- this wiring does not exist yet.

---

## 7. Summary of Findings

### Critical (must fix before production)

1. **No SLOs instantiated for Section 0.1 targets.** The framework exists but zero production SLOs are defined.
2. **No alerting infrastructure.** Zero alerts, zero notification routing, zero burn-rate computation.
3. **No runbooks.** Zero operational runbooks for any failure mode.
4. **No tracing.** Zero OpenTelemetry integration, zero spans.
5. **No logging framework.** Zero structured logging, zero log output in production.
6. **No error budget tracking.** Cannot determine budget consumption or trigger deploy freezes.

### Major (must fix before GA)

7. **Only 1 metric emitted** (`invariant.violation.*`). Missing: latency, throughput, queue depth, replication lag, election count, connection count, error rates.
8. **Micrometer declared but unused.** The bridge to external monitoring systems does not exist.
9. **Rollback does not revert config on edges.** `RolloutController.rollback()` is a state machine transition with no downstream effect.
10. **No automated health-triggered rollback.** Health must be manually pushed and rollback manually triggered.
11. **No dashboards.** Zero pre-built operational views.

### Minor

12. No feature flags / kill switches.
13. Histogram min/max CAS uses `synchronized` instead of `VarHandle`.
14. No audit trail implementation for IMMEDIATE rollout overrides.

---

## 8. Recommendations

1. **Instantiate SLOs:** Create a startup configuration that calls `defineSlo()` for each Section 0.1 target with appropriate windows (30-day for availability, 1-hour for latency).
2. **Implement burn-rate alerting:** Add a `BurnRateAlertEvaluator` that computes multi-window burn rates and emits alerts via a pluggable notification channel.
3. **Write runbooks:** Create operational runbooks in `docs/runbooks/` for all 8 documented failure modes. Each runbook must include: detection, diagnosis, mitigation, recovery, post-incident.
4. **Add OpenTelemetry:** Integrate `opentelemetry-api` and instrument the write commit path, read path, fan-out path, and reconfiguration path with sampled spans.
5. **Add SLF4J + Logback:** Configure structured JSON logging with leveled output. Enforce no INFO+ on hot paths via code review and static analysis.
6. **Wire Micrometer:** Replace or wrap `MetricsRegistry` with Micrometer `MeterRegistry`. Register metrics for all critical paths.
7. **Implement automated rollback:** Connect `RolloutController` health gates to actual health signal collection, and trigger `rollback()` automatically when health degrades.
8. **Create Grafana dashboards:** Define dashboard-as-code for: cluster overview, write path, read path, fan-out, SLO burn-down, invariant violations.
