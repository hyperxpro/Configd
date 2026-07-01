# Verdict — horizontal scale across separate machines

**Question (charter §1):** with one Raft group's leader on each of 3 separate machines, does aggregate
write throughput cross the ~1100 w/s single-box plateau and head toward ~N×?

## Answer: HORIZONTAL SCALE — PROVEN (near-linear, ~2.45× on 3 machines)

Measured on 3 × m6i.xlarge, one group-leader per box (verified 1-1-1), plaintext cross-box,
closed-loop:

| N (leader machines) | clean aggregate w/s | multiplier vs N=1 |
|---:|---:|---:|
| 1 | 656 | 1.00× |
| 2 | 1075 | 1.64× |
| 3 | **1607** | **2.45×** |

- **Aggregate scales ~linearly with the number of leader-bearing machines** (~+475 w/s per machine).
  This is the horizontal-scale claim, now measured on metal — the item the single-box run left open.
- **1607 w/s at N=3 is 1.46× the single-box multi-group plateau (~1100)** and, unlike the single box
  (which plateaued hard at N=4 and gained nothing from N=8), it *keeps rising as machines are added*.
- All three boxes are evenly loaded (~62% CPU each) doing leader-commit work in parallel; the load
  generator (15% CPU), the network (<1% NIC), and disk (gp3, idle) are all non-binding. The number is
  a genuine cluster ceiling.

## The honest multiplier — why 2.45×, not 3.0×

Two effects hold it below a naive 3×, and both are reported plainly:

1. **Single-group throughput is *lower* cross-machine than the single-box loopback baseline.** N=1 here
   is ~656 w/s vs the single-box ~800 (RR-113), because every commit now replicates to followers over
   the network instead of loopback. The right like-for-like comparison is therefore N=3 ÷ N=1 measured
   identically → **2.45×**, not 1607 ÷ 800.
2. **Each group keeps its own heartbeat-starvation churn ceiling** (~535 w/s cross-machine, the RR-113
   single-leader finding confirmed on separate hardware). Sharding runs N groups in parallel *below*
   their knees; it does not raise any single group's ceiling. So the aggregate is `~N × per-group-knee`,
   bounded by consensus dynamics — not by CPU/disk/NIC, which all have headroom at the knee.

Framed against an "ideal" of 3 × the cross-machine single-group knee (3 × 656 = 1968), the measured
1607 is ~82% of ideal; the ~18% gap is churn-onset near the knee plus each box also carrying follower
work for the two other live leaders.

## Corrected framing vs the single-box run

- Single-box run: sharding lifted the aggregate only ~1.4× (~800→~1100) and then **plateaued** — its
  headline was "removes churn (stability win); raw-throughput horizontal scaling NOT validated on one
  box."
- This run: on **separate** machines the aggregate is **not** plateaued — it scales ~linearly with
  leader-machines (656 → 1075 → 1607, 2.45× at N=3). The horizontal-scale direction is **validated**.
  The multiplier is **honestly sub-3×** for the two reasons above, and is a conservative floor: bigger
  instances (more cores/faster single-group commit) or mitigating the per-group churn knee (group
  commit tuning, batching) would raise it.

## What this closes / opens for the readiness review

- **CLOSES** the last open empirical item from `docs/measurement/ec2-2026-06-30/` ("N× horizontal
  UNPROVEN — plateaus single-box"): horizontal N× is now **measured and PROVEN** across separate
  machines, near-linear at 2.45× on 3 boxes, cluster-bound by consensus churn (not hardware).
- **Cross-box mTLS** (the new risk) is **retired** (`03-mtls-bringup.md`).
- **OPENS** one operability gap for v1/v2 (`05-leadership-placement.md`): multi-Raft leadership is not
  auto-balanced — no leadership-transfer API and no balancer, so leaders can pile onto one node and
  forfeit the horizontal benefit until a restart. The Raft `transferLeadership` primitive already
  exists; exposing it + a balancer is the recommended follow-up.

**Next:** the production-readiness review — draw the v1 ship line. The empirical foundation is now
complete: durability/soak/DR GREEN (single-box run) + horizontal scale PROVEN near-linear (this run),
with the leadership-balancing gap documented as the one horizontal-scale operability follow-up.

## Reproduction

Environment + exact flags in `00-environment.md`; raw driver/sar/mpstat output in `captures/`
(`raw-results.txt` is the consolidated log; `mtls-bringup-proof.txt` the TLS evidence; `net/` the
per-box `sar -n DEV`). System under test: `main`-identical server @ `68463e5`.
