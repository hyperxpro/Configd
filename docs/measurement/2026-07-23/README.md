# Measurement run — 2026-07-23 (commit `a93eae8`)

The endurance-and-verification run: perf recapture on current HEAD, a ≥72h fault-injected soak,
end-to-end snapshot restore, a game-day drill, and alert-threshold ratification. Everything here was
measured on **commit `a93eae8`** — the perf and soak on a paid EC2 box, the restore and game-day as
correctness demos (hardware-independent).

**Box (perf + soak):** AWS `m6id.xlarge`, us-east-1, on-demand — 4 vCPU / 16 GiB / 237 GB local NVMe,
Amazon Corretto 25, ZGC. Chosen as a modest, fixed-performance endurance box (a soak is
memory/FD/GC/time-bound, not throughput-bound). **Note the contrast with the June-30 numbers, which were
on a 16-vCPU m6id.4xlarge** — so absolute throughput here is lower by roughly the core ratio, while
failover (election-timeout-bound) matches.

## Perf recapture (`perf/`)

| Metric | June-30 (16 vCPU) | This run (`a93eae8`, 4 vCPU) |
| --- | --- | --- |
| Single-group write knee (open-loop) | ~800/s | **~150/s** (stable ≤150, collapses ≥200) |
| Closed-loop sustained (N=1) | ~450/s | **~272–327/s** (churn/heartbeat-bound, ~66% CPU) |
| Leader-loss failover | 372 ms | **371 ms** (Δ=1 election, no storm) |
| Committed-write loss | 0/1000 | **0/1000** |
| WAL-replay recovery RTO | 4.2 s | **3.8 s** |
| InstallSnapshot catch-up RTO | 5.9 s | **7.0 s** (0 install failures) |
| Sharded single-box (N=4) | plateau ~1100/s | **knee not raised** (12 co-located raft groups are CPU-bound) |

Failover and zero-loss are hardware-independent and reproduced. The write knee scales with core count
(the balancer was active for the N=4 run and spread leaders 1-2-1). **True horizontal N× throughput
needs multiple machines** (proven near-linear 2.45×/3 machines on June-30) and was not re-run on this
single box.

A **real finding** surfaced and was fixed here: the DR "wipe a follower's disk and restart it under the
same node-id" drill is now correctly **refused** by the R-a′ peer-quorum anchor witness (added after
June-30) — a same-id durable rollback that could double-vote/split-brain. That is intended safety
behaviour; the supported lost-disk recovery is whole-cluster snapshot restore or membership add-server
under a new node-id. `perf/dr-drill.sh` was corrected: Drill C now asserts the refusal, and a new Drill D
exercises genuine InstallSnapshot catch-up on a legitimately-behind follower (passes, 0 loss).

## 72h soak (`soak/`)

`perf/soak.sh` (the real harness — `perf/soak-72h.sh` is a no-op stub) with the companion
`perf/soak-faults.sh` injector. 3-node ZGC cluster, 100/s (grounded in the fresh 150/s knee), NVMe,
sample every 30s.

- **Duration:** full **72h0m** (259,201 s), 8,263 samples.
- **Faults:** 11 injected (4 leader-kills, 4 follower-restarts, 3 clock-skews, ~every 6h) — **every one
  recovered to 3/3 in <60s** with writes never dropping below rate (`soak/fault-schedule.log`).
- **Leak verdict — no leak on any definitive signal:** jstat heap-used 2nd-half median vs 1st-half
  **+0.6%** (flat), FD **144→143**, threads **116→111**, commit p99 median 4.5 ms / worst-window 10.8 ms.
- **RSS** rose 1.47→2.80 GB but heap-used stayed flat — ZGC making the committed `-Xms1g` heap resident
  as GC touches pages (confirmed by NMT: native categories flat, Java-heap committed = exactly 1 GB),
  bounded ~1.2 GB/node (~3.6 GB for three nodes vs 16 GB box), **not a leak**.

## Snapshot restore (`restore/`)

A node was genuinely restored from a snapshot (booted from a data-dir that replays its WAL) and
`ops/scripts/restore-conformance-check.sh` returned **PASS** on all four checks — including the two
metrics added for this: the restored node's `configd_raft_last_applied_index` reached the snapshot index
and its `configd_state_machine_hash` matched the snapshot payload hash byte-for-byte
(`25dd50c3…506a`). The reference `.snap` was reproduced in-process (`restore/SnapReproducer.java`)
because **no product command exports a raw snapshot** — the on-disk `raft-log.snapshot.dat` is
integrity-envelope-wrapped (a documented tooling gap). `restore-snapshot.sh` was fixed: it no longer
requires `kubectl` for a dry-run, and it runs the conformance check against the scaled-back-up cluster
rather than while scaled to zero.

## Game-day drill (`game-day/`)

`gates/game-day-drill.sh` → `gates/e2e-compose-scenario.sh` on Docker: a 3-CP + edge topology under
load, then kill-leader / partition-edge / bootstrap-fresh-edge. **19/19 checks PASSED** — leader
failover with **no monotonic-read regression**, staleness ladder to DEGRADED and back to CURRENT,
fresh edge bootstrapped via snapshot transfer, and a **byte-equal linearizable audit** (45 keys
identical on all 4 edges vs the leader).

## Alert ratification (`ops/alerts/configd-slo-alerts.yaml`)

The 72h distributions **ratified** the rules they produced a real distribution for: the write-commit
latency budget (150 ms; measured p99 median 4.5 ms / max 10.8 ms — 14× margin) and the three
resource-leak ceilings (FD >500 vs max 144; threads >400 vs max 115; heap >0.90 vs peak 0.36). The
edge-read, edge-staleness, clock-skew, ACL-load, and cross-region rules were **not** exercised by this
soak (no edges / no ACL / single-box) and stay `PROPOSED`; the game-day drill separately validated that
the staleness-ladder and availability alerts fire on their injected faults.

## Honest scope

Single-box, three-co-located-node, plaintext-loopback, 100/s. Not exercised here: cross-machine network
faults, TLS/auth under sustained load, high-throughput saturation, endurance beyond 72h. Correctness
(as distinct from endurance) is covered by the faulted-linearizability matrix and the game-day drill.
