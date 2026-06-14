# Session 4 (Durability, Recovery & Chaos) — progress / resume handoff

Branch `session-4-chaos`. Commits: `892eb95` (Workstream A) · `d63b74d` (B partial) ·
`ce916b3` (progress index) · `869dbdb` (D§2 in-sim, EXP-004) · `a4535b5` (A3 four legs, EXP-005) ·
`d171bf1` (RR-005 re-verification) · `684dd7d` (resume handoff) · `47b6022` (RR-005 RESOLVED fix,
EXP-006 — first S4 production change, 2nd-agent signed off).
CI was green at pickup (run 27412723506 / 14a0f87). **Matrix-before-execution rule holds:** every
fault cell declares its oracle (in `fault-matrix.md` / `kill-matrix.md`) before it runs.

---

## ✅ SESSION 4 COMPLETE — all workstreams landed; residuals are S5 live-infra (ENVIRONMENT-BLOCKED)

Every charter workstream is done and CI-protected: **A** (RR-103/095 + liveness), **B**-durability
(RR-005/008, fsync-lie, ENOSPC, short-read), **D§2** reconfig-under-fault, **A3** edge-chaos legs,
**C** partition/WAN matrix (+ clock-skew safety), **D** overload (write-flood + reconnect storm),
**E** mini-Jepsen, the **§6/§11/§12 claim-evidence** conversions, and the **S5 handoff**. gate-4 is
CI-wired, cumulative, GREEN + captured (CI subset + nightly chaos, 0 safety violations). The
authoritative closeout is `handoff-to-session-5.md`; the spine is `fault-matrix.md`. Definition of
Done: see §9 scorecard in the handoff — the only open lines are ENVIRONMENT-BLOCKED, deferred to S5
with exact infra named.

### Deferred to S5 (ENVIRONMENT-BLOCKED, exact infra in `handoff-to-session-5.md §3`):
- **Porcupine full history-linearizability** on a real Go runner (in-sim safety invariants are the
  always-on substitute; runs in CI gate-2 linzgate). Go is absent on this dev box.
- **Multi-host asymmetric/partial WAN partitions** across real hosts (single-box `netem`/`docker`
  did the intra-host scenarios; C-1..C-6 are deterministic in-sim).
- **Real fsync / firmware-lie** durability on a real disk (`dm-flakey` + `hdparm -W1` + power-cut).
- **fsync > 1 s voluntary step-down** (B3 slow-disk; `latencyHook` built, step-down not yet wired).
- **CT-02** staleness SLO numbers (p99 < 500 ms) on real hardware; **clock-skew fence threshold** (S6).
- Low-value B-rest tail (snapshot-install / leadership-transfer kill cells; RR-019/086/064) — optional.

### ⚠ ENVIRONMENT-BLOCKED candidates to clear BEFORE starting C (carried forward):
1. **netem/iptables need NET_ADMIN/root** on the Compose host; the bridge network can do
   intra-host loss/latency/partition, but **true multi-host asymmetric/partial partitions across
   real hosts** are limited on the single 2-vCPU box — those cells are ENVIRONMENT-BLOCKED → S5
   multi-host hardware. **Compose cannot share the box with Maven** (env memory) — serialize.
2. **Clock-skew injection** (charter §6 D) needs container time control (`libfaketime` or per-
   container clock offset) — verify the harness can skew one node's clock without host-wide effect.
3. **Real fsync / firmware-lie** (from EXP-007 / `storage-fault-layer-design.md §3`): `dm-flakey` /
   `dm-delay` + `hdparm -W1` + power-cut on a real disk — ENVIRONMENT-BLOCKED → S5.
4. **CT-02** staleness-distribution NUMBERS (p99 < 500 ms) — sanctioned S5 deferral (handoff §3).
5. **Stalled-transfer signal** (A3-3 / c5-signoff F2) — S6 observability item (detection proxy works).

### Low-value B-rest tail (optional; mostly re-verifies RR-003-covered behavior):
snapshot-install-on-follower crash + leadership-transfer crash (CrashStorage kill cells);
RR-019/086/064 durability review. Not gating; skip toward C unless a reviewer wants them.

Pattern reminder (held all session): declare the cell oracle first → write the discriminating/RED
test → implement (if prod change) → revert any mutation → confirm GREEN → `git diff -- '*/src/main'`
clean → commit → second-agent replay for production/safety cells.

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
| **gate-4 + CI** | ✅ `gates/gate-4.sh` cumulative (gates 1-3 stay green) + CI job `needs: gate-3` (CI-subset on push/PR, full nightly on schedule). CI subset GREEN; nightly chaos GREEN (10k integrated sweep + 7 RR-095 seeds + mini-Jepsen, 0 safety violations) | `gates/gate-4.sh`, `.github/workflows/ci.yml`, `captures/gate-4-{ci-subset,nightly}-run.txt` |
| **C — partition/WAN matrix** | ✅ `PartitionMatrixTest` (6/6): single-region/leader/asymmetric/partial/gray + clock-skew safety; continuous safety oracles + recovery. Edge partition + live iptables cited; Porcupine + multi-host → S5 | `EXP-009`, `fault-matrix.md §C` |
| **D — overload** | ✅ `OverloadChaosTest` (2/2): write-flood backpressure (plateau at 1024, OVERLOADED shed, recovers) + post-partition reconnect storm (5 edges, 258t, none terminal) | `EXP-010`, `fault-matrix.md §D` |
| **E — mini-Jepsen** | ✅ `MiniJepsenSweepTest` sustained mixed-fault, 0 safety violations; 10k adversarial sweeps re-run clean. Nightly | `EXP-011`, `fault-matrix.md §E` |
| **Claim-evidence §6/§11/§12 + S5 handoff** | ✅ converted with commands; closeout written | `claim-evidence-conversions.md`, `handoff-to-session-5.md` |

## PENDING (resume at clean seams)

| # | Item | Where / oracle | Note |
|---|---|---|---|
| 1 | **C — partition & WAN matrix** | Compose + netem/iptables (REJECT *and* DROP); clock skew; `configd-linz` linearizability over the partition/failover write history | **the big remaining workstream.** per cell: safety + liveness + client-experience + recovery-time histogram. See RESUME-HERE for the ⚠ ENVIRONMENT-BLOCKED candidates to clear FIRST (NET_ADMIN, multi-host, clock-skew injection) |
| 2 | **D — reconnect-storm overload** (charter §6) | post-partition reconnect storm (the data plane's most dangerous overload) | §11 shed order + 429/version-stale signals + bounded queues + clean recovery. Extra-scrutiny cell |
| 3 | **E — sustained mini-Jepsen** | LAST, against the fully-fixed system; nightly, not in the CI gate | |
| 4 | **Handoff-S5** | `handoff-to-session-5.md` | session-close (≠ this resume index): residual risks, the ENVIRONMENT-BLOCKED list (CT-02, fsync-shim, netem multi-host), measured recovery-time baselines, chaos to re-run on real multi-host hardware |
| — | (optional) B-rest tail | snapshot-install-on-follower + leadership-transfer crash cells; RR-019/086/064 | low value — mostly re-verifies RR-003-covered behavior; not gating |

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
