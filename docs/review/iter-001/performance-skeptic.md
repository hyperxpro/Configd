# performance-skeptic — iter-001
**Findings:** 16

Lens: assume every benchmark is lying. Severity floor S3, cap 30. Skipped already-YELLOW residuals (O5 cursor cache contention, O6 wire format, O7 SafeLog, PA-5018 bucket alignment) per scope.

## P-001 — `docs/performance.md` claims "HdrHistogram for all latency measurements" but no code uses it
- **Severity:** S2
- **Location:** `docs/performance.md:75` and pom dependency at `pom.xml:63-64`
- **Category:** lying-bench
- **Evidence:** `docs/performance.md:75` — `**HdrHistogram** for all latency measurements (percentile-accurate, no averaging)`. Grep for `org.HdrHistogram|HdrHistogram\.Histogram|new Recorder|new SingleWriterRecorder` returns zero matches in any `*.java` file. The actual production histogram is the hand-rolled `MetricsRegistry$DefaultHistogram` with a 4096-slot ring buffer and `Arrays.sort` on each percentile query (`MetricsRegistry.java:402`). HdrHistogram is declared in `pom.xml` and `configd-observability/pom.xml:22-23` but never imported.
- **Impact:** Every latency p99/p999 cited in performance.md, perf-baseline.md, and SLO alerts is sourced from a 4096-sample sliding ring buffer + JMH `Mode.AverageTime` runs. p999 from a 4096-sample ring is a single-sample observation; "percentile-accurate" is unsupportable. At 100k records/s the ring wraps in 41 ms, so any tail beyond that horizon is invisible.
- **Fix direction:** Either swap `DefaultHistogram` to a real `org.HdrHistogram.Recorder`/`ConcurrentHistogram` with millisecond/microsecond-resolution buckets and genuine percentile estimation, or strike the "HdrHistogram" sentence from `performance.md` and replace with "ring-buffer summary, 4096 samples". Do not ship both inconsistencies.
- **Proposed owner:** configd-observability

## P-002 — `HistogramBenchmark` runs `@Fork(value = 1)` with only 3 warmup iterations
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/bench/HistogramBenchmark.java:35-37`
- **Category:** warmup
- **Evidence:** `@Warmup(iterations = 3, time = 2)`, `@Measurement(iterations = 5, time = 2)`, `@Fork(value = 1)`. Every other JMH benchmark in the suite runs `@Fork(value = 2)` (see `RaftCommitBenchmark.java:28`, `HamtReadBenchmark.java:30`, `SubscriptionMatchBenchmark.java:37`, etc.). Single-fork JMH cannot distinguish JVM-local artefacts (a single bad C2 inlining decision pinned to that JVM start) from real signal. The O5 contended-throughput claim "114→52 ops/μs at 8 threads" in `docs/perf-baseline.md:38-44` is sourced from this single-fork run.
- **Impact:** O5 baseline is unreplicated. The "before" number that the lock-free min/max rewrite is benchmarked against is single-fork; any post-O5 delta is not statistically defensible.
- **Fix direction:** Set `@Fork(value = 3)` and `@Warmup(iterations = 5, time = 2)` to match the rest of the suite, then re-run and re-pin `docs/perf-baseline.md`.
- **Proposed owner:** configd-testkit

## P-003 — `MetricsRegistry$DefaultHistogram` allocates `long[]` snapshot on every `percentile()` call
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:386-409`
- **Category:** hot-path-alloc
- **Evidence:** `long[] snapshot = new long[n];` then `Arrays.sort(snapshot)`, where `n = min(count, 4096)`. `PrometheusExporter.appendHistogram` (`PrometheusExporter.java:111-117`) iterates 19 default bucket bounds and calls `hist.bucketCount(le)` per bound, scanning the 4096-entry buffer linearly each time (`MetricsRegistry.java:411-425` — O(capacity × buckets) per scrape per histogram). For N histograms scraped every 15 s, this is N × 4096 × 19 long comparisons per scrape, plus a separate `n*8`-byte allocation for any `percentile()` call.
- **Impact:** At 50 histograms × 4096 × 19 = ~3.9 M long-compares per scrape; at 1 s scrape this is non-trivial CPU. More importantly, percentile reads allocate a 32 KB long[] each — bench `percentileP99` (`HistogramBenchmark.java:93-96`) does not use `-prof gc` evidence in the baseline doc, so the "allocation-free recording path" claim is unverified for the read side.
- **Fix direction:** (a) Cache a per-histogram sorted snapshot reused across multiple percentile reads in the same scrape, (b) maintain pre-bucketed counters incrementally on `record()` so `bucketCount(le)` is O(1), (c) require `-prof gc` evidence for every benchmark in `docs/perf-baseline.md` before any "zero allocation" claim is made.
- **Proposed owner:** configd-observability

