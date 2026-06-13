# Session 4 (Durability, Recovery & Chaos) — progress / resume handoff

Branch `session-4-chaos`. Commits: `892eb95` (Workstream A) · `d63b74d` (B partial) ·
`ce916b3` (progress index) · `869dbdb` (D§2 in-sim, EXP-004) · `a4535b5` (A3 four legs, EXP-005) ·
`d171bf1` (RR-005 re-verification).
CI was green at pickup (run 27412723506 / 14a0f87). **Matrix-before-execution rule holds:** every
fault cell declares its oracle (in `fault-matrix.md` / `kill-matrix.md`) before it runs.

---

## ⏭ RESUME HERE — first item, treat as P0-grade consensus work

**RR-005 FIX** (re-verified OPEN this run; `rr-005-reverification.md`, register row, commit
`d171bf1`). Two coupled, independent bugs in the *wired* server:
1. Raft-log compaction is **unreachable** — nothing in `configd-server/src/main` triggers
   `RaftNode.triggerSnapshot()` by size/interval; the only caller is the circular
   `sendInstallSnapshot` (`RaftNode.java:1730`). WAL grows for the life of the process.
   (The resume-note's "ConfigdServer already calls `compactor.compact()`" was a FALSE ALARM —
   that `:656` call is the snapshot-RETENTION `Compactor`, `io.configd.store.Compactor`, NOT
   Raft-log compaction; it never touches the WAL. Verified against `a4535b5`.)
2. `FileStorage.java:128` `ByteBuffer.allocate((int) fileSize)` truncates on a ≥ 2 GiB WAL at
   boot → opaque crash-loop (made inevitable by (1)).

**Handle as P0-grade from minute one** (per the resume directive), even though it is filed P1
(availability/crash-loop, not a safety violation): the fix touches the **consensus tick loop**
(the H-009 zombie-tick surface — an uncaught throwable there silently kills consensus) **and the
storage recovery read path**, and it **interacts with RR-003's durable-prefix invariant**
(compaction becomes *reachable* for the first time in the wired server, so persist-before-truncate
+ `durable_prefix_no_gap` get genuinely exercised end-to-end — re-run `SnapshotCrashRecoveryTest`
semantics through the live trigger). **Write the discriminating (RED) test BEFORE any production
change**: pre-fix the WAL/applied grows without bound and `snapshotIndex` never advances in the
wired server; post-fix compaction fires at the threshold and the durable-prefix invariant holds
across a real compaction+restart. Second-agent reproduction required before the fix is accepted.
Fix shape: a threshold trigger (`lastApplied − snapshotIndex > compactionThreshold`, a named
config + metric) calling `triggerSnapshot()` in the tick loop, analogous to the retention
`compactor.compact()` already there; + a long-safe / fail-loud recovery read for (2).

---

## DONE (this session)

| Workstream | State | Evidence |
|---|---|---|
| **A1 RR-103** | ✅ RESOLVED (kernel regime): heartbeat decay of the per-peer inflight window; recovery = 1 heartbeat; `inflight_window_progress` twin; 10k re-sweep 0 safety; independent APPROVE | `EXP-001`, `reviews/rr103-kernel-fix-review.md` |
| **A2 RR-095** | ✅ ACCEPTED-RISK + first-class liveness checking (7 seeds diagnosed = never-healed artifacts; `LivenessBoundedProgressSweepTest` 200 seeds, 0 violations) | `EXP-002` |
| **A3 (4 owed edge-chaos legs)** | ✅ ALL FOUR — A3-1 accept-then-blackhole (real-socket TLS, handshake-timeout bites; CT-40 closed), A3-2 prod-threshold ack-lag 8192 (M-acklag RED), A3-3 wedged transport (RR-102 characterization; stalled-transfer signal → S6), A3-4 governor churn (never evicts distressed; M-evict RED). 2nd-agent signed off | `EXP-005`, `fault-matrix.md §A3`, `captures/exp-005-*` |
| **B1 storage-fault layer** | ✅ `FaultInjectingStorage` + self-test; oracle catalogue + ENVIRONMENT-BLOCKED list | `storage-fault-layer-design.md` |
| **B/RR-008** | ✅ RESOLVED — inbound-routing Throwable swallow → mute zombie; red→green | `EXP-003` |
| **B/RR-005 re-verify** | ✅ both halves CONFIRMED OPEN (fix owed — see RESUME above) | `rr-005-reverification.md`, `d171bf1` |
| **D §1 status check** | ✅ joint consensus is REAL → no P0 | `reconfiguration-status-check.md` |
| **D §2 reconfig-under-fault (in-sim)** | ✅ negative split-brain (M1-discriminated) + mid-joint crash recovery (M2-discriminated) + pre-joint/final restart; reconfig suite GREEN; 2nd-agent signed off | `EXP-004`, `captures/exp-004-m{1,2}-*` |
| **Fault-matrix spine** | ✅ created (`fault-matrix.md`, charter §8) — indexes A/A3/B2/D§2/C/E | `fault-matrix.md` |

