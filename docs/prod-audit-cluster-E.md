# Production Audit — Cluster E (Observability + Formal Methods)

**Phase:** 1 of 12 — GA Hardening Pass
**Scope:** `configd-observability/**`, `spec/**`, `docs/runbooks/**`
**Date:** 2026-04-17
**Auditor:** Principal observability engineer + formal-methods auditor
**ID range:** PA-5001 … PA-5999 (assigned sequentially as findings are filed)

---

## Coverage Accounting (read-before-filter)

| File | LoC read |
|------|----------|
| `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java` | 383 |
| `configd-observability/src/main/java/io/configd/observability/SloTracker.java` | 232 |
| `configd-observability/src/main/java/io/configd/observability/InvariantMonitor.java` | 237 |
| `configd-observability/src/main/java/io/configd/observability/ProductionSloDefinitions.java` | 34 |
| `configd-observability/src/main/java/io/configd/observability/BurnRateAlertEvaluator.java` | 69 |
| `configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java` | 80 |
| `configd-observability/src/main/java/io/configd/observability/PropagationLivenessMonitor.java` | 63 |
| **observability Java subtotal** | **1,098** |
| `spec/ConsensusSpec.tla` | 526 |
| `spec/ConsensusSpec.cfg` | 49 |
| `spec/tlc-results.md` | 83 |
| **spec subtotal** | **658** |
| `docs/runbooks/cert-rotation.md` | 67 |
| `docs/runbooks/edge-catchup-storm.md` | 55 |
| `docs/runbooks/leader-stuck.md` | 47 |
| `docs/runbooks/poison-config.md` | 57 |
| `docs/runbooks/reconfiguration-rollback.md` | 52 |
| `docs/runbooks/region-loss.md` | 43 |
| `docs/runbooks/version-gap.md` | 61 |
| `docs/runbooks/write-freeze.md` | 62 |
| **runbook subtotal** | **444** |
| `docs/consistency-contract.md` | 237 |
| `docs/verification/inventory.md` (§1–8) | 178 |
| `docs/verification/final-report.md` (skimmed §V1–§V8 + remediation) | 424 |
| `docs/verification/findings/F-0073.md` | 57 |
| **ref-doc subtotal** | **896** |
| **TOTAL READ** | **3,096 LoC** |

`ops/` directory does not exist. No alert YAML, no Grafana dashboard JSON, no Prometheus rule file in the repo. All "alert" references live inside runbook prose (PA-5004).

---

## Residuals Investigated (R-03, R-14, R-15)

### R-03: `EdgePropagationLiveness` commented out

**Status:** still commented out at `spec/ConsensusSpec.cfg:42-44`. Reason is explained in both places:

- `spec/ConsensusSpec.tla:261-266` (comment in spec): "TLC finds a spurious liveness violation at model bounds (MaxTerm=3 exhausted, all nodes voted for themselves in distinct candidacies, no further leader election possible). This is a well-known bounded model checking limitation — the Raft protocol guarantees liveness only under eventual message delivery and unbounded terms."
- `docs/verification/final-report.md:95-99`: "Residual spec gap. Apalache with symbolic bounds would close the gap; not attempted this round."

**Enablement path:**
1. Move to Apalache symbolic bound check (deterministic, not state-enumerative) — the tool of choice named in the spec comment.
2. Or: enlarge MaxTerm / introduce fairness constraints that prohibit all nodes from exhausting their vote self-quota at once.
3. Or: add a stronger `WF_vars(Next)` decomposition so that `EdgeApply` gets its own strong-fairness clause.

Filed as PA-5020 (spec hygiene), PA-5021 (Apalache follow-up).

### R-14: SPEC-GAP-3 (VersionMonotonicity) and SPEC-GAP-4 (ReadIndex)

**SPEC-GAP-3 — VersionMonotonicity:** `docs/verification/inventory.md:57` says it is *not modeled in TLA+*. **This is outdated.** `spec/ConsensusSpec.tla:185-186` now defines:

```
VersionMonotonicity ==
    \A e \in Nodes: edgeVersion[e] <= commitIndex[e]
```

and it is listed in `ConsensusSpec.cfg:31` INVARIANTS. F-V2-01 (referenced in the spec header) added it. **SPEC-GAP-3 should be closed in the inventory.** Filed as PA-5022 (doc drift).

**SPEC-GAP-4 — ReadIndex:** confirmed **still open**. There is zero mention of ReadIndex in `ConsensusSpec.tla`. The linearizable read protocol is implemented in `RaftNode.readIndex() / whenReadReady()` (per F-0009 fix) but has no TLA+ counterpart. An AppendEntries-heartbeat-confirmation action + a `pendingReads` variable are missing. Filed as PA-5023.

### R-15: SPEC-GAP-1 — `NoStaleOverwrite` byte-identical to `StateMachineSafety`

**Status:** already remediated in `ConsensusSpec.tla:188-195` — the invariant has been removed and a deletion comment left in place explaining it was identical to `StateMachineSafety` (F-V2-01). `ConsensusSpec.cfg` no longer lists it; the doc drift is only in `spec/tlc-results.md:17` and `docs/verification/inventory.md:41,55` which still mention it. Filed as PA-5024 (trivial doc cleanup — both files still list NoStaleOverwrite).

---

## Findings

### PA-5001: BurnRateAlertEvaluator is wired but never invoked — every SLO breach goes undetected
- **Severity:** S0
- **Location:** `configd-server/src/main/java/io/configd/server/ConfigdServer.java:301` (constructed) and no call site for `.evaluate()` anywhere in production code (confirmed via `grep -rn "burnRateAlertEvaluator\|\.evaluate()" src/main`).
- **Category:** observability
- **Evidence:** Only two references to the constructed instance exist: the `new` call and that's it. In the periodic tick (`ConfigdServer.java:500-520`) `propagationMonitor.checkAll()` is called but `burnRateAlertEvaluator.evaluate()` is not. Tests call it, production does not.
- **Impact:** None of the seven SLOs registered by `ProductionSloDefinitions.register()` can ever produce an alert at runtime. PROMPT.md §0.1 targets (write p99 < 150 ms, edge read p99 < 1 ms, propagation p99 < 500 ms, availability 99.999% / 99.9999%) are defined but unmonitored. The entire cluster-E observability surface is dead code after construction.
- **Fix direction:** Schedule `burnRateAlertEvaluator.evaluate()` on a dedicated executor at ~60 s cadence per the comment in `BurnRateAlertEvaluator.evaluate()`. Attach at least one real `AlertSink` (e.g., a sink that emits to a structured log at CRITICAL + increments a Prometheus counter). Add a regression test that asserts the evaluator runs at least once between test start and SLO compliance window close.
- **Owner:** sre

