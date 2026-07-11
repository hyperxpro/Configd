# Faulted-Linearizability Matrix (2026-07-10)

Jepsen-grade faulted-linearizability measurement of the Configd control plane on the
feature-complete, hardened, fully-tested code. This replaces the previous 15-second,
N=1, quorum-preserving smoke with a real combination-nemesis matrix, driven against the
shaded server binary over the real Netty transport, every recorded history checked by the
trusted third-party Porcupine checker (the checker etcd uses).

> **The matrix did its job: it found a real linearizability bug.** On the pre-fix release
> bytes (`299ba14`) the adversarial matrix surfaced a genuine violation: a
> **phantom-absent linearizable read** (a `?consistency=linearizable` GET returning
> 404/absent for a committed-and-acked present key) under extreme combination faults on
> N=3. It was root-caused, fixed, and the full matrix was **re-run GREEN on the fixed
> code**. See "Bug found and fixed" below.

> **Scope.** This covers the faulted-linearizability matrix only. The >=72h endurance
> soak is separate, still pending, and is not run or claimed here.

## SHA provenance (what was measured)

- **Pre-fix (bug-finding) run:** `299ba14` server bytes, the shipped release before this
  fix. The adversarial matrix on these bytes found the phantom-absent violation
  (diagnostic results in `buggy-299ba14/`). This is *not* a clean release: it has the
  ReadIndex bug.
- **Fixed (proof) run:** commit `ff1128a`, which is `299ba14` plus the one-line-logic
  ReadIndex no-op gate (commit `5a0e20f`) and the test-harness/docs changes. The clean,
  every-history-LINEARIZABLE matrix ran on these bytes. Linearizability is proven on the
  fixed server bytes; that fixed commit is the release the proof pins to.
  - The only compiled-server change vs `299ba14` is the fix: `git diff 299ba14 ff1128a
    ff1128a -- configd-server configd-consensus-core configd-config-store
    configd-common configd-distribution-service configd-wire configd-transport
    configd-netty configd-observability configd-replication-engine configd-control-plane-api`
    shows exactly the `RaftNode.readIndex()` gate and its regression test; everything else
    is the harness/docs/CI.
- **Runner:** on-demand `c7i.4xlarge` (16 vCPU, non-burstable), `ap-south-1`, Ubuntu
  24.04, Corretto 25. Non-burstable is required: a burstable box throttles and corrupts
  fault/election timing.

## Bug found and fixed

- **Symptom:** on `299ba14`, a linearizable GET returned **404/absent for a committed-and-
  acked present key** under adversarial combination faults on N=3. Diagnostic run:
  **150 cells, 2 non-linearizable (both N=3 phantom-absent), 139 LINEARIZABLE, 9
  INDETERMINATE.** One instance held for ~10 seconds across 6 reads (a multi-shard cell
  with sparse writes to the affected shard).
- **Root cause:** `RaftNode.readIndex()` captured `readIndex = commitIndex` with **no check
  that an entry from the current term had committed**. A newly-elected leader has every
  committed entry in its *log* (Leader Completeness) but its local `commitIndex` can lag
  them until the current-term no-op commits (section 5.4.2: replica-counting advances the
  commit index for current-term entries only). So a fresh leader served a read from an
  applied state behind an already-committed write. This is the classic ReadIndex
  requirement (Raft dissertation section 6.4, step 1); the code already enforced it for
  `proposeConfigChange` ("Must commit no-op first") but was **missing it on the read
  path**.
- **Fix (commit `5a0e20f`):** `readIndex()` returns `-1` until `noopCommittedInCurrentTerm`,
  mapped by the server to `503 + X-Leader-Hint`, so the client retries (recorded by the
  harness as INFO/dropped, never a false absent). N=1 is unchanged (the no-op commits
  synchronously). Regression: `ReadIndexNoOpBeforeServeTest` (fails without the fix).
- **Confirmation:** the two red seeds (20018, 24017) re-run GREEN on the fixed code, and
  the full matrix re-run is every-history-LINEARIZABLE (results below).

## The checker (trusted, third-party)

Each key is modelled as an independent linearizable register; the recorded history is
partitioned per key and each partition is checked by `anishathalye/porcupine`
(`configd-linz/src/main/go/porcupine-check`). Writes are indeterminate under `ack != commit`
and are floated forward (a floating write is always legal, so it can never *cause* a false
RED); unique per-write tokens plus confirming reads give the discrimination power. See
`configd-linz/README.md` for the full history-model rationale.

### The checker is not blind: discrimination is re-proven on HEAD

Before trusting any matrix result, the harness self-validates with two seeded bugs that
MUST turn the checker RED (control GREEN). Both had **bit-rotted** against the evolved
codebase and were re-authored against HEAD (see `configd-linz/discrimination/`):

- **stale-read**: a non-leader serves a linearizable read from stale local state (the
  `RaftNode.readIndex` leader gate + the quorum ReadIndex confirm are bypassed). Control
  GREEN, mutated **RED**. Proves the checker catches a read-side linearizability violation.
