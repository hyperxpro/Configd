# EC2 Horizontal-Scale Measurement — 2026-07-01

Multi-machine (3× m6i.xlarge consensus + 1 load box) session closing the last open empirical item from
the single-box run: **does write throughput scale horizontally across separate machines?**

**Verdict: YES — near-linear, ~2.45× on 3 machines (656 → 1075 → 1607 w/s for N=1/2/3 leader-machines),
cluster-bound by per-group consensus churn (not CPU/disk/NIC).** Cross-box mTLS proven; leadership
one-per-box verified; the single-box ~1100 plateau is exceeded and, unlike the single box, keeps rising
with added machines.

- `00-environment.md` — boxes, toolchain, topology, mTLS posture, load generator
- `01-burst-gate.md` — the go/no-go burst (PASSED at 1607 clean / 1831 peak vs ~1100)
- `02-scaling-curve.md` — N=1/2/3 curve, per-node CPU/network, driver-headroom proof
- `03-mtls-bringup.md` — cross-box mTLS proof (the new risk, retired; no cert regen)
- `04-verdict.md` — horizontal scale PROVEN; honest 2.45× framing; what it closes/opens
- `05-leadership-placement.md` — achieving/verifying 1-1-1; the leadership-balancing operability gap
- `captures/` — raw driver output (`raw-results.txt`), mTLS evidence, per-box `sar`/`mpstat`, scripts

System under test: `main`-identical server @ `68463e5`. Measure only; no code change.
