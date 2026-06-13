# Recovery-Bounds Ledger — Session 4

Per fault class: measured time-to-full-service (median / worst observed) on **this
hardware** (2-vCPU t3a.large, CPU-credit throttling real), with caveats. This is the
input Session 6 SLOs and alert thresholds must use instead of invented numbers (the
S6-rows failure pattern from Session 1). "Full service" = the documented end-state for
that fault: a leader is serving, every reachable replica is at the committed prefix, and
every reachable edge is CURRENT within the staleness bound.

**Time domains.** Sim measurements are in *sim ticks* (the deterministic harness;
`RaftConfig.of` sim default `tickPeriodMs=1`, so 1 tick ≈ 1 ms of modelled time, heartbeat
interval = 50 ticks, election timeout 150–300 ticks). Live/Compose measurements are
wall-clock seconds on the throttled box and carry the throttling caveat. The two are
**not** directly comparable — sim ticks measure protocol round-trips under modelled
latency; wall-clock includes JVM/OS/throttle overhead. Both are recorded honestly and
labelled.

| Fault class | Domain | Median | Worst observed | Source / experiment | Caveats |
|---|---|---|---|---|---|
| Follower backfill after partition heal (committed-prefix catch-up, same term) | sim | **50 ticks** (1 heartbeat interval) | 50 ticks (deterministic) | EXP-001 / `Rr103InflightWindowRecoveryTest` (post-RR-103-fix) | Single deterministic scenario (3-node, 1 follower isolated, window pinned at cap=10, ~14-entry backlog ≤ one batch). Pre-fix: **unbounded** (never recovers within the term — the RR-103 defect). Multi-batch backlogs (> maxBatchSize) add one heartbeat interval per round; not yet measured at scale. |
| Post-heal minority rejoin → whole-cluster convergence (write committed on ALL nodes) | sim | ~21 ticks (mean over 200 seeds) | **48 ticks** | EXP-002 / `LivenessBoundedProgressSweepTest` (post-RR-103-fix) | 5-node, leader+1 isolated as a 2-node minority, window pinned during a 700-tick soak, heal → all 5 converge. Pre-fix (decay reverted): **never converges** — the rejoined minority stays behind (RR-103 live-net proof, seed 0 violates). ~1 heartbeat interval typical. |
| Majority re-election after losing the leader (leader isolated into minority) | sim | (swept) | **995 ticks** | EXP-002 / `LivenessBoundedProgressSweepTest` | 3-node majority elects a new leader after the old one is partitioned away. Worst over 200 seeds ≈ 3 election cycles (split-vote retries). |
| Fresh-cluster bootstrap election | sim | (swept) | **398 ticks** | EXP-002 / `LivenessBoundedProgressSweepTest` | 5-node cold start to first leader, worst over 200 seeds ≈ 1–2 election cycles. |
| Leader re-election after leader kill | live (wall) | ~0.25–0.58 s | 0.58 s | S2 RR-006 re-election drill (`docs/session-2/captures/rr-006-reelection-drill.sh`) | Kill→first commit-confirmed write on a survivor. Throttling inflates the worst case; the 0.575 s figure was a throttled run. Documented 150–300 ms election window + PreVote/vote/no-op round-trips. |

## Pending fault classes (to be filled as Session-4 experiments land)

- WAL/snapshot crash-recovery restart (kill -9 at each lifecycle state) — Workstream B kill matrix.
- InstallSnapshot-on-follower interrupted + resumed — Workstream B.
- Majority/minority partition heal → leader re-establishment — Workstream C partition matrix.
- Asymmetric partition recovery — Workstream C.
- Edge reconnect → re-bootstrap → CURRENT (staleness-bound convergence) — Workstream C / A3.
- Membership add/remove convergence under load — Workstream D.
- Gray-failure (loss/latency) detection-to-degradation latency — Workstream C.

## Method

Each row's measurement command is the named experiment's reproduction command (see the
EXP-NNN record). Recovery time is measured from **fault clear** (heal / restart / kill
acknowledged) to the invariant-checker confirming full service, never from fault
injection. Where a fault is never cleared (never-healed-schedule, RR-095 class), there is
no recovery bound — the cluster correctly makes no progress (safety preserved); that is a
liveness *non-event*, recorded as such, not a recovery measurement.
