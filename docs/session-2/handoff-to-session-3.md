# Handoff: Session 2 → Session 3 (Edge Data Plane)

> Session 2 (branch `session-2-correctness`) hardened the control plane. Session 3 builds the edge
> data plane — the wire path that makes RR-001 real. Read in order: this doc, then
> `docs/decisions/adr-0034-commit-notification-boundary.md` (the interface you consume),
> `docs/consistency-contract.md` (the guarantees you must satisfy), `docs/readiness-register.md`
> (your owned rows). The Session-1 handoff (`docs/audit-session-1/handoff-to-session-2.md`) remains
> the model for evidence discipline.

## 1. What Session 2 changed under you (so you don't re-derive it)

All four control-plane P0/P1-class defects are fixed and second-agent-verified:
- **RR-004** ack≠commit → HTTP 200 `Committed: seq=S` only after quorum commit + apply (ADR-0033).
  The client-visible **S is the applied-mutation sequence** (skips no-ops/RCFG) — the same value
  the read cursor consumes. `StateMachine.apply` now returns that seq.
- **RR-003** restart-after-compaction data loss → snapshots persist (fsync + atomic rename) BEFORE
  WAL truncation; recovery loads persisted snapshot + WAL suffix; a `durable_prefix_no_gap` runtime
  assertion fires on any recovery gap. The follower `handleInstallSnapshot` had the same bug, fixed
  symmetrically.
- **RR-002** timeout-less connect freeze → bounded connect (1000ms)/handshake (2000ms), all socket
  establishment moved OFF the tick thread onto a dedicated connector; one black-holed peer no longer
  stalls consensus (`gates/rr-002-blackhole-drill.sh`).
- **RR-006** timing 10× bug → `...Ms` config is now real milliseconds (live re-election ~0.25s).
- **RR-094** gate-1 TLS flake → fixed; **gate-1 PASSES** officially.

Determinism, proof-layer, formal, and boundary work also landed — see §4.

## 2. The interface you consume: `CommitNotificationSource` (ADR-0034)

Session 2 delivered the bounded, replayable commit-notification boundary (the §4.6 deliverable).
**You implement the wire path on top of this; do NOT re-instrument the apply path.**

- **`io.configd.distribution.CommitNotificationSource`** (configd-distribution-service):
  `readSince(cursor)` → `Ok(notifications)` (contiguous from cursor) or `Gap` (cursor lapped /
  buffer overflowed). Each `CommitNotification` = `(long seq, long commitTimestampMillis,
  ConfigDelta delta)`: `seq` is the ADR-0033 applied-mutation sequence (your cursor key);
  `commitTimestampMillis` is the leader wall-clock at apply (the staleness clock — see §3);
  `delta` carries keys/payloads + F-0052 signature/epoch/nonce (forward it unchanged so the edge
  can verify + reject replays). Obtain it via `ConfigdServer.commitNotificationSource()`.
- **Overflow policy** (documented, deliberate): bounded ring (10,000), drop-oldest with a
  `fanout_buffer_dropped_total` metric and a `Gap` signal — NEVER silent wrong data. Justified
  because the log + snapshot is the replay source (post-RR-003 the durable prefix reconstructs all
  committed state).
- **On `Gap`**: call `ConfigdServer.replaySource().replayFromSnapshot()` (a `ReplaySource` over the
  state-machine snapshot — snapshot-equivalent state at seq S, honestly characterized in ADR-0034,
  NOT full historical-log replay), apply the snapshot wholesale, set cursor to the returned seq,
  resume `readSince` tailing. This gives **exactly-once over effect** (proved by
  `CommitNotificationSourceTest.replayThenTailObservesEveryMutationEffectExactly`).
- **Do NOT use `FanOutBuffer.deltasSince`** — it is the legacy non-atomic read (RR-066), retained
  only for old tests, consumer-unreachable. Use `readSince`.

## 3. Contract guarantees the data plane must satisfy

From `docs/consistency-contract.md` (reconciled to reality this session):
- **§2 Edge staleness bounds** (p99 500ms / p999 1s / p9999 2s) are unchanged TARGETS. The
  measurement mechanism was redefined (ADR-0035): staleness = `edge_wall_now −
  commitTimestampMillis` of the last applied notification. **Session 3 implements the measurement**:
  feed `commitTimestampMillis` into `StalenessTracker` (today it ignores its timestamp param and
  measures local idle time — fix that). The §2 state machine (CURRENT/STALE/DEGRADED/DISCONNECTED)
  and the `X-Configd-Stale` header are still owed.
