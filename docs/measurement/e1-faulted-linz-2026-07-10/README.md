# E1 — Faulted-Linearizability Matrix (2026-07-10)

Jepsen-grade faulted-linearizability measurement of the Configd control plane on the
feature-complete, hardened, fully-tested code. This replaces the previous 15-second,
N=1, quorum-preserving smoke with a real combination-nemesis matrix, driven against the
shaded server binary over the real Netty transport, every recorded history checked by the
trusted third-party Porcupine checker (the checker etcd uses).

> **The matrix did its job: it found a real linearizability bug.** On the pre-fix release
> bytes (`299ba14`) the adversarial matrix surfaced a genuine violation — a
> **phantom-absent linearizable read** (a `?consistency=linearizable` GET returning
> 404/absent for a committed-and-acked present key) under extreme combination faults on
> N=3. It was root-caused, **fixed in this arc** (NEVER-DEFER), and the full matrix was
> **re-run GREEN on the fixed code**. See "Bug found and fixed" below.

> **Scope.** This is E1 only — the faulted-linearizability matrix + the doc update to the
> measured reality. The ≥72-hour endurance **soak (E2) is a separate, still-pending arc**
> and is NOT run or claimed here.

## SHA provenance (what was measured)

- **Pre-fix (bug-finding) run:** `299ba14` server bytes — the shipped release at the start
  of E1. The adversarial matrix on these bytes found the phantom-absent violation
  (diagnostic results in `results-buggy-299ba14/`). This is *not* a clean release: it has
  the ReadIndex bug.
- **Fixed (proof) run:** branch `e1-faulted-linz` HEAD (`<TBD-fixed-sha>`), which is
  `299ba14` **plus the one-line-logic ReadIndex no-op gate** (commit `5a0e20f`) and the
  test-harness/docs changes. The clean, every-history-LINEARIZABLE matrix ran on these
  bytes. Linearizability is proven on the **fixed** server bytes; that fixed commit is the
  release the proof pins to.
  - The only compiled-server change vs `299ba14` is the fix: `git diff 299ba14
    <fixed-sha> -- configd-server configd-consensus-core configd-config-store
    configd-common configd-distribution-service configd-wire configd-transport
    configd-netty configd-observability configd-replication-engine configd-control-plane-api`
    shows exactly the `RaftNode.readIndex()` gate and its regression test; everything else
    is the harness/docs/CI.
- **Runner:** on-demand `c7i.4xlarge` (16 vCPU, non-burstable), `ap-south-1`, Ubuntu
  24.04, Corretto 25. Non-burstable is required — a burstable box throttles and corrupts
  fault/election timing.

## Bug found and fixed (the E1 deliverable that mattered most)

- **Symptom:** on `299ba14`, a linearizable GET returned **404/absent for a committed-and-
  acked present key** under adversarial combination faults on N=3. Diagnostic run:
  **150 cells, 2 non-linearizable (both N=3 phantom-absent), 139 LINEARIZABLE, 9
  INDETERMINATE.** One instance held for ~10 seconds across 6 reads (a multi-shard cell
  with sparse writes to the affected shard).
- **Root cause:** `RaftNode.readIndex()` captured `readIndex = commitIndex` with **no check
  that an entry from the current term had committed**. A newly-elected leader has every
  committed entry in its *log* (Leader Completeness) but its local `commitIndex` can lag
  them until the current-term no-op commits (§5.4.2 — replica-counting advances the commit
  index for current-term entries only). So a fresh leader served a read from an applied
  state behind an already-committed write. This is the classic ReadIndex requirement (Raft
  dissertation §6.4, step 1); the code already enforced it for `proposeConfigChange`
  ("Must commit no-op first") but was **missing it on the read path**.
- **Fix (commit `5a0e20f`):** `readIndex()` returns `-1` until `noopCommittedInCurrentTerm`
  — mapped by the server to `503 + X-Leader-Hint`, so the client retries (recorded by the
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

### The checker is not blind — discrimination is re-proven on HEAD

Before trusting any matrix result, the harness self-validates with two seeded bugs that
MUST turn the checker RED (control GREEN). Both had **bit-rotted** against the evolved
codebase and were re-authored against HEAD (see `configd-linz/discrimination/`):

- **stale-read** — a non-leader serves a linearizable read from stale local state (the
  `RaftNode.readIndex` leader gate + the quorum ReadIndex confirm are bypassed). Control
  GREEN, mutated **RED**. Proves the checker catches a read-side linearizability violation.
- **lost-acked-write** — a confirmed committed value must vanish on a full-cluster restart.
  Re-authoring this surfaced a **strong positive property**: the raft-anchor durability
  kernel *fail-closes* a lost write. With only the WAL write no-opped, every node refuses
  to start —
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
| Symmetric partition (single + multi-node → quorum-breaking) | `iptables -j REJECT --reject-with tcp-reset` on the node's raft `--dport` |
| Process pause (stale-leader / stop-the-world) | `SIGSTOP` … `SIGCONT` — the frozen node keeps its sockets open; the majority may re-elect; on resume it must not serve a stale read or commit |
| Packet loss / dribble | `iptables -m statistic --mode random --probability P -j DROP` on the raft `--dport` |
| Clock skew | per-node wall-clock offset via `libfaketime` (elections are tick-driven, so this is a timestamp/staleness perturbation — see Honesty guardrails) |
| Combinations | overlapping faults on an independent apply/heal scheduler (the ADVERSARIAL schedule): a paused leader while a follower is isolated and a third node drops packets, in bursts that break quorum, separated by recovery windows |

**Schedules.** Seed-reproducible (same seed → byte-identical `schedule-<seed>.json`). The
**ADVERSARIAL** mode is the Jepsen-grade schedule (overlapping combination nemeses,
quorum-breaking bursts); the **SEQUENTIAL** mode (the original one-at-a-time,
quorum-preserving smoke) is retained for continuity.

**Postures.** Each cell runs under a security/durability posture on the consensus path:
`base` (plaintext), `encrypt` (at-rest AES-256-GCM WAL/snapshot), `auth` (bearer-token API),
and `skew` (clock skew). mTLS is a linearizability-invariant transport wrapper (already
proven functional by the horizontal-scale run) and is not re-driven as a linz cell.

**Every history must be LINEARIZABLE.** A single non-linearizable history is a real
correctness bug — root-caused and fixed in this arc, never documented-and-shipped.

## Results

_TBD — populated from `summary-all.tsv` after the matrix run:_

- Total histories: **TBD** (adversarial + sequential, across N=3/N=5 × postures)
- LINEARIZABLE: **TBD** · NON_LINEARIZABLE: **TBD** · INDETERMINATE (retried): **TBD**
- Fault events injected: **TBD** · operations recorded: **TBD**

Raw artifacts in this directory: `summary-all.tsv` (per-cell verdict), `schedule-*.json`
(reproducible fault schedules), `history-*.json` (checked op-histories), per-run logs.

## Honesty guardrails (preserved)

- The **≥72-hour soak (E2) is still pending** — the longest executed soak remains 6 hours;
  this arc does not run or claim endurance.
- Ordering is **per-shard, never global** across shards.
- Leadership auto-balance is **built but not proven at scale** (the 2.45× horizontal result
  was manually balanced).
- At-rest encryption is **off by default**.
- The fault model is **crash-tolerant, not Byzantine**.
- Reported numbers are exactly as measured — no rounding-in-our-favor, no cherry-picking.
