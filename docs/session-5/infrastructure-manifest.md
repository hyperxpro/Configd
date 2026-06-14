# Session 5 — Infrastructure Manifest (the enumerated pre-production gap)

> **A required deliverable (charter §10), assembled continuously.** This is the precise, costed list
> of every performance/durability claim that local hardware (the 2-vCPU t3a.large audit box, single
> region, single host, 7.7 GB) **cannot** verify, and exactly what infrastructure would verify it.
> This is what makes "we don't test in production" honest: the gap is *enumerated, costed, and has a
> harness already written and waiting* — not hidden behind a green checkmark.
>
> Each item: **the claim** · **why local can't prove it** · **exact infra** (instance types, regions,
> count, indicative cost) · **the waiting harness** (already written, names the invocation) · **status**.
>
> Cost figures are indicative AWS on-demand US pricing for a short verification run (a few hours), and
> EXCLUDE inter-region data-transfer egress (which for these low-byte control-plane workloads is
> negligible). They are sizing guidance, not a quote.
>
> This file is updated as each workstream surfaces its ENV-BLOCKED component; see the per-item
> "surfaced by" tag. The honesty split that produces these items is `methodology.md §1`.

---

## M-1 — Cross-region write-commit p99 < 150 ms  (surfaced by: Workstream B, charter §6)

- **Claim.** Write commit latency p99 < 150 ms cross-region (§0.1).
- **Why local can't prove it.** The single box has no inter-region network. Local measurement proves
  the **local quorum-commit component** (consensus append + majority ack + apply, in-memory transport)
  and the commit *mechanism*; the cross-region term is the RTT to the follower that completes the
  majority, which is **modeled** from the `methodology.md §2` RTT matrix. A median-RTT model also
  understates the p99 tail (jitter), which is exactly the percentile the target names.
- **Exact infra.** 5 Raft voters, one per region: **us-east-1, us-west-2, eu-west-1, ap-northeast-1,
  ap-southeast-1**, each **c6i.2xlarge** (8 vCPU / 16 GB, dedicated — not burstable), + 1 load client
  in us-east-1 (c6i.xlarge). NET: default inter-region Internet (or a 3-voter co-located placement for
  the flexible-quorum variant). ~**6 instances × ~3–4 h**.
- **Indicative cost.** 5 × c6i.2xlarge @ ~$0.34/h + 1 × c6i.xlarge @ ~$0.17/h ≈ **$1.9/h → ~$6–8** for a
  verification run.
- **Waiting harness.** Workstream B's open-loop write-commit load harness (CO-corrected per
  methodology §3b) pointed at the multi-region cluster; report HdrHistogram p99 vs the modeled
  `local_commit + RTT(2nd-fastest follower)` to confirm the model and capture the real p99 tail.
- **Status.** ENV-BLOCKED. Local component VERIFIED in Workstream B; modeled total reported PENDING.

## M-2 — Global edge propagation / staleness p99 < 500 ms  (surfaced by: Workstream C / CT-02, charter §7)

- **Claim.** Edge propagation (staleness) p99 < 500 ms, p9999 < 2 s, global (§0.1 / consistency-contract CT-02).
- **Why local can't prove it.** Local multi-edge **Docker Compose** gives a real-but-intra-host fan-out
  staleness number (no WAN leg). The global target = local fan-out component (measured) + the WAN
  propagation leg (1–3 Plumtree hops, **modeled** from the RTT matrix). Real cross-region edge
  visibility needs edges in distant regions.
- **Exact infra.** 3 control-plane nodes (us-east-1, c6i.xlarge) + edges in **eu-west-1,
  ap-northeast-1, ap-southeast-1, us-west-2** (c6i.large each). ~**7–8 instances × ~3–4 h**.
- **Indicative cost.** 3 × c6i.xlarge @ ~$0.17/h + 4 × c6i.large @ ~$0.085/h ≈ **$0.85/h → ~$3–4**.
- **Waiting harness.** The S3 propagation probe + Workstream C staleness sampler (fixed wall-clock edge
  cadence; write side driven open-loop) producing the HdrHistogram staleness distribution against
  leader-assigned commit timestamps (ADR-0035). Closes the **owed INV-S2 p99-distribution** test.
