# Session 4 → Session 5 Handoff: Durability, Recovery & Chaos — what's proven, what S5's live infra must clear

> Session 4 made the system fail in ways nobody scripted and proved every failure degrades per the
> consistency contract and the §6/§11/§12 policy — not by luck. The liveness-stall debt (RR-095/103)
> is closed; the durability/crash matrix, the four owed edge-chaos legs, reconfiguration-under-fault,
> the partition/WAN matrix, overload, and a sustained mini-Jepsen all landed, each cell oracle-first
> with red/green discipline and (for production/safety changes) independent second-agent reproduction.
> Branch `session-4-chaos`; **gate-4 is CI-wired, cumulative (gates 1–3 stay green), green + captured.**
> Read this with `fault-matrix.md` (the session spine), `recovery-bounds.md`, and the `EXP-001..011`
> records.

## 1. What landed (the fault matrix is the spine)

| Workstream | Outcome | Evidence |
|---|---|---|
| **A — liveness stalls** | RR-103 RESOLVED (inflight-window leak; recovery = 1 heartbeat); RR-095 ACCEPTED-RISK (7 stall seeds = never-healed schedules, edge starves SAFELY); first-class bounded-progress liveness checking | EXP-001/002 |
| **B — durability/crash** | RR-005 RESOLVED (compaction reachable + long-safe WAL read); RR-008 RESOLVED (mute-zombie); fsync-lie, ENOSPC (append+snapshot), short-read; RR-003 durable-prefix re-verified through the live trigger | EXP-003/006/007/008, `kill-matrix.md` |
| **D§2 — reconfig under fault** | split-brain prevention (an old-only majority cannot elect mid-joint) + mid-joint crash recovery | EXP-004 |
| **A3 — owed edge-chaos legs** | accept-then-blackhole (handshake timeout bites), prod-threshold ack-lag, wedged transport, governor churn | EXP-005 |
| **C — partition & WAN matrix** | single-region/leader/asymmetric/partial/gray + clock-skew safety, continuous safety oracles + recovery | EXP-009 |
| **D — overload** | control-plane write-flood backpressure + post-partition reconnect storm | EXP-010 |
| **E — mini-Jepsen** | sustained mixed-fault sweep, 0 safety violations; 10k adversarial sweeps re-run clean | EXP-011 |

Register deltas: RR-103 →RESOLVED · RR-095 →ACCEPTED-RISK · RR-008 →RESOLVED · RR-005 →RESOLVED ·
RR-018 row annotated (D§2 under-fault discharged).

## 2. Residual reliability risks (none are open safety violations)

- **RR-095 (ACCEPTED-RISK, liveness):** never-healed adversarial schedules stall the CP; the edge
  starves safely (0 safety violations). This is the FLP boundary, not a defect. Seeds + diagnosis in
  EXP-002; the 7 seeds + the integrated rerun are in gate-4 nightly (0 safety).
- **RR-099 (P3, S6):** `invariant.violation.monotonic_read` conflates benign catch-up refusals with
  store regressions — do NOT page on that series alone.
- **RR-098 (P2, S5):** edges hold `secure/` values in memory (exfiltration surface on lower-trust edge hosts).
- **B3 disk-pathology residual:** the **fsync > 1 s voluntary leader step-down** (slow-disk follower
  must not drag the leader, arch §6) is not yet exercised — `FaultInjectingStorage.latencyHook` is
  built; wire the step-down + a slow-disk follower cell. (`storage-fault-layer-design.md §B3`.)
- **B-rest tail (low value):** snapshot-install-on-follower + leadership-transfer kill cells, and the
  RR-019/086/064 durability review — mostly re-verify RR-003-covered behavior; not gating.

## 3. ⚠ ENVIRONMENT-BLOCKED — exact infra S5 must provide (never "skipped")

