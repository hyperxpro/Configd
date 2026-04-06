# Runbook: Edge Read Latency

**Alert:** `ConfigdEdgeReadFastBurn`, `ConfigdEdgeReadP999Breach`
**SLO:** `edge.read.latency.p99 < 1 ms`, `edge.read.latency.p999 < 5 ms`
**Severity:** page (p99), warn (p999)

## Symptoms

- Page from `ConfigdEdgeReadFastBurn` (p99 breach) or warn from
  `ConfigdEdgeReadP999Breach`.
- `Configd Overview` dashboard shows `configd_edge_read_seconds` p99
  above 1 ms or p999 above 5 ms across multiple scrape intervals (this
  is the histogram registered by `ConfigdMetrics.NAME_EDGE_READ_SECONDS`;
  the legacy `configd_edge_read_latency_seconds` name is not emitted).
  <!-- TODO PA-XXXX: legacy metric configd_edge_read_latency_seconds
  not emitted; the canonical histogram is configd_edge_read_seconds. -->

- Application-side timeouts on config reads (typically logged by client
  libraries as `read deadline exceeded`).

## Impact

Edge reads are served from a per-node materialized HAMT snapshot — they
should be in-process O(log₃₂ N) lookups with no remote IO. Crossing
the SLO means application threads are blocking on what is supposed to
be a sub-microsecond local lookup. Effects compound:

- Application thread pools fill, causing upstream cascades.
- Cache thrash if the application has its own short-TTL cache layered
  above Configd reads.
- Operator dashboards drift further from the SLA contract — the
  five-nines edge-read claim is the customer-facing latency promise.

## Operator-Setup

Per ADR-0025 the operator must, before this runbook applies:

1. Wire `ConfigdEdgeReadFastBurn` / `ConfigdEdgeReadP999Breach` from
   `ops/alerts/configd-slo-alerts.yaml` into the on-call paging
   service.
2. Make sure each edge pod is shipping JFR or async-profiler-friendly
   binaries (the `jcmd` mitigation step assumes a JDK image, not a JRE
   image; `docker/Dockerfile.runtime` already uses the JDK).

## Diagnosis

1. **Check JVM pause.** `kubectl logs <pod> | grep -E "Pause|Total time"`.
   ZGC pauses > 1 ms are anomalous; investigate heap pressure or pinned
   threads.

2. **Check snapshot rebuild rate.** The metric
   `configd_snapshot_rebuild_total` should increment only on
   leader-change. A high rate during steady state indicates either Raft
   leadership churn (see `propagation-delay.md`) or a poison entry that
   keeps re-failing.

3. **Check read concurrency.** If the spike correlates with a known
   client deployment, the issue is fan-in not the server. Confirm via
   `configd_edge_read_total` rate.

## Mitigation

- Roll the affected pod (in a 3+ replica edge tier, this is safe):
  `kubectl -n configd delete pod <edge-pod>`. The replacement comes up
  from the most recent verified snapshot and rejoins fan-out.
- If JVM pause is the culprit, check for off-heap leak via
  `jcmd <pid> Native.memory.summary`; if a leak is confirmed, recycle
  the pod immediately and file a follow-up ticket against
  `configd-edge-cache`.
- If snapshot-rebuild is thrashing on a poison entry, isolate the bad
  config key and submit a `DELETE` of that key from the control plane
  to clear the rebuild loop.

## Resolution

`configd_edge_read_seconds` p99 returns below 1 ms (and p999
below 5 ms) for the entire alert window. Both alerts clear after the
SLO evaluation interval. The root cause (GC pause, snapshot churn,
fan-in spike, or poison entry) is documented and a follow-up ticket
filed if the cause is a code regression rather than a transient.

## Rollback

If the chosen mitigation made the situation worse:

- A rolled pod that comes up slower than the others (cold cache) is
  expected — give it the documented bootstrap window before declaring
  failure.
- If the `DELETE` of a "poison entry" turns out to have been a wrongful
  deletion (the entry was real config some application depended on),
  re-PUT the entry from the source-of-truth in the operator's config
  repo. There is no automatic undelete.

## Postmortem

Open a post-incident review only if the alert was paged (not for the
warn-level p999 breach unless it sustained > 30 minutes). Required
fields:

- Was the cause GC pause, snapshot rebuild, fan-in spike, or other?
- Was the SLO budget exhausted? Cross-check the in-process
  `SloTracker` exports.
  <!-- TODO PA-XXXX: metric configd_slo_budget_seconds_remaining not
  yet emitted by ConfigdMetrics; the in-process SloTracker is
  authoritative until a derived gauge ships. -->

- Action items: profile capture (JFR if possible), instrumentation
  gap, regression-test gap.

## Related

- `ProductionSloDefinitions.EDGE_READ_LATENCY_P99`
- `docs/decisions/adr-0005-lock-free-edge-reads.md` — the design
  contract that asserts the < 50 ns read path.
- `docs/decisions/adr-0014-zgc-shenandoah-gc-strategy.md` — the GC
  strategy that this SLO depends on.
- `docs/perf-baseline.md` — historical edge read latencies.

## Do not

- Do not route around the edge tier — the SLA assumes edge reads.
  Going to the leader for reads is a 50× latency hit and will breach
  propagation SLO too.
