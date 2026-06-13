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
