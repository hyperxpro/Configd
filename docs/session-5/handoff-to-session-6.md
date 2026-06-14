# Session 5 → Session 6 Handoff: Performance & Capacity — measured baselines, the metric gap, the pre-prod gap

> Session 5 measured what Sessions 1–4 built. Every §0.1 target now has a number with an honesty
> label, a reproducible command, and — where the 2-vCPU box can't prove it — a costed waiting harness.
> **Session 6 (Operability) derives its SLO/alert thresholds from the baselines below**, wires the
> metric series that S5's measurement showed are missing, and tracks the infrastructure manifest as the
> enumerated pre-production gap. Read this with `scorecard-and-claim-evidence.md` (the headline),
> `infrastructure-manifest.md` (the gap), `methodology.md` (the honesty rules), and the `workstream-{a..e}`
> docs + `decision-log.md`.

## 1. Measured baselines — the numbers S6 turns into alert thresholds

All on the reference box (t3a.large 2 vCPU / 7.7 GB, ZGC per ADR-0041, JDK 25). **These are
reference-hardware baselines, not production SLOs** — S6 should set alert thresholds as a *multiple* of
these (headroom for production hardware variance), and re-baseline on real infra (manifest).

| Signal | Measured baseline (S5) | Suggested S6 alert basis | Source |
|---|---|---|---|
| Edge read p99 (in-process) | 1.60 µs @10⁶ (p999 32 µs) | page if p99 > ~1 ms (the §0.1 oracle) — ~600× headroom | WS-A |
| Edge read steady-state alloc | 0 B/op (strict paths) | alert if `gc.alloc.rate.norm` on the read gate > 1 B/op | WS-A / gate-5 |
| Write commit p99 (local component) | 16 ms (live 3-node) | page if local commit p99 > ~50 ms; cross-region budget < 150 ms | WS-B |
| Write commit p99 (cross-region, modeled) | ~84 ms (5-voter) | the < 150 ms SLO — **measure on real WAN (M-1)** before alerting | WS-B / methodology §2 |
| Edge staleness p99 (local fan-out) | 255 ms (live Compose); 76 ms propagation proper | the `edge_staleness_ms` gauge already exists; alert at the contract 500 ms / 2 s thresholds | WS-C / CT-02 |
| GC STW pause (ZGC) | max 0.045 ms | page if STW pause > ~1 ms (ZGC regression / fallback to G1) | ADR-0041 |
| Sustained commit rate (this box) | ~125–172 commits/s (box ceiling) | **NOT a production SLO** — 10k/s is ENV-BLOCKED (M-9); alert thresholds need dedicated-core hosts | WS-B / M-9 |
| Write backpressure bound | `maxPendingProposals` = **1024** → HTTP 429 | alert on sustained 429 rate (queue saturating) | WS-D / RR-110 |
| Overload dominant shed (this box) | leadership step-down 503 (CheckQuorum) before 429 | a **box artifact**; on prod the 429 path leads — verify on M-10 | WS-D / M-10 |
| Reconnect-storm recovery | 258 ticks @5 edges → 261 @50 (flat) | recovery-time SLO ~hundreds of ms; flat in fleet size | WS-D §D.3 |
| JIT cold→steady | iter-1 535 ns → steady ~340–410 ns by iter ~6; C2 < 1 s | warm-up budget ~seconds; no megamorphic read site | WS-E §JIT |
| Soak leak signals (5.5-min smoke) | FD flat 69, threads flat 93, heap flat ~220–290 MB | the soak harness emits FD/thread/heap/GC/latency trends — wire them as leak alerts | WS-E §Soak |

**Caution (carried from S4):** RR-099 — do NOT page on `invariant.violation.monotonic_read` alone; it
conflates benign catch-up refusals with real regressions.

## 2. Metric series: what EXISTS vs what S6 still needs to wire

**Exist (measured this session):** `edge_staleness_ms` gauge (per edge, ADR-0039 frontier; WS-C used
it live); `configd_inbound_routing_throwable_total` (S4); the fan-out slow-consumer warning metric
(80% queue, `FanOutSessionCore`); HTTP 429 "Overloaded" + 503 (leadership) responses (`HttpApiServer`);
the soak harness's process trends (RSS/jstat-heap/FD/thread/cumulative-GC/commit-latency — `perf/soak.sh`).

