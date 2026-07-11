# The burst gate -- go/no-go before the full scaling run

Once leadership was verified one-per-box (1-1-1) on the plaintext N=3 cluster (cp1 leads g0, cp2 leads
g2, cp3 leads g1; readiness 3/3; elections settled), a short heavy-write burst decided whether horizontal
scaling was real enough to justify the full scaling curve.

## The burst

`ShardAwareWriteDriver calibrate-sharded` from the load box, keys hash-distributed across all 3 groups so
all 3 leaders (on 3 separate boxes) commit in parallel. 512 B values.

| concurrency | sustained writes/s | 200 | non-200 | leadership | verdict |
|---:|---:|---:|---:|:--|:--|
| 128 | 1677 -> 1607 | 28506 / 48212 | 698 / 0 | 1-1-1 held | clean knee |
| 160 | 1831 | -- | 1059 | drifted 0-1-2 | churn onset (peak-but-shedding) |
| 192 | 1256 | -- | 473 | drifted 2-0-1 | collapsing |
| 256 | 723 | -- | 895 (+504s) | churned | collapsed |
| 384 | 459 | -- | 1776 | churned | collapsed |
| 512 | 197 | -- | 1946 (+504s) | churned | collapsed |

The clean sustained knee is ~1607/s at C=128 (a 30 s run: 48,212 commits, zero non-200, zero retargets,
1-1-1 held, elections flat at 2). Above the knee, over-driving pushes each group's single leader into
heartbeat starvation, which produces 504 commit-timeouts, then leadership churn, and the 1-1-1 topology
breaks and aggregate falls -- the same single-group-churn signature seen on the single box, now at a
higher ceiling.

## The gate decision

Baselines to beat: ~800/s single-group open-loop (the single-box baseline); ~1100/s single-box
multi-group plateau (the single-box run's hard ceiling, reached by N=4 and unmoved by N=8).

Aggregate at the clean knee is 1607/s, clearly and repeatably above ~1100 (1.46x the single-box
plateau), with all 3 boxes doing leader-commit work in parallel (even CPU, ~62% each) and the load box at
only 15% CPU (so the number is cluster-bound, not driver-bound). Horizontal scaling is real.

Decision: proceed to the full N=1/2/3 scaling curve (`02-scaling-curve.md`).

This was not a marginal pass (for example 1150 vs. 1100) that would warrant pausing to double-check --
1607 clean / 1831 peak is a decisive, repeatable margin over the single-box plateau.
