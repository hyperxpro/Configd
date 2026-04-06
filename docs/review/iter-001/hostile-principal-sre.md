# hostile-principal-sre — iter-001
**Findings:** 18

## H-001 — every SLO alert queries a metric that production code never emits
- **Severity:** S0
- **Location:** ops/alerts/configd-slo-alerts.yaml:1-180 ; verified by repo-wide grep across `**/src/main/**/*.java`
- **Category:** observability-unwired
- **Evidence:** `configd-slo-alerts.yaml` references `configd_write_commit_seconds_bucket`, `configd_write_commit_total`, `configd_write_commit_failed_total`, `configd_edge_read_seconds_bucket`, `configd_propagation_delay_seconds_bucket`, `configd_raft_pending_apply_entries`, `configd_snapshot_install_failed_total`. A grep over every `src/main/**/*.java` for those metric names returns **zero matches**. Production-side `MetricsRegistry` only emits `propagation.lag.violation` and `invariant.violation.*`. `SloTracker.recordSuccess`/`recordFailure` (configd-observability/SloTracker.java:74,84) have **no production caller** — only tests in `SloTrackerTest`. `BurnRateAlertEvaluator` reads from the SloTracker that nobody feeds.
- **Impact:** Every SLO burn-rate alert and the entire `ops/dashboards/` SLO surface is decorative. A real outage will be silent. The "MWMBR alerts wired" claim in `docs/ga-review.md` is unsubstantiated by the binary that ships. This is the *single* most consequential GA-blocker: there is no way to know production is broken because the alerts cannot fire.
- **Fix direction:** Wire histograms in (a) `ConfigWriteService` (commit-seconds), (b) HTTP read handler in `HttpApiServer.handleGet` (edge-read-seconds), (c) `PlumtreeNode`/edge applier (propagation-delay), (d) `RaftDriver.tick` apply path (raft-pending-apply-entries gauge), (e) snapshot install failure path (counter). Then have `SloTracker.recordSuccess/Failure` called from those same code paths, OR remove `SloTracker` and let `BurnRateAlertEvaluator` query the raw histograms. Either way, end-to-end test: trigger a synthetic SLO breach in `ChaosScenariosTest`, scrape `/metrics`, assert the alert query in `configd-slo-alerts.yaml` returns > threshold.
- **Proposed owner:** configd-observability + configd-server (code), ops/alerts (test rig)

## H-002 — the disaster-recovery runbook calls scripts that do not exist in the repo
- **Severity:** S1
- **Location:** ops/runbooks/restore-from-snapshot.md:165 ; ops/runbooks/disaster-recovery.md:95 ; verified by `ls /home/ubuntu/Programming/Configd/ops/scripts/` → `No such file or directory`
- **Category:** dr-runbook-broken
- **Evidence:** `restore-from-snapshot.md:165` runs `./ops/scripts/restore-conformance-check.sh --snapshot=... --cluster-endpoint=...`. `disaster-recovery.md:95` runs `./ops/scripts/restore-snapshot.sh --snapshot=<URI> --target=data-configd-0`. The directory `ops/scripts/` does not exist. There are no other scripts named restore-conformance-check or restore-snapshot anywhere in the repo (verified by Glob `**/restore-*`).
- **Impact:** The "verification (post-restore)" gate that the runbook calls "complete only after the conformance check passes" cannot be executed. Operators following the runbook will hit "command not found" mid-incident at the highest-stakes moment. The DR drill cannot be run as written, which means the empty `ops/dr-drills/results/` (already YELLOW per ga-review.md) will *stay* empty — there is nothing to drill against.
- **Fix direction:** Create `ops/scripts/restore-conformance-check.sh` (read snapshot manifest, fetch each key over HTTPS, byte-compare) and `ops/scripts/restore-snapshot.sh` (object-store download → verify signature → untar to PVC). Or, change the runbook to use Kubernetes Jobs only and excise the script references. Whichever path is taken, the next DR drill MUST execute the runbook end-to-end with a real artifact in `ops/dr-drills/results/`.
- **Proposed owner:** ops/scripts/ (new) + ops/runbooks/restore-from-snapshot.md

