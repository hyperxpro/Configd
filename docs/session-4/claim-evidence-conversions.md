# Claim–Evidence Matrix — Session-4 Conversion Addendum

> The Session-1 matrix (`docs/audit-session-1/claim-evidence-matrix.md`) is an immutable audit
> artifact; the S2/S3 addenda converted the control-plane and data-plane rows. This addendum records
> the rows Session 4 was charged to convert (charter §9 DoD: "§6 failure-handling and §12 WAN rows
> converted with commands"). Conversion verbs as before: **→VERIFIED**, **→RESOLVED-BY-FIX**,
> **→RESOLVED-BY-AMEND**, **→PARTIAL(owner)**. Statuses here supersede prior statuses for the listed
> claims as of branch `session-4-chaos`.

## §6 Failure Handling

| Claim (arch §6) | New status | Evidence (command) |
|---|---|---|
| **Leader Isolation** — CheckQuorum steps a leader down on loss of majority; PreVote prevents term inflation | **→VERIFIED** | `PartitionMatrixTest.leaderIsolation…` (C-2): the isolated leader is shed and stops committing, the majority re-elects (worst 543 ticks), single-leader-per-term holds. Live: `bash gates/rr-002-blackhole-drill.sh` (iptables black-hole a peer; the leader does not freeze). `./mvnw -o -pl configd-testkit test -Dtest=PartitionMatrixTest` |
| **Asymmetric Partitions** — CheckQuorum + PreVote prevent disruption/split-brain | **→VERIFIED** | `PartitionMatrixTest.asymmetricPartition…` (C-3): one-way cut A→B; safety held throughout an 800-tick soak ×12 seeds; heal converges. |
| **Clock Skew** — no TrueTime; consensus must not depend on synchronized wall clocks | **→RESOLVED-BY-FIX** | `PartitionMatrixTest.clockSkew…` (C-6): per-node ±hours wall-clock offset; consensus safety + liveness hold (Raft is tick-driven). **Doc note:** the §6 wording "HLC-based … 500 ms max skew … fenced" describes the *timestamp/data-plane* policy (`SkewedClock` feeds `ConfigStateMachine` timestamps; the NTP ±50 ms operational bound is in `consistency-contract.md §6`); consensus SAFETY is wholly clock-independent, which is the property charter §6 (Hard Rule 4) demands and C-6 proves. Numeric fencing threshold = config policy (S6 operability). |
| **Gray Failures** — latency/health monitoring beyond Raft heartbeats; fsync > 1 s → voluntary step-down; packet loss tolerance | **→PARTIAL(S5/B3)** | Latency resilience VERIFIED: `PartitionMatrixTest.grayFailure…` (C-5, +40 ms spike → safety + no leadership-flap storm) and the mixed-loss/latency `MiniJepsenSweepTest` (E, 10–40% loss). The **fsync > 1 s voluntary step-down** (slow-disk follower must not drag the leader) is the B3 disk-pathology residual — `FaultInjectingStorage.latencyHook` built, the step-down wiring not yet exercised → carried to S5 (`storage-fault-layer-design.md`). |

## §11 Backpressure & Overload Policy

| Claim (arch §11) | New status | Evidence (command) |
|---|---|---|
| Past-capacity load is shed (not buffered unbounded); client-visible signal; clean recovery | **→VERIFIED** | `OverloadChaosTest.controlPlaneWriteFlood…` (D-1): `OVERLOADED` shed, the uncommitted queue plateaus at `maxPendingProposals` (1024) across sustained load (bounded, never unbounded), drains + accepts again on recovery. Fan-out admission/queue bounds: `FanOutServerAdmissionBoundTest`, `DemotionNoticeBackpressureTest`, A3-2/A3-3/A3-4. |
| Post-partition reconnect storm absorbed (the data plane's most dangerous overload) | **→VERIFIED** | `OverloadChaosTest.postPartitionReconnectStorm…` (D-2): 5 edges DISCONNECTED → healed at once → all recover to CURRENT (258 ticks), none terminal. `./mvnw -o -pl configd-testkit test -Dtest=OverloadChaosTest` |

## §12 Network & WAN Modeling — Partition Scenarios

| Scenario (arch §12 table) | New status | Evidence (command) |
|---|---|---|
| **Single region isolated** — majority in surviving regions continues; isolated region serves stale | **→VERIFIED** | `PartitionMatrixTest.singleRegionIsolation…` (C-1): majority re-elects + commits, minority makes no progress, heal converges (re-elect ≤ 703, converge ≤ 59 ticks), committed prefix preserved. |
| **Split-brain (2+3 partition)** — majority continues, minority steps down leaders | **→VERIFIED** | `PartitionMatrixTest` C-1 (minority isolation) + C-2 (leader on the minority side shed by CheckQuorum); single-leader-per-term + no divergent commit asserted every tick. |
| **Asymmetric partition** — PreVote/CheckQuorum prevent term inflation + force step-down | **→VERIFIED** | `PartitionMatrixTest.asymmetricPartition…` (C-3). |
| **Submarine cable cut** — edge nodes serve stale; recover when rerouted (DEGRADED state) | **→VERIFIED** | `EdgeReBootstrapOnDisconnectTest` (edge → ADR-0039 ladder → DEGRADED/DISCONNECTED → re-bootstrap → CURRENT) + `e2e-compose-scenario.sh` phase 3 (live docker-network disconnect). |
| **Linearizability over the partition/failover history** (INV-L1) | **→PARTIAL(S5)** | Deterministic in-sim safety invariants (no divergent commit / single-leader-per-term) asserted every tick across all C cells; the full **Porcupine** history-linearizability check is `configd-linz` (gate-2 linzgate, CI/Go) — **ENVIRONMENT-BLOCKED on the dev box (Go absent)**; runs in CI. → S5. |

## Provenance

Session-4 experiments `EXP-001..011`; the fault matrix `docs/session-4/fault-matrix.md`; gate
`gates/gate-4.sh` (CI-wired). Residuals (fsync-step-down B3, Porcupine on real Go, multi-host netem,
CT-02 SLO numbers) carried to `handoff-to-session-5.md`.
