# EC2 measurement — go/no-go summary (2026-06-30)

The single paid hardware measurement of the sharding/scale/durability claims that were sim-verified but
never measured on metal. Box: `m6id.4xlarge` (16 vCPU, NVMe), `main` @ `ce7d719`, JDK 25, ZGC, Epoll.
Plaintext-loopback throughput methodology (matches the RR-113 baseline + wsC/s75; mTLS proven functional
separately). Full detail in the sibling docs; raw captures in `captures/`.

## What's proven (measured, holds)

| Claim | Measured | Verdict |
|---|---|---|
| Single-group write knee (RR-113 ~800/s) | **~800/s open-loop knee reproduced** (758 at 800 offered); **leadership-churn-bound** at ~20% CPU / ~16% NVMe, NOT fsync/CPU. (Closed-loop overdrive degrades it to ~450/s.) | ✅ confirmed + root-caused |
| Sharding lifts aggregate write throughput | single-box **~800→~1100/s ≈ 1.4× by N=4** (plateaus); the bigger win is **churn-removal** (N=4/8: 0–2 elections vs N=1's 26–43) | 🟡 modest raw lift + stability win on one box; true N× unproven |
| Leader-loss failover | **~372 ms** write-availability gap, single bounded election (no storm) | ✅ pass |
| No committed-write loss on fault | **0 loss** across leader-kill, WAL-replay restart, wipe+InstallSnapshot (1000/1000 keys each) | ✅ durability contract holds |
| Node recovery RTO | **~4.2 s** (WAL replay) / **~5.9 s** (snapshot rebuild) | ✅ pass |
| mTLS on the production CLI path | 3-node Raft peer mTLS + API serve, curl-P12 → 200 | ✅ functional |
| **Soak — no leak/OOM** (6 h burn-in) | full 6 h reached (prior attempt OOM'd at 3.45 h); **FD flat 350→350**, RSS 2.6 % spread, heap floor stable, GC 0.92 %, 0 rejected | ✅ **PASS** (gate closed) |

## What's NOT proven (the honest gaps)

1. **The "scales ~N× horizontally" claim is NOT established.** Aggregate write throughput **plateaus at
   ~1100/s by N=4** on this single 3-co-located-node box — N=8 adds nothing, at only ~30% CPU / ~15% NVMe /
   zero churn, and *more* load (3 concurrent drivers → ~590/s) makes it worse. This is a single-box ceiling
   (3 JVMs sharing 16 cores + one NVMe + loopback replication), not the load generator. The N× claim is
   fundamentally about N groups across **separate machines** (each its own CPU/disk/NIC), which one box
   cannot represent. **A true multi-machine N×knee (3+ instances × N groups) remains the open empirical
   item** — a v1 ship caveat or a v2 measurement. What *is* shown on one box: sharding gives only ~1.4×
   raw throughput (~800→~1100/s) but de-churns the cluster; real multi-machine scaling should exceed
   ~1100/s by an unmeasured factor.

2. ~~Soak~~ — now **PROVEN** (moved to the proven table below).

## Allocation hypotheses (oracle)
- server `ByteBufFrameSink` per-connection reuse — **real win ~176 B/op** (confirmed). Follow-up to merge.
- edge-node static `byte[]` error bodies — **negligible** (per-request floor is the ~33 KB/op HTTP shell).
- edge-cache `PrefixStorageFilter.isEmpty()` — clean-but-tiny; follow-up to merge.
None merged this session (per charter).

## Bottom line for the v1 ship line
- **Durability & availability: GREEN.** Sub-second failover, zero committed-write loss under three fault
  modes, single-digit-second recovery — measured on metal.
- **Single-node performance: characterised + root-caused.** The single-group write knee is ~800/s (RR-113)
  and is leadership churn, not resource saturation; sharding lifts the single-box aggregate only ~1.4×
  (~800→~1100/s) but removes the churn (a stability win, not a throughput-scaling win).
- **Horizontal scale (N× across machines): UNPROVEN — the one empirical item this session could not close
  on a single box.** Recommend either (a) shipping v1 with an explicit "per-cell ~1100 w/s on comparable
  hardware; multi-machine N× is a v2 measurement" caveat, or (b) a short follow-up multi-instance run
  before committing to the horizontal-scale claim in v1 docs.
- **Soak: GREEN.** 6 h clean past the 3.45 h prior OOM — FD/RSS/heap/GC all flat; the leak/OOM concern is
  closed on clean code.

## The v1 ship line, in one sentence
Durability, availability, and long-run stability are **measured and green**; single-node performance is
**characterised (~800 w/s knee, churn-bound)**; the **one open item is multi-machine horizontal N× scaling**,
which a single box cannot prove — ship v1 with that explicit caveat, or run a short multi-instance
follow-up first.

## Empirical 🔬 items closed this session
Real-hardware single-box N×knee · single-group knee root-cause · DR failover + no-loss + RTO · 6 h soak
leak/OOM · allocation floors + hypothesis verdicts · mTLS functional. **Still open:** multi-machine N×knee.

## Follow-ups (NOT this session)
Merge the confirmed alloc win (`ByteBufFrameSink` reuse ~176 B/op) + `PrefixStorageFilter.isEmpty()`;
harden `soak.sh` cleanup (`$BASE`==`$OUT_DIR` guard); run the multi-machine N×knee.
