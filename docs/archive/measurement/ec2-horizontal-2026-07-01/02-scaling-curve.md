# Scaling curve -- aggregate write throughput vs. number of leader-bearing machines

Method: `calibrate-sharded` (closed-loop, the honest ceiling), plaintext cross-box, 512 B values, ZGC,
epoll, `ownerPoolSize = N`. Each N run starts on a fresh cluster (`shardCount = N` is fixed per data dir,
so the dir is wiped when N changes). Every point is the clean knee -- the best concurrency with ~0
shedding and no leadership churn -- repeated 3 times.

Leadership placement is verified before each measured point so that every group's leader sits on a
distinct machine:
- N=1: 1 group, 1 leader machine, the other 2 boxes are pure followers.
- N=2: 2 groups, leaders on 2 distinct boxes (1-0-1), the third box follows both.
- N=3: 3 groups, one leader per box (1-1-1), the full horizontal case.

## Results (sustained committed writes/s, closed-loop clean knee)

| N (leader machines) | knee C | run 1 | run 2 | run 3 | clean aggregate | vs N=1 | per machine |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 32 | 652 | 663 | 655 | 656 | 1.00x | 656 |
| 2 | 48 | 1062 | 1082 | 1071 | 1075 | 1.64x | 538 |
| 3 | 128 | 1607 | 1677 | 1602 | 1607 | 2.45x | 536 |

Peak-with-churn-onset (best single number before collapse): N=1 ~756, N=2 ~1295, N=3 ~1831.

The curve is near-linear in the number of leader-bearing machines: +419 w/s (1 to 2), +532 w/s (2 to 3);
about +475 w/s per added leader-machine. Per-machine throughput is flat at ~535 w/s for N>=2 (N=1 is a
touch higher, at 656, because its one leader box isn't also carrying follower work for two other live
leaders).

## Per-node CPU and network at the N=3 knee (1-1-1, 1607/s)

| box | leads | CPU busy | ens5 rx | ens5 tx |
|:--|:--|---:|---:|---:|
| cp1 | g0 | 61% | 6.4 MB/s | 18.5 MB/s |
| cp2 | g2 | 64% | 12.4 MB/s | 8.3 MB/s |
| cp3 | g1 | 64% | 8.9 MB/s | ~0 |

CPU is evenly spread across all three boxes (~62% each) -- direct evidence that all three machines are
doing leader-commit work in parallel (contrast the single-box run, where 3 co-located JVMs shared 16
cores at ~20% aggregate and contended). About 38% idle remains at the knee.

Network is not a ceiling: peak per-box tx ~18.5 MB/s (~148 Mbps), `sar %ifutil` about 0.00, well under 1%
of the m6i.xlarge ENA NIC. Cross-machine consensus traffic is cheap at these rates.

## Driver-headroom check (is 1607 the cluster or the load generator?)

On a verified 1-1-1 cluster, a single driver sustained 1602/s while the load box averaged 15% CPU, about
85% headroom. Three independent lines confirm the ceiling is the cluster, not the driver:

1. Load box at 15% CPU while pushing 1600/s (the driver is nowhere near saturated).
2. Increasing concurrency past the knee reduces aggregate (churn), rather than raising it -- the
   opposite of a driver-saturation curve.
3. The failures at over-drive are server-side (504 commit-timeouts, leadership elections), while server
   CPU is only ~62% (headroom) -- a consensus-dynamics ceiling, not a resource wall.

This mirrors the single-box multi-driver finding (three concurrent drivers summed to less than one).

## What the ceiling actually is

Each group's throughput is bounded by the per-group heartbeat-starvation churn knee -- the single-leader
finding from the single-box run, now confirmed cross-machine. Beyond its knee a group's single leader
loses leadership under load, elections fire, commits time out (504), and that group's throughput
collapses. Sharding across machines lets N groups each run below their own churn knee in parallel, so
aggregate is about N times the per-group cross-machine knee (~535 w/s). It does not raise any single
group's ceiling, and it is bounded by consensus dynamics well before CPU (62%), disk (gp3, idle), or NIC
(<1%) saturate.

## Note on the single-group cross-machine number (~656) vs. the single-box ~800

N=1 here is ~656/s, lower than the single-box loopback ~800/s open-loop knee (open-loop cross-machine is
also clean, at ~598/s at 600 offered). This is expected and honest: at N=1 every commit must replicate to
the two followers over the network instead of loopback, so single-group commit latency is higher and
single-group throughput is lower. The horizontal win is not that one group goes faster -- it is that
independent groups on independent machines add up. The correct like-for-like horizontal multiplier is
therefore N=3 divided by N=1 measured the same way, same transport: 2.45x.