## H-003 — tick loop catches Throwable, prints to stderr, no metric, no alert
- **Severity:** S1
- **Location:** configd-server/src/main/java/io/configd/server/ConfigdServer.java:512-521
- **Category:** silent-failure
- **Evidence:** ```try { driver.tick(); propagationMonitor.checkAll(); watchService.tick(); plumtreeNode.tick(); ... } catch (Throwable t) { System.err.println("CRITICAL: Exception in tick loop (continuing): " + t); t.printStackTrace(System.err); }```. The catch branch increments no counter and exposes no `_total` or `_last_seen_timestamp` metric. The class comment correctly notes that a dying tick loop produces a "zombie node" — but the recovery path is "log and pray".
- **Impact:** A repeating `NullPointerException` from any of the four tick-driven services will silently degrade the node into a zombie that holds its leadership lease (heartbeats stop), force a re-election, and then re-acquire leadership and re-degrade — observable only via stderr scrape. There is no Prometheus signal an operator can write a runbook against. The accompanying `ConfigdControlPlaneAvailability` alert fires only when *availability* drops, not on the leading indicator.
- **Fix direction:** Add `configd_tick_exceptions_total{component="driver|propagation|watch|plumtree"}` counter. Increment in the catch block (use `Throwable.getClass().getSimpleName()` as a label, hard-capped). Add an alert `ConfigdTickLoopUnstable` at `rate(configd_tick_exceptions_total[5m]) > 0.1`. Page on it.
- **Proposed owner:** configd-server, configd-observability, ops/alerts

## H-004 — TLS reload failure is logged to stderr only; no metric, no alert
- **Severity:** S1
- **Location:** configd-server/src/main/java/io/configd/server/ConfigdServer.java:528-537
- **Category:** silent-failure
- **Evidence:** ```try { tlsManager.reload(); } catch (Exception e) { System.err.println("WARNING: TLS reload failed (continuing with current context): " + e.getMessage()); }```. No metric, no health-check downgrade.
- **Impact:** A cert that fails to renew (CA outage, file-permission flip, malformed PEM, expired cert in chain) leaves the cluster running on the *current* context. The current context has a hard expiry. Two weeks later, *every* TLS handshake fails simultaneously. There is no alert before the outage. There is no readiness probe failure to drain the pod.
- **Fix direction:** Add `configd_tls_reload_failures_total` and `configd_tls_cert_not_after_seconds` (gauge of cert expiry epoch). Alert `ConfigdTLSCertExpiringSoon` at `(configd_tls_cert_not_after_seconds - time()) < 7*86400`. Alert `ConfigdTLSReloadFailing` at `increase(configd_tls_reload_failures_total[1h]) > 3`. Wire the cert expiry gauge from `TlsManager` even when reload succeeds.
- **Proposed owner:** configd-server, configd-observability, ops/alerts

## H-005 — readiness probe checks only one signal (raft-leader)
- **Severity:** S1
- **Location:** configd-server/src/main/java/io/configd/server/ConfigdServer.java:338-345
- **Category:** missing-health-check
- **Evidence:** Only one readiness check is registered:
  ```healthService.registerReadinessCheck(() -> { NodeId leader = raftNode.leaderId(); if (leader != null) { return CheckResult.healthy("raft-leader"); } return CheckResult.unhealthy(...); });```.
  No check for: (a) data dir writable, (b) signing key loaded, (c) snapshot install in progress (would mean state machine is incomplete), (d) tick loop heartbeat (last-tick-age), (e) TCP transport accept loop running, (f) `FanOutBuffer` not saturated, (g) `WatchService` cursor drain rate.
- **Impact:** A node that has lost its signing key, has a hung tick loop, or is mid-InstallSnapshot reports `200 OK` on `/health/ready` because the cluster has a leader (which may be *another* pod). Kubernetes therefore continues sending traffic to a degraded node. PDB-correct rolling restarts will succeed without surfacing real problems.
- **Fix direction:** Register five additional checks: `signing-key`, `data-dir-writable`, `tick-heartbeat` (assert `now - lastTick < 1s`), `transport-accept-loop`, `snapshot-install-not-blocking`. Wire `lastTick` from the tick loop body. Each readiness check must be < 5ms; do the work asynchronously and read a cached result.
- **Proposed owner:** configd-server, configd-observability

## H-006 — Raft message decode failure is swallowed; no metric, no alert
- **Severity:** S2
- **Location:** configd-server/src/main/java/io/configd/server/RaftTransportAdapter.java:55-64
- **Category:** silent-failure
- **Evidence:** ```try { RaftMessage raftMessage = RaftMessageCodec.decode(frame); handler.accept(from, raftMessage); } catch (Exception e) { System.err.println("Failed to decode Raft message from " + from + ": " + e.getMessage()); }```. No counter, no histogram, no alert. The error message includes peer NodeId but goes to stderr (where it is generally lost in container log volume).
- **Impact:** A subtle wire-protocol regression (R-007/R-008 from release-skeptic) manifests as silent message drop from one peer. Cluster keeps a quorum of two (still committing), but the third voter is effectively partitioned for replication purposes. Detectable today only by stderr inspection or a downstream "follower lag" symptom that may not have an alert.
- **Fix direction:** Add `configd_raft_decode_errors_total{from="<peer>",error="<class>"}` (cardinality-bounded — peer count is fixed and error count enumerated). Alert at `rate(configd_raft_decode_errors_total[5m]) > 0`. Pair with R-007's wire-version field so genuine cross-version skew can be distinguished from corruption.
- **Proposed owner:** configd-server, configd-observability, ops/alerts

