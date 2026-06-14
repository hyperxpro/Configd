# Runbook: Raft Apply Backlog / Wedged Leader / Election Livelock

**Alert:** `ConfigdRaftApplyBacklog` (warn, `max(configd_raft_pending_apply_entries) > 5000`, 5m)
**Also covers (no direct alert):** stuck/wedged leader, election livelock
(the RR-095 / RR-103 family).
**Severity:** warn (early indicator before `ConfigdWriteCommitFastBurn`)

The leader is committing entries faster than the state machine applies them
(`commitIndex − lastApplied` is growing), OR the leader is wedged and not
making progress at all. Apply backlog is ~0 in steady state; sustained
growth means write commit latency will breach within minutes.

## Symptom

- `ConfigdRaftApplyBacklog` warns after 5 min above 5000.
- `Configd Control Plane` dashboard **"Raft apply backlog"** panel
  (`configd_raft_pending_apply_entries`) climbing on the leader.
- **"State-machine apply p99"** (`configd_apply_seconds`) trending up: the
  apply call (HAMT mutation) is the bottleneck.
- Wedged-leader variant: a leader is present (consistent `X-Leader-Hint`)
  but `configd_write_commit_total` rate is flat at zero — it accepts nothing
  new, or accepts but never applies.

## Diagnosis

Open `Configd Control Plane` (`ops/dashboards/configd-control-plane.json`).

1. **Backlog growing — is apply slower than ingress?** Compare
   `rate(configd_apply_seconds_count[1m])` (apply rate) against
   `rate(configd_write_commit_total[1m])` (commit rate). Apply < commit →
   the queue grows.
   ```sh
   kubectl -n configd exec <leader> -- curl -sf http://localhost:8080/metrics | \
     grep -E '^configd_(raft_pending_apply_entries|apply_seconds_count|write_commit_total)'
   ```
2. **Profile the apply hot path.** On the leader:
   ```sh
   pid=$(kubectl -n configd exec <leader> -- pgrep -f configd-server)
   kubectl -n configd exec <leader> -- jcmd $pid JFR.start duration=30s filename=/tmp/apply.jfr
   ```
   Usual culprits: a HAMT mutation-cost regression on a deep prefix, a
   signature-verification spike, or a GC pause during apply
   (`Configd Runtime` GC panel).
3. **Wedged leader (backlog NOT growing, but no commits either).** This is
   the RR-095/RR-103 family: the leader holds leadership but inflight
   replication is starved.
   - `Configd Control Plane` **"Term changes / min"** flat (it is **not**
     re-electing — it still thinks it is leader) and `configd_write_commit_total`
     flat at zero.
   - The operator-visible Raft diagnostics — `leaderId`, `currentTerm`,
     `role` — are in the per-pod logs (there is no `/raft/status` endpoint);
     grep the leader's log for the role/term/leaderId lines to confirm it is
     LEADER at a stable term while making no progress.
4. **Election livelock (term climbing, no stable leader).** **"Term changes /
   min"** climbing while no `X-Leader-Hint` is stable across voters → repeated
   elections, usually one voter with a stale log forcing them. This is the
   leader-loss path — go to [control-plane-down.md](control-plane-down.md).

## Resolution steps

1. **Apply backlog from a hot-prefix burst:** rate-limit the offending
   namespace at the API gateway. Do **not** raise the apply-queue capacity —
   the bound is intentional back-pressure; lifting it just delays the symptom.
2. **Apply backlog from a slow leader (GC / disk):** step the leader to a
   less-loaded voter; the PDB ensures only the leader is evicted:
   ```sh
   kubectl -n configd delete pod <leader>
   ```
   If JFR shows signature verification dominating, confirm the pinned
   `ConfigSigner` library in `pom.xml` was not swapped. If GC, follow
   [resource-leak.md](resource-leak.md). If fsync await is high, follow
   [disk-full-fsync.md](disk-full-fsync.md).
3. **Wedged leader (RR-095/RR-103):** force a leadership change by recycling
   the wedged leader so a fresh voter takes over and unblocks progress:
   ```sh
   kubectl -n configd delete pod <leader>
   ```
   Confirm the new leader's `configd_write_commit_total` rate resumes. File
   against the consensus owner with the leader's logs (leaderId/term/role
   evidence) — a recurring wedge is a P1 (RR-095 is a known stall family).
4. **Election livelock:** go to [control-plane-down.md](control-plane-down.md)
   — recycle the churn-source voter.

## Verification

- `configd_raft_pending_apply_entries` returns below 5000 and holds; the
  `ConfigdRaftApplyBacklog` alert clears.
- Apply rate (`rate(configd_apply_seconds_count[1m])`) ≥ commit rate.
- A test write commits and applies; if no `ConfigdWriteCommitFastBurn`
  followed, the mitigation held (otherwise switch to
  [write-commit-latency.md](write-commit-latency.md)).

## Escalation

- Page the consensus owner if the leader re-wedges after recycle (RR-095
  family stall recurring) — this is a correctness-adjacent P1, not a capacity
  event.
- Escalate to [control-plane-down.md](control-plane-down.md) if the backlog
  is a symptom of leader churn rather than apply cost.

## Validation (fault injection)

`Rr095StallSeedDiagnosisTest`
(`configd-testkit/src/test/java/io/configd/testkit/Rr095StallSeedDiagnosisTest.java`)
re-runs the registered stall seeds against the RR-103-fixed kernel and proves
the wedge family; `LivenessBoundedProgressSweepTest` (same module)
distinguishes never-heals (RR-095) from recovers-after-heal (RR-103). The
apply-backlog gauge being live (not hardwired to 0) is proven by
`MetricsWiringContractTest.gaugesAndElectionsCounterAreNotHardwiredToZero`
(`configd-server/src/test/java/io/configd/server/MetricsWiringContractTest.java`).
There is **no** harness that injects a *running-cluster* apply backlog >5000;
the backlog-threshold drill is validation-pending. Recovery-verified for the
wedge = a recycled leader resumes `configd_write_commit_total`.

## Related

- RR-095 (stall seeds) / RR-103 (per-peer inflight leak, S4 fix).
- `docs/decisions/adr-0001-embedded-raft-consensus.md` — apply pipeline.
- [write-commit-latency.md](write-commit-latency.md), [control-plane-down.md](control-plane-down.md),
  [resource-leak.md](resource-leak.md), [disk-full-fsync.md](disk-full-fsync.md)
