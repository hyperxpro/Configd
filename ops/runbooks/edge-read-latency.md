# Runbook: Edge Read Latency

**Alerts:** `ConfigdEdgeReadFastBurn` (page, p99 burn-rate), `ConfigdEdgeReadP999Breach` (warn, p999 > 5 ms)
**SLO:** edge read p99 < 1 ms, p999 < 5 ms (`configd_edge_read_seconds`)
**Severity:** page (p99), warn (p999)

Edge reads are served from a per-node in-process HAMT snapshot — an
O(log₃₂ N) lookup with no remote IO (measured p99 = 1.6 µs, ~600× headroom). If
this alert fires, the local read path has regressed badly; the usual cause
is a JVM pause, not the lookup itself.

## Symptom

- Page from `ConfigdEdgeReadFastBurn` or warn from `ConfigdEdgeReadP999Breach`.
- `Configd Overview` / `Configd Data Plane` dashboards show
  `histogram_quantile(0.99, ... configd_edge_read_seconds_bucket ...)` above
  1 ms (or p999 above 5 ms).
- Application threads block on config reads (client libs log read-deadline
  exceeded).

## Diagnosis

The read path is in-process, so a slow read is almost always the JVM
stalling, not Configd logic.

1. **JVM / GC pause** — `Configd Runtime` dashboard, **"GC time fraction"**
   (`rate(jvm_gc_collection_millis[5m])`) and **"Heap used vs max"**. ZGC
   STW pauses should be sub-millisecond (measured max 0.045 ms); a spike here is
   the prime suspect. Confirm on the pod:
   ```sh
   kubectl -n configd exec <edge> -- sh -c 'pid=$(pgrep -f configd); jcmd $pid GC.heap_info'
   ```
   Heap > 90% / climbing → [resource-leak.md](resource-leak.md).
2. **Read volume / fan-in spike** — `Configd Data Plane` panel **"Edge read
   throughput + refusals"** (`rate(edge_reads_total[5m])`). A read-rate spike
   correlated with a client deploy means the pressure is fan-in, not a server
   regression.
3. **Read refusals** — same panel, `edge_read_refusals_cursor_behind_total` /
   `edge_read_refusals_strong_read_total`. A spike in cursor-behind refusals
   means the edge is stale (not slow) — that is a propagation problem, go to
   [propagation-delay.md](propagation-delay.md), not a read-latency one.

## Resolution steps

1. **Recycle the affected edge pod** (safe in a 3+ replica tier — the
   replacement comes up from the latest verified snapshot and rejoins
   fan-out):
   ```sh
   kubectl -n configd delete pod <edge>
   ```
   A freshly-rolled pod with a cold cache is briefly slower — give it the
   bootstrap window before declaring failure.
2. **If GC pause is the culprit and recurs after recycle:** suspect a heap
   leak — capture `jcmd <pid> GC.class_histogram` and follow
   [resource-leak.md](resource-leak.md). Do not just keep recycling.
3. **If fan-in is the cause:** the server is healthy; the fix is client-side
   (rate-limit or cache at the caller). Do not route reads around the edge
   tier to the leader — that is a ~50× latency hit and breaches propagation
   SLO too.
4. **Do not raise the SLO.** The sub-millisecond edge read is the customer-
   facing latency promise.

## Verification

- `histogram_quantile(0.99, sum by (le)(rate(configd_edge_read_seconds_bucket[5m])))`
  back below 0.001 (and p999 below 0.005) for the full alert window.
- Both alerts clear after the SLO evaluation interval.
- `Configd Runtime` GC-time-fraction back to the ZGC baseline.

## Escalation

- Page the next tier only if the p99 page (not the p999 warn) persists after
  recycling, with GC ruled out — an in-process read path that is slow with no
  GC pause is an unexplained regression and a P1 against the edge read path.

## Validation (fault injection)

No harness injects edge-read p99 breach directly. The hot-path guard is
`gates/jmh-gc-check.sh` (CT-34), which runs the JMH GC profiler on
`LocalConfigStoreReadBenchmark.getMiss` / `getIntoHit` and gates
`gc.alloc.rate.norm < 1 B/op` — i.e. zero steady-state allocation, the
property whose violation would cause GC pressure and a read-latency breach.
Metric emission is proven by `EdgeMetricsContractTest`
(`configd-edge-node/src/test/java/io/configd/edge/node/EdgeMetricsContractTest.java`).
Recovery-verified = `gc.alloc.rate.norm` stays at 0 B/op and edge read p99
stays sub-millisecond; the live-breach drill is **validation-pending** (no
injector exists).

## Related

- `docs/adr/adr-0041-gc-collector.md` — the GC strategy this SLO leans on.
- [resource-leak.md](resource-leak.md), [propagation-delay.md](propagation-delay.md)
