# performance-skeptic — iter-002
**Findings:** 18

Lens: every benchmark still lying until proven otherwise. Severity floor S3. iter-1 closed §8.1 (HdrHistogram claim struck) and F5 metric wire-up — those are out of scope. Where iter-1 already left a measured/modeled disclaimer (cross-region RTT, fan-out lognormal), I do **not** re-file the same complaint; instead I file the unfixed code-path findings that the disclaimer cannot cover.

Top-level state of P-001 … P-016 from iter-1:
- P-001 CLOSED (F7 — disclaimer in `docs/performance.md:75`).
- P-002 OPEN — `HistogramBenchmark.java:35-37` is still `@Fork(1)` `@Warmup(3)`.
- P-003 OPEN — `MetricsRegistry$DefaultHistogram.percentile()` still allocates `long[n]` per call (`MetricsRegistry.java:386-409`).
- P-004 OPEN — `_sum = Math.round(mean × count)` still in `PrometheusExporter.java:233`.
- P-005 OPEN — `SubscriptionManager.matchingNodes` still O(P) prefix scan (`SubscriptionManager.java:112-123`); D13 not landed.
- P-006 OPEN — `Set.copyOf` defensive snapshot accessors unchanged in `PlumtreeNode.java:240,245`, `HyParViewOverlay.java`, `SubscriptionManager.java:130`.
- P-007 OPEN — `PlumtreeNode.drainOutbox` still `new LinkedList<>(outbox)` (`PlumtreeNode.java:232-236`).
- P-008 OPEN — `RaftCommitBenchmark.proposeAndCommit` still interleaves propose + 50-tick loop in one `@Benchmark` (`RaftCommitBenchmark.java:111-130`).
- P-009 OPEN — no `RateLimiterBenchmark` exists; `RateLimiter.tryAcquire` still spins on `clock.nanoTime()` per CAS retry.
- P-010 OPEN — `CatchUpService.trimHistory` still O(N) keyset scan per `recordDelta` (`CatchUpService.java:159-170`); the comment in iter-1 said "use `LinkedHashMap` like `PlumtreeNode.receivedMessages`" — `PlumtreeNode.java:81-86` does it; `CatchUpService` does not.
- P-011 OPEN — `bucketCount(le)` still iterates physical slots `0..n-1` (`MetricsRegistry.java:412-425`); inconsistent with the wrap-aware copy in `percentile()`.
- P-012 OPEN — `cursor.AtomicLong.getAndIncrement` still no `@Contended` and no per-thread stripe (`MetricsRegistry.java:311,323`).
- P-013 OPEN — `WatchCoalescer.flush` still allocates `List.copyOf(pending)` and `WatchEvent` re-allocates `Set.copyOf` of affected keys.
- P-014 OPEN — `PropagationLivenessMonitor.checkAll` still calls `metrics.counter("propagation.lag.violation").increment()` per violation inside the loop (`PropagationLivenessMonitor.java:52`); the constructor pre-warms it but the loop re-resolves.
- P-015 / P-016 — closed by F7 disclaimer (cross-region rows now say "MODELED, NOT MEASURED"); not re-filed.

That leaves a **carryover stack of 12 P-items** (P-002…P-014 minus the two doc items already disclaimed). Per honesty invariant, I report new and persistence-aged findings below — not duplicates of the carryover whose status I just summarised. Persistence note: iter-1 to iter-2 with no fix is **first persistence event** for P-002…P-014; another iteration without movement triggers escalation under §4.7.

---

## P-017 — `perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/` is a literal placeholder; no JMH artefact pinned to any SHA on disk
- **Severity:** S2
- **Location:** `perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/README.md` and `perf/results/smoke/result.txt`
- **Category:** lying-bench
- **Evidence:** Iter-1 P-001 was closed by "F7 — JMH log dir created". The dir contains exactly one file:
  > "This directory is intentionally empty until a JMH run on commit 22d2bf3071271334998df7a721e533c187d1dc17 produces results."

  The other dir, `perf/results/smoke/`, contains:
  > "soak-72h harness / requested_duration_sec=60 / status=YELLOW (no workload wired; duration honoured)"

  So under the directory that §8.1 requires for every perf claim, there are zero JMH artefacts and one self-described no-workload smoke file. Meanwhile `docs/performance.md:108-162` still cites concrete ns/op numbers (HamtReadBenchmark `52.939 ns/op`, RaftCommit `0.815 ops/μs`, PlumtreeFanOut `7.250 μs/op`, etc.) inline as "JMH Measured (avg)".
