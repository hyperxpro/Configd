# Session 4 — Fault Matrix (the session spine)

Charter §8: *"The fault matrix lives at `docs/session-4/fault-matrix.md` and is the session's
spine."* Charter §1 (matrix-before-execution): **every cell declares fault + expected behavior
(with the governing contract/§-clause) + a named oracle BEFORE it runs.** A cell with no written
expectation is not runnable. Safety violations are P0 and halt the matrix; liveness findings are
registered with a reproducing seed + measured duration.

This file is the index. Detailed per-workstream matrices live alongside it; cells point to their
evidence (an EXP-NNN record, a test, a capture).

## Status legend
✅ done (evidence cited) · 🔬 mechanism exists, cell characterized but not a pass/fail gate ·
⏳ declared, pending execution · 🌍 ENVIRONMENT-BLOCKED (exact infra named).

## Index of matrices
- **Workstream A (liveness stalls)** — RR-103/RR-095: `experiments/EXP-001`, `EXP-002`. DONE.
- **Workstream A3 (owed edge-chaos legs)** — this file, §A3 below.
- **Workstream B / B2 (durability & crash-recovery kill matrix)** — `kill-matrix.md` (two planes,
  per-cell oracles). Storage-fault layer: `storage-fault-layer-design.md`. RR-008: `EXP-003`.
- **Workstream D §2 (reconfiguration under fault)** — reconfig cells in `kill-matrix.md` +
  `EXP-004-reconfig-under-fault.md` (split-brain prevention; mid-joint crash recovery). DONE in-sim.
- **Workstream C (partition & WAN)** — ⏳ pending (Compose + netem/iptables; clock skew).
- **Workstream E (sustained mini-Jepsen)** — ⏳ pending (nightly, against the fully-fixed system).

---

## §A3 — The four owed edge-chaos legs (S3 handoff §1)

Governing docs: `architecture.md` §6 (failure handling) / §11 (backpressure & overload);
ADR-0039 (staleness ladder), ADR-0040 (poison ladder), RR-102 (would-block pause/resume).
Enforcement points located by reconnaissance (file:line) cited per cell.

| # | Fault (cell) | Expected behavior (governing clause) | Oracle | Status |
|---|---|---|---|---|
| A3-1 | **Accept-then-black-hole fan-out endpoint** — peer completes TCP accept but never produces bytes / never completes the edge handshake | The edge-side bound bites: `CONNECT_TIMEOUT_MS=1000` / `HANDSHAKE_TIMEOUT_MS=2000` (`EdgeStreamClient.java:95/97`, mirroring `TcpRaftTransport.java:102/110`) abandon the dead endpoint **within bound**; the edge does not stall indefinitely; it surfaces the failure and retries/fails-over (arch §6 — a wedged peer must not freeze the consumer). | A real-socket test points a client at a ServerSocket that accepts-but-blackholes; assert the connect/handshake attempt aborts within ~`CONNECT+HANDSHAKE+slack` (not hang); failure surfaced (exception/metric `edge_reconnects_total`), tick thread never blocked. | ✅ **EXP-005** `EdgeTransportMtlsTest.blackholedEndpointHandshakeTimesOutAndEdgeKeepsRetrying` (real-socket + TLS; reconnects accumulate within bound, never subscribes; 15.8 s). Hang-discriminating: no timeout → blocks to `@Timeout`. |
| A3-2 | **Prod-threshold ack-lag demotion** — a consumer that READS but never ACKs, driven past the **production** `ackLagDemoteSeqs=8192` (`FanOutConfig.java:89`; the E2E/sim can only reach the tuned-down `=2`) | At `cursor − lastAckedSeq > 8192` the session demotes `REASON_ACK_LAG` → forced snapshot (`FanOutSessionCore.java:291`), per the ADR-0039/§11 slow-consumer ladder — bounded queue, no unbounded buffering. | Drive `FanOutSessionCore` with the **default** config (8192) past the threshold without acks; assert exactly-at-threshold demotion with `REASON_ACK_LAG`; `edge_fanout_demotions_ack_lag_total` increments; a snapshot transfer is queued. Discriminate via mutation (threshold off-by-one / wrong reason). | ✅ **EXP-005** `FanOutSessionCoreTest.prodThresholdAckLag{Over,At}Threshold*` (8193→demote, 8192→no demote); M-acklag (`>`→`>=`) RED. |
| A3-3 | **Wedged-but-open transport during a paced transfer** — the transport sink returns would-block forever mid-snapshot (RR-102 pause path, never drains) | Degrades **safely & boundedly**: pause/resume on the same envelope (`FanOutSessionCore.java:371–427`), **no** cutover until SNAPSHOT_END is accepted, **no** hot loop, queue stays ≤ capacity, no corruption; resumes correctly when unwedged. No dedicated *stalled-transfer* signal exists (c5-signoff F2 / S6) → characterize the observable proxy. | Wedge the sink mid-transfer; assert across many ticks: bounded work (no hot loop), queue ≤ capacity, cursor does NOT advance (no premature cutover), `snapshot_transfers_total` flat; on unwedge the transfer completes and cursor advances. Record the detection proxy (`edge_fanout_queue_depth` pinned + no completion) and confirm whether a new signal is *needed* to detect it (charter §8.9). | 🔬 **EXP-005** `FanOutSessionCoreTest.wedgedTransportDuringSnapshotPausesSafelyThenResumesAsOneEnvelope` (CATCHUP held, cursor frozen, no hot loop; unwedge → exactly one envelope). Detection proxy works; dedicated stalled-transfer signal → S6. |
| A3-4 | **Long-running governor churn** — repeated quarantine/readmit across **more distinct identities than the bound** `maxTrackedIdentities=4096` (`SlowConsumerPolicyConfig.java:86`) | Bounded-memory safety (§11): the access-order identity map evicts **only HEALTHY, least-recently-touched** records (`SlowConsumerGovernor.evictIfAtBound`, `:370–385`); **distressed (SLOW/CATCHUP/QUARANTINED/UNHEALTHY) records are NEVER evicted** (policy state preserved); an evicted-then-returning HEALTHY identity re-enters fresh (forced SNAPSHOT_FIRST on readmit, never a stale-cursor serve). | Drive ≫ bound distinct identities through quarantine cycles with a tiny `maxTrackedIdentities`; assert: (a) no QUARANTINED/UNHEALTHY identity is ever evicted, (b) tracked size bounded (≤ bound, or honest overflow only when ALL distressed), (c) HEALTHY re-entry after eviction is treated fresh, (d) no unbounded growth. Mutation: evict-regardless-of-state → assert the policy-state-loss is caught. | ✅ **EXP-005** `GovernorBoundedIdentityMapChurnTest` (3 quarantined survive 5000 healthy churns, map==bound; all-distressed honest overflow); M-evict RED. |