- **Status.** ENV-BLOCKED (WAN component). Local fan-out component VERIFIED in Workstream C.

## M-3 — fsync-under-power-loss / firmware-lie durability  (surfaced by: S4 Workstream B)

- **Claim.** A committed write survives power loss because the device honors `fsync` (durable-prefix /
  no-gap recovery holds on real hardware).
- **Why local can't prove it.** The in-JVM `CrashStorage.lieOnSyncForKey` / `FaultInjectingStorage`
  models the **recovery-side detection**; only a real device under a real power-cut proves the device
  itself honors the barrier. The shared box cannot be power-cut.
- **Exact infra.** A power-cuttable node (bare-metal preferred, or a VM with the device-mapper fault
  layer): **i4i.large** (local NVMe) or an `i3en`/bare-metal `*.metal`. Disable the write-cache barrier
  (`hdparm -W1`, no `fua`); run the S4 kill matrix under **`dm-flakey` / `dm-delay`**; record device +
  filesystem + mount flags. ~**1 instance × ~2 h**.
- **Indicative cost.** 1 × i4i.large @ ~$0.17/h ≈ **~$0.5**; true power-cut on `*.metal` (~$1–5/h) if a
  device-mapper model is deemed insufficient.
- **Waiting harness.** The S4 durability kill-matrix (`storage-fault-layer-design.md §3`,
  `FaultInjectingStorage`) run over `dm-flakey`; the recovery cells (RR-003/005, fsync-lie EXP-007,
  ENOSPC EXP-008) re-asserted against a real device.
- **Status.** ENV-BLOCKED. In-sim detection VERIFIED in S4.

## M-4 — NUMA / CPU-pinning for edge serving  (surfaced by: Workstream E, charter §9)

- **Claim.** Edge read/serve latency holds (and the no-lock/0-alloc path benefits from pinning) on a
  production multi-socket topology.
- **Why local can't prove it.** A 2-vCPU single-socket burstable instance has no NUMA topology to
  represent; cross-socket memory effects and core pinning are invisible here.
- **Exact infra.** A multi-socket NUMA host: **m6i.metal** (2-socket) or equivalent bare-metal.
  Measure the read-path serving harness with `numactl --cpunodebind/--membind` pinning vs unpinned.
  ~**1 instance × ~2 h**.
- **Indicative cost.** 1 × m6i.metal @ ~$6/h ≈ **~$12**.
- **Waiting harness.** `LocalConfigStoreReadBenchmark` + the edge serving harness under `numactl`
  pinning vs default placement (same harness as Workstream A, different topology).
- **Status.** ENV-BLOCKED / FLAGGED. Workstream E measures what the local box can; the NUMA delta is here.

## M-5 — 10^9-key read-path scale  (surfaced by: Workstream A, charter §5)

- **Claim.** Read p99 < 1 ms / p999 < 5 ms holds at the §0.1 10^9-key working set (not just 10^6).
- **Why local can't prove it.** 10^9 entries ≈ 200–300 GB resident — will not fit in 7.7 GB. Workstream
  A measures at **10^6** (LOCAL-VERIFIED) and provides a **documented extrapolation** (HAMT depth grows
  ~log32 N: ~4 levels at 10^6 → ~6 levels at 10^9, i.e. ~1.5× more cache-line hops), flagged as
  extrapolated, not measured.
- **Exact infra.** A high-memory host: **r6i.8xlarge** (256 GB) or **r6i.16xlarge** (512 GB) to hold
  10^9 entries with headroom. ~**1 instance × ~4 h** (build the store + run).
- **Indicative cost.** 1 × r6i.8xlarge @ ~$2.0/h ≈ **~$8** (r6i.16xlarge ~$4/h → ~$16 if 256 GB is tight).
- **Waiting harness.** The same `LocalConfigStoreReadBenchmark` at `-p size=1000000000` on the big box
  (the harness is size-parametric; only memory blocks it here).