### PA-5002: SloTracker is never populated — compliance is vacuously 1.0 for every SLO
- **Severity:** S0
- **Location:** `configd-observability/src/main/java/io/configd/observability/SloTracker.java:74-86` (`recordSuccess` / `recordFailure`); search for call sites under `src/main` returns zero hits — only test files call these methods.
- **Category:** observability
- **Evidence:** `grep -rn "sloTracker\." src/main --include='*.java'` matches only the three declarations in `ConfigdServer.java:299-301`. No write/read/propagation path ever calls `recordSuccess` / `recordFailure`. `SloTracker.compliance()` returns 1.0 for empty windows by design (line 116-118). Therefore `BurnRateAlertEvaluator.evaluate()` (even if wired per PA-5001) would see zero breaching SLOs forever.
- **Impact:** Every single SLO registered by `ProductionSloDefinitions` is permanently "healthy" — control plane availability 99.999%, edge read availability 99.9999%, write commit p99, edge read p99/p999, propagation p99, write throughput baseline. Double-failure with PA-5001: even if the evaluator ran, the tracker has no data.
- **Fix direction:** Wire `SloTracker.recordSuccess()` / `recordFailure()` at the boundary of each SLO-tracked operation: `ConfigWriteService.commit()` result (latency threshold check → success/failure), `HttpApiServer` read handler (latency threshold), `DeltaApplier.apply()` (propagation arrival time vs. commit HLC), and a periodic probe that samples availability. Add a property test that records 1,000 synthetic events and asserts `compliance()` and `BurnRateAlertEvaluator.evaluate()` converge correctly.
- **Owner:** sre

### PA-5003: SLO trackers are ratio-of-events, not latency-percentile — the seven "p99" SLOs cannot be expressed by this tracker
- **Severity:** S0
- **Location:** `configd-observability/src/main/java/io/configd/observability/SloTracker.java:98-120` (`compliance` counts `success/total`), and `ProductionSloDefinitions.java:14-23` which defines `"write.commit.latency.p99"`, `"edge.read.latency.p99"`, `"edge.read.latency.p999"`, `"propagation.delay.p99"` as trackers.
- **Category:** observability / correctness
- **Evidence:** `SloTracker` has a Boolean success/failure model. A "p99 < 150 ms" SLO has a two-step SLI: (a) measure the latency histogram, (b) threshold at the budget limit (150 ms), (c) declare the event a success iff latency <= threshold. Step (a)-(b) are not present anywhere — there is no bridge between `MetricsRegistry.histogram(...)` and `SloTracker.recordSuccess/Failure`. `ProductionSloDefinitions` just names seven SLOs; the latency contract is encoded only in the string name.
- **Impact:** Even once PA-5002 is fixed, the caller has no canonical place to decide "did this write meet the 150 ms budget?". Different call sites will pick different thresholds or will record every write as success, making the SLO uniformly green regardless of tail.
- **Fix direction:** Either (a) add a `SloTracker.recordLatency(name, latencyNanos, thresholdNanos)` helper that maps to success/failure, or (b) change `ProductionSloDefinitions` to additionally register per-SLO thresholds that `SloTracker` stores, and expose `SloTracker.recordLatency(name, latencyNanos)` that internally compares against the threshold. Document PROMPT.md §0.1 thresholds as the canonical source. Add tests that exercise a breaching latency stream and verify the burn-rate critical path.
- **Owner:** sre

### PA-5004: No Prometheus alert rules / Grafana dashboards checked into repo — every runbook "Alert:" line is aspirational
- **Severity:** S0
- **Location:** repo root — `ops/` does not exist; no `.yaml` / `.yml` / `.rules` / `alerts*` / `dashboard*.json` files in repo. Runbook detection lines (e.g., `docs/runbooks/leader-stuck.md:4`) name alerts like `configd_raft_role` without any source-of-truth rule file.
- **Category:** ops
- **Evidence:** `find / -name "*.rules"`, `find . -iname "alert*.yaml"`, `find . -iname "dashboard*.json"` all empty. `docs/runbooks/*.md` reference eight distinct Prometheus-style alert expressions; none are persisted anywhere runnable.
- **Impact:** When operator follows a runbook, they cannot verify the alert exists or fires on the documented expression. The alert ↔ runbook relationship is one-way: runbook names an alert, alert is undefined.
- **Fix direction:** Add `ops/prometheus/alerts.yaml` with one `PrometheusRule` per runbook Alert line, and `ops/grafana/dashboards/*.json` for each Dashboard panel referenced. Gate a CI check that every `Alert:` in `docs/runbooks/*.md` has a corresponding alert name in `ops/prometheus/alerts.yaml`. See the coverage matrix at the end of this report.
- **Owner:** sre

### PA-5005: Runbook alert metric names do not exist in code — the entire runbook set monitors ghosts
- **Severity:** S0
- **Location:** `docs/runbooks/*.md` alert expressions reference `configd_raft_role`, `configd_raft_current_term`, `configd_edge_staleness_seconds`, `configd_transport_tls_cert_expiry_days`, `configd_raft_quorum_reachable`, `configd_raft_log_replication_lag`, `configd_raft_config_change_pending`, `configd_distribution_fanout_queue_depth`, `configd_edge_poison_pill_detected_total`, `configd_raft_heartbeat_sent_total`, `configd_raft_election_started_total`, `configd_raft_match_index`, `configd_store_current_version`. None of these appear in any `.java` file (grep across full repo).
- **Category:** observability / docs
- **Evidence:** the only production `counter(...)` / `histogram(...)` / `gauge(...)` names registered anywhere in `src/main` are: `propagation.lag.violation` (`PropagationLivenessMonitor.java:26,52`) and `invariant.violation.<name>` (`InvariantMonitor.java:225`, with `monotonic_read` and `staleness_bound` the only two named constants). Everything in the runbooks is fiction relative to the actual code.
- **Impact:** Every `Alert:` line in every runbook points to a metric Prometheus will never scrape because the code never emits it. This is the worst observability failure mode: the runbooks make the operator feel coverage exists.
- **Fix direction:** Either (a) instrument RaftNode, TcpRaftTransport, StalenessTracker, PlumtreeNode, PoisonPillDetector, ConfigStateMachine to emit every metric named in the runbooks, or (b) rewrite the runbooks to match the three metrics that actually exist — `propagation.lag.violation`, `invariant.violation.monotonic_read`, `invariant.violation.staleness_bound` — and file a separate backlog for the missing instrumentation. Strongly prefer (a) since PROMPT.md §0.1 requires the SLOs.
- **Owner:** observability