**Missing / partial (S6 must wire) — surfaced by S5 measurement:**
- The §11 backpressure ladder metrics that don't exist because the *paths* don't exist (RR-110):
  no `Retry-After` on the 429, no apply-lag-503 counter, no ReadIndex-queue-depth shed/metric, no
  queue<500 hysteresis gauge. S6 decides relabel-vs-implement (RR-110) and wires the chosen reality.
- A commit-latency histogram metric (S5 measured it with an external probe; production needs it as a
  served metric for alerting, not just a benchmark).
- The clock-skew fence metric/alert (500 ms, S4 carried to S6): consensus is clock-independent, but the
  operational fence + alert is S6's (S4 handoff §3).

## 3. The infrastructure manifest IS the pre-production gap (S6/S8 must track it)

`infrastructure-manifest.md` enumerates **M-1…M-10**, each costed with a waiting harness. The honest
summary: **everything the 2-vCPU single-region box cannot prove is listed, not hidden.** S6/S8 own
running the costed campaign before GA:

| Item | Claim blocked locally | ~Cost |
|---|---|---|
| M-1 | cross-region write-commit p99 < 150 ms | ~$6–8 |
| M-2 | global propagation/staleness p99 < 500 ms | ~$3–4 |
| M-3 | fsync-power-loss durability (real device) | ~$0.5 |
| M-4 | NUMA / CPU-pinning for edge serving | ~$12 |
| M-5 | 10⁹-key read-path scale | ~$8–16 |
| M-6 | real-WAN multi-host partition recovery (wall-clock) | folds into M-1 |
| M-7 | Porcupine faulted-history linearizability (Go) | ~$0.7 / CI |
| M-9 | end-to-end 10k/s sustained + 100k/s burst (dedicated cores) | folds into M-1 +$1–2 |
| M-10 | architected 429/503 backpressure ladder at thresholds | folds into M-9 |

Total coordinated campaign ≈ **$40–60 of short on-demand compute**. The point: every gap has a number,
a machine, and a harness already written and committed.

## 4. Open items handed forward

- **RR-110 (P2, S6 owns):** decide relabel-vs-implement for the §11 backpressure ladder; wire the metrics.
- **RR-108 (P3, S6):** refresh the `consistency-contract.md` §8 RR-003 line anchor (carried from 4.5).
- **RR-105 (P2, S5→ongoing):** register owner-session renumbering — partially addressed by this session's
  forward-pointing (RR-110 owner=6); a full re-triage of the ~17 stale-owner OPEN rows remains.
- **The ENV-BLOCKED campaign (M-1…M-10):** the pre-prod measurement gap; S6/S8 run it on real infra.
- **The 24 h soak:** harness `perf/soak.sh` is wired + smoke-clean; **launch status below.**

## 5. The 24 h soak — status & resume

Per charter §14.6, a 24 h wall-clock soak cannot complete inside one session, and the box can only
sustain ~100–150 commits/s (M-9), so the soak runs at a **box-sustainable rate** (leaks/drift are
rate-independent — a heap/FD/thread leak shows as a monotonic trend regardless of rate). The harness is
validated (5.5-min smoke: all leak signals flat). **The lead launches the long run from the main
session** (so it outlives the sub-agents); it is collected within this session if it finishes, else
handed to resume:

```
nohup flock /tmp/configd-mvn.lock perf/soak.sh \
  --duration=86400 --rate=100 --sample=60 \
  --out=perf/results/soak-24h-<ts> > perf/results/soak-24h.out 2>&1 &
```

**Honesty:** the run is labeled by its **real elapsed duration** (`measured_elapsed_sec`); a partial run
is reported as the duration it actually ran, never called a 24 h soak. A production-representative soak
(real fleet, NUMA, real WAN) inherits M-1/M-2/M-4.

## 6. Gate / CI state for S6

- **gate-5** is CI-wired (`needs: gate-4`), cumulative: read-path 0 B/op, read p99/p999 regression bounds,
  throughput floor, the §11 1024 bound, CO self-check. It locks in the S5 wins. Full soak + sustained/burst
  are NOT in the blocking gate (ENV-BLOCKED / the lead's soak run) — documented in the gate-5 header.
- gates 1–5 chain via CI `needs:`; the nightly path (schedule or `run_full_nightly=true`) runs the full
  cumulative gate-5. RR-107's lesson stands: the `schedule:` cron only fires once `ci.yml` is on the
  default branch (merge-to-main, an S6/S8 release decision).
