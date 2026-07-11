# Runbook: Write Commit Latency

**Alerts:** `ConfigdWriteCommitFastBurn` (page, 2m), `ConfigdWriteCommitSlowBurn` (page, 15m)
**SLO:** write commit p99 < 150 ms (`configd_write_commit_seconds`)
**Severity:** page

A burn-rate guard fired: the fraction of commits under the 150 ms budget
dropped below the burn threshold (14.4× over 5 min for fast-burn, 6× over
1 h for slow-burn). The Raft commit pipeline is degrading and the monthly
error budget is draining fast.

## Symptom

- `ConfigdWriteCommitFastBurn` pages within 2 min, or
  `ConfigdWriteCommitSlowBurn` at 15 min.
- `Configd Control Plane` dashboard **"Write commit p99"** panel
  (`histogram_quantile(0.99, ... configd_write_commit_seconds_bucket ...)`)
  sits above the 150 ms threshold line across multiple scrapes.
- Clients see slow `PUT/DELETE /v1/config/<key>` round-trips (not errors —
  these are committing, just slowly).

## Diagnosis

Open `Configd Control Plane` (`ops/dashboards/configd-control-plane.json`).

1. **Confirm scope and that commits are slow, not failing.** **"Write commit
   p99"** high while **"Write throughput + outcomes"** shows `committed`
   (`configd_write_commit_total`) still flowing and `failed` / `429 shed`
   near zero → latency regression, not an availability or overload event.
   (If `failed` is climbing → [control-plane-down.md](control-plane-down.md);
   if `429 shed` is climbing → [overload-shedding.md](overload-shedding.md).)
2. **Is the cost in apply (state machine) or in append/replicate?**
   - **"State-machine apply p99"** (`configd_apply_seconds`) high AND
     **"Raft apply backlog"** (`configd_raft_pending_apply_entries`) climbing
     → the state machine is the bottleneck. Go to
     [raft-saturation.md](raft-saturation.md).
   - Apply p99 and backlog normal, but commit p99 high → the cost is in the
     leader's WAL append/fsync or quorum replication. Check disk:
     ```sh
     kubectl -n configd exec <leader> -- sh -c 'iostat -x 1 3 2>/dev/null || cat /proc/diskstats'
     ```
     Growing await / queue depth on `/data` → fsync saturation →
     [disk-full-fsync.md](disk-full-fsync.md).
3. **Is leadership churning?** **"Term changes / min"** panel
   (`rate(configd_raft_elections_total[5m]) * 60`). Repeated term changes mean
   commits are paying re-election + no-op-commit cost each time →
   [control-plane-down.md](control-plane-down.md).
4. **Recent deploy?** If the latency rise correlates with a `configd-config-store`
   or consensus deploy, suspect a state-machine cost regression (`git log` the
   relevant module).

## Resolution steps

1. **If a voter's disk is the cause** (fsync await high): follow
   [disk-full-fsync.md](disk-full-fsync.md). Tactically, step the leader off
   the bad disk so a healthier voter takes over — only the leader pod is
   evicted:
   ```sh
   kubectl -n configd delete pod <leader>
   ```
2. **If a hot-prefix write burst is driving apply cost up:** rate-limit the
   offending namespace at the API gateway. Do **not** raise the apply queue
   bound. See [raft-saturation.md](raft-saturation.md).
3. **If a code regression correlates:** roll back the deploy —
   ```sh
   kubectl -n configd rollout undo statefulset/configd
   ```
   then confirm apply p99 returns to baseline (the apply queue drains within
   a minute once the cost regression is gone). See `release.md` for the full
   rollback procedure.
4. **Do not raise the 150 ms SLO threshold.** The budget is the contract; the
   fix is in the system. Do not silence the alert without an incident ticket.

## Verification

- `histogram_quantile(0.99, sum by (le)(rate(configd_write_commit_seconds_bucket[5m])))`
  returns below 0.150 across two scrape intervals.
- Both `ConfigdWriteCommitFastBurn` and `ConfigdWriteCommitSlowBurn` clear
  after their respective windows.
- `configd_raft_pending_apply_entries` is back to ~0; a test write
  round-trips at baseline latency.

## Escalation

- Page platform/storage if the bottleneck is fsync await on the data device
  (hardware — Configd cannot mitigate disk latency).
- If commit p99 stays breached after isolating disk, apply, churn, and
  recent deploys, escalate to the consensus owner — an undiagnosed commit-
  pipeline regression is a P1.

## Validation (fault injection)

Slow/failing fsync is injected by `FaultInjectingStorage.failNextSyncs(n)`
(`configd-testkit/src/test/java/io/configd/testkit/FaultInjectingStorage.java`),
exercised via `StorageEnospcConsensusReactionTest`. The
`configd_write_commit_seconds` emission itself is proven by
`MetricsWiringContractTest.committedWriteRecordsCommitLatency...`
(`configd-server/src/test/java/io/configd/server/MetricsWiringContractTest.java`).
A true production-grade *latency* injection (artificial fsync delay end-to-
end at a running cluster) does **not** exist as a harness — validation here
is the metric-emission + storage-fault contract, not a live p99-breach drill.
Recovery-verified = commit p99 back under 150 ms after the injected disk
fault clears.

## Related

- `docs/adr/adr-0001-embedded-raft-consensus.md` — commit pipeline.
- [raft-saturation.md](raft-saturation.md), [disk-full-fsync.md](disk-full-fsync.md),
  [control-plane-down.md](control-plane-down.md), [overload-shedding.md](overload-shedding.md)