### PA-5006: Consistency contract invariants INV-V1, INV-V2, INV-W1, INV-W2, INV-RYW1, INV-L1 have no runtime assertion bridge
- **Severity:** S1
- **Location:** `configd-observability/src/main/java/io/configd/observability/InvariantMonitor.java:106-113` defines public constants only for `MONOTONIC_READ` (INV-M1) and `STALENESS_BOUND` (INV-S1).
- **Category:** observability / correctness
- **Evidence:** `docs/consistency-contract.md:208-220` (§8 Runtime Assertions) lists six invariants: INV-V1, INV-V2, INV-M1, INV-W1, INV-S1, INV-L1. Only INV-M1 + INV-S1 are implemented in `InvariantMonitor` (per F-0073 remediation). INV-V1 (`assert_sequence_monotonic`), INV-V2 (`assert_sequence_gap_free`), INV-W1 (`assert_per_key_order`), INV-L1 (`assert_leader_completeness`) are documented as existing runtime assertions but there is no helper method, no metric name constant, and no call site in `src/main` grep. `F-0073` closure text mentions only the two data-plane invariants were fixed.
- **Impact:** Consistency-contract §8 promises every formal invariant has a runtime assertion with a metric counter. Four of the six land in "documented, not implemented". If a Raft apply thread receives a gap in sequence numbers (INV-V2 — "data corruption, halt apply"), the documented behavior cannot fire because the helper does not exist.
- **Fix direction:** Add four companion helpers to `InvariantMonitor`: `assertSequenceMonotonic(newSeq, lastSeq)`, `assertSequenceGapFree(newSeq, lastSeq)`, `assertPerKeyOrder(key, newVersion, existingVersion)`, `assertLeaderCompleteness(newLeaderLog, committedEntries)`. Wire into `ConfigStateMachine.apply`, `RaftLog.append`, and `RaftNode.becomeLeader`. Follow the pattern established by F-0073 (public name constant, `configd.invariant.violation.<name>` metric, production test + test-mode throw).
- **Owner:** observability

### PA-5007: Burn-rate evaluator implements single-window threshold, not Google SRE multi-window/multi-burn-rate
- **Severity:** S1
- **Location:** `configd-observability/src/main/java/io/configd/observability/BurnRateAlertEvaluator.java:47-61`
- **Category:** correctness
- **Evidence:** Implementation computes one burn rate off the single `SloStatus` snapshot returned by `tracker.snapshot()` and bins into `Critical (>= 14.4)` / `Warning (>= 1.0)`. The Google SRE workbook ("Alerting on SLOs", chapter 5) requires **two parallel windows** (short + long) per severity tier and AND-combines them to control false-positive / false-negative rates — e.g. for a 30 d SLO: *fast-burn* fires when `burn_rate(1h) > 14.4 AND burn_rate(5m) > 14.4`; *slow-burn* fires when `burn_rate(6h) > 6 AND burn_rate(30m) > 6`, and separately `burn_rate(3d) > 1 AND burn_rate(6h) > 1`. Our implementation only reads the single window declared at `SloTracker.defineSlo(..., window)` and has no second (short) window.
- **Impact:** Current detector will either flap (short bursts over 14.4x instantly critical, even transient) or silently miss slower drifts because the 30-day window in `ProductionSloDefinitions.java:26-29` for availability SLOs averages away the signal. Neither the "alert only on real breaches" nor the "alert quickly" criteria from SRE Chapter 5 are met.
- **Fix direction:** Rewrite `BurnRateAlertEvaluator` to support per-SLO `{shortWindow, longWindow, burnRateThreshold, severity}` tuples; evaluate each tuple and AND-combine the two windows. Per PROMPT.md §0.1 targets, adopt the canonical 4-tuple set from the SRE workbook table. Backing storage in `SloTracker` must be able to answer "compliance over last X minutes" for arbitrary X — current design computes compliance over the *single* window registered at definition time; the deque-eviction model (line 103) must be changed to either keep events indefinitely (bounded by the longest window) or to maintain buckets per time-window.
- **Owner:** sre

### PA-5008: DefaultHistogram has unbounded `min/max` global across all time — violates "sliding window" contract stated in Javadoc
- **Severity:** S1
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:257-259,330-339` (`minValue`/`maxValue` volatile fields; `min()`/`max()` methods)
- **Category:** correctness / observability
- **Evidence:** Class Javadoc (line 30-32) describes the histogram as "an approximate sliding window — old values are overwritten as new values arrive". But `minValue` / `maxValue` are not scoped to the ring-buffer window — they are updated on every `record()` and never decay (line 283-285). Once a single large spike is recorded, `max()` returns that value forever. Comment on line 258 admits this: "Tracks min/max across all time (not just the ring buffer window)." This contradicts the class Javadoc and will silently confuse any operator reading `max()`.
- **Impact:** The exporter does not surface min/max directly (PrometheusExporter only emits percentiles) so no Prometheus metric is affected, but the `Histogram.min() / max()` API is exposed and any caller (tests, future users, dashboards) will read incorrect values. The contract drift is a trap for the first engineer who trusts the class Javadoc.
- **Fix direction:** Either (a) document explicitly that min/max are all-time and recommend percentile(0.0)/percentile(1.0) for window-scoped values, or (b) recompute min/max from the ring buffer snapshot on each read (cost already comparable to `percentile()` — an `Arrays.sort` on the snapshot is already O(n log n)). Align Javadoc with the chosen semantics.
- **Owner:** observability

### PA-5009: DefaultHistogram `record()` is not lock-free — spin-CAS on min/max uses `synchronized` fallback
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:309-323` (`compareAndSetMin`, `compareAndSetMax` are `synchronized`; called from `record()` on every invocation).
- **Category:** observability / correctness
- **Evidence:** Class Javadoc line 241-243 claims "Thread-safe via synchronized write and volatile/snapshot reads". The `record()` path calls `updateMin` / `updateMax`, which spin on `compareAndSetMin` / `compareAndSetMax`, which take the **monitor on the enclosing DefaultHistogram object**. Every `record()` that discovers a new min or max contends the monitor. Worse, because the spin loop reads a plain volatile (line 290, 298) then re-enters the synchronized block, under contention it degrades to serialized writes. The comment "only contended when a new min is discovered, which is rare after warmup" is plausible only for steady-state; during warm-up, cold edges, or latency-regression events (precisely when histograms matter most), min/max contention is maximal.
- **Impact:** Claim of "high-throughput concurrent increments" (Javadoc line 23-24) is weaker than stated for the histogram path. Under a p99/p999 tail regression the spike events will contend on the same monitor. Allocation-wise the `synchronized` is alloc-free but the latency spikes are measurable. Not the zero-lock story PROMPT.md §5 demands.
- **Fix direction:** Replace the `synchronized` CAS fallback with `VarHandle`-based CAS on the plain `long` fields (Javadoc already mentions VarHandle was considered "more ceremony than warranted"). If the observation from PA-5008 is adopted (compute min/max from ring snapshot on demand), the CAS goes away entirely.
- **Owner:** observability