- **Status.** ENV-BLOCKED (scale). 10^6 component VERIFIED + extrapolation in Workstream A.

## M-6 — Real-WAN multi-host partition recovery (wall-clock SLOs)  (surfaced by: S4 Workstream C)

- **Claim.** The §12 partition matrix (single-region/leader/asymmetric/partial/gray) holds over real
  sockets + real RTTs, with wall-clock recovery within the §9 budgets (not just deterministic sim ticks).
- **Why local can't prove it.** Single-box Compose can do intra-host `netem`/docker-network faults;
  true cross-host WAN partitions and their real-RTT recovery need real hosts with `NET_ADMIN`.
- **Exact infra.** Reuses the **M-1 multi-region 5-node cluster** (≥3–5 hosts, `sudo iptables` / `tc
  netem`). No new instances if co-scheduled with M-1.
- **Indicative cost.** Folds into M-1 (**~$0** marginal) for the partition cells; +~1 h runtime.
- **Waiting harness.** `PartitionMatrixTest`'s real-socket variant + `FaultInjector` (real iptables)
  across hosts; collect wall-clock recovery distributions to replace the S4 sim-tick bounds
  (`recovery-bounds.md`) with real SLO baselines.
- **Status.** ENV-BLOCKED. In-sim safety + recovery VERIFIED in S4 (EXP-009).

## M-7 — Porcupine full history-linearizability over faulted histories  (surfaced by: S4)

- **Claim.** Operation histories under partition/failover are linearizable (full Porcupine checker, not
  just the sim-internal invariant checker).
- **Why local can't prove it.** `go` is absent on the dev box PATH (the checker binary can't be built
  locally). NOTE: **CI already builds it** (gate-1/gate-2 use `actions/setup-go` → the porcupine-check
  binary) — so the *checker* runs in CI; what is blocked locally is a **dedicated faulted-history**
  linearizability campaign with real iptables faults.
- **Exact infra.** A runner with Go (the CI runner suffices for the checker; a dedicated **c6i.2xlarge**
  with `NET_ADMIN` for the faulted-history campaign). ~**1 instance × ~2 h** (or the CI lane).
- **Indicative cost.** 1 × c6i.2xlarge @ ~$0.34/h ≈ **~$0.7** (or $0 on the CI runner).
- **Waiting harness.** `configd-linz` `HarnessMain` + `FaultInjector` + `porcupine-check` (Go), over
  partition/failover histories.
- **Status.** ENV-BLOCKED locally / available in CI for the checker self-tests.

## M-8 — (carried, not a perf item) Clock-skew fencing threshold (500 ms)  (→ S6)

- Consensus safety is clock-independent (proven, S4 C-6); the 500 ms numeric fence is an **operational
  policy** (alert/fence wiring), owned by Session 6 operability — listed here for completeness so the
  pre-prod gap is whole. Not costed as an S5 measurement item.

---

## What is NOT on this list (and why)

- **The 24 h soak** runs ON the local box — it is a *duration* commitment, not an infra-blocked one, so
  it is executed (Workstream E), not deferred. A *production-representative* soak (real fleet, NUMA,
  real WAN) would inherit M-1/M-2/M-4; the local soak is real-duration on reference hardware and labeled
  as such (`methodology.md §0`).
- **Throughput 10k/s sustained & 100k/s burst** are measured locally (single-host mechanism); only
  per-region *cluster-scale* capacity at production fleet size is infra-bound, and is a sizing exercise
  on the M-1 cluster, not a separate blocker.

## Total indicative cost of clearing the entire pre-prod gap

A single coordinated multi-region verification campaign (M-1 + M-2 + M-6 co-scheduled on one 5-region
cluster, plus M-3 durability, M-4 NUMA, M-5 10^9-scale, M-7 linz) is on the order of **~$40–60 of
on-demand compute for a few hours**, dominated by the m6i.metal (M-4) and r6i (M-5). The point is not
the dollar figure — it is that **every gap has a number, a machine, and a harness already waiting.**
