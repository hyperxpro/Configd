# Leadership placement — achieving & verifying one-per-box (a real operational finding)

The charter's validity requirement is that each of the 3 boxes leads exactly one of the 3 groups
(`1-1-1`) so the N=3 headline measures true cross-machine parallelism, not single-box contention in
disguise. Getting there turned out to be a genuine, honest operational finding.

## What we observed

- **Fresh simultaneous boot rarely lands on 1-1-1 (~1 in 20 empirically, not the naive 22%).** The
  three groups do *not* elect independently: whichever node becomes ready a beat sooner campaigns for
  and wins *all* of its groups before the others are up — a "sweep." So fresh boots are heavily biased
  to `3-0-0` / `0-3-0` / `2-1-0` forms.
- **`transferLeadership` (Raft TimeoutNow) exists in the core** (`RaftNode.transferLeadership`) **but
  is not invoked on shutdown and not exposed via any admin HTTP route** (the only routes are
  `/health/*`, `/metrics`, `/v1/config/`). So there is no runtime lever to *place* a group's leader on
  a chosen node without a code change (which this measure-only session forbids).
- **Kill-based rebalancing is biased away from 1-1-1.** Killing a node zeroes its leadership (it rejoins
  as a follower) and redistributes its groups to the other two — so the immediate post-kill state can
  never be 1-1-1 (the killed node leads 0). 1-1-1 only emerges from incidental churn during the settle
  window, which is rare.

## What worked

**Fresh-boot-until-balanced:** boot all three fresh (wiped) in parallel, check the per-node
`raft_shard_leader_*` counts, and repeat until `1-1-1`. It is stochastic (~4–20 boots) but reliable,
and 1-1-1 is a **stable fixed point at rest** — once reached with no load, the leaders hold (no election
fires), so the measured point is stable. Every measured N=3 point was taken on a verified-and-held
1-1-1, re-checked immediately after the run (it held, elections flat).

We also confirmed the aggregate is **robust to modest imbalance**: a `2-1-0` N=3 run still sustained
1628/s (≈ the 1-1-1 1607) because the double-leader box (cp1) had CPU headroom — but 1-1-1 is the
reported, validity-clean headline.

## Production implication (feeds the readiness review)

Multi-Raft leadership is **not auto-balanced** in the as-built system: there is no leadership-transfer
API and no balancer, so N groups can pile their leaders onto one node (losing the horizontal benefit
until something restarts). For a production multi-shard deployment this is a real gap. Recommended v1/v2
follow-ups (not built; no code changed this session):

- expose `transferLeadership` via an admin endpoint (the core primitive already exists), and/or
- a lightweight leadership balancer that spreads group leaders across nodes, and/or
- leadership-transfer on graceful shutdown so rolling restarts re-spread instead of sweeping.

This is filed as a horizontal-scale operability item for the production-readiness review.