## P-004 — `PrometheusExporter._sum` is `Math.round(mean × count)`, not the actual sum
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java:119-122`
- **Category:** lying-bench
- **Evidence:** `// Approximate sum as mean × count — the registry tracks sum internally / but does not expose it; mean × count round-trips with sub-1-ulp loss. / long approxSum = (count == 0) ? 0 : Math.round(hist.mean() * count);`. `mean()` is computed as `(double) sum.sum() / c` (`MetricsRegistry.java:373`). So `_sum` reported to Prometheus is `round((sum/count) × count)`, which loses the precision of `sum` (a `LongAdder`) and converts to double-precision. For sums > 2^53 (millisecond-recorded latencies aggregated over a long-running pod) the result is silently wrong.
- **Impact:** Prometheus `rate(_sum[5m]) / rate(_count[5m])` (used in many burn-rate alerts) is double-rounded. For a histogram of nanosecond timestamps, sum can exceed 2^53 in ~104 days. The error compounds with `rate()` over time and there is no test for `_sum` correctness in `PrometheusExporterTest`.
- **Fix direction:** Expose `sum()` from `MetricsRegistry.Histogram` and emit it directly as a `long`. The current double round-trip is gratuitous precision loss for "sub-1-ulp" savings that don't exist in practice.
- **Proposed owner:** configd-observability

## P-005 — `SubscriptionManager.matchingNodes` is O(P) linear scan on the propagation hot path
- **Severity:** S2
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/SubscriptionManager.java:112-123`
- **Category:** tail-amp
- **Evidence:** `for (var entry : prefixIndex.entrySet()) { String prefix = entry.getKey(); if (key.startsWith(prefix)) { result.addAll(entry.getValue()); } }`. The `SubscriptionMatchBenchmark` baseline (`docs/perf-baseline.md:12-29`) shows 196 μs/op at 10k prefixes — the doc itself acknowledges this is "~20% of a core … before any actual delivery." Plus `result = new HashSet<>()` per call (`SubscriptionManager.java:115`).
- **Impact:** The doc claims D13 will fix this but D13 is not landed. Until it lands, every commit at 1k commits/s burns 200 ms of CPU per second on prefix matching alone at 10k subs. The propagation p99 < 500 ms claim in `docs/performance.md:97` ("Plumtree broadcast to 500 peers = 7.25 μs CPU time. Network RTT is the bottleneck") ignores this 196 μs subscription-match step that runs before broadcast.
- **Fix direction:** Land D13 (radix-trie prefix index). Until then, restate `docs/perf-baseline.md` to acknowledge propagation p99 budget is consumed by `matchingNodes` not RTT for any cluster with > 5k subscriptions.
- **Proposed owner:** configd-distribution-service

## P-006 — `Set.copyOf` defensive snapshots on hot getters in PlumtreeNode / HyParViewOverlay / SubscriptionManager
- **Severity:** S2
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java:240,245`; `HyParViewOverlay.java:204,209`; `SubscriptionManager.java:130`; `WatchEvent.java:56`
- **Category:** hot-path-alloc
- **Evidence:** `public Set<NodeId> eagerPeers() { return Set.copyOf(eagerPeers); }`. `Set.copyOf` allocates a new immutable set and copies all elements every call. `WatchEvent` constructor builds `affectedKeys = Set.copyOf(keys)` per event. None of these are guarded behind a "snapshot read" comment — a caller polling `eagerPeers()` from a metrics scraper allocates a fresh set every scrape.
- **Impact:** Steady-state allocation on observed hot accessors. No `-prof gc` evidence anywhere in `docs/perf-baseline.md` to refute. The "near-zero allocation" claim in `docs/performance.md:41` ("hot read path achieves near-zero allocation") doesn't cover these accessors.
- **Fix direction:** For internal hot callers, provide a non-defensive `eagerPeersUnsafe()`-style accessor or expose iteration without materializing a Set. Externally-visible getters can keep `Set.copyOf` but document them as scrape-only.
- **Proposed owner:** configd-distribution-service