- **Perf cost / impact:** §8.1 of the loop directive ("no perf claim without HdrHistogram artifact" — generalised in iter-1's CHANGELOG to "no perf claim without artefact under `perf/results/`") is violated for every measured-row in `docs/performance.md` table at §3. The numbers in the doc could be from any JVM, any commit, any host. Iter-1 closed the **claim** (struck the HdrHistogram word) but did not produce the **artefact**.
- **Fix proposal:** Either (a) actually run `./mvnw -pl configd-testkit test -Dtest='io.configd.bench.*' -Djmh.rf=json -Djmh.rff=perf/results/jmh-<sha>/` once and commit the JSON outputs (a single fork is enough to demonstrate the infra works; a 3-fork pinned run is the real artefact), or (b) tag every concrete ns/op number in `docs/performance.md:108-162` with the same "MODELED, NOT MEASURED" disclaimer that §7 carries. Mixing one disclaimer with a hundred unbacked numbers is exactly the failure mode §8.1 was meant to prevent.

## P-018 — `RaftNode.tickHeartbeat` allocates a fresh `HashSet<NodeId>` every heartbeat round
- **Severity:** S2
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:773-792` calls `buildActiveSetAndReset()` at `:1548-1565`, which does `var activeSet = new HashSet<NodeId>();` on every fire.
- **Category:** hot-path-alloc
- **Evidence:**
  ```java
  private Set<NodeId> buildActiveSetAndReset() {
      Set<NodeId> peers = clusterConfig.peersOf(config.nodeId());
      var activeSet = new HashSet<NodeId>();      // alloc per heartbeat
      activeSet.add(config.nodeId());
      for (NodeId peer : peers) {
          if (Boolean.TRUE.equals(peerActivity.get(peer))) {
              activeSet.add(peer);
          }
      }
      ...
  }
  ```
  Plus `clusterConfig.peersOf(...)` returns a Set that may itself be a defensive copy — needs verification but worst-case it is another HashSet alloc.
- **Perf cost / impact:** `tickHeartbeat` fires every `heartbeatIntervalTicks` (default ~50ms) per leader. Per RaftNode that is ~20 HashSet allocations/s on the LEADER hot path. Per cluster of N Raft groups colocated on one host (the multi-Raft model in `docs/architecture.md`) that scales linearly: 100 Raft groups × 20/s = 2000 HashSets/s, each ~48 B + table backing. Plus `Boolean.TRUE.equals(peerActivity.get(peer))` autoboxes the lookup result. The result is fed to `clusterConfig.isQuorum(activeSet)` which only needs membership tests, not a Set itself.
- **Fix proposal:** Stash a pre-allocated `HashSet<NodeId> reusableActiveSet` on `RaftNode`; clear() at the start of `buildActiveSetAndReset` and `confirmPendingReads`. Even better: replace with a primitive bitset over the voter index since voter count is bounded (typically ≤7) — `peerActivity` could be a `long` bitmap and `isQuorum` a `Long.bitCount`. Allocation drops to zero on the hot heartbeat path.

## P-019 — `RaftMessageCodec.encode*` allocates `ByteBuffer.allocate(...)` + `new byte[]` per Raft RPC
- **Severity:** S2
- **Location:** `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:104,131,146,156,174,193,212,235,243` — every `encodeAppendEntriesRequest`, `encodeAppendEntriesResponse`, `encodeRequestVote`, `encodeRequestVoteResponse`, `encodeInstallSnapshot` etc. does `ByteBuffer.allocate(payloadSize)` and at decode time `byte[] cmd = new byte[cmdLen]` per log entry.
- **Category:** hot-path-alloc
- **Evidence:**
  ```java
  ByteBuffer buf = ByteBuffer.allocate(payloadSize);
  ...
  return new FrameCodec.Frame(MessageType.APPEND_ENTRIES, groupId, req.term(), buf.array());
  ```
  `ByteBuffer.allocate` is a heap buffer wrapping a fresh `byte[]` — so each RPC is at minimum 1 ByteBuffer header + 1 backing array + 1 `Frame` record + N inner LogEntry `cmd` byte[] copies on decode.
- **Perf cost / impact:** At the documented Raft commit throughput claim ("trivially supports 10K/s") and N voters per group, every committed write is at least N × 1 AppendEntriesRequest + N × 1 AppendEntriesResponse = 2N messages, each with a ByteBuffer + backing array. For 5 voters × 10k commits/s × 10 entries/batch (the documented batch size in `docs/performance.md:222-227`) the decode side alone allocates 5 × 10k × 10 = 500k `byte[cmdLen]` per second per node. Cmd sizes 100-1000 bytes ⇒ 50-500 MB/s of allocation just for log entry bodies on each follower. That eats most of the documented "50 MB/s allocation rate target" (`docs/performance.md:11`) by itself.
- **Fix proposal:** Pool ByteBuffers on the encode side via a per-thread `ThreadLocal<byte[]>` scratch buffer reused across encodes (Frame can hold a length and a borrowed array). On decode, decode-into-existing-array via a sliced view rather than `byte[] cmd = new byte[cmdLen]` per LogEntry. JMH bench needed: a `RaftCodecBenchmark` covering encode + decode for AppendEntries with realistic batch sizes, run with `-prof gc` to attribute allocation. Without it the "10K/s trivially supported" claim is unbacked.

## P-020 — `FrameCodec.encode/decode` allocates `new CRC32C()` per frame
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/FrameCodec.java:155,187,239`
- **Category:** hot-path-alloc
- **Evidence:** `CRC32C crc = new CRC32C();` in `encode` (twice — header CRC and payload CRC sites) and again in `decode`. `java.util.zip.CRC32C` is a stateful object with an internal long state; constructing one is cheap but not free, and allocating per frame is gratuitous when the codec could keep a single instance and `crc.reset()` between uses.
- **Perf cost / impact:** Every Raft message and every Plumtree eager-push goes through `FrameCodec`. At 10k commits/s × 5 voters × 2 (req+resp) + Plumtree fan-out, frame encode/decode rate is in the 100k/s range per leader-side node. 100k CRC32C objects/s ≈ 2-3 MB/s allocation just for the CRC instance, plus the frame `byte[]` itself. The CRC32C state is small (~24 B header) but the count is high.
- **Fix proposal:** Hold a per-codec-instance `CRC32C` and call `crc.reset(); crc.update(...); long v = crc.getValue();`. CRC32C is single-threaded by construction so this works under the existing single-I/O-thread invariant of FrameCodec callers. Adds zero API surface; removes one allocation per frame.

## P-021 — `PrometheusExporter._sum` divides by `count`, then multiplies — additionally now contradicts the new bucket-cutoff units
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java:233` and `ConfigdMetrics.java:134-148`
- **Category:** lying-bench (compounds P-004)
- **Evidence:** `long approxSum = (count == 0) ? 0 : Math.round(hist.mean() * count);`. The histogram **records nanoseconds** (HttpApiServer.java:307 records `elapsedNanos` into `edgeReadSeconds`), but the override `BucketSchedule` labels them as fractional seconds (`"0.150" → 150_000_000L` cutoff). Prometheus `histogram_quantile` from the `_bucket{le="0.150"}` series will return a fractional-second answer, but the `_sum` series is `round(mean_ns × count)` = nanoseconds. So `rate(_sum[5m]) / rate(_count[5m])` returns nanoseconds while `histogram_quantile(0.99, ...)` returns seconds. The two views of the same histogram are off by a factor of 10⁹.
- **Perf cost / impact:** Any operator dashboard that does `rate(configd_edge_read_seconds_sum[5m]) / rate(configd_edge_read_seconds_count[5m])` to get the average latency in seconds will see a number around 10⁵ (nanoseconds-from-mean / 1, displayed as "seconds"). That number then drives further math in any composite SLI. The fast-burn alerts use `_bucket` so they correctly fire on seconds, but the burn-rate UI panels and any "avg latency" panel are off by 10⁹.
- **Fix proposal:** Either (a) record values in the unit named by the metric (record `elapsedNanos / 1_000_000_000.0` × scaled-long, which is fragile), or (b) expose `Histogram.sum()` returning the actual long sum, scale it once at exposition time by the same factor as the bucket cutoffs (define a `unitDivisor` per `BucketSchedule`), and emit `_sum` in the documented unit. Preferred: add `long sumScaled(long divisor)` to `Histogram` and a per-schedule divisor field; then `_sum` matches `_bucket` units. Add a regression test to `PrometheusExporterTest`: "for an edge-read histogram fed with 1ms = 1_000_000 ns samples, _sum/_count must be ~0.001, not 1_000_000."

## P-022 — `WatchFanOutBenchmark.coalescedBurstDispatch` allocates `"key-" + (i % 10)` 100 times per `@Benchmark` invocation
- **Severity:** S3
- **Location:** `configd-testkit/src/main/java/io/configd/bench/WatchFanOutBenchmark.java:103`
- **Category:** lying-bench
- **Evidence:** `service.onConfigChange(List.of(new ConfigMutation.Put("key-" + (i % 10), payload)), versionCounter);`. Inside the timed region. `"key-" + i` allocates a StringBuilder + char[] + final String each iteration, plus `List.of(...)` allocates an ImmutableCollections.List12 record per call.
- **Perf cost / impact:** The bench reports a per-burst time that includes ~100 String allocations + 100 List.of allocations + 100 ConfigMutation.Put records — none of which represent the production workload (where keys are pre-existing strings from the request). The "coalesced burst dispatch" number is therefore inflated and the headline "fan-out aware" claim from `docs/performance.md` cannot be backed by it.
- **Fix proposal:** Pre-build `String[] keys = new String[10]` and `List<ConfigMutation>[] singletons` in `@Setup(Level.Trial)`. Reuse them in the `@Benchmark` method. Re-record the bench number once allocations are removed from the timed region.

## P-023 — `WatchService.dispatchEvent` allocates a fresh `WatchEvent` per filtered watcher
- **Severity:** S2
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/WatchService.java:271-285`
- **Category:** hot-path-alloc, tail-amp
- **Evidence:**
  ```java
  List<ConfigMutation> matching = filterByPrefix(event.mutations(), watch.prefix(), affectedKeys);
  ...
  WatchEvent filtered;
  if (matching.size() == event.mutations().size()) {
      filtered = event;
  } else {
      filtered = new WatchEvent(matching, event.version());   // alloc per watcher
  }
  ```
  And `WatchEvent`'s canonical constructor (per iter-1 P-013) wraps `Set.copyOf` of affected keys. So per-watcher dispatch with a non-trivial prefix filter allocates: `ArrayList` from `filterByPrefix:317`, a new `WatchEvent` record, a new `HashSet` of affected keys, a new immutable `Set.copyOf` view, plus `List.copyOf` if any.
- **Perf cost / impact:** With N=1000 watchers (the upper bound of `WatchFanOutBenchmark`'s sweep), and a mutation that matches half of them, dispatch produces 500 fresh WatchEvent + 500 ArrayList + 500 ImmutableSet snapshots per commit. At 1k commits/s that is 500k×4 = 2M short-lived objects/s. ZGC handles it but the "fan-out aware" claim masks per-watcher allocation cost that scales linearly with subscribers — and there is no `-prof gc` in the bench output.
- **Fix proposal:** Two layers. (a) Compute filtered mutations into a thread-local reusable ArrayList and pass an `IntPredicate` mask through to listeners, so each listener sees the same `WatchEvent` plus an int mask telling it which mutations apply to its prefix. (b) Stop pre-computing `affectedKeys` as a Set in WatchEvent — pass mutations directly and let listeners short-circuit. Add `-prof gc` to `WatchFanOutBenchmark` measurement output and capture before/after.

## P-024 — `ConfigStateMachine.snapshot()` does two-pass collect-then-pack with O(N) intermediate `byte[]` per key+value
- **Severity:** S2
- **Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:329-368`
- **Category:** hot-path-alloc, snapshot-cliff
- **Evidence:**
  ```java
  List<byte[]> keys = new ArrayList<>();
  List<byte[]> values = new ArrayList<>();
  snap.data().forEach((key, vv) -> {
      keys.add(key.getBytes(StandardCharsets.UTF_8));   // alloc per key
      values.add(vv.valueUnsafe());
  });
  // size pass
  for (int i = 0; i < keys.size(); i++) { size += 4 + keys.get(i).length + 4 + values.get(i).length; }
  ByteBuffer buf = ByteBuffer.allocate(size);
  for (int i = 0; i < keys.size(); i++) { buf.putInt(...); buf.put(keys.get(i)); ... }
  ```
  At 1M keys (the documented snapshot size in `docs/performance.md:322`) this is **3M+ allocations** per snapshot: 2× ArrayList growth (~20 reallocations each), 1M `byte[]` from `getBytes`, plus the final `ByteBuffer.allocate(size)` which can be in the GB range.
- **Perf cost / impact:** Snapshots happen periodically (log compaction) and during InstallSnapshot. The doc claims "Full snapshot re-bootstrap (1M keys, 1KB avg) < 30s". The serialise step is one of two phases (the other is HAMT walk). At 1M keys × 1 KB avg, the intermediate `byte[]` allocation is ~1-2 GB short-lived plus the final `ByteBuffer` is ~1 GB live for the duration of the network send. ZGC can keep up but the GC pause budget for "< 1ms p99" (`docs/performance.md:14`) is not measured during a snapshot — and snapshot-during-load is the worst-case GC cliff.
- **Fix proposal:** Single-pass streaming write to a `ByteArrayOutputStream` sized via cardinality estimate, or better — write directly to a `FileChannel` / `WritableByteChannel` provided by the snapshot transport, eliminating the intermediate `ByteBuffer.allocate(size)` entirely. The `forEach` callback should write the bytes immediately rather than collecting into Lists. Add a benchmark `SnapshotSerializeBenchmark` covering 10k / 100k / 1M keys with `-prof gc`.

## P-025 — `Set.copyOf` accessor allocations are now also in the Raft `becomeFollower` step-down path
- **Severity:** S3
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1060` — `var toFire = new ArrayList<>(readReadyCallbacks.values());` on every `becomeFollower`.
- **Category:** hot-path-alloc
- **Evidence:** Step-down can fire on `becomeFollower(higherTerm)` from any AppendEntries with higher term, or from CheckQuorum failure. The pattern is "copy values into a fresh ArrayList, clear the original, iterate the copy." Allocation is bounded by `readReadyCallbacks.size()` at the moment of step-down, which under load is "all currently-pending linearizable reads."
- **Perf cost / impact:** Step-down is rare (≤1/election timeout), so cost-per-event is small. But this creates a transient allocation cliff exactly when the system is **already** struggling (lost quorum). Adding allocation pressure to a path that already needs every bit of CPU to run a stable election is the wrong direction.
- **Fix proposal:** Iterate `readReadyCallbacks.values()` directly with a try/catch around each callback and `clear()` afterwards. The defensive copy is to avoid concurrent modification, but RaftNode is documented single-threaded — the callbacks should not re-enter `becomeFollower`. Document that invariant and remove the copy.

## P-026 — `MetricsRegistry.snapshot()` constructs a fresh `LinkedHashMap` per scrape, then wraps in `unmodifiableMap`
- **Severity:** S3
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:218-231`
- **Category:** hot-path-alloc
- **Evidence:** Per scrape, `MetricsRegistry.snapshot()` allocates one LinkedHashMap, populates it via three forEach loops, then wraps in an UnmodifiableMap. `PrometheusExporter.export()` then iterates that map. With ~50 metrics × 16 B per Map.Entry × LinkedHashMap chain headers, scrape allocates ~2-4 KB per scrape — small per scrape, but Prometheus typically scrapes every 15 s and many burn-rate alert evaluators scrape every 30 s; over a day that is ~6k scrapes ⇒ ~25 MB allocation just for the snapshot Map.
- **Perf cost / impact:** Trivial in absolute terms. Worth flagging because `docs/performance.md:11` claims "< 50 MB/s in steady state" and the scrape path is one of the steady-state allocators that wasn't separately accounted for. Also: `MetricValue` is itself a record with a `String name` and `String type` — the `type` field is one of three constant strings ("counter", "gauge", "histogram") and could be interned or replaced by an enum to avoid the per-entry String reference.
- **Fix proposal:** Replace the `MetricsSnapshot(Map<String, MetricValue>)` API with a `forEach(BiConsumer<String, MetricValue>)` style so the exporter can stream without materializing a Map. Replace `String type` with `enum MetricType {COUNTER, GAUGE, HISTOGRAM}`. Allocation drops to N MetricValue records only.

## P-027 — `docs/performance.md:97-98` write-commit row carries the modeled-disclaimer but `docs/performance.md:148-149` and §11 row 1 still claim the consensus 815K ops/s number as if it were a write-commit number
- **Severity:** S3
- **Location:** `docs/performance.md:148-150,166-168,353-360`
- **Category:** lying-bench, doc-drift
- **Evidence:** `RaftCommitBenchmark.proposeAndCommit  3  thrpt    3   0.815 ± 0.851  ops/μs  (815K ops/s)` is rendered as the JMH measurement for "Raft commit (3-node simulated)" in the §3 latency table. Then §3 analysis line 167 says "consensus overhead per write is ~1.2 μs — trivially supports 10K/s." Per iter-1 P-008, this number is "we ran 815k iterations of `propose + 50 ticks` per iteration", not "Raft committed 815k entries/sec." The error bar `± 0.851` is itself larger than the score, which by JMH convention means **the number is statistical noise**.
- **Perf cost / impact:** Operators reading §11 (the Quicksilver scorecard) see "Write throughput: 10K+/s per group" backed by "JMH 815K ops/s." The JMH number does not back that claim; the inference chain is broken. The cross-region row already carries MODELED, NOT MEASURED — but this row does not, despite suffering from the same lack of a real cluster artefact.
- **Fix proposal:** Rewrite §3 lines 148-149 to read "RaftCommitBenchmark.proposeAndCommit (in-memory, deterministic; per-iter cost includes propose + ≤50 simulated ticks). Score has error > mean, treat as upper-bound smoke." Replace the §11 row 1 "10K+/s per group × N groups" claim with the same MODELED disclaimer the other rows carry. Land the P-008 fix (split into `propose` latency and a windowed-throughput bench) before the next docs cycle.

## P-028 — Architecture says edges 10K-1M but `PlumtreeFanOutBenchmark` only goes to 500 peers; gap between bench coverage and documented fan-out is two orders of magnitude
- **Severity:** S2
- **Location:** `docs/architecture.md:188` ("Edge (10K-1M nodes) | Plumtree consumers"), `docs/performance.md:152-162` (PlumtreeFanOutBenchmark sweep stops at 500), `configd-testkit/src/main/java/io/configd/bench/PlumtreeFanOutBenchmark.java`.
- **Category:** tail-amp, fan-out
- **Evidence:** Plumtree's `broadcast()` does `for (NodeId peer : eagerPeers) outbox.add(...)` — O(eager peers) per broadcast. ADR-0003 sizes eager peers as `log(N)+1` so for 1M edges that is `log2(1M) + 1 = 21` eager peers per node. So a single node's per-broadcast cost is bounded by 21, **not** by 1M. This is fine in principle but requires the whole tree-overlay to actually achieve that fan-out — the bench proves the per-node cost up to fan-out=500 (`PlumtreeFanOutBenchmark.broadcastAndDrain @ 500 = 7.25 μs`) which already covers the 21-peer case 24× over. So the per-node math works.
  However: the **whole-tree** propagation latency from leader to last edge for N=1M with `log(N)+1` eager fan-out is 20 hops. At the documented per-hop cost of 7.25 μs CPU + ~5 ms intra-region RTT, that is 100 ms intra-region propagation across 1M edges — and there is no bench, simulation, or measured number for whole-tree convergence at the documented cluster size. The "p99 propagation < 500 ms" target in `docs/performance.md:97` is not backed by a 1M-edge measurement.
- **Perf cost / impact:** Operators sizing for 1M edges expect the `< 500 ms` propagation target. The per-node cost is fine; the whole-tree convergence is unmeasured. Two failure modes: (a) tail amplification at 20 hops with p99 RTT=10ms gives p99 whole-tree = `10 ms × ceil(20 × hedging-loss-factor)` which can easily breach 500 ms. (b) lazy-peer GRAFT timeouts (`graftTimeoutTicks`) compound the tail: each missed eager-push adds a GRAFT round-trip.
- **Fix proposal:** Add `WholeTreeConvergenceBenchmark` or simulation under `configd-testkit` that builds an N-edge Plumtree overlay (using SimulatedNetwork for RTT) and measures broadcast → last-edge-applied latency for N ∈ {1k, 10k, 100k, 1M}. Output as a percentile sweep, not an avg. Until that exists, restate `docs/performance.md` propagation target as MODELED for N > 1k.

## P-029 — `RateLimiter.tryAcquire` still has no benchmark; "lock-free" claim in iter-1 P-009 remains unmeasured
- **Severity:** S3
- **Location:** `configd-control-plane-api/src/main/java/io/configd/api/RateLimiter.java` (P-009 carryover) and absence of `configd-testkit/src/main/java/io/configd/bench/RateLimiter*Benchmark.java`.
- **Category:** cache-contention, missing-bench
- **Evidence:** Glob `bench/RateLimiter*` returns nothing. Iter-1 P-009 fix proposal: "Add a `RateLimiterBenchmark` that measures `tryAcquire` at 1, 8, 64 threads." Not landed.
- **Perf cost / impact:** Without contention measurement we cannot say whether the unbounded CAS retry loop is a real cliff at N writer threads — see P-009 for the analysis. Persistence: this is the second iteration the missing-bench ask is unaddressed.
- **Fix proposal:** Author the bench. 30 lines of JMH; copy the `@Threads(8)` pattern from `HistogramBenchmark.recordContended`. Until then, strip the "lock-free" comment from `RateLimiter.java` to match honesty invariant.

## P-030 — `SubscriptionMatchBenchmark` covers up to 10k prefixes but `matchingNodes` allocates `new HashSet<>()` per call regardless of prefix count — that allocation is in the timed region
- **Severity:** S3
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/SubscriptionManager.java:115` and `configd-testkit/src/main/java/io/configd/bench/SubscriptionMatchBenchmark.java:84-87`
- **Category:** hot-path-alloc, lying-bench
- **Evidence:** `Set<NodeId> result = new HashSet<>();` per call, plus `result.addAll(entry.getValue())` for every matching prefix. `SubscriptionMatchBenchmark.matchingNodes` consumes the returned Set via Blackhole — so the bench measures linear-scan + HashSet alloc + addAll, conflating two concerns. The bench has no `-prof gc` flag in `docs/performance.md`. iter-1 perf-baseline cited 196 μs/op at 10k prefixes — that 196 μs includes the allocation, not just the algorithm.
- **Perf cost / impact:** Once D13 (radix-trie) lands, the algorithmic cost will drop but the per-call HashSet allocation will dominate the new fast path. Without -prof gc evidence, the post-D13 perf claim will repeat the iter-1 mistake (claiming algorithmic improvement that is mostly washed out by allocation cost).
- **Fix proposal:** Provide a `matchingNodes(String key, Set<NodeId> dst)` overload that fills a caller-provided Set. Add `-prof gc` to the bench. Pre-D13, the carry-over savings are modest (the linear scan dominates); post-D13 they are dominant.

## P-031 — Iter-1 closed P-001 by adding a JMH-log-dir but no CI gate ensures every perf-doc edit re-pins to a real artefact
- **Severity:** S3
- **Location:** `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `docs/performance.md`
- **Category:** doc-drift
- **Evidence:** Grep of CI workflows for `perf/results` returns the directory exists but no workflow asserts (a) every concrete number in `docs/performance.md` references a file under `perf/results/`, or (b) any commit that edits `docs/performance.md` table §3 also touches `perf/results/`. Without such a gate, the iter-1 closure of §8.1 is conventionally enforced, not mechanically enforced — exactly the failure mode that produced P-001 in the first place.
- **Perf cost / impact:** No latency cost. Documentation drift cost: the next change to a perf number can land without a corresponding artefact and the §8.1 violation re-opens silently.
- **Fix proposal:** Add a CI step (Bash) that greps `docs/performance.md` for ns/op | μs/op | ops/μs | ops/s patterns, verifies that the most recent commit touching `docs/performance.md` also touched a file under `perf/results/`, and fails otherwise. Or simpler: add a footer to every numeric row pointing to the specific JMH log file path.

## P-032 — `PlumtreeNode.broadcast` and `receiveEagerPush` enqueue per-peer `OutboundMessage.EagerPush` records carrying the same `byte[] payload` reference — fine, but the `Prune`/`IHave` messages allocate per-peer, with no batching
- **Severity:** S3
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java:117-128, 139-167`
- **Category:** hot-path-alloc
- **Evidence:**
  ```java
  for (NodeId peer : eagerPeers) outbox.add(new OutboundMessage.EagerPush(peer, id, payload));
  for (NodeId peer : lazyPeers)  outbox.add(new OutboundMessage.IHave(peer, id));
  ```
  Each iteration allocates one record; with eager+lazy together at 6×log(N)+log(N) = 7×log(N)+7 peers ≈ 154 per broadcast at N=1M, that is 154 record allocations per broadcast plus 154 LinkedList.Node allocations from the outbox (P-007 carryover compounds here).
- **Perf cost / impact:** At 1k commits/s × 154 outbound msgs = 154k record allocations/s + 154k LinkedList.Node/s = ~1k objects per ms. Per-record cost is ~24 B (NodeId ref + MessageId ref + payload ref) so ~3.7 MB/s of Plumtree-only allocation. ZGC ok, but the doc claim "near-zero allocation hot read path" only covers the read path — the broadcast path is unmeasured.
- **Fix proposal:** Compose with the P-007 fix: replace `outbox` (LinkedList) with `ArrayDeque<OutboundMessage>`. For broadcast, expose a `drainTo(Consumer<OutboundMessage>)` so callers can iterate without an intermediate Queue. For per-peer record allocation, hold pre-allocated `IHave` records keyed by `(peer, id)` only when the same id is broadcast to multiple peers — but the lazy `IHave` set already shares the `MessageId`, so the only allocation is the record header (24 B). This is the smallest of the Plumtree allocs; flag for tracking, not for urgent fix.

---

## Persistence inventory at end of iter-2

| Iter-1 ID | Status at iter-2 end | Persistence count |
|---|---|---|
| P-002 | OPEN | 1 |
| P-003 | OPEN | 1 |
| P-004 | OPEN (compounded by P-021) | 1 |
| P-005 | OPEN (D13 carry-over) | 1 |
| P-006 | OPEN | 1 |
| P-007 | OPEN | 1 |
| P-008 | OPEN (compounded by P-027) | 1 |
| P-009 | OPEN | 1 (also P-029) |
| P-010 | OPEN | 1 |
| P-011 | OPEN | 1 |
| P-012 | OPEN | 1 |
| P-013 | OPEN (compounded by P-023) | 1 |
| P-014 | OPEN | 1 |

Per §4.7 of the loop directive, items reaching persistence count = 2 trigger escalation. None are at 2 yet but **all 13 carryovers are at 1** — a third iteration without movement will move them all to escalation simultaneously. Recommend the next iter-2 fix dispatch lane batch P-007/P-010/P-014/P-018/P-020/P-026 (the trivial allocation/loop-cache fixes) into one in-process F-lane and the algorithmic ones (P-005 D13, P-011 wrap-aware bucketCount, P-012 cursor stripe, P-021 sum unit fix) into a subagent lane.

## Scope notes

- **Skipped (already covered by iter-1 disclaimers):** P-015 cross-region RTT, P-016 lognormal tail-amp model — both rows in `docs/performance.md` already carry "MODELED, NOT MEASURED".
- **Not investigated (out of scope per "perf, not security"):** TLS handshake cost on the auth path; key derivation cost in signing.
- **Not measurable from code review:** real cluster behaviour under load — needs the soak/burn/longevity scripts to actually run with a workload (the smoke result file documents `status=YELLOW (no workload wired)`). C4 (shadow harness) remains YELLOW per `docs/ga-review.md`; without it none of the §11 scorecard rows can be authoritatively closed.
