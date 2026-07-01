# ADR-0039: Frontier-based staleness - fixing the idle-staleness defect in contract section 2

- **Status:** Accepted (arbitrated and adopted by review-architect at the C1 design review, 2026-06-11; prod-blocking at the C2 gate: C2 may not wire the idle-time `StalenessTracker` as the production staleness signal)
- **Date:** 2026-06-11
- **Amends:** `docs/consistency-contract.md` section 2 (INV-S1 measurement definition; the threshold
  table and state machine are unchanged), ADR-0035 (the commit-timestamp clock remains the
  data-age instrument; this ADR adds the idle-frontier term)
- **Affects contract-test-map rows:** CT-01, CT-02, CT-07, CT-08 (and the C2 serving rows
  CT-03..CT-06 that consume the state machine)

## Context - the defect

Contract section 2 / INV-S1 defines edge staleness as

```
staleness(e, t) = wall_now(e, t) - commit_ts(last_applied_notification(e, t))
```

This is the right *data-age* definition while writes flow, but it is **unsound on a quiet
system**: with no commits for 30 s - entirely normal for a configuration workload - every
healthy, fully-caught-up edge marches CURRENT -> STALE (500 ms) -> DEGRADED (5 s, reports
unhealthy to its load balancer) -> DISCONNECTED (30 s, triggers re-bootstrap). A fleet of
idle edges would alarm, drain from load balancers, and re-bootstrap in a permanent storm,
all while serving perfectly fresh data. The existing proxy implementation
(`StalenessTracker` measuring idle time since the last update) has the identical defect -
it was tolerable only because nothing consumed it (RR-001). The defect was found during the
C1 protocol design and independently verified by review-architect (review section B-1):
"an idle-but-healthy edge does walk to DISCONNECTED as written."

The root confusion: section 2 conflates **"my data is old"** with **"I am behind the source"**.
Staleness - the thing the thresholds, the `X-Configd-Stale` header, and the re-bootstrap
trigger should key off - is the second thing.

## Decision

Staleness is measured against the **covered frontier**: the latest point in the commit
stream the edge *knows* it has fully covered.

```
frontier(e, t)  = max( commit_ts(last_applied_notification),
                       server_now(last HEARTBEAT h where h.latestSeq == cursor(e)) )
staleness(e, t) = wall_now(e, t) - frontier(e, t)
```

Mechanics:

1. The C1 fan-out protocol's `HEARTBEAT(latestSeq, serverNowMillis)` frame (cadence
   `edge.fanout.heartbeatMs` = 250 ms, 2x margin against the 500 ms STALE threshold) is the
   idle-frontier carrier. C1 ships the frame as a **carrier only**; interpreting it is C2.
2. On receiving a heartbeat, the edge advances its frontier to `serverNowMillis` **iff**
   `heartbeat.latestSeq == local cursor` - i.e., the server attests "there is nothing you
   have not seen as of my clock T". If `latestSeq > cursor` the heartbeat must NOT advance
   the frontier (the edge is genuinely behind; data age is real lag) - instead it is the
   cursor-lag signal (`edge_fanout_cursor_lag` metric, and the input to catch-up decisions).
3. While writes flow, applied notifications dominate the max and the measurement is exactly
   ADR-0035's data-age clock - behavior under load is unchanged from the contract as
   written.
4. The section 2 thresholds (500 ms / 5 s / 30 s), states, behaviors (header, counter, unhealthy,
   re-bootstrap), and INV-S2 percentile targets are **unchanged**; only the measured
   quantity is corrected.
5. **Implausibility tripwire (CT-08, unchanged in spirit):** a frontier in the future
   (negative staleness beyond the documented <= 50 ms NTP skew allowance) or a backwards
   jump is flagged on a dedicated metric (`edge_staleness_implausible_total`) and the
   sample is clamped, never silently trusted - a skewed or lying clock must be visible.

## Trust analysis (the honest part)

The heartbeat is **relay-asserted, not leader-signed**. A compromised or wedged fan-out
node could keep asserting `latestSeq == cursor` while suppressing the stream, masking
staleness for as long as no genuine delta arrives. This residual is:

- **identical in kind to the ADR-0038 residual** (wholesale-stall suppression is the one
  attack the signed chain cannot detect until the next chain link arrives), and narrower
  than the pre-ADR-0038 position (per-key suppression is detectable via chain breaks);
- **bounded by the control plane's own health surface**: the fan-out node sits on a CP node
  whose Raft liveness, commit indices, and `fanout_buffer_dropped_total` are independently
  monitored (the alerts are wired; the metrics exist);
- the alternative - leader-signed heartbeats - would put a signing operation on a 250 ms
  timer per node and still not defend against the leader itself wedging; rejected as cost
  without commensurate benefit. Recorded as a possible future revisit if the threat
  model hardens.

## Consequences

- C2 implements `StalenessTracker` against the frontier: `recordUpdate(version, commitTs)`
  becomes load-bearing (ADR-0035) AND a new `recordFrontier(serverNowMillis)` (heartbeat
  path, cursor-matched only). The idle-time proxy measurement is deleted, not retained
  alongside (two staleness numbers is how dashboards lie).
- Contract section 2's measurement paragraph and INV-S1 formula are amended by this ADR (the
  consolidated contract-text pass will update the contract;
  until then this ADR is the authoritative definition - map rows cite it).
- `StalenessUpperBoundTest` (CT-07) is rebuilt by C2 against the frontier clock: threshold
  transitions exercised by withholding BOTH deltas and heartbeats (a true stall), and the
  idle-but-heartbeating case pinned as CURRENT forever (the defect's regression test).
- The probe (V2) is unaffected: it measures delivery latency of real writes
  (publish->visible), which is orthogonal to idle-frontier bookkeeping.

## Sign-off

- review-architect: **ADOPTED at C1 design review** (review section B-1: "frontier-based fix is
  arbitrated as adopted; residual trust acceptable and honestly characterized; ADR-0039
  belongs before C2"). This document records that arbitration; prod enforcement is at the
  C2 gate.