## P-007 — `PlumtreeNode.drainOutbox` allocates a `LinkedList` copy of the entire outbox every drain
- **Severity:** S2
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java:232-236`
- **Category:** hot-path-alloc
- **Evidence:** `public Queue<OutboundMessage> drainOutbox() { Queue<OutboundMessage> result = new LinkedList<>(outbox); outbox.clear(); return result; }`. `LinkedList` is a node-per-element allocation (~40 B per `Node` plus a `LinkedList` header). On a 500-peer broadcast (`PlumtreeFanOutBenchmark.broadcastAndDrain` with `fanOut=500`), this drain allocates 500 + 1 = 501 objects per drain. The benchmark `broadcastAndDrain @ 500 = 7.25 μs` (`docs/performance.md:157`) is reported without `-prof gc` so the allocation cost is not visible.
- **Impact:** At a broadcast rate of N events/s × 500 peers, allocations go to ~500N/s — at 1k events/s, that is 500k objects/s of LinkedList nodes plus the OutboundMessage records themselves. ZGC handles this but it is throughput, not latency-budget-friendly.
- **Fix direction:** Either return the existing `outbox` directly and replace it with a fresh empty `ArrayDeque` (atomic swap pattern), or make `drainOutbox(Consumer<OutboundMessage>)` so the caller iterates without materializing a Queue.
- **Proposed owner:** configd-distribution-service

## P-008 — `RaftCommitBenchmark` interleaves `propose + 50 ticks` in a single `@Benchmark` method, polluting throughput math
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/bench/RaftCommitBenchmark.java:111-130`
- **Category:** lying-bench
- **Evidence:** The `proposeAndCommit` method does `leader.propose(proposalData)` then loops up to 50 iterations of `deliverAllMessages()` and `node.tick()` on every node, breaking when the commit advances. The bench reports "815K ops/s (3 voters)" in `docs/performance.md:148-149` and the analysis on line 167 says "consensus overhead per write is ~1.2 μs — trivially supports 10K/s." But each `@Benchmark` call performs O(50 ticks × 3 nodes) of work plus message delivery — that is what's being timed, not "per-write overhead." Reporting it as throughput (`Mode.Throughput`, ops/μs) makes the per-op number meaningless.
- **Impact:** The "Raft commit at 815K ops/s" headline is unsupportable. It is "we can drive a deterministic in-memory simulation 815K times per second", which says nothing about Raft commit throughput on a real network. The `docs/performance.md:167` claim "trivially supports 10K/s" is inferred from this misleading number.
- **Fix direction:** Split into two benchmarks: (a) `propose` only (latency to enqueue, not commit); (b) `commit` measured by counting committed entries inside a fixed time window, dividing entries/duration. Drop the in-loop tick from inside the bench method. Mark the existing simulation harness as a smoke test, not a throughput claim.
- **Proposed owner:** configd-testkit

## P-009 — `RateLimiter.tryAcquire` reads `clock.nanoTime()` inside an unbounded CAS retry loop
- **Severity:** S2
- **Location:** `configd-control-plane-api/src/main/java/io/configd/api/RateLimiter.java:82-128`
- **Category:** cache-contention
- **Evidence:** `while (true) { long now = clock.nanoTime(); … if (storedPermitsScaled.compareAndSet(currentScaled, afterAcquire)) return true; }`. Under contention, all callers spin re-reading `nanoTime()` (a sysnocall on Linux unless TSC is invariant; even then it is a memory fence and ~25 ns) and re-running double arithmetic. There is no backoff. Loss of one CAS forces a fresh `nanoTime()` and a fresh `permitsPerSecond / 1_000_000_000.0` floating-point divide.
- **Impact:** Under N writer threads contending the limiter, spin contention is visible in a tail. There is no benchmark for this code path under contention; the "lock-free" comment claims a property that has not been measured.
- **Fix direction:** Add a `RateLimiterBenchmark` that measures `tryAcquire` at 1, 8, 64 threads and report scaling. If contention dominates, switch to per-thread token reservoirs with periodic central reconciliation.
- **Proposed owner:** configd-control-plane-api

