# F5 — Tier-1-METRIC-DRIFT (Closes S0 H-001 + DOC-014, N-003, N-005, N-016)

## Summary

`ops/alerts/configd-slo-alerts.yaml`, `ops/runbooks/*.md`, and
`ops/dashboards/configd-overview.json` referenced metric names that
were never registered in `MetricsRegistry`. The SLO pipeline was
decorative — alerts could never fire because no time series existed in
the Prometheus exposition.

This change registers every alert-cited metric, wires emission at the
correct points (HTTP edge read, state-machine commit, snapshot install,
Raft pending-apply queue, change-listener propagation), and adds
per-histogram bucket schedules so that the exposition format emits the
exact `_bucket{le="X"}` labels the alert queries select.

## Metric Diff

### Cited by alerts but previously unregistered

| Metric | Type | Wire-up |
|---|---|---|
| `configd_write_commit_total` | counter | `ConfigStateMachine.apply` success |
| `configd_write_commit_failed_total` | counter | `ConfigStateMachine.apply` RuntimeException path |
| `configd_write_commit_seconds` | histogram | `ConfigStateMachine.apply` start→end nanos |
| `configd_apply_seconds` | histogram | (alias of write_commit_seconds, present for ADR cross-ref) |
| `configd_edge_read_total` | counter | `HttpApiServer.handleGet` finally |
| `configd_edge_read_seconds` | histogram | `HttpApiServer.handleGet` finally |
| `configd_propagation_delay_seconds` | histogram | `ConfigdServer` change-listener |
| `configd_raft_pending_apply_entries` | gauge | `commitIndex - lastApplied` (late-bound after RaftNode init) |
| `configd_snapshot_install_failed_total` | counter | `ConfigStateMachine.restoreSnapshot` failure |
| `configd_snapshot_rebuild_total` | counter | `ConfigStateMachine.restoreSnapshot` success |

Full enumeration: `docs/review/iter-001/fixers/F5-metric-diff.md`.

### Bucket schedule rationale

Alerts use fractional-second `le` labels (`le="0.150"`, `le="0.001"`,
`le="0.005"`, `le="0.500"`). The registry stores nanoseconds as longs.
`PrometheusExporter.BucketSchedule` maps a Prometheus le-label string
to the underlying long cutoff so `bucketCount(long)` queries return
the right cumulative count without the exporter inferring units.

## Files Modified / Added

### Production
- `configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java` — added `BucketSchedule` static class and per-histogram override map; `appendHistogram` uses override if present.
- `configd-observability/src/main/java/io/configd/observability/ConfigdMetrics.java` — **new**. Eagerly registers all SLO-cited metrics; exposes `histogramSchedules()` static factory; supports late-bound gauge via `bindRaftPendingApplyGauge(LongSupplier)`.
- `configd-config-store/src/main/java/io/configd/store/StateMachineMetrics.java` — **new**. Functional interface mirroring `InvariantChecker.NOOP` pattern so config-store does not depend on observability.
- `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java` — added 5-arg constructor; wraps `apply` in try/catch and emits success/failure with nanos delta; refactored `restoreSnapshot` to delegate to private internal with try/catch.
- `configd-server/src/main/java/io/configd/server/HttpApiServer.java` — added 11-arg constructor accepting `ConfigdMetrics, SloTracker`; `handleGet` records counter + histogram + SloTracker inside try/finally (allocation-free hot path: System.nanoTime + primitive counter.increment + histogram.record).
- `configd-server/src/main/java/io/configd/server/ConfigdServer.java` — wires `MetricsRegistry`, `ConfigdMetrics`, `SloTracker`, `ProductionSloDefinitions.register`; constructs `StateMachineMetrics` adapter to bridge config-store → observability; binds the pending-apply gauge after RaftNode init; records propagation_delay histogram in change-listener; constructs `PrometheusExporter` with `histogramSchedules()`.

### Tests (real registry, no mocks per codebase convention)
- `configd-observability/src/test/java/io/configd/observability/ConfigdMetricsTest.java` — **new**. 4 tests verifying registration of every alert-cited metric, late gauge binding, alert-required `_bucket{le="X"}` series in Prometheus output, strictly-increasing schedule cutoffs.
- `configd-config-store/src/test/java/io/configd/store/ConfigStateMachineMetricsTest.java` — **new**. 6 tests using a real `RecordingMetrics` (no mocks): PUT/DELETE success counts, Noop is no-op, signing failure (verify-only `ConfigSigner(kp.getPublic())`) increments failure, snapshot success → rebuild, malformed snapshot → install_failed.
- `configd-server/src/test/java/io/configd/server/HttpApiServerMetricsTest.java` — **new**. 4 tests against a real `HttpApiServer` on an ephemeral port: 200 read increments counter, 404 read still increments counter, SLO success recorded, `/metrics` endpoint contains the alert-required series. Polls SloTracker snapshot to absorb the JDK HttpServer handler-thread latency on the finally block.

## Test Results

```
./mvnw -pl configd-observability,configd-config-store,configd-server -am test
…
[INFO] Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(Aggregate across reactor modules; the three F5 modules contribute the
14 new tests above; no pre-existing tests regressed.)

## Metrics Cited by Alerts Not Yet Wired

None. Every metric named in `ops/alerts/configd-slo-alerts.yaml` is now:
1. registered in the registry on `ConfigdMetrics` construction (so the
   `# TYPE` line is emitted on first scrape), and
2. wired to the appropriate emission point in production code.

## Notes / Out-of-Scope

- The `configd_apply_seconds` histogram is registered with the same
  schedule as `configd_write_commit_seconds`. The name is referenced
  by ADR-0005 cross-references and the ApplyLatency dashboard panel,
  but the alert rule keys off `write_commit_seconds`. Both are wired
  from the same nanos delta in `ConfigStateMachine.apply`, so they
  stay in lock-step.
- `bindRaftPendingApplyGauge` is late-bound (post-RaftNode construction)
  because `RaftLog.commitIndex()` / `lastApplied()` are not available
  during `ConfigdServer` early-startup. The `ConfigdMetrics` constructor
  accepts a nullable `LongSupplier` so the gauge can be a placeholder
  at registration time and rebound once the consensus layer is up.
- The HttpApiServer test polls the SloTracker snapshot with a 2 s
  deadline (5 ms intervals) because the JDK `HttpServer` runs handler
  finally blocks on its own executor thread, and `getResponseCode()`
  on the client side returns when the headers are flushed — before the
  finally block has necessarily executed. Polling absorbs that gap
  without weakening the assertion.
