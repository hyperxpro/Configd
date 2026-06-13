# Session 4 (Durability, Recovery & Chaos) — progress / resume index

Branch `session-4-chaos`. Commits: `892eb95` (Workstream A), `d63b74d` (Workstream B partial).
CI was green at pickup (run 27412723506 / 14a0f87). Hold the matrix-before-execution rule:
every fault cell declares its oracle (in `kill-matrix.md` / the partition matrix) before it runs.

## DONE
- **A1 RR-103 RESOLVED** (kernel regime): heartbeat decay of the per-peer inflight window;
  discriminating test red→green (recovery = 1 heartbeat interval); `inflight_window_progress`
  twin; spec review; 10k re-sweep 0 safety; independent review APPROVE-WITH-CHANGES.
  → `experiments/EXP-001`, `reviews/rr103-kernel-fix-review.md`.
- **A2 RR-095 ACCEPTED-RISK + first-class liveness checking**: 7 seeds per-seed-diagnosed
  (all never-healed artifacts; RR-103 refuted as cause); `LivenessBoundedProgressSweepTest`
  (200 seeds, 0 violations, proven a live net). → `experiments/EXP-002`.
- **D §1 status check**: joint consensus is REAL → no P0. → `reconfiguration-status-check.md`.
- **D §2 reconfig-under-fault (in-sim)**: negative split-brain cell (old-only majority cannot
  elect mid-joint, M1-discriminated) + mid-joint crash recovery (restart rebuilds JOINT,
  M2-discriminated) + pre-joint/final restart cells. `ReconfigurationTest$JointConsensusEndToEnd`
  3→6, full reconfig suite GREEN, prod source byte-clean. → `experiments/EXP-004`,
  `captures/exp-004-m{1,2}-*-RED.txt`. Live/under-load reconfig gated on S6 admin seam.
- **B1 storage-fault layer**: `FaultInjectingStorage` + self-test; oracle catalogue +
  ENVIRONMENT-BLOCKED. → `storage-fault-layer-design.md`.
- **B/RR-008 RESOLVED**: inbound-routing Throwable swallow → mute zombie; fixed + red→green.
  → `experiments/EXP-003`.
- **A3 — the four owed edge-chaos legs DONE** (EXP-005, fault-matrix §A3): A3-1 accept-then-
  blackhole (real-socket TLS, handshake-timeout bites, edge retries — CT-40 closed); A3-2
  prod-threshold ack-lag demotion (8192, M-acklag-discriminated); A3-3 wedged transport
  (RR-102 pause/resume characterization + observability proxy, stalled-transfer signal → S6);
  A3-4 governor churn (bounded identity map never evicts distressed, M-evict-discriminated).
- The session spine `fault-matrix.md` created (charter §8) — indexes A/A3/B2/D§2/C/E.
- Recovery bounds recorded so far → `recovery-bounds.md`.

## PENDING (resume here, at clean seams — see kill-matrix.md for B cells)
| Item | Where | Note |
|---|---|---|
| B: fsync-lie cell | extend `CrashStorage.lieOnSync` | oracle in design §2; detect-gap-or-fail-loud |
| B: ENOSPC-under-load, short-read, snapshot-install/leadership-transfer crash cells | kill-matrix.md | `FaultInjectingStorage` built; storage-back a follower WAL to reach the append path |
| B/RR-005 | `FileStorage` `(int) fileSize` 2 GiB cast; compaction trigger | RE-VERIFY first: ConfigdServer tick loop ALREADY calls `compactor.compact()` every 1000 ticks — the "compaction unreachable" half may be stale |
| B: RR-019, RR-086, RR-064 | durability review | |
| ~~A3~~ DONE | 4 owed edge chaos legs (S3 handoff §1) | ✅ all four — EXP-005 / fault-matrix §A3 |
| C | partition matrix on Compose + netem/iptables; clock skew vs documented 500ms bound; recovery-bound ledger per fault class | REJECT + DROP both; safety+liveness+client-experience per cell |
| D §2 | reconfig under fault | **build the mid-joint-phase leader-election case** (existing test finalizes before the election) + kill -9 per phase boundary (reuse B2) |
| E | sustained mini-Jepsen | LAST, against fully-fixed system; nightly, not in gate |
| Gate-4 | `gates/gate-4.sh` CI-wired | RR-103/095 seeds + `LivenessBoundedProgressSweepTest` in the gate seed set; curated chaos subset; ledger lint; recovery-bounds coverage; gates 1–3 + mutation unregressed |
| Handoff | `handoff-to-session-5.md` | at session close |

## Register deltas this session
RR-103 OPEN→RESOLVED · RR-095 OPEN→ACCEPTED-RISK · RR-008 OPEN→RESOLVED.
