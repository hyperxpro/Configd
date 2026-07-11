# Runbook: Write Overload Shedding (sustained 429s)

**Alert:** `ConfigdWriteOverloadShedding`
**Series:** `rate(configd_write_rejected_overloaded_total[5m]) > 1` for 5m
**Severity:** warn

The control plane is shedding writes. The bounded proposal queue
(`maxPendingProposals = 1024`) is full, so the leader is
answering new writes with `429 Overloaded` + `Retry-After: 1` instead of
unbounded-queueing them. This is correct back-pressure, not a fault — but
sustained shedding means write demand exceeds commit throughput.

## Symptom

- `ConfigdWriteOverloadShedding` warns after 5 min of > 1 reject/s.
- Clients see `HTTP 429` on `PUT/DELETE /v1/config/<key>` with a
  `Retry-After: 1` header (the only 429 the server emits —
  `HttpApiServer` `WriteResult.Overloaded` branch).
- A runaway fan-out / reconnect storm can show the same shape: the box is
  CPU-bound serving edge re-bootstraps and commits slow down.

## Diagnosis

1. **Confirm the shed is real and sustained.** `Configd Control Plane`
   dashboard, panel **"Write throughput + outcomes (rate/s)"** — the
   `429 shed` series (`rate(configd_write_rejected_overloaded_total[5m])`)
   is non-zero while `committed` (`configd_write_commit_total`) is flat or
   falling. Scrape it directly:
   ```sh
   kubectl -n configd exec configd-0 -- \
     curl -sf http://localhost:8080/metrics \
     | grep -E '^configd_write_(rejected_overloaded|commit)_total'
   ```
2. **Is commit throughput the bottleneck, or apply?** Same dashboard,
   **"Raft apply backlog"** panel (`configd_raft_pending_apply_entries`).
   - Backlog ~0 but shedding → writers simply exceed commit rate
     (measured box ceiling ~125–172 commits/s). Scale writers/cores or
     rate-limit the offending namespace.
   - Backlog climbing → apply is the bottleneck; cross to
     [raft-saturation.md](raft-saturation.md).
3. **Is this a fan-out / reconnect storm driving the box?** `Configd Data
   Plane` dashboard — **"Connected subscribers"**
   (`edge_fanout_connected_subscribers`) and **"Edge re-bootstrap /
   reconnect"** (`edge_rebootstrap_triggered_total`,
   `edge_reconnects_total`) spiking at the same time. If so the shed is a
   symptom of the storm; cross to [edge-catchup-storm.md](edge-catchup-storm.md).
4. **Identify the write source.** There is no per-namespace write metric;
   the API gateway access log is the operator-visible source of which
   caller is driving the flood.

## Resolution steps

1. **If a single caller / namespace is flooding:** rate-limit it at the
   API gateway. The 429 + `Retry-After: 1` is already telling well-behaved
   clients to back off; the offender is one ignoring it. Do **not** raise
   `maxPendingProposals` — the bound is the back-pressure.
2. **If demand is legitimately above box throughput:** the only lever is
   capacity. The commit rate is bounded by the leader's fsync + replicate
   path on this hardware; add cores / faster disk and re-measure the real
   ceiling under production load. There is no horizontal write
   scaling — Raft commits are single-leader.
3. **If a reconnect storm is the driver:** follow
   [edge-catchup-storm.md](edge-catchup-storm.md) — the bounded per-session
   queues + `SlowConsumerGovernor` ladder self-limit it; staggering edge
   restarts in waves widens the herd.
4. **Do not** disable the bounded queue or the 429 path to "let writes
   through". An unbounded queue converts a clean shed into an OOM
   (the box-OOM seen in an earlier soak run).

## Verification

- `rate(configd_write_rejected_overloaded_total[5m])` returns to 0 and the
  `ConfigdWriteOverloadShedding` alert clears after its 5 min window.
- `configd_write_commit_total` rate resumes at the box baseline; clients
  stop seeing 429s.
- If the cause was a storm: `edge_fanout_connected_subscribers` and
  `edge_rebootstrap_triggered_total` return to baseline.

## Escalation

- Page the next tier if shedding persists after gateway rate-limiting AND
  apply backlog is ~0 — that means raw commit capacity is exhausted and a
  capacity decision (hardware) is required, not an operator mitigation.
- If shedding co-occurs with leader churn (term changes climbing on the
  `Configd Control Plane` "Term changes/min" panel), the root cause is
  control-plane instability — go to [control-plane-down.md](control-plane-down.md).

## Validation (fault injection)

`OverloadChaosTest` (`configd-testkit/src/test/java/io/configd/testkit/OverloadChaosTest.java`)
floods a leader past the bounded queue and asserts the shed plateaus
(bounded, never unbounded) and the reconnect-storm path recovers.
`MetricsWiringContractTest.overloadedWriteRecordsRejectCounter`
(`configd-server/src/test/java/io/configd/server/MetricsWiringContractTest.java`)
saturates the queue (`maxPendingProposals = 3`) and asserts
`configd_write_rejected_overloaded_total` increments and the 429 path fires.

## Related

- Bounded proposal queue + 429 + Retry-After contract.
- `HttpApiServer` `WriteResult.Overloaded` → `429 + Retry-After: 1`.
- [raft-saturation.md](raft-saturation.md), [edge-catchup-storm.md](edge-catchup-storm.md)