**Notes carried from recon:** A3-1's timeouts have **no in-sim seam** (socket layer); A3-3 is partly
covered by `BootstrapSnapshotBackpressureTest` (paced) — this cell adds the *wedged-forever* (not
merely paced) characterization; A3-2/A3-4 are deterministic and fast.

---

## §C — Partition & WAN chaos matrix (architecture §12)

Governing docs: `architecture.md` §6 (failure handling) / §12 (WAN modeling); `consistency-contract.md`
(no two leaders commit in the same term; no acked write lost; committed prefix never regresses;
bounded-stale minority reads; monotonic-read survival). **Primary oracle (deterministic, always-on,
CI):** the in-sim matrix asserts the linearizability-relevant SAFETY invariants every step —
single-leader-per-term + no divergent commit + no committed-entry loss + minority-no-progress. The
**full Porcupine history-linearizability** check is the env-gated `configd-linz` path (gate-2 linzgate).

| # | Fault (cell) | Expected behavior (clause) | Oracle | Status |
|---|---|---|---|---|
| C-1 | **Single-region isolation** (leader+1 → 2-node minority, 3-node majority) | majority elects + commits; minority makes NO progress (no-quorum); heal → all converge, committed prefix preserved | `PartitionMatrixTest.singleRegionIsolation…` — continuous safety + minority-frozen-commit + measured recovery (worst re-elect 703, converge 59 ticks/12 seeds) | ✅ EXP-009 |
| C-2 | **Leader isolation** (leader alone vs 4-node majority) | old leader sheds (CheckQuorum) within bound, majority re-elects; isolated leader does NOT keep committing; no split-brain | `PartitionMatrixTest.leaderIsolation…` (worst re-elect 543 ticks) | ✅ EXP-009 |
| C-3 | **Asymmetric partition** (A→B cut, B→A intact) | PreVote prevents term inflation; CheckQuorum sheds; no split-brain commit; heal converges | `PartitionMatrixTest.asymmetricPartition…` (safety held throughout 800-tick soak ×12 seeds) | ✅ EXP-009 |
| C-4 | **Partial partition** (a subset of links cut; no clean split) | a connected majority component still progresses; no split-brain / no divergent commit; heal converges | `PartitionMatrixTest.partialPartition…` | ✅ EXP-009 |
| C-5 | **Gray failure** (elevated latency, no drops) | safety holds; leadership does NOT flap into a storm; writes still commit (slower); recovers when latency clears | `PartitionMatrixTest.grayFailure…` (termBumps ≤ 25 under +40 ms ×12 seeds) | ✅ EXP-009 |
| C-6 | **Clock skew** (per-node ±hours wall-clock offset) | consensus safety + liveness are INDEPENDENT of synchronized clocks (Raft is tick-driven) — charter §6 | `PartitionMatrixTest.clockSkew…` — full isolate+heal under maximal skew, safety + bounded re-elect/converge | ✅ EXP-009 |
| C-edge | **Fan-out partition** (edge cut from fan-out) | ADR-0039 staleness ladder → DISCONNECTED → re-bootstrap → catch-up → CURRENT; no edge stuck stale-but-silent | `EdgeReBootstrapOnDisconnectTest` (S3, sim) + `e2e-compose-scenario.sh` phase 3 (live docker-network disconnect, gate-3) | ✅ (cited) |
| C-linz | **Linearizability over the partition/failover history** (full Porcupine check) | the recorded write/read history is linearizable across the fault | `configd-linz` HarnessMain + `FaultInjector` (real iptables partitions + kill-9) → Porcupine; **gate-2 linzgate** (CI, builds porcupine from Go) | 🌍 ENVIRONMENT-BLOCKED on this dev box (Go ABSENT → porcupine unbuildable, `PORCUPINE_BIN` unset). Runs in CI gate-2; the deterministic in-sim safety invariants (C-1..C-6) are the always-on substitute. → S5 |
| C-live | **Live partition** (real network) | a black-holed peer must not freeze the node; live partition + heal | `rr-002-blackhole-drill.sh` (iptables DROP a follower's raft port, gate-1; `sudo -n iptables` works here) + `e2e-compose` phase 3 (docker-network) | ✅ (cited, CI). True **multi-host asymmetric/partial** partitions across real hosts → 🌍 ENVIRONMENT-BLOCKED → S5 |

**Recovery bounds (sim ticks, 12 seeds each)** → `recovery-bounds.md`: leader-isolation re-elect ≤ 543;
single-region re-elect ≤ 703, converge ≤ 59. Asymmetric/partial/gray/clock-skew: safety held, bounded
convergence after heal.
