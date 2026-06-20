# Session 7.5 — Shrunken infrastructure manifest

> Per Prime Directive §4.1–4.2: an item stays here ONLY if it genuinely requires geographic
> distribution, multiple dedicated hosts, or special hardware this 16-vCPU/64-GB single box cannot
> honestly provide. Items a single host COULD prove are NOT here — they are the PENDING list in
> `handoff-to-session-8.md` (single-host-provable work not yet done), never manifest-dumped.
> Source manifest: `docs/session-5/infrastructure-manifest.md` (M-1…M-10).

## Stays — genuinely multi-region / multi-host / special-hardware (signed)

| Item | Why this box cannot prove it | What WAS established here | Waiting harness |
|---|---|---|---|
| **M-1 — cross-region write-commit p99 < 150 ms** | Loopback is sub-ms; real inter-region is tens of ms. A cross-region p99 measured on one box is forbidden (§4.2). | **Local component VERIFIED-at-scale: p99 = 5.51 ms** (phase-2, 3-node, data on NVMe, CO-corrected). Combined = local + RTT(quorum) from the cited matrix; MODELED, < 150 ms target → PENDING multi-box. | ≥3-region cluster; `perf/wsB-live-write.sh` + RTT matrix |
| **M-2 — global edge propagation / staleness p99 < 500 ms** | Same — WAN propagation cannot be measured standing still. | Local multi-edge component is the S5 probe at scale (re-run pending §9). | Multi-region edges; `LivePropagationProbeMain` |
| **M-3 — fsync-under-power-loss / firmware-lie durability** | Requires a real power-loss / firmware-fault rig. | NEW DATA POINT: this instance-store NVMe reports **no volatile write cache** (`iostat f/s`=0, `w_await`=0.03 ms) — fsync is effectively free, so the device is *favorable* for power-loss durability, but proving firmware honesty still needs a rig. | Power-cut rig / fault-injecting controller |
| **M-4 — NUMA / CPU-pinning for edge serving** | This box is **single-socket** (1×8c×2t, no second socket, no cross-socket memory topology). Genuinely unprovable here. | n/a — hardware-necessity. | 2-socket host (e.g. m6i.metal) |
| **M-6 — real-WAN multi-host partition recovery (wall-clock SLOs)** | Needs ≥3–5 dedicated hosts + real `tc`/`iptables` partitions across the WAN. | Single-host partition behavior covered by sim/testkit; wall-clock WAN SLOs are multi-host. | Multi-host cluster + network-fault tooling |
| **M-9 (precise ceiling) — sustained 10k/s & 100k/s burst** | **CO-LOCATION CONFOUND:** 3 full nodes + a 256-thread load driver share 16 vCPUs, so OS-scheduler latency for the single consensus tick thread is amplified vs a dedicated host. The PRECISE stable ceiling needs one-node-per-host. | **QUALITATIVE answer PROVEN here (the headline):** the as-built ceiling is **single-threaded-consensus heartbeat-starvation churn at ~800–1000/s, NOT fsync** (group commit doesn't move it; fsync is free) and NOT aggregate CPU (idle). See `throughput-part2.md`. The single-host *fix* (admission control / replication coalescing) IS provable here and is the top next-step — only the dedicated-host *number* is deferred. | dedicated-core cluster (1 node/host); `perf/s75-throughput.sh` |
| **True cross-host rolling upgrade (N↔N+1)** | The TRUE geographically-distributed rolling upgrade needs real separate hosts. | The synthetic single-host mixed-version WIRE-compat proof is single-host-provable (§10) and is in the PENDING list (not yet done); the cross-host geo upgrade is the multi-box remainder. | Multi-host staged-rollout harness |
| **Multi-region alert thresholds** | Inherently cross-region (e.g. cross-region replication-lag, geo-failover-time). Stay PROPOSED. | Single-region thresholds are promotable here after real load exists (PENDING §11). | Multi-region observability stack |

## Signed-deferral justification (autonomy §3, logged for retroactive veto)
Each row above fails the screen "could this 16-vCPU/64-GB single box have proven it?" — by geography
(M-1/M-2/M-6/multi-region thresholds/true-cross-host-upgrade), by socket count (M-4), by special
hardware (M-3 power-loss rig), or by the dedicated-host requirement to remove the co-location confound
on the PRECISE number (M-9 ceiling — whose QUALITATIVE cause is nonetheless proven here). The
single-host-provable items (M-5 10⁹-extrapolation, M-7 porcupine, M-10 ladder thresholds after the
admission-control fix, burst characterization, latency WAN write-up, slowloris + S7 residuals +
synthetic upgrade, soak, threshold promotion) are deliberately NOT here — they are honest PENDING work
in the handoff, to be done on this class of box, not manifest-dumped.