### PA-5010: DefaultHistogram ring-buffer write is racy — `buffer[idx % capacity] = value` has no memory fence
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:276-280`
- **Category:** correctness
- **Evidence:** The cursor is atomically incremented (`cursor.getAndIncrement()`) but the slot write `buffer[(int)(idx % capacity)] = value` is a plain store. The percentile() reader on a different thread copies `buffer` via `System.arraycopy` after snapshotting `cursor.get()`; there is no happens-before edge between the writer's plain store and the reader's plain read of the same slot. Under the JMM, the reader may see a stale or torn value. For long writes on 64-bit JVMs tearing is typically not observed, but the spec does not guarantee it without volatile.
- **Impact:** `percentile()` may return a value from a prior ring generation or — on 32-bit JVMs — a torn long. Under model-check-style adversarial timing, p99/p999 can report spurious values. Impact limited because all values are nanosecond latencies and outliers get lost in sort.
- **Fix direction:** Use `VarHandle.setOpaque` / `setRelease` on the slot write and `getOpaque` / `getAcquire` on the read. Alternatively, use `AtomicLongArray` for the buffer. The cost is negligible on hot paths because the slot store is already dominated by the atomic cursor CAS.
- **Owner:** observability

### PA-5011: DefaultHistogram `percentile()` snapshot is non-atomic with concurrent writers — wrap boundary yields garbled sample
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:349-381`
- **Category:** correctness
- **Evidence:** Lines 361-372 snapshot the ring by reading `cursorVal = cursor.get()` and then `System.arraycopy`-ing two half-buffers. Between the read of `cursor` and the two arraycopies, a concurrent writer can advance `cursor` by up to `capacity` entries and wrap around, so some slots are overwritten while the snapshot is in progress. No memory barrier prevents the mid-snapshot tearing. The snapshot is approximate by design (see Javadoc: "approximate sliding window"), but the sort-and-percentile step mixes values from two different logical ring generations, producing a percentile that samples from a union that is not a valid window.
- **Impact:** At 100k writes/sec into a 4096-entry ring, the whole buffer rolls over in ~40 ms. A reader that samples during a scrape (every 15 s) has a non-zero probability of straddling a wrap. Percentiles may be biased toward whichever half was last written. Primarily S2 because the error is statistical, not a correctness breach.
- **Fix direction:** Use a generational snapshot: writer CAS a generation counter, reader reads generation, copies, reads generation again; if different, retry. Or switch to HdrHistogram (which `docs/verification/inventory.md` names as an external dep — not actually imported, so this is also a cleanup of that drift).
- **Owner:** observability

### PA-5012: MetricsRegistry has no cardinality guard — any caller can register unbounded gauges/histograms/counters
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:71-77, 93-100, 145-151`
- **Category:** observability
- **Evidence:** `counter(name)`, `gauge(name, supplier)`, `histogram(name)` all accept arbitrary `String name` and store in `ConcurrentHashMap`. There is no whitelist, no max-cardinality check, no per-prefix budget. A caller who passes the config-key as part of the metric name (e.g. `counter("configd.read." + key)`) would grow the registry unboundedly, and PrometheusExporter would emit one line per key — the canonical cardinality bomb in Prometheus. No tests exist that assert cardinality stays bounded.
- **Impact:** Low today because the caller surface is tiny (PA-5005) but the design permits the failure mode. A future edit that adds `metrics.counter("edge.reads." + key)` would DoS the scrape endpoint and exhaust the Prometheus server's TSDB.
- **Fix direction:** Add a configurable `maxMetrics` cap (default 10k). On overflow, log once at WARN and drop the new metric. Add a unit test that registers 10,001 counters and asserts the 10,001st is rejected. Consider forbidding `.` / `-` / `:` inside the name for dynamic-component detection (Prometheus best practice is labels, not name-embedded dimensions, but MetricsRegistry has no labels at all — see PA-5013).
- **Owner:** observability

### PA-5013: MetricsRegistry has no label / tag dimension — every Prometheus metric is monodim, forcing cardinality into the name
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:71-151` (no labels anywhere); `PrometheusExporter.java:36-50` emits single-series-per-name output.
- **Category:** observability
- **Evidence:** Counter/Gauge/Histogram all key off a single `String name`. No `Map<String,String> tags` surface. Prometheus best practice is to expose dimensions via labels (e.g. `configd_raft_role{node_id="n1",group="default"}`). Because the registry has no labels, any per-node or per-group metric must encode the dimension into the name (which is what the Javadoc on line 19-20 implies the pragmatic-use answer is: "Designed to be replaced with Micrometer in production deployments"). That future migration is the design escape hatch — but the migration has not happened and cluster-E ships in GA.
- **Impact:** The runbook-expected expressions like `configd_raft_role` (with implicit {node_id=...}) cannot be surfaced. Aggregation in Prometheus (by region, by group, by percentile tier) is impossible with the current exporter.
- **Fix direction:** Either (a) complete the Micrometer migration the Javadoc anticipates, or (b) add a second argument to each registration API: `counter(String name, Map<String,String> tags)`, and have `PrometheusExporter` emit `name{k1="v1",k2="v2"} value` lines. Sanitize label values the same way names are sanitized (line 67-79).
- **Owner:** observability