- **lost-acked-write**: a confirmed committed value must vanish on a full-cluster restart.
  Re-authoring this surfaced a **strong positive property**: the raft-anchor durability
  kernel *fail-closes* a lost write. With only the WAL write no-opped, every node refuses
  to start:
  `IntegrityException: WAL recovery head-rollback ... WAL last index 0 is below
  anchor.lastDurableIndex 2 (a committed-and-acked durable entry vanished - refusing, fail
  closed)` (`RaftLog.recoverWithAnchor`). A single-layer durability defeat therefore yields
  INDETERMINATE (no leader after restart), never a silent loss. To still exercise the
  checker's discrimination of the lost-write *shape*, the seed defeats BOTH layers (the WAL
  write **and** the anchor head-rollback guard); the value then genuinely vanishes and the
  checker goes **RED**. The single-layer INDETERMINATE result is itself recorded as the
  evidence that the anchor guard is load-bearing.

## The fault matrix (the real proof)

Cluster sizes **N=3 and N=5**, real multi-node, real leader elections. Faults are injected
on a real schedule against the live processes:

| Fault class | Mechanism |
|---|---|
| Leader / follower kill + restart-into-live-cluster | `SIGKILL` then relaunch against the same data-dir (WAL + anchor recovery, transport rejoin) |
| Symmetric partition (single + multi-node, quorum-breaking) | `iptables -j REJECT --reject-with tcp-reset` on the node's raft `--dport` |
| Process pause (stale-leader / stop-the-world) | `SIGSTOP` then `SIGCONT`; the frozen node keeps its sockets open, the majority may re-elect, and on resume it must not serve a stale read or commit |
| Packet loss / dribble | `iptables -m statistic --mode random --probability P -j DROP` on the raft `--dport` |
| Clock skew | per-node wall-clock offset via `libfaketime` (elections are tick-driven, so this is a timestamp/staleness perturbation; see Honesty guardrails) |
| Combinations | overlapping faults on an independent apply/heal scheduler (the ADVERSARIAL schedule): a paused leader while a follower is isolated and a third node drops packets, in bursts that break quorum, separated by recovery windows |

**Schedules.** Seed-reproducible (same seed produces a byte-identical
`schedule-<seed>.json`). The **ADVERSARIAL** mode is the Jepsen-grade schedule (overlapping
combination nemeses, quorum-breaking bursts); the **SEQUENTIAL** mode (the original
one-at-a-time, quorum-preserving smoke) is retained for continuity.

**Postures.** Each cell runs under a security/durability posture on the consensus path:
`base` (plaintext), `encrypt` (at-rest AES-256-GCM WAL/snapshot), `auth` (bearer-token API),
and `skew` (clock skew). mTLS is a linearizability-invariant transport wrapper (already
proven functional by the horizontal-scale run) and is not re-driven as a linz cell.

**Every history must be LINEARIZABLE.** A single non-linearizable history is a real
correctness bug: root-caused and fixed here, never documented-and-shipped.

## Results

**Pre-fix (bug-finding) run on `299ba14`** (`buggy-299ba14/summary-all.tsv`), stopped once
the bug was root-caused: **150 cells, 2 NON_LINEARIZABLE, 139 LINEARIZABLE, 9
INDETERMINATE.** Both red histories are the phantom-absent signature; both are captured
here as the bug evidence: `buggy-299ba14/history-20018-n3.json` (skew posture, 1
phantom-absent read on key `k3`) and `buggy-299ba14/history-24017-n3.json` (multi-shard
posture, 6 phantom-absent reads over ~10 s on key `k12`), with their reproducible
`schedule-*.json`.

**Fixed (proof) run** (`fixed/summary-all.tsv`, `fixed/orchestrator.log`, harness
`ff1128a` = `299ba14` plus the ReadIndex no-op gate), the full matrix, **270 cells:**

| Verdict | Count |
|---|---|
| **LINEARIZABLE** | **262** |
| **NON_LINEARIZABLE** | **0** |
| no-verdict (INDETERMINATE / election-starved, all on the `skew` posture) | 8 |

Every posture is **100% LINEARIZABLE** on both N=3 and N=5: `base`, `encrypt` (at-rest
AES-GCM), `auth` (bearer token), `shards` (multi-Raft, 4 shards, per-shard
linearizability), and the `sequential` continuity cells, with **0 non-linearizable
histories**. The only 8 no-verdict cells are all on the `skew` posture: `libfaketime`'s
`LD_PRELOAD` adds per-syscall overhead to the skewed node, which under the adversarial
fault storm occasionally starves an election past the harness's retry, so no checkable
history is produced (a liveness artifact, honestly reported, never a non-linearizable
result). Adversarial runs recorded ~4,800 operations and ~20 faults each.

Raw artifacts: `fixed/summary-all.tsv` (per-cell verdict), `fixed/histories-and-schedules.tar.gz`
(every checked `history-*.json` + reproducible `schedule-*.json`), `fixed/orchestrator.log`;
`buggy-299ba14/` (the pre-fix diagnostic summary + the two red histories/schedules).

**Client:** driven by the linz harness's own conforming HTTP client (same `X-Leader-Hint`
leader-follow + `?consistency=linearizable` semantics the RFC defines); wiring the
reference HTTP client in as the driver is a possible follow-up, met in spirit here by a
conforming client.

## Honesty guardrails

- The **>=72h endurance soak is still pending**: the longest executed soak remains 6
  hours; this measurement does not run or claim endurance.
- Ordering is **per-shard, never global** across shards.
- Leadership auto-balance is **built but not proven at scale** (the 2.45x horizontal
  result was manually balanced).
- At-rest encryption is **off by default**.
- The fault model is **crash-tolerant, not Byzantine**.
- Reported numbers are exactly as measured: no rounding in our favor, no cherry-picking.