| Item | Why blocked here | Exact infra / recipe |
|---|---|---|
| **Real fsync / firmware-lie durability** | the in-JVM `CrashStorage.lieOnSyncForKey` models the recovery-side detection; only a real device proves the device honors fsync | a power-cuttable node (or VM); disable the write-cache barrier (`hdparm -W1`, no `fua`); run the kill matrix under `dm-flakey` / `dm-delay`; record device + FS + mount flags (`storage-fault-layer-design.md §3`) |
| **Porcupine full history-linearizability** | **Go is absent on the dev box** → the porcupine-check binary cannot be built; `PORCUPINE_BIN` unset | a runner with Go (the CI gate-2 linzgate already does this: `GOTOOLCHAIN=local go -C configd-linz/src/main/go/porcupine-check build`; then `bash gates/gate-2.sh`). Re-run the linz harness over partition/failover histories (`FaultInjector` real iptables) |
| **Multi-host asymmetric/partial WAN partitions** | the single-box Compose can do intra-host netem/docker-network; true cross-host WAN partitions need real hosts | ≥3 hosts with `NET_ADMIN` (`sudo -n iptables` works here for single-host; `tc/netem` present); run the §12 scenarios across hosts with the cross-region RTT matrix |
| **CT-02 — staleness-distribution SLO numbers** (p99 < 500 ms, p9999 < 2 s) | sanctioned S5 deferral; the mechanism is delivered (probe both modes, ADR-0039 frontier) | real multi-host hardware; set + verify the targets (handoff-S3 §3) |
| **Clock-skew fencing threshold** (500 ms, arch §6) | consensus safety is clock-independent (proven, C-6); the numeric fence is an operational policy | S6 operability (alert/fence wiring) |

## 4. Measured recovery-time bounds (sim ticks — S5 perf baselines, NOT SLOs)

Re-run on real multi-host hardware to get wall-clock SLO baselines (these are deterministic sim
ticks; 1 tick ≈ 1 ms modelled). Full ledger in `recovery-bounds.md`.

| Fault class | Measured |
|---|---|
| Follower backfill after partition heal (same term) | 50 ticks (1 heartbeat) — EXP-001 |
| Post-heal minority rejoin → whole-cluster convergence | mean ~21, worst 48 (200 seeds) — EXP-002 |
| Single-region isolation → majority re-elect / converge | re-elect ≤ 703 / converge ≤ 59 (12 seeds) — EXP-009 |
| Leader isolation → majority re-elect | ≤ 543 (12 seeds) — EXP-009 |
| Post-partition reconnect storm → whole fleet CURRENT | 258 ticks (5 edges) — EXP-010 |
| Fresh-cluster bootstrap election | worst 398 (200 seeds) — EXP-002 |
| Leader re-election after kill (live wall-clock) | ~0.25–0.58 s (throttled) — S2 RR-006 drill |

## 5. Chaos scenarios S5 should re-run on real multi-host hardware

1. The §12 partition matrix (C-1..C-6) across ≥3 real hosts with `netem`/`iptables` — confirm the
   in-sim safety + recovery holds over real sockets + real RTTs; collect wall-clock recovery SLOs.
2. The Porcupine linearizability checker over partition/failover histories (`configd-linz` HarnessMain
   + `FaultInjector`, with Go) — the full history-linearizability oracle C-linz left env-blocked here.
3. The post-partition reconnect storm (D-2) at fleet scale on real edges (8192-seq ack-lag reachable).
4. The fsync-lie / power-cut durability matrix on a real disk (B / `dm-flakey`), to clear the
   real-firmware detection boundary.
5. CT-02 staleness SLO measurement (probe both modes) on real hardware.

## 6. Gate-4 (CI-wired, captured)

`gates/gate-4.sh` — cumulative (chains gate-3→2→1), CI subset on push/PR + full nightly chaos on the
schedule trigger; `.github/workflows/ci.yml` `gate-4` job `needs: gate-3`. CI subset GREEN
(`captures/gate-4-ci-subset-run.txt`); nightly chaos GREEN, 0 safety violations
(`captures/gate-4-nightly-run.txt` + the mini-Jepsen). Re-run: `bash gates/gate-4.sh` (full chain) or
`GATE4_SKIP_GATE3=1 GATE4_SKIP_NIGHTLY=1 bash gates/gate-4.sh` (CI subset).