### PA-5014: SloTracker compliance() + statusFor() double-eviction race — concurrent callers can mutate the deque during iteration
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/SloTracker.java:98-120, 205-227, 198-203`
- **Category:** correctness
- **Evidence:** `evict()` calls `deque.pollFirst()` (destructive) inside `compliance()` and `statusFor()`. These are invoked on the caller thread concurrently with `recordEvent()` (which does `deque.addLast()`) and with other readers. `ConcurrentLinkedDeque` is individually safe per-op, but the for-each iteration in lines 107-114 and 214-221 snapshots a weakly-consistent iterator; between `evict()` and the iteration, another thread can have already evicted further. More serious: `compliance()` and `statusFor()` both `evict()` then iterate — two concurrent readers both do the mutation, so the deque size is double-reduced if both land in the eviction loop simultaneously. ConcurrentLinkedDeque's poll is atomic but the order-dependent "evict all before cutoff" semantics are not.
- **Impact:** Compliance values can be under/over-counted on reads. Not safety-critical but the SLO gauge may jitter by a few events per scrape under high concurrency. Worse, under pathological read contention, events can be evicted that a later reader would have counted — compliance drifts upward from actual.
- **Fix direction:** Make `evict()` idempotent under concurrent callers (acceptable since it only removes stale entries) but add a test that asserts no event recorded inside the window is lost. Or serialize eviction under a single re-entrant lock per-SLO. Or switch to a generational ring buffer keyed on time-bucket (dovetails with PA-5007).
- **Owner:** observability

### PA-5015: PrometheusExporter emits `summary` for histograms but Prometheus `summary` cannot be aggregated across instances
- **Severity:** S1
- **Location:** `configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java:42-51`
- **Category:** observability / correctness
- **Evidence:** Line 43 emits `# TYPE <name> summary`. The Prometheus text format supports `summary` (client-side pre-computed percentiles) and `histogram` (buckets, aggregatable). Per PromQL semantics, summary percentiles **cannot be aggregated across series** (you cannot average two p99 values to get a global p99). For a multi-region, multi-node system where the runbooks (e.g. `edge-catchup-storm.md`, `version-gap.md`) and PROMPT.md §0.1 call for global p99, summary emission is architecturally wrong.
- **Impact:** Global p99 SLO (write commit latency p99 < 150ms cross-region; propagation p99 < 500ms global) cannot be computed by Prometheus queries. Each instance exposes its own p99 and there is no math to aggregate them. Any dashboard panel that plots "p99 across the fleet" will silently show per-instance numbers, not fleet numbers.
- **Fix direction:** Change exporter to emit `# TYPE <name> histogram` with `_bucket{le="..."} ` lines. This requires `DefaultHistogram` to track bucket counts instead of a ring buffer (or dual-mode). Alternatively, adopt HdrHistogram and emit log-scale buckets. The migration to Micrometer also solves this.
- **Owner:** observability

### PA-5016: PrometheusExporter calls `registry.histogram(name)` on scrape, re-invoking `computeIfAbsent` — mutates the registry on read
- **Severity:** S3
- **Location:** `configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java:45`
- **Category:** correctness
- **Evidence:** Scrape path does `MetricsRegistry.Histogram hist = registry.histogram(name);` where `histogram(name)` calls `computeIfAbsent` and *creates* a histogram with default capacity if one did not exist. It always exists here because the snapshot enumerated it, so no race; but the design smell is that scraping is a read-only operation and should not write through to the registry. Future racy change: if `snapshot()` includes a histogram, then a concurrent `clear()`-like operation removes it before `export()` re-looks up, a brand new empty histogram is inserted.
- **Impact:** Cosmetic today, but the API shape invites bugs. An extra allocation of an empty 4096-long buffer is possible per scrape in the worst case.
- **Fix direction:** Replace `registry.histogram(name)` with a read-only getter `registry.histogramIfPresent(name)` that returns `Optional<Histogram>`. Also revisit storing percentiles in the `MetricValue` record (PA-5008 and PA-5015 recommend rebaselining histogram snapshotting anyway) so the exporter reads only the snapshot.
- **Owner:** observability

### PA-5017: PropagationLivenessMonitor has no timeout semantics — "eventually" is reduced to a single lag-count threshold
- **Severity:** S1
- **Location:** `configd-observability/src/main/java/io/configd/observability/PropagationLivenessMonitor.java:22,45-56`
- **Category:** correctness / observability
- **Evidence:** Monitor definition comment (line 8-13) says it is the runtime counterpart of TLA+ LIVE-1 `EdgePropagationLiveness` — "every committed write eventually reaches every live edge". Implementation fires a violation iff `lag > maxLagEntries`. There is no time bound, no first-seen timestamp, no "alert only if stuck for > N seconds". An edge that is behind by `maxLagEntries + 1` for one tick (perfectly normal during burst) fires the violation counter, whereas an edge stuck at `maxLagEntries` forever never fires.
- **Impact:** Two failure modes: (a) false positive during bursts — the alert counter climbs every tick during normal catch-up; (b) false negative for a bounded-but-stuck edge. Neither gives the operator the signal PROMPT.md §0.1 wants (propagation p99 < 500ms). No way to distinguish "temporarily behind" from "liveness violation".
- **Fix direction:** Record a `firstSeenBehindNanos[edgeId]` timestamp; clear it on catch-up. Fire a violation only if `(now - firstSeenBehind) > maxLagDurationNanos`. Expose a second gauge `propagation.lag.max_edge_seconds` for dashboard use. Align the threshold semantics with `PROMPT.md §0.1` (500 ms) — the unit should be *time*, not *entries*.
- **Owner:** observability

### PA-5018: No tracing / OpenTelemetry integration anywhere in the repo
- **Severity:** S2
- **Location:** entire repo — `grep -rn "opentelemetry\|OpenTelemetry\|io.opentelemetry\|Tracer"` in `src/main` returns zero files (only PROMPT.md, performance.md and certain ADRs reference it aspirationally).
- **Category:** observability
- **Evidence:** No OTel SDK imports, no `Span`, no `TraceContext`, no W3C-traceparent propagation in `HttpApiServer` or `TcpRaftTransport`. The transport does carry a `FrameCodec`, which could propagate a traceparent; it does not.
- **Impact:** The request lifecycle from HTTP write → Raft propose → commit → fan-out → edge apply → read cannot be traced end-to-end. Incident response is degraded — operators cannot attribute tail latency to a specific hop.
- **Fix direction:** Add OTel autoconfig (`io.opentelemetry:opentelemetry-sdk`, `opentelemetry-exporter-otlp`), install a global tracer, inject W3C traceparent at the HTTP ingress and at the Raft frame boundary, record spans for `ConfigWriteService.put`, `RaftNode.propose`, `DeltaApplier.apply`, and `LocalConfigStore.get`. Document opt-out via env var.
- **Owner:** observability