## PENDING (resume at clean seams)

| # | Item | Where / oracle | Note |
|---|---|---|---|
| 1 | **RR-005 FIX** | see RESUME-HERE above | **first; P0-grade; RED test before any prod change; 2nd-agent** |
| 2 | B: fsync-lie cell | extend `CrashStorage.lieOnSync`; oracle in `storage-fault-layer-design.md §2` | restart detects the gap / fails loud, never loads un-fsynced data; in-sim shim models it (real-FS-shim variant ENVIRONMENT-BLOCKED w/ exact infra) |
| 3 | B: ENOSPC-under-load · short-read · snapshot-install-on-follower · leadership-transfer crash cells | `kill-matrix.md` (cells declared) | `FaultInjectingStorage` built (`enospcAfterBytes`/`shortReadLog`); storage-back a follower WAL to reach the append path |
| 4 | B: RR-019 · RR-086 · RR-064 | durability review | |
| 5 | **C — partition & WAN matrix** | Compose + netem/iptables (REJECT *and* DROP); clock skew vs the documented 500 ms bound | per cell: safety (linearizability over the failover/partition history) + liveness + client-experience + recovery-time. **Heaviest** — Compose cannot share the box with Maven; some cells may be ENVIRONMENT-BLOCKED |
| 6 | **D — reconnect-storm overload** (charter §6) | post-partition reconnect storm (the data plane's most dangerous overload) | charter flags it for extra scrutiny alongside reconfig-under-fault (done) |
| 7 | **E — sustained mini-Jepsen** | LAST, against the fully-fixed system; nightly, not in the CI gate | |
| 8 | **Gate-4** | `gates/gate-4.sh` CI-wired, cumulative (gates 1–3 stay green) | RR-103/095 seeds + `LivenessBoundedProgressSweepTest` in the gate seed set; curated chaos subset (incl. D§2/A3 cells); ledger lint; recovery-bounds coverage; mutation unregressed |
| 9 | **Handoff-S5** | `handoff-to-session-5.md` | at session close: residual risks, the ENVIRONMENT-BLOCKED list (incl. CT-02, fsync-shim), measured recovery-time baselines, the chaos scenarios S5 re-runs on real multi-host hardware |

## Register deltas (cumulative this session)

RR-103 OPEN→RESOLVED · RR-095 OPEN→ACCEPTED-RISK · RR-008 OPEN→RESOLVED ·
RR-005 OPEN (re-verified, both halves confirmed real; fix owed) ·
RR-018 RESOLVED-row annotated with the D§2 under-fault discharge (EXP-004).

## Environment reminders

2-vCPU box: serialize Maven (`pgrep -f "[a]pache-maven|[s]urefirebooter"`); offline scoped builds
(`./mvnw -q -o -pl <module> test -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false`); never run
Maven while a Compose topology is up; 10k consensus sweep ≈ 67–96 s; install consensus-core before
testkit runs. Mutation-revert discipline: apply → capture RED → **revert** → confirm GREEN →
verify `git diff -- '*/src/main/'` is empty before commit.
