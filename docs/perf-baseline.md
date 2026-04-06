# Phase 6 Perf Baseline

Measured 2026-04-17 on the in-session JMH run. Used to anchor the
before-state for D13 (radix trie) and O5 (lock-free histogram). Every
later perf change must beat or match these numbers.

## Hardware / JVM
- Linux 6.17.0-1010-aws / amd64
- Java 25.0.2-amzn (Amazon Corretto), `--enable-preview`
- ZGC default; JMH default fork settings

## SubscriptionMatchBenchmark.matchingNodes (D13 baseline)

| prefixes | mean ns/op |
| --- | --- |
| 100 | 760.6 |
| 1000 | 7 324.5 |
| 10000 | 196 383.0 |

The 10× scaling (760 → 7 324 → 196 383) confirms the linear-scan
hypothesis: each new prefix adds ~20 ns of additional `String.startsWith`
work. At 10 k subscribed prefixes the match call costs ~196 μs per
mutation — at the SLO commit rate of 1 k commits/sec this is **~20% of a
core spent in `matchingNodes` alone**, before any actual delivery.

D13 (radix-trie prefix index, gap-closure §2 work-unit D13) targets O(K)
where K = key length. Expected post-D13 delta: 10 k prefixes ≤ 5 μs/op
(a ~40× improvement). The benchmark stays in tree as the regression
guard.

## HistogramBenchmark (O5 baseline)

| benchmark | threads | thrpt (ops/μs) |
| --- | --- | --- |
| recordSingleThreaded | 1 | 114.2 |
| recordContended | 8 | 52.3 |
| percentileP99 | 1 | 1118.8 |

The contention drop (114 → 52 across 1→8 threads, **per-thread
throughput collapses from 114 to 6.5 ops/μs**) is the synchronized
min/max CAS visible in `MetricsRegistry.DefaultHistogram`. O5 (PA-5008
through PA-5010) is the rewrite that removes this cliff. Expected
post-O5 delta: contended throughput should scale near-linearly with
thread count, i.e. ≥ 600 ops/μs at 8 threads.

`percentileP99` at 1.1 k ops/μs is the read path (sort the ring buffer);
it does not need to scale because exporters call it at most once per
scrape interval. Recorded for completeness so a future regression in the
sort path is caught.

## Calendar-bounded harnesses (yellow)

`perf/soak-72h.sh`, `perf/burn-7d.sh`, `perf/longevity-30d.sh` exist as
stub harnesses that honour the duration contract and emit
`measured_elapsed_sec` to a result file. They do not yet wire to a real
cluster bringup — that lands in Phase 10 (DR drills). Until then they
are **YELLOW**: harness in place, real workload pending the cluster
bootstrap in Phase 10.

In-session smoke run of `perf/soak-72h.sh --duration=60` recorded
`measured_elapsed_sec=60`, `status=YELLOW (no workload wired; duration
honoured)`. This is the contract — the GA review records what was
actually run, never what was promised.