### PA-5019: No structured / JSON logging, no rate limiting, no PII scrubbing in observability module
- **Severity:** S2
- **Location:** `configd-observability/src/main/java/io/configd/observability/**` has no logger imports; `InvariantMonitor.java:133` embeds the **raw config key** into a stringified assertion message.
- **Category:** observability / security
- **Evidence:** `grep -rn "Logger\|LoggerFactory\|System.Logger\|slf4j" configd-observability/src/main` → zero hits. Errors go to `System.err.println(...)` in `ConfigdServer.java:518`. No JSON layout, no MDC, no rate-limited logger. `InvariantMonitor.assertMonotonicRead` builds `"monotonic read violated: key=" + key + ...`. The config key can be a customer-supplied string up to 1 KB — including secrets or PII if the caller violated the non-goal in PROMPT.md §0.2 (secrets manager).
- **Impact:** (a) No structured logs — SRE cannot query by invariant name, by node, by request-id; (b) a single runaway invariant violation at apply-time will spam stderr without rate limiting, filling the console buffer and masking other signals; (c) if a customer ever writes a secret-shaped value as a key, the key will be captured in the violation log and potentially shipped to a log aggregator.
- **Fix direction:** Standardize on `java.lang.System.Logger` (JDK 25-native, no external dep) with a JSON-producing `System.LoggerFinder` implementation. Add a 60-events-per-minute rate limiter on invariant-violation logs (`LongAdder` + timestamp-bucket gate). Replace the raw key with a hash (`SHA-256 prefix hex[0:8]`) in the log message; keep the raw key in a debug-only MDC field gated on a config flag.
- **Owner:** observability

### PA-5020: `EdgePropagationLiveness` remains commented out in `ConsensusSpec.cfg` — liveness is not model-checked at all
- **Severity:** S3
- **Location:** `spec/ConsensusSpec.cfg:42-44` (commented block) and `spec/ConsensusSpec.tla:261-270` (definition + explanatory comment).
- **Category:** docs / correctness
- **Evidence:** The block `\* SPECIFICATION Spec / \* PROPERTIES / \* EdgePropagationLiveness` is commented out; the comment explains TLC finds a spurious violation at model bounds. SPEC-GAP-2 (`docs/verification/inventory.md:56`) still open.
- **Impact:** Liveness is never verified in CI. The runtime counterpart (`PropagationLivenessMonitor`) is independently wired (see PA-5017 for its defects). A protocol-level regression that broke eventual delivery would pass both TLC and build.
- **Fix direction:** Pursue Apalache symbolic check (named in `docs/verification/final-report.md:424` as carry-forward) or enlarge MaxTerm enough for fair scheduling to reach. Near-term: add a smaller fairness-only spec in a separate `LivenessSpec.tla` that focuses on `EdgeApply` under WF.
- **Owner:** formal-methods

### PA-5021: Apalache symbolic check for `EdgePropagationLiveness` carried forward but never scheduled
- **Severity:** S3
- **Location:** `docs/verification/final-report.md:424` (item 4 "carry-forward").
- **Category:** formal-methods / docs
- **Evidence:** Carry-forward item has lived across two verification rounds without a deliverable commit.
- **Impact:** SPEC-GAP-2 persists. Non-blocker for GA if the runtime monitor is sound (it is not — see PA-5017).
- **Fix direction:** File a concrete task (install Apalache container, translate WF_vars, add target in `pom.xml` profile `verify:liveness`). Gate a weekly CI cron rather than per-commit.
- **Owner:** formal-methods

### PA-5022: SPEC-GAP-3 (`VersionMonotonicity not modeled in TLA+`) is stale — now modeled at `ConsensusSpec.tla:185-186`
- **Severity:** S3
- **Location:** `docs/verification/inventory.md:57` (gap table entry still lists "Medium: not modeled in TLA+").
- **Category:** docs
- **Evidence:** `ConsensusSpec.tla:185-186` defines `VersionMonotonicity` and `ConsensusSpec.cfg:31` lists it in INVARIANTS. TLC run on 2026-04-16 (`docs/verification/final-report.md:88-91`) confirms it passes.
- **Impact:** Doc drift only — any reader following the inventory file thinks coverage is worse than it is.
- **Fix direction:** Update `docs/verification/inventory.md` row 57 to mark SPEC-GAP-3 as **closed** with a reference to the F-V2-01 commit. Add a "History" column if other gaps have similar closures.
- **Owner:** docs

### PA-5023: SPEC-GAP-4 (ReadIndex not modeled in TLA+) — confirmed still open
- **Severity:** S2
- **Location:** `spec/ConsensusSpec.tla` (no ReadIndex action); `docs/verification/inventory.md:58`.
- **Category:** formal-methods
- **Evidence:** Zero occurrences of `ReadIndex` / `readIndex` / `pendingReads` / `isReadReady` in `ConsensusSpec.tla`. F-0009 (linearizable read) was fixed in Java only. No TLC action models the heartbeat-confirm-then-apply semantics that makes ReadIndex linearizable.
- **Impact:** Linearizability of the read protocol is not formally verified. F-0009 and FIND-0002 showed two distinct implementation bugs where the code called the protocol but did not await confirmation — exactly the class of bugs TLA+ would catch on the action graph.
- **Fix direction:** Add a `ReadIndexState` variable (per-node pending-reads map), a `ClientReadRequest(n, readId)` action, a `ConfirmReadIndex(n)` action that fires after heartbeat quorum, and a `ResolveRead(n, readId)` action gated on `applyIndex >= storedIndex`. Invariant: every resolved read was preceded by a confirm with a heartbeat quorum in the leader's term. Add to `SafetyInvariants`.
- **Owner:** formal-methods

### PA-5024: SPEC-GAP-1 / R-15 residual doc drift — `tlc-results.md` and `inventory.md` still list removed `NoStaleOverwrite`
- **Severity:** S3
- **Location:** `spec/tlc-results.md:17,37`; `docs/verification/inventory.md:41,55`.
- **Category:** docs
- **Evidence:** `ConsensusSpec.cfg` and `ConsensusSpec.tla` removed `NoStaleOverwrite` (line 188-195 of TLA file has the removal comment). Both summary files still carry the old row.
- **Impact:** Trivial doc cleanup. Any reader reconciling the code and docs will be briefly confused.
- **Fix direction:** Delete the `NoStaleOverwrite` row from both files; append a short "History" note that it was redundant with `StateMachineSafety` and removed in F-V2-01.
- **Owner:** docs