## H-007 — TCP transport accept loop / inbound errors swallowed to stderr
- **Severity:** S2
- **Location:** configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:208-213, 254-262
- **Category:** silent-failure
- **Evidence:** Three places print and continue without any metric: accept loop on `IOException`, inbound `SocketException`, inbound `IOException`. No counter or per-peer breakdown.
- **Impact:** A noisy peer that resets connections in a tight loop (e.g. NIC flap, MTU mismatch, intermediate firewall dropping idle conns) consumes accept-loop CPU and produces no monitoring signal. Operator has no way to scope the blast radius without scraping container logs.
- **Fix direction:** Add `configd_transport_accept_errors_total` and `configd_transport_inbound_errors_total{kind="socket|io|eof"}`. Alert when rate > 1/min sustained for 5 minutes.
- **Proposed owner:** configd-transport, configd-observability, ops/alerts

## H-008 — HTTP PUT body is read with no size cap before validation (DoS)
- **Severity:** S2
- **Location:** configd-server/src/main/java/io/configd/server/HttpApiServer.java:269
- **Category:** unbounded-input
- **Evidence:** ```byte[] body = exchange.getRequestBody().readAllBytes();``` with no `Content-Length` cap, no streaming guard, no max-frame check. `ConfigWriteService.put` rate-limits *commits* but only after the body is fully resident.
- **Impact:** A single attacker (or a buggy client) sending a 10GB PUT can OOM-kill the server before any 429/413 fires. The `RateLimiter` (10k/s, 10k burst) does not protect against this — it limits successful commits, not request bytes ingested. Pair this with `readOnlyRootFilesystem: true` (so OOM-killer can't even page swap from /tmp) — the JVM dies hard with `-XX:+ExitOnOutOfMemoryError`.
- **Fix direction:** Reject upfront if `Content-Length > MAX_VALUE_BYTES` (default e.g. 1 MiB; configurable). For chunked encoding, read into a bounded buffer with a hard limit and short-circuit `413 Payload Too Large`. Add `configd_http_request_body_bytes` histogram and `configd_http_request_rejected_total{reason="oversize"}`.
- **Proposed owner:** configd-server

## H-009 — PlumtreeNode outbox is an unbounded LinkedList
- **Severity:** S2
- **Location:** configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java:60, 88
- **Category:** unbounded-queue
- **Evidence:** `private final Queue<OutboundMessage> outbox;` initialised as `new LinkedList<>()`. `broadcast`, `receiveEagerPush`, `receiveLazyNotification`, `receiveGraft`, `tick` all `outbox.add(...)`. There is no drain SLA, no size cap, no metric.
- **Impact:** A slow downstream consumer of `outbox.drain()`, or a spike in fanout (large message + N peers + GRAFT storm), grows the outbox without bound. Each entry holds a `byte[] payload` reference for `EagerPush` — memory pressure scales with message size × peers × backlog. JVM dies with OOM well before any operator-visible signal.
- **Fix direction:** Bound to `maxOutboxSize` (configurable, default e.g. 100k entries). On overflow, increment `configd_plumtree_outbox_drops_total{kind=...}` and drop oldest IHave (cheapest), then EagerPush, then Prune/Graft (costliest to drop). Expose `configd_plumtree_outbox_size` gauge. Alert at sustained > 50k.
- **Proposed owner:** configd-distribution-service

## H-010 — HyParViewOverlay outbox is an unbounded LinkedList
- **Severity:** S2
- **Location:** configd-distribution-service/src/main/java/io/configd/distribution/HyParViewOverlay.java:67, 90
- **Category:** unbounded-queue
- **Evidence:** Same shape as H-009: `Queue<OutboundMessage> outbox = new LinkedList<>();`. `join`, `receiveJoin`, `receiveForwardJoin`, shuffle, etc. all append.
- **Impact:** During a join storm (large datacentre joining edge mesh), `ForwardJoin` messages multiply by `shuffleTtl` per join — outbox grows N × TTL × active-view-size. No backpressure.
- **Fix direction:** Same as H-009 with `configd_hyparview_outbox_*` metrics, prioritising drop of `Shuffle` over `ForwardJoin` over `Join`/`Disconnect`.
- **Proposed owner:** configd-distribution-service

## H-011 — WatchService.watches is unbounded — register-spam DoS
- **Severity:** S2
- **Location:** configd-distribution-service/src/main/java/io/configd/distribution/WatchService.java:103, 143-152
- **Category:** unbounded-queue
- **Evidence:** `private final Map<Long, Watch> watches;` initialised as `new HashMap<>()`. `register` (line 143) does `long id = nextWatchId++; watches.put(id, w);`. No rate-limit on `register`, no cap on map size, no per-client tracking.
- **Impact:** A misbehaving (or hostile) client that calls register-watch in a loop can grow the map without bound. Each `Watch` holds a `WatchListener` reference that may itself retain socket / connection state. OOM is the failure mode.
- **Fix direction:** Cap at `maxWatches` (configurable, default 100k). On overflow, refuse with `WATCHES_EXCEEDED` and increment `configd_watches_register_rejected_total`. Add `configd_watches_active` gauge and alert at sustained > 80% of cap. Track per-principal watch count when auth lands.
- **Proposed owner:** configd-distribution-service

## H-012 — DeltaApplier hot path logs at WARNING with string concatenation per delta
- **Severity:** S2
- **Location:** configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java:142-145, 151-152, 158-159, 163-164, 173-176
- **Category:** hot-path-log
- **Evidence:** Each `LOG.warning("...message... " + delta.fromVersion() + "..." + delta.toVersion() + ...)` runs string concatenation and full message construction *before* the JUL filter. `offer()` is the per-delta entry point — i.e. once per propagation event per edge. Five separate concat'd `LOG.warning` callsites in the rejection path.
- **Impact:** Under attack (replay flood, signature-fail flood, gap-detect flood), every reject allocates throw-away strings on the hot path. Allocations starve nursery, increase GC pressure, increase tail latency, increase the very metric (`edge-read-seconds`) the propagation SLO measures. Worse: log volume can swamp the JUL handler and stall the applier thread.
- **Fix direction:** Use `LOG.log(Level.WARNING, () -> "Rejecting...")` (Supplier form, lazy) AND/OR rate-limit via a `RateLimitedLogger` (1 per peer per second). Replace the rejection counters with `configd_edge_delta_rejected_total{reason=...}`; downgrade to FINE-with-counter for the per-event log line.
- **Proposed owner:** configd-edge-cache, configd-observability

## H-013 — CatchUpService.trimHistory is O(N) per delta record (≥ O(N²) over time)
- **Severity:** S2
- **Location:** configd-distribution-service/src/main/java/io/configd/distribution/CatchUpService.java:159-170
- **Category:** algorithmic-hot-path
- **Evidence:** ```private void trimHistory() { while (deltaHistory.size() > maxDeltaHistory) { long oldest = Long.MAX_VALUE; for (long version : deltaHistory.keySet()) { if (version < oldest) oldest = version; } deltaHistory.remove(oldest); } }```. Linear scan over `keySet()` to find the oldest, on every call. `recordDelta` calls this on every committed delta.
- **Impact:** With `maxDeltaHistory = 10_000`, a steady write rate triggers a 10k-key linear scan per write. Under burst (after a leader change), this becomes per-write 10k scans = 10k × 10k = 100M comparisons. Hot path on the leader's apply thread, directly inflating commit latency and the propagation SLO.
- **Fix direction:** Use `LinkedHashMap` (insertion order = version order, since versions are monotonic), then `keySet().iterator().next()` is O(1). Or use a `TreeMap` and `firstKey()`. Cover with a benchmark in `configd-testkit/src/main/java/io/configd/bench/`.
- **Proposed owner:** configd-distribution-service

## H-014 — SubscriptionManager.matchingNodes is O(prefixes) per match
- **Severity:** S3
- **Location:** configd-distribution-service/src/main/java/io/configd/distribution/SubscriptionManager.java:112-123 (verified in earlier exploration)
- **Category:** algorithmic-hot-path
- **Evidence:** `matchingNodes(key)` iterates `prefixIndex.entrySet()` linearly per key. Called per mutation per fanout.
- **Impact:** Fanout cost grows linearly in subscription-prefix count. With 10k subscriptions × 1000 mutations/s × 100 nodes, work scales unfavourably. Latency is paid on the leader apply thread.
- **Fix direction:** Replace with a radix/trie index keyed on prefix; lookup is O(key length). Benchmark via `configd-testkit/src/main/java/io/configd/bench/SubscriptionMatchBenchmark.java` (exists, but not verifying current vs. proposed).
- **Proposed owner:** configd-distribution-service

## H-015 — JVM diagnostic log written to /data PVC competes with WAL writes
- **Severity:** S2
- **Location:** deploy/kubernetes/configd-statefulset.yaml:88-89
- **Category:** ops-misconfig
- **Evidence:** ```- "-XX:+UnlockDiagnosticVMOptions" - "-XX:+LogVMOutput" - "-XX:LogFile=/data/jvm.log"```. PVC requests 10 Gi.
- **Impact:** JVM diagnostic log is unbounded and written to the same volume that holds the Raft WAL and snapshots. A burst of safepoint logs or ZGC logs can (a) fill the PVC, blocking WAL append and stalling consensus, (b) compete with WAL fsync for IOPS, inflating commit latency. There is no log rotation.
- **Fix direction:** Move `LogFile` to `/tmp/jvm.log` (already an `emptyDir` with `sizeLimit: 64Mi`), or remove `-XX:+LogVMOutput` entirely (it is rarely useful in production; `-Xlog:gc*:file=...:time,uptime,level,tags:filecount=5,filesize=20m` is the modern, rotated equivalent). Even better: emit GC events as Prometheus metrics via JFR streaming.
- **Proposed owner:** deploy/kubernetes/configd-statefulset.yaml

## H-016 — MetricsRegistry.bucketCount reads buffer without snapshot under concurrent record
- **Severity:** S2
- **Location:** configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java:412-425 (verified in earlier exploration)
- **Category:** observability-correctness
- **Evidence:** `bucketCount()` iterates `buffer[0..n]` lock-free. `record()` writes concurrently. There is no copy-then-iterate, no `VarHandle` acquire/release, no reservoir sampling.
- **Impact:** Under heavy traffic, scrape can read a torn slot or under/over-count. Burn-rate alerts (which divide histograms) compound the inaccuracy. Worst case: an SLO breach reads as healthy because the count of failures was scrape-time-undercounted relative to the count of totals.
- **Fix direction:** Either (a) snapshot the buffer to a local array under a single `getAndSet` per bucket, then iterate; or (b) use `LongAdder` per bucket; or (c) use HdrHistogram with double-buffered phaser. Property-test under concurrent record + scrape that the totals exposed monotonically increase.
- **Proposed owner:** configd-observability

## H-017 — PrometheusExporter `_sum` is `Math.round(mean * count)` — lossy round-trip
- **Severity:** S3
- **Location:** configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java:121
- **Category:** observability-correctness
- **Evidence:** `_sum` reported as `Math.round(hist.mean() * count)`. Mean is itself a derived statistic. The Prometheus convention is for `_sum` to be the *true* sum of all observations, used directly by `histogram_quantile()` and burn-rate divisors.
- **Impact:** Under heavy traffic, mean × count drifts from the true sum by sub-ulp loss × N — but more importantly, it is no longer mathematically `sum(observations)`, which `histogram_quantile` and Grafana panels rely upon. SLO panels can subtly mislead.
- **Fix direction:** Track an actual `LongAdder sum` alongside count; emit `_sum` from it directly. This is also a prerequisite for H-001 (the SLO histograms once wired must be Prometheus-spec correct).
- **Proposed owner:** configd-observability

## H-018 — SlowConsumerPolicy.state mutates state inside a query method
- **Severity:** S3
- **Location:** configd-distribution-service/src/main/java/io/configd/distribution/SlowConsumerPolicy.java:137-148 (verified in earlier exploration)
- **Category:** silent-state-change
- **Evidence:** `state()` transitions DISCONNECTED → QUARANTINED based on time-since-disconnect *inside* the getter. The class-level invariant relies on "I/O thread only" but there is no enforcement and the method is on the public surface.
- **Impact:** Any monitoring code that calls `state()` from a Prometheus-scrape thread (cross-thread) will both (a) trigger a state transition that the consumer logic did not request, and (b) race with the I/O thread. Reads can be torn; transitions can be lost.
- **Fix direction:** Split into `currentState()` (pure observer, no mutation) and `evaluateAndAdvance()` (called only from the I/O loop). Annotate the latter as `@NotThreadSafe`. Property-test under concurrent observer + advancer to assert all transitions are observed exactly once.
- **Proposed owner:** configd-distribution-service
