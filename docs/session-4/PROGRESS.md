# Session 4 (Durability, Recovery & Chaos) — progress / resume handoff

Branch `session-4-chaos`. Commits: `892eb95` (Workstream A) · `d63b74d` (B partial) ·
`ce916b3` (progress index) · `869dbdb` (D§2 in-sim, EXP-004) · `a4535b5` (A3 four legs, EXP-005) ·
`d171bf1` (RR-005 re-verification) · `684dd7d` (resume handoff) · `47b6022` (RR-005 RESOLVED fix,
EXP-006 — first S4 production change, 2nd-agent signed off).
CI was green at pickup (run 27412723506 / 14a0f87). **Matrix-before-execution rule holds:** every
fault cell declares its oracle (in `fault-matrix.md` / `kill-matrix.md`) before it runs.

---

## ⏭ RESUME HERE — remaining B-rest cells (RR-005, fsync-lie, ENOSPC, short-read all DONE)

Done so far in B-rest: RR-005 (`47b6022`), fsync-lie (`EXP-007`), **ENOSPC-append + short-read
(`EXP-008`)**. Resume at:
1. **ENOSPC during SNAPSHOT write** (distinct from the append cell already done) — `persistSnapshot`
   put ENOSPCs → `compact` must NOT run / WAL prefix NOT truncated (no loss), retry next interval.
   Inject via `CrashStorage`-style or a `FaultInjectingStorage.failNextWrites` armed on the snapshot
   `put`; oracle: WAL prefix intact, snapshotIndex unchanged, no `durable_prefix_no_gap`.
2. **Crash during snapshot-install on a FOLLOWER** + **crash during leadership transfer** — reuse the
   `CrashStorage` kill matrix; persist-before-compact on the install path; no split-brain on transfer.
3. **RR-019 / RR-086 / RR-064** durability review.
Then **C** (partition/WAN matrix — heavy, Compose/netem) / **D-overload** (reconnect storm) /
**E** (mini-Jepsen) / **gate-4 + CI** / **handoff-S5**.

NB short-read is CLOSED by analysis (EXP-008): trailing short-read ≡ torn-tail (covered); middle
short-read ≡ gap → `durable_prefix_no_gap` (covered). `FaultInjectingStorage` only injects trailing;
a middle-drop injector is a non-blocking follow-up.

Pattern reminder (held all run): declare the cell oracle first → write the discriminating/RED test
→ implement (if prod change) → revert any mutation → confirm GREEN → `git diff -- '*/src/main'` clean
→ commit → second-agent replay for production/safety cells.

---

## DONE (this session)

| Workstream | State | Evidence |
|---|---|---|
| **A1 RR-103** | ✅ RESOLVED (kernel regime): heartbeat decay of the per-peer inflight window; recovery = 1 heartbeat; `inflight_window_progress` twin; 10k re-sweep 0 safety; independent APPROVE | `EXP-001`, `reviews/rr103-kernel-fix-review.md` |
| **A2 RR-095** | ✅ ACCEPTED-RISK + first-class liveness checking (7 seeds diagnosed = never-healed artifacts; `LivenessBoundedProgressSweepTest` 200 seeds, 0 violations) | `EXP-002` |
| **A3 (4 owed edge-chaos legs)** | ✅ ALL FOUR — A3-1 accept-then-blackhole (real-socket TLS, handshake-timeout bites; CT-40 closed), A3-2 prod-threshold ack-lag 8192 (M-acklag RED), A3-3 wedged transport (RR-102 characterization; stalled-transfer signal → S6), A3-4 governor churn (never evicts distressed; M-evict RED). 2nd-agent signed off | `EXP-005`, `fault-matrix.md §A3`, `captures/exp-005-*` |
| **B1 storage-fault layer** | ✅ `FaultInjectingStorage` + self-test; oracle catalogue + ENVIRONMENT-BLOCKED list | `storage-fault-layer-design.md` |
| **B/RR-008** | ✅ RESOLVED — inbound-routing Throwable swallow → mute zombie; red→green | `EXP-003` |
| **B/RR-005** | ✅ RESOLVED — re-verified both halves OPEN (`d171bf1`) then FIXED: compaction reachable (`RaftNode.maybeCompact`→`MultiRaftDriver.maybeCompact`→tick-loop) + long-safe `FileStorage` recovery guard; M-compact RED, RR-003 6/6 green; 2nd-agent SOUND | `rr-005-reverification.md`, `EXP-006`, `47b6022` |
| **D §1 status check** | ✅ joint consensus is REAL → no P0 | `reconfiguration-status-check.md` |
| **D §2 reconfig-under-fault (in-sim)** | ✅ negative split-brain (M1-discriminated) + mid-joint crash recovery (M2-discriminated) + pre-joint/final restart; reconfig suite GREEN; 2nd-agent signed off | `EXP-004`, `captures/exp-004-m{1,2}-*` |
| **B: fsync-lie cell** | ✅ `CrashStorage.lieOnSyncForKey` + `gapDetectionFiresWhenSnapshotFsyncLied` (recovers to the same gap as blob-unrecoverable → `durable_prefix_no_gap` fires; M-lie RED); real-firmware variant ENVIRONMENT-BLOCKED | `EXP-007`, `captures/exp-007-*`, kill-matrix |
| **B: ENOSPC-append + short-read** | ✅ ENOSPC at the consensus layer (`StorageEnospcConsensusReactionTest` — surfaces, no silent log advance via durable-first `RaftLog.append`, recovers; M-order RED). short-read CLOSED by analysis (trailing≡torn-tail; middle≡gap→`durable_prefix_no_gap`) | `EXP-008`, `captures/exp-008-*`, kill-matrix |
| **Fault-matrix spine** | ✅ created (`fault-matrix.md`, charter §8) — indexes A/A3/B2/D§2/C/E | `fault-matrix.md` |