### PA-5025: Counter increment IS alloc-free, but InvariantMonitor adds a `ConcurrentHashMap.computeIfAbsent` per violation → measurable alloc on hot failure path
- **Severity:** S3
- **Location:** `configd-observability/src/main/java/io/configd/observability/InvariantMonitor.java:224-225`
- **Category:** observability / performance
- **Evidence:** `LongAdder` + `metrics.counter(...).increment()` is alloc-free once steady-state. But `violationCounts.computeIfAbsent(invariantName, k -> new LongAdder())` allocates a `LongAdder` on the first violation per invariant. Repeat violations are alloc-free. The caller-side `MetricsRegistry.counter(...)` also only allocates once. This is acceptable, but a worst-case burst scenario (100k violations/s of a new invariant name) allocates tens of `LongAdder` objects per burst during the race on `computeIfAbsent`.
- **Impact:** Minor. Alloc rate claim of PROMPT.md §5 is "counter increment must be alloc-free" — it is, after first-use warm-up.
- **Fix direction:** Pre-register all known invariants (register all `InvariantMonitor` name constants at startup). Document this as the contract. Add a test that asserts no allocation on repeat `check()` calls for a pre-registered invariant.
- **Owner:** observability

### PA-5026: No profiling hooks — no async-profiler / JFR integration registered in observability module
- **Severity:** S3
- **Location:** entire repo — `grep -rn "FlightRecorder\|jdk.jfr\|async.profiler\|Continuous"` in `src/main` returns zero files.
- **Category:** ops
- **Evidence:** No JFR events defined, no `@Name @Label` annotations, no `jdk.jfr.Event` subclasses. No async-profiler JNI hook. Nothing in `configd-observability` exports a profiling endpoint.
- **Impact:** Tail-latency incident response must rely on a restart-with-JFR-enabled workflow. Cannot attach live to a running node to trace allocation / contention without operator SSH + `jcmd`.
- **Fix direction:** Add two `jdk.jfr.Event` classes: `RaftCommitEvent` (term, index, latency, committed_size) and `EdgeApplyEvent` (edge_id, version, staleness_ms). Publish via `FlightRecorder.register(...)`. Optionally add an admin endpoint `POST /admin/jfr/start?duration=60s` that writes to a tmp file and returns the path for async-profiler-style workflows.
- **Owner:** observability

### PA-5027: Spec does not model snapshot install / log compaction — a major Raft correctness hazard is unverified
- **Severity:** S2
- **Location:** `spec/ConsensusSpec.tla` — grep for `snapshot|Snapshot|compact|Compact|install` returns nothing.
- **Category:** formal-methods
- **Evidence:** The production code has `SnapshotCompactor`, `ConfigStateMachine.restoreSnapshot`, and `FanOutBuffer.snapshot()`. The spec does not model any snapshot/install-snapshot transition. When a follower is too far behind (version gap > snapshot threshold, per `docs/runbooks/version-gap.md`), the leader sends an InstallSnapshot RPC instead of AppendEntries; the state transition is non-trivial (follower must discard prefix of log, reset commitIndex, adopt snapshot state-machine state).
- **Impact:** A class of bugs — follower applies a snapshot then rewinds commitIndex, snapshot truncation races with log-truncation, InstallSnapshot under joint consensus — is entirely unverified. F-V7-01 (snapshot bounds check) was caught only because it was a pure parsing bug; a state-machine bug would slip through.
- **Fix direction:** Add `InstallSnapshot(n, m, snapshotIndex, snapshotTerm, stateMachineState)` action; add `logPrefixDiscarded[n]` tracking; extend invariants to account for log truncation via snapshot. Cite Ongaro §5.4 (log compaction). Expect state-space growth; symmetry reduction becomes necessary (already a commented-out suggestion in `ConsensusSpec.cfg:48-49`).
- **Owner:** formal-methods