- **§3 Monotonic reads / §6 read-your-writes**: the cursor is the applied-mutation seq S. A client
  that wrote and got `Committed: seq=S` must not see < S from any edge in-region (within
  `ryw_timeout`). The interface gives you S on every notification; the cursor compare is yours.
- **§6 BATCH** is still unwired (HttpApiServer exposes PUT/GET/DELETE only) — if the edge needs it,
  it is net-new.
- **GLOBAL/strong-read keys (RR-020)**: fail-closed linearizable reads are enforced on the control
  plane (`StrongReadPolicy`, default prefix `secure/`). The edge MUST NOT serve strong-read keys
  from stale local state — route them to the control-plane ReadIndex or fail closed.

## 4. Reusable infrastructure (do NOT rebuild)

- **Deterministic simulator** (`configd-testkit`): `RaftSimulation` + the adversarial layer
  (`AdversarialSim`, `AdversarialSchedule`, `AdversarialNetwork`, `SkewedClock`) — all seed-derived
  (`mixSeed`), replayable by seed, with `SimInvariants` checking the full safety set every tick.
  Fault classes: reorder/drop/dup/delay/partition (full/partial/asymmetric)/crash-restart incl.
  mid-fsync. **Extend this for edge-propagation scenarios** rather than writing a new sim.
- **CrashStorage** (`configd-consensus-core` test-jar, consumed via the `io.configd.raft`
  same-package adapter pattern — see env memory): models unsynced-write loss on crash; reuse it for
  any edge durability testing.
- **Linearizability checker** (`configd-linz`, Porcupine): self-tests 8/8 in gate-1 step (b); the
  **sim-history bridge** (`HistoryRecorder` → `SimHistoryCheck`) checks sim op-histories without a
  cluster. Capture edge histories in the same checker-neutral `invoke/ok/fail/info` format and they
  check for free. The live faulted seed matrix + discrimination gates are in `docs/session-2/linz-plan.md`.
- **Adversarial gate seed set**: `configd-testkit/src/test/resources/gate/adversarial-gate-seeds.txt`
  (507 seeds, ~4s, full invariants) — add edge invariants to `SimInvariants` and they ride this set.
- **Gates**: `gates/gate-1.sh` (green) and `gates/gate-2.sh` (cumulative). Add an edge end-to-end
  step to gate-2 when the wire path exists.
- **DROP is now safe** for fault injection (RR-002 fixed) — you no longer need REJECT-only.

## 5. Open findings you own (register is authoritative)

- **RR-001** (P0, OPEN, yours): the edge wire path. Blast radius reduced — the buffer is bounded,
  the interface is defined, replay is proven — but committed writes still cannot reach an edge until
  you build transport + subscription + the drain loop in §2's shape. This is your headline.
- **RR-005** (compaction unreachable + ≥2GiB WAL int-cast), **RR-019** (4MiB InstallSnapshot cliff),
  **RR-008** (silent Throwable swallow — its swallow path now has its first test observation via
  RR-091's de-vacuation, noted in the row), **RR-033** (restart amnesia — symptom reduced by RR-003,
  not eliminated), **RR-034–RR-043** (orphan replication/distribution/edge code — implement-or-delete
  decisions), **RR-022/RR-032** (chaos/Jepsen harness — the adversarial sim covers much of the
  intent now; decide what live chaos adds), **RR-064** (wire-compat stubs — the new `raft-log.snapshot`
  envelope extends their scope, see RR-003 review N-2), plus the durability/recovery P2s.
- **New this session**: **RR-095** (P3) — 7/10,000 adversarial seeds hit a liveness stall under
  sustained ~44% drop + never-healed partitions (expected never-healed-schedule artifact, 0 safety
  impact, replayable by seed; `docs/session-2/captures/sweep-10k-run.txt`). Registered for honesty,
  not action. Cross-check it doesn't mask an edge-propagation liveness bug once the edge exists.

## 6. Evidence discipline (unchanged, enforced)

Every fix: discriminating test FIRST with a captured pre-fix failure, then fix, then mutation-check
the fix region, then register with the commit + test + capture. P0/P1 closures need second-agent
verification. No sleeps as synchronization. Spec and code may not disagree at exit — the specs now
model the ack point (`ClientAck`/`AckImpliesCommitted`) and durable recovery (`DurablePrefix`);
extend them for edge propagation. The captures directory `docs/session-2/captures/` and the
review files `docs/session-2/reviews/` are the model for what "proven" looks like.
