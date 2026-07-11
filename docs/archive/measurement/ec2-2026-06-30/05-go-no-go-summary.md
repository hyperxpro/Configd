# EC2 measurement -- go/no-go summary (2026-06-30)

The single paid hardware measurement of the sharding/scale/durability claims that had previously only
been verified in simulation, never on real metal. Box: `m6id.4xlarge` (16 vCPU, NVMe), `main` @
`ce7d719`, JDK 25, ZGC, Epoll. Plaintext-loopback throughput methodology (matches the established
baseline plus the `wsC`/`s75` scripts; mTLS is proven functional separately). Full detail in the sibling
docs; raw captures in `captures/`.

## What's proven (measured, holds)

| Claim | Measured | Verdict |
|---|---|---|
| Single-group write knee (~800/s baseline) | ~800/s open-loop knee reproduced (758 at 800 offered); leadership-churn-bound at ~20% CPU / ~16% NVMe, not fsync/CPU. (Closed-loop overdrive degrades it to ~450/s.) | confirmed and root-caused |
| Sharding lifts aggregate write throughput | single-box ~800 to ~1100/s, about 1.4x by N=4 (plateaus); the bigger win is churn removal (N=4/8: 0-2 elections vs. N=1's 26-43) | modest raw lift plus stability win on one box; true N-x scaling unproven here |
| Leader-loss failover | ~372 ms write-availability gap, single bounded election (no storm) | pass |
| No committed-write loss on fault | 0 loss across leader-kill, WAL-replay restart, wipe+InstallSnapshot (1000/1000 keys each) | durability contract holds |
| Node recovery RTO | ~4.2 s (WAL replay) / ~5.9 s (snapshot rebuild) | pass |
| mTLS on the production CLI path | 3-node Raft peer mTLS + API serve, curl-P12 to 200 | functional |
| Soak -- no leak/OOM (6 h burn-in) | full 6 h reached (prior attempt OOM'd at 3.45 h); FD flat 350 to 350, RSS 2.6% spread, heap floor stable, GC 0.92%, 0 rejected | pass, gate closed |

## What's not proven here (the honest gaps)

The "scales ~N x horizontally" claim is not established by this session. Aggregate write throughput
plateaus at ~1100/s by N=4 on this single 3-co-located-node box -- N=8 adds nothing, at only ~30% CPU /
~15% NVMe / zero churn, and more load (3 concurrent drivers gives ~590/s) makes it worse. This is a
single-box ceiling (3 JVMs sharing 16 cores plus one NVMe plus loopback replication), not the load
generator. The N-x claim is fundamentally about N groups across separate machines (each its own
CPU/disk/NIC), which one box cannot represent. A true multi-machine N x knee (3+ instances x N groups)
is the open empirical item from this session -- it is closed by the follow-up horizontal-scale run, see
`docs/archive/measurement/ec2-horizontal-2026-07-01/`. What is shown on one box: sharding gives only
~1.4x raw throughput (~800 to ~1100/s) but de-churns the cluster; real multi-machine scaling exceeds
~1100/s by a factor quantified in that follow-up.

## Allocation hypotheses
- server `ByteBufFrameSink` per-connection reuse -- real win ~176 B/op (confirmed). Follow-up to merge.
- edge-node static `byte[]` error bodies -- negligible (the per-request floor is the ~33 KB/op HTTP shell).
- edge-cache `PrefixStorageFilter.isEmpty()` -- clean-but-tiny; follow-up to merge.
None of these were merged in this session; the session's scope was measurement only.

## Bottom line

Durability and availability: green. Sub-second failover, zero committed-write loss under three fault
modes, single-digit-second recovery, measured on metal.

Single-node performance: characterized and root-caused. The single-group write knee is ~800/s and is
leadership churn, not resource saturation; sharding lifts the single-box aggregate only ~1.4x (~800 to
~1100/s) but removes the churn (a stability win, not a throughput-scaling win).

Horizontal scale (N x across machines): unproven by a single box -- the one empirical item this session
could not close. See the horizontal-scale follow-up for the resolution.

Soak: green. 6 h clean past the 3.45 h prior OOM -- FD/RSS/heap/GC all flat; the leak/OOM concern is
closed on clean code.

In one sentence: durability, availability, and long-run stability are measured and green; single-node
performance is characterized (~800 w/s knee, churn-bound); the one open item is multi-machine horizontal
N-x scaling, which a single box cannot prove on its own.

## Empirical items closed in this session
Real-hardware single-box N x knee; single-group knee root-cause; DR failover plus no-loss plus RTO; 6 h
soak leak/OOM; allocation floors and hypothesis verdicts; mTLS functional. Still open at the time: the
multi-machine N x knee.

## Follow-ups (not part of this session)
Merge the confirmed alloc win (`ByteBufFrameSink` reuse, ~176 B/op) and `PrefixStorageFilter.isEmpty()`;
harden `soak.sh` cleanup (guard `$BASE` == `$OUT_DIR`); run the multi-machine N x knee.