## P-010 — `CatchUpService.trimHistory` is O(N) linear scan per `recordDelta`
- **Severity:** S2
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/CatchUpService.java:80-83,159-170`
- **Category:** hot-path-alloc
- **Evidence:** `recordDelta` calls `trimHistory()` which loops `for (long version : deltaHistory.keySet()) { if (version < oldest) oldest = version; }`. So when `deltaHistory.size() > maxDeltaHistory`, every insert scans the entire keyset to find the oldest entry — O(N) per insert, total O(N²) for inserting N entries past the cap.
- **Impact:** For `maxDeltaHistory = 10_000`, every commit past 10k incurs a 10k-entry linear scan. At 1k commits/s this is 10M HashMap key iterations/s.
- **Fix direction:** Use a `LinkedHashMap` with `removeEldestEntry` (already used in `PlumtreeNode.java:81-86` for the same pattern) or a `TreeMap` so `firstKey()` is O(log N).
- **Proposed owner:** configd-distribution-service

## P-011 — `PrometheusExporter._sum` for nanosecond-recorded histograms wraps `int n` in `bucketCount`
- **Severity:** S3
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:412-425`
- **Category:** lying-bench
- **Evidence:** `int n = (int) Math.min(c, capacity); long count = 0; for (int i = 0; i < n; i++) { if (buffer[i] <= upperBound) count++; }`. The loop iterates over physical buffer slots `0..n-1` rather than the logical newest-N samples — when the ring buffer has wrapped, `bucketCount` includes evicted-but-still-resident entries from the previous wrap. The result is a stale tail in the bucket count, not the current 4096-sample window.
- **Impact:** Prometheus bucket counts include samples older than the percentile-snapshot window, so `_bucket{le=...}` is inconsistent with `percentile()` (which does the wrap-aware copy at `MetricsRegistry.java:391-400`). Operators querying `histogram_quantile()` from buckets get a different answer than the registry's own `percentile()` API.
- **Fix direction:** Apply the same wrap-aware logic in `bucketCount` as `percentile`, or share the snapshot.
- **Proposed owner:** configd-observability

## P-012 — `cursor.AtomicLong.getAndIncrement` cache contention on the histogram hot path is documented but unfixed
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:323`
- **Category:** cache-contention
- **Evidence:** `long idx = cursor.getAndIncrement();`. `docs/ga-review.md:82` (O5) records this as YELLOW with the explicit note "real bottleneck is `cursor.AtomicLong.getAndIncrement` cache contention." This finding is in scope (NOT skipped — only the "lock-free min/max" portion of O5 is YELLOW; the cursor contention is a distinct issue acknowledged in `ga-review.md` as needing per-thread cursor striping). After O5's CAS rewrite of min/max, the next contention cliff is on the cursor cache line. `MetricsRegistry.java:299-311` puts `totalCount`, `cursor`, and `LongAdder sum` close together but with no `@Contended` padding — false sharing across these fields is plausible.
- **Impact:** Per `docs/perf-baseline.md:43`, contended throughput collapses from 114→52 ops/μs at 8 threads. Even after O5, the cursor will keep this ratio. Direct ZGC-friendly remediation is per-thread cursor stripes; without it, the headline "histogram-record is hot-path-friendly" claim is unsupportable for any system with > 4 writer threads on the same metric.
- **Fix direction:** Stripe per-thread (e.g., `LongAdder`-style cells) and union on read, OR add `@jdk.internal.vm.annotation.Contended` to `cursor` to evict false sharing; rerun `HistogramBenchmark.recordContended` with `@Threads(8)` and `@Threads(16)`.
- **Proposed owner:** configd-observability

## P-013 — `WatchCoalescer.flush` allocates `List.copyOf(pending)` then `WatchEvent` allocates `Set.copyOf` of affected keys
- **Severity:** S3
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/WatchCoalescer.java:130`; `WatchEvent.java:48-57`
- **Category:** hot-path-alloc
- **Evidence:** `new WatchEvent(List.copyOf(pending), latestVersion)` then in the WatchEvent canonical constructor `var keys = new HashSet<String>(); … affectedKeys = Set.copyOf(keys);`. So a single flush allocates: ImmutableList copy + HashSet + ImmutableSet copy + WatchEvent record = 4 objects minimum, plus per-mutation `String` retention. `WatchService.dispatchEvent` then allocates `new WatchEvent(matching, …)` per filtered watcher (`WatchService.java:282`).
- **Impact:** With N watchers and M mutations per event, dispatch produces O(N) WatchEvent records. The "fan-out aware" doc claim (`WatchService.java:28-30`) does not note this allocation cost and `WatchFanOutBenchmark` runs without `-prof gc`.
- **Fix direction:** Reuse a single WatchEvent across all watchers when filter is the empty prefix; for filter dispatch, pass an `IntPredicate`-style mask rather than allocating a filtered List.
- **Proposed owner:** configd-distribution-service