## PENDING (resume at clean seams)

| # | Item | Where / oracle | Note |
|---|---|---|---|
| 1 | **B: ENOSPC-snapshot-write + crash cells** | see RESUME-HERE — snapshot-write ENOSPC (WAL prefix NOT truncated), snapshot-install-on-follower + leadership-transfer crash (`CrashStorage`) | **first**; ENOSPC-append + short-read already DONE (EXP-008) |
| 3 | B: ENOSPC-under-load · short-read · snapshot-install-on-follower · leadership-transfer crash cells | `kill-matrix.md` (cells declared) | `FaultInjectingStorage` built (`enospcAfterBytes`/`shortReadLog`); storage-back a follower WAL to reach the append path |
| 4 | B: RR-019 · RR-086 · RR-064 | durability review | |
| 5 | **C — partition & WAN matrix** | Compose + netem/iptables (REJECT *and* DROP); clock skew vs the documented 500 ms bound | per cell: safety (linearizability over the failover/partition history) + liveness + client-experience + recovery-time. **Heaviest** — Compose cannot share the box with Maven; some cells may be ENVIRONMENT-BLOCKED |
| 6 | **D — reconnect-storm overload** (charter §6) | post-partition reconnect storm (the data plane's most dangerous overload) | charter flags it for extra scrutiny alongside reconfig-under-fault (done) |
| 7 | **E — sustained mini-Jepsen** | LAST, against the fully-fixed system; nightly, not in the CI gate | |
| 8 | **Gate-4** | `gates/gate-4.sh` CI-wired, cumulative (gates 1–3 stay green) | RR-103/095 seeds + `LivenessBoundedProgressSweepTest` in the gate seed set; curated chaos subset (incl. D§2/A3 cells); ledger lint; recovery-bounds coverage; mutation unregressed |
| 9 | **Handoff-S5** | `handoff-to-session-5.md` | at session close: residual risks, the ENVIRONMENT-BLOCKED list (incl. CT-02, fsync-shim), measured recovery-time baselines, the chaos scenarios S5 re-runs on real multi-host hardware |

## Register deltas (cumulative this session)

RR-103 OPEN→RESOLVED · RR-095 OPEN→ACCEPTED-RISK · RR-008 OPEN→RESOLVED ·
RR-005 OPEN→RESOLVED (re-verified both halves real, then fixed — EXP-006, `47b6022`) ·
RR-018 RESOLVED-row annotated with the D§2 under-fault discharge (EXP-004).

## Environment reminders

2-vCPU box: serialize Maven (`pgrep -f "[a]pache-maven|[s]urefirebooter"`); offline scoped builds
(`./mvnw -q -o -pl <module> test -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false`); never run
Maven while a Compose topology is up; 10k consensus sweep ≈ 67–96 s; install consensus-core before
testkit runs. Mutation-revert discipline: apply → capture RED → **revert** → confirm GREEN →
verify `git diff -- '*/src/main/'` is empty before commit.