### PA-5028: Spec does not model multi-Raft (hierarchical groups) — production runs many groups, spec reasons about one
- **Severity:** S3
- **Location:** `spec/ConsensusSpec.tla`; `configd-replication-engine` is the hierarchical Raft replication engine (per `docs/verification/inventory.md:22`).
- **Category:** formal-methods
- **Evidence:** Spec has a single set `Nodes` and one log per node. The inventory describes five replication-engine files (1,028 LoC) implementing hierarchical Raft across groups. Cross-group ordering is *explicitly* not guaranteed per `docs/consistency-contract.md:145`, so there is no correctness property to prove cross-group — but the composition of multiple independent Raft groups sharing transport + storage is not modeled.
- **Impact:** Protocols that subtly depend on single-group assumptions (e.g., a shared disk's fsync fence across two groups) cannot be checked. The `docs/consistency-contract.md` non-goal ("cross-group not ordered") is a reasonable scope fence for the spec.
- **Fix direction:** Document that the spec covers "one Raft group"; add a paragraph in `spec/README` (does not exist) or at the top of `ConsensusSpec.tla` explaining the scope boundary. Multi-Raft modeling is a separate spec (out of scope for GA).
- **Owner:** formal-methods

### PA-5029: Spec models joint consensus but does not model membership-change *removal* beyond arbitrary `newMembers` sets — empty-to-nonempty boundary unchecked
- **Severity:** S3
- **Location:** `spec/ConsensusSpec.tla:373-392` (`ProposeConfigChange`) and line 379 (`newMembers /= {}`).
- **Category:** formal-methods
- **Evidence:** `newMembers /= {}` rules out the degenerate empty config but allows arbitrary subsets otherwise. Safe path is covered by TLC. Not covered: joint config where `C_old` and `C_new` are disjoint (catastrophic partition in a naively-implemented Raft — Raft paper §4.3 explicitly says disjoint joint configs still preserve safety because the joint quorum requires both). The spec *should* cover it via `QuorumsOf(cfg)` but TLC with `MaxLogLen=3` may not reach disjoint-config states in the current bound.
- **Impact:** Low — the invariant family is structurally correct by the joint-quorum definition (line 71-80). But disjoint-config scenarios are the most interesting for joint consensus and are the least tested.
- **Fix direction:** Add a guided test run with `MaxLogLen=5` and a config-only profile that forces at least one disjoint transition. Record state-space size in `tlc-results.md`.
- **Owner:** formal-methods

### PA-5030: TLC results file is stale — reports `NoStaleOverwrite` as "PASS" but it was removed; reports 8 invariants checked, .cfg now lists 9
- **Severity:** S3
- **Location:** `spec/tlc-results.md:13-21,32-41`.
- **Category:** docs
- **Evidence:** The "Invariants Checked" list includes `NoStaleOverwrite` (removed) and does not include `LeaderCompleteness` or `VersionMonotonicity` (added). `ConsensusSpec.cfg:26-34` is the authoritative list. Date on `tlc-results.md` is 2026-04-10; final-report TLC re-run was 2026-04-16.
- **Impact:** Anyone reading `tlc-results.md` as the source of truth sees a different invariant set than what TLC actually checks.
- **Fix direction:** Regenerate `tlc-results.md` from the current `.cfg` file (scriptable). Add a CI step that fails if the .md and .cfg INVARIANT lists diverge.
- **Owner:** docs

---

## Alert → Runbook Coverage Matrix

### Legend
- **Alert defined in code:** the metric named in the runbook's `Alert:` line is registered in `MetricsRegistry` somewhere in `src/main`.
- **Alert rule persisted:** a Prometheus alert rule (`ops/prometheus/alerts.yaml` or equivalent) exists in the repo.
- **Runbook reachable from alert:** the alert's annotation / label names the runbook path.
- **Fires automatically / manual:** runbook detection mode.

### Runbooks

| Runbook | `Alert:` expression | Metric in code? | Alert rule persisted? | Runbook link from alert? | Status |
|---------|--------------------|-----------------|----------------------|--------------------------|--------|
| `cert-rotation.md` | `configd_transport_tls_cert_expiry_days < 30` | **NO** | NO | N/A (no rule) | BROKEN (PA-5005) |
| `edge-catchup-storm.md` | `configd_distribution_fanout_queue_depth > 10000` | **NO** | NO | N/A | BROKEN (PA-5005) |
| `leader-stuck.md` | `configd_raft_role` no LEADER for >5s | **NO** | NO | N/A | BROKEN (PA-5005) |
| `poison-config.md` | `configd_edge_poison_pill_detected_total > 0` | **NO** | NO | N/A | BROKEN (PA-5005) |
| `reconfiguration-rollback.md` | `configd_raft_config_change_pending == 1 for >60s` | **NO** | NO | N/A | BROKEN (PA-5005) |
| `region-loss.md` | `configd_raft_quorum_reachable == 0 for >30s` | **NO** | NO | N/A | BROKEN (PA-5005) |
| `version-gap.md` | `configd_raft_log_replication_lag > 1000 for >30s` | **NO** | NO | N/A | BROKEN (PA-5005) |
| `write-freeze.md` | (manual trigger, no auto alert) | N/A | N/A | N/A | OK (manual) |

### Metrics that DO exist in production code (inverse gap: metric → runbook)

| Metric name | Location | Runbook reference? | Status |
|-------------|----------|--------------------|--------|
| `propagation.lag.violation` (counter) | `PropagationLivenessMonitor.java:26,52` | **none** — no runbook discusses `propagation.lag.violation` | UNMATCHED (no runbook) |
| `invariant.violation.monotonic_read` (counter) | `InvariantMonitor.java:106,225` | **none** — `docs/consistency-contract.md §8` alludes to "alert on the violation counter" but no runbook expands it | UNMATCHED (no runbook) |
| `invariant.violation.staleness_bound` (counter) | `InvariantMonitor.java:113,225` | **none** — same as above | UNMATCHED (no runbook) |
| `invariant.violation.*` (generic) | `InvariantMonitor.java:225` | none | UNMATCHED (no runbook) |

### SLO / burn-rate alerts → runbooks

| SLO (from `ProductionSloDefinitions`) | Burn-rate alert emitted? (PA-5001) | Runbook on breach? |
|---------------------------------------|-----------------------------------|---------------------|
| `write.commit.latency.p99` | NO (dead wiring) | NO |
| `edge.read.latency.p99` | NO | NO |
| `edge.read.latency.p999` | NO | NO |
| `propagation.delay.p99` | NO | partially: `edge-catchup-storm.md`, `version-gap.md`, but neither mentions the SLO |
| `control.plane.availability` | NO | partially: `leader-stuck.md`, `region-loss.md` |
| `edge.read.availability` | NO | partially: `edge-catchup-storm.md`, `region-loss.md` |
| `write.throughput.baseline` | NO | NO |

**Summary:** Every runbook-named alert points to a metric that does not exist (7 of 7 auto-triggered runbooks). Every production SLO has no alert routing and no runbook to follow on breach. The only three real metrics have no runbook. The observability↔runbook coverage is effectively zero.

---

## Summary

- **30 findings filed** (PA-5001 … PA-5030): 4 S0, 4 S1, 10 S2, 12 S3.
- **Top 4 S0** (undetected SLO breach): PA-5001 (burn evaluator never invoked), PA-5002 (SLO tracker never populated), PA-5003 (SLO semantics mismatch — ratio vs. latency-percentile), PA-5004 (no alert rules persisted).
- **R-03, R-14, R-15 residuals** investigated in full:
  - R-03: `EdgePropagationLiveness` commented out, reason documented (spurious violation at MaxTerm=3 bound); PA-5020 for closure path.
  - R-14: SPEC-GAP-3 is stale (now modeled — PA-5022); SPEC-GAP-4 confirmed open (ReadIndex — PA-5023).
  - R-15: SPEC-GAP-1 is resolved in code but doc drift remains (PA-5024).
- **Coverage matrix**: 7 of 7 auto-triggered runbook alerts reference metrics that do not exist in code; 3 of 3 real production metrics have no runbook. Complete decoupling.
- **Spec gaps beyond R-series**: snapshot install (PA-5027), multi-Raft (PA-5028, accepted non-goal), ReadIndex (PA-5023).
- **Tracing**: not present at all (PA-5018).
- **Structured logging / PII safety**: not present (PA-5019).
- **Profiling hooks**: not present (PA-5026).
- **Cardinality guard**: not present (PA-5012); label dimension: not present (PA-5013).

**Overall verdict for Cluster E:** The observability module ships code that compiles, has unit tests, and is wired into `ConfigdServer`, but **no SLO can fire an alert, no runbook points to a real metric, and the TLA+ spec has three open gaps**. GA with the current observability surface is a production risk: the first incident response will discover that the dashboards the SRE team expects do not exist and the alerts that should have fired are dead code.