## P-014 — `PropagationLivenessMonitor.checkAll` re-resolves `metrics.counter("propagation.lag.violation")` per violation
- **Severity:** S3
- **Location:** `configd-observability/src/main/java/io/configd/observability/PropagationLivenessMonitor.java:45-56`
- **Category:** hot-path-alloc
- **Evidence:** `metrics.counter("propagation.lag.violation").increment();` inside the `for` loop. `MetricsRegistry.counter` (`MetricsRegistry.java:74-80`) does `counters.computeIfAbsent(name, …)` — a `ConcurrentHashMap` lookup with a `String.hashCode()` and equality check on every call. The constructor already pre-warms the counter, but the loop still re-looks it up.
- **Impact:** O(violations) ConcurrentHashMap lookups per `checkAll`. Cheap individually, but called by a periodic monitor against potentially thousands of edges. Trivial to fix.
- **Fix direction:** Cache `Counter violationCounter = metrics.counter(...)` in the constructor as a field.
- **Proposed owner:** configd-observability

## P-015 — Cross-region commit budget computed from a single AWS CloudPing snapshot, no jitter modelling
- **Severity:** S2
- **Location:** `docs/performance.md:251-271`
- **Category:** rtt
- **Evidence:** Section 7 ("Cross-Region RTT Impact") cites flat RTT numbers — `us-east-1 ↔ eu-west-1 = 68ms`, then derives "Commit at 2nd-closest = 68ms … With processing overhead (~10ms): ~78ms p50, < 100ms p99". This treats RTT as a constant. Real cross-region p99 RTT is 1.5×–3× p50 (the variance is dominated by middle-mile congestion, not last-mile). The "p99 < 100ms" prediction subtracts the 10 ms processing budget from a constant — there is no JMH or simulation evidence backing the < 100 ms p99 claim, only `docs/performance.md:181` saying "p99 includes: batch wait (200μs), queuing delay, network jitter (10-20ms)" which is about 10× too small for cross-region jitter.
- **Impact:** The Surpass-Quicksilver scorecard headline (`docs/performance.md:352`) "Write commit latency (p99, cross-region) ~100ms" is built on a constant-RTT model. Realistic cross-region p99 with even modest jitter (σ=15 ms applied at 4 hops × queuing) will exceed 150 ms. The "SURPASSED" claim is not measured.
- **Fix direction:** Replace the constant-RTT calculation with a Monte-Carlo simulation seeded from a multi-week RTT histogram (CloudWatch / Atlas / RIPE Atlas data), and rerun the section as percentile-based, not point-estimate.
- **Proposed owner:** docs + configd-replication-engine

## P-016 — Tail-amplification model uses an unverified lognormal distribution
- **Severity:** S3
- **Location:** `docs/performance.md:282-303`
- **Category:** tail-amp
- **Evidence:** `*Based on modeled latency distribution: lognormal with μ=1ms, σ=0.5 for intra-region; μ=68ms, σ=15ms for cross-region.*` — the table of "p99 5ms / p999 20ms / p9999 100ms" amplification is computed from this model, not measured. The model parameters have no citation.
- **Impact:** Customers reading `docs/performance.md` see "p9999 = 100ms / 180ms / 280ms" as fact. There is no JMH or end-to-end measurement under realistic fan-out load to validate the model. With lognormal σ=0.5, the model under-states p9999 vs typical empirical heavy-tailed networks.
- **Fix direction:** Either (a) cite the source measurements that produced μ and σ, or (b) replace the table with "modeled, not measured" disclaimer and a TODO to measure once a real shadow run completes (C4 — currently YELLOW per `docs/ga-review.md:96`).
- **Proposed owner:** configd-observability + docs
