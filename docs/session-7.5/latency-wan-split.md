# Session 7.5 — §9 Latency: local-component-at-scale + WAN split (three numbers, never one)

> Charter §9 / Hard Rule §13.1: no cross-region target is VERIFIED on one box (loopback is sub-ms;
> real inter-region is tens of ms — §4.2). Each cross-region latency target is reported as THREE
> numbers: local component VERIFIED here + WAN leg MODELED (cited RTT matrix) + combined flagged
> PENDING multi-box. RTT matrix + coordinated-omission method signed by review-architect
> (`methodology-note.md`, §0.2 sign-offs). Box spec: `run-log.md §6.1`.

## M-1 — Write-commit p99 < 150 ms (cross-region)  → THREE NUMBERS

| # | quantity | value | basis |
|---|---|---|---|
| 1 | **local quorum-commit component (VERIFIED on this box)** | **p99 = 5.51 ms** (p50 2.58, p90 3.27, p999 29.1, max 39.4) | phase-2: 3-node, 200/s, 30 s, data+WAL on `/mnt/nvme`, CO-corrected HdrHistogram (`captures/throughput/s75-phase2-lowrate.txt`). Measured in the **stable regime** (the cluster is churn-free ≤ ~800/s — see `throughput-part2.md`); a higher-stable-rate value would be modestly larger but the WAN leg dominates the combined number. |
| 2 | **WAN leg (MODELED)** | **+ 68 ms** = RTT(2nd-fastest follower), the round trip that completes the write quorum in the 5-region layout | cited RTT matrix, `methodology-note.md §3` / `methodology.md §2`. Non-overlapping with the leader-side serial terms already in the local component. |
| 3 | **modeled cross-region commit p99 (COMBINED — flagged)** | **≈ 73.5 ms** (5.51 + 68) | **< 150 ms target → PENDING multi-box confirmation.** Forbidden to VERIFY on one box. |

**Read:** the local quorum-commit path is fast and healthy (5.51 ms p99 on real NVMe, vs S5's ~16 ms
on a 2-vCPU laptop). The cross-region budget is dominated by the 68 ms WAN quorum round trip; even the
worst plausible local-component growth within the stable regime leaves comfortable headroom under the
150 ms target. The number is MODELED, not measured — the ≥3-region cluster confirmation stays in the
shrunken manifest (M-1).

## M-2 — Edge propagation / staleness p99 < 500 ms (global)  → split, local component PENDING

| # | quantity | value | basis |
|---|---|---|---|
| 1 | **local multi-edge fanout component (VERIFIED)** | **PENDING** — the S5 propagation probe re-run at scale on this box is not yet done (handoff PENDING §9). | `LivePropagationProbeMain` at scale |
| 2 | **WAN leg (MODELED)** | **+ (1–3 Plumtree hops × matrix RTT)** | `methodology-note.md §3`; Plumtree gossip tree depth × inter-region RTT |
| 3 | **modeled global propagation p99 (COMBINED — flagged)** | local_fanout + WAN gossip leg, **compare to 500 ms** → PENDING multi-box | — |

M-2's local component requires re-running the S5 edge probe at scale (single-host-provable, in the
handoff PENDING list); the WAN gossip leg + combined target are multi-region (shrunken manifest M-2).

## Surpass-Quicksilver scorecard (§0.3) — measured-at-scale where proven, modeled+pending where WAN-bound

| metric | Quicksilver baseline (S1) | Configd, this box | status |
|---|---|---|---|
| local write-commit p99 | — | **5.51 ms** (real NVMe, 3-node, stable regime) | **MEASURED-at-scale** |
| cross-region write-commit p99 | (target < 150 ms) | **≈ 73.5 ms modeled** (5.51 + 68 WAN) | MODELED + PENDING multi-box |
| global propagation p99 | (target < 500 ms) | local PENDING + WAN modeled | PENDING (local) + MODELED (WAN) |
| sustained write throughput | — | **~800/s stable, churn-bound** (NOT fsync; see `throughput-part2.md`) | MEASURED-at-scale (precise ceiling co-location-confounded → M-9) |

## Method rails
CO-corrected (intended-time) HdrHistogram; the local component is a genuine quorum commit on data
fsynced to `/mnt/nvme` (runtime-asserted); the WAN leg is the signed RTT matrix, never a loopback
proxy; combined numbers are explicitly MODELED and flagged PENDING multi-box (§13.1). More cores make
a stall easier to hide, so the driver paces by intended send time, not response time (§4.3).
