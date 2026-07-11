# Verdict -- horizontal scale across separate machines

Question: with one Raft group's leader on each of 3 separate machines, does aggregate write throughput
cross the ~1100 w/s single-box plateau and head toward ~N x?

## Answer: horizontal scale is proven, near-linear, ~2.45x on 3 machines

Measured on 3 x m6i.xlarge, one group-leader per box (verified 1-1-1), plaintext cross-box, closed-loop:

| N (leader machines) | clean aggregate w/s | multiplier vs N=1 |
|---:|---:|---:|
| 1 | 656 | 1.00x |
| 2 | 1075 | 1.64x |
| 3 | **1607** | **2.45x** |

- Aggregate scales near-linearly with the number of leader-bearing machines (~+475 w/s per machine).
  This is the horizontal-scale claim, now measured on metal -- the item the single-box run left open.
- 1607 w/s at N=3 is 1.46x the single-box multi-group plateau (~1100) and, unlike the single box (which
  plateaued hard at N=4 and gained nothing from N=8), it keeps rising as machines are added.
- All three boxes are evenly loaded (~62% CPU each) doing leader-commit work in parallel; the load
  generator (15% CPU), the network (<1% NIC), and disk (gp3, idle) are all non-binding. The number is a
  genuine cluster ceiling.

## The honest multiplier -- why 2.45x, not 3.0x

Two effects hold it below a naive 3x, and both are reported plainly:

1. Single-group throughput is lower cross-machine than the single-box loopback baseline. N=1 here is
   ~656 w/s vs. the single-box ~800, because every commit now replicates to followers over the network
   instead of loopback. The right like-for-like comparison is therefore N=3 divided by N=1 measured
   identically, giving 2.45x, not 1607 / 800.
2. Each group keeps its own heartbeat-starvation churn ceiling (~535 w/s cross-machine, the single-leader
   finding confirmed on separate hardware). Sharding runs N groups in parallel below their knees; it does
   not raise any single group's ceiling. So the aggregate is ~N times the per-group knee, bounded by
   consensus dynamics, not by CPU/disk/NIC, which all have headroom at the knee.

Framed against an ideal of 3 times the cross-machine single-group knee (3 x 656 = 1968), the measured
1607 is ~82% of ideal; the ~18% gap is churn-onset near the knee plus each box also carrying follower
work for the two other live leaders.

## Corrected framing vs. the single-box run

- Single-box run: sharding lifted the aggregate only ~1.4x (~800 to ~1100) and then plateaued -- its
  headline was that sharding removes churn (a stability win) but raw-throughput horizontal scaling was
  not validated on one box.
- This run: on separate machines the aggregate is not plateaued -- it scales near-linearly with
  leader-machines (656, 1075, 1607; 2.45x at N=3). The horizontal-scale direction is validated. The
  multiplier is honestly sub-3x for the two reasons above, and is a conservative floor: bigger instances
  (more cores, faster single-group commit) or mitigating the per-group churn knee (group commit tuning,
  batching) would raise it.

## What this closes / opens

- Closes the open empirical item from `docs/archive/measurement/ec2-2026-06-30/` ("N x horizontal
  unproven, plateaus single-box"): horizontal N x is measured and proven across separate machines,
  near-linear at 2.45x on 3 boxes, cluster-bound by consensus churn, not hardware.
- Cross-box mTLS (the new risk) is retired (`03-mtls-bringup.md`).
- Opens one operability gap, documented in `05-leadership-placement.md`: at the time of this measurement,
  multi-Raft leadership was not auto-balanced -- no leadership-transfer API and no balancer, so leaders
  could pile onto one node and forfeit the horizontal benefit until a restart. The Raft
  `transferLeadership` primitive already existed; exposing it plus a balancer was the recommended
  follow-up (since built -- see `docs/architecture/architecture.md` for the current leadership
  auto-balancer).

## Reproduction

Environment and exact flags are in `00-environment.md`; raw driver/sar/mpstat output is in `captures/`
(`raw-results.txt` is the consolidated log; `mtls-bringup-proof.txt` the TLS evidence; `net/` the
per-box `sar -n DEV`). System under test: `main`-identical server @ `68463e5`.
