# Adversarial Code Audit Report
## Modules: configd-common, configd-observability, configd-control-plane-api, configd-server
### Date: 2026-04-13

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 8     |
| High     | 14    |
| Medium   | 12    |
| Low      | 5     |

---

## Module 1: configd-common

### HybridTimestamp.java

**FIND-C01** `HybridTimestamp.java:28-29:Critical:Packed timestamp silently truncates wallTime and logical counter`
The `packed()` method packs wallTime into the top 48 bits and logical into the bottom 16 bits:
```java
public long packed() {
    return (wallTime << 16) | (logical & 0xFFFF);
}
```
- `wallTime` is a `long` (64 bits). Shifting left by 16 discards the top 16 bits. Current wall-clock milliseconds (epoch ~1.7 trillion) fit in ~41 bits, so this is safe today, but will overflow around year 10889. Acceptable for practical purposes.
- `logical` is an `int` (32 bits) but only the bottom 16 bits are preserved (max 65535). If the logical counter exceeds 65535 (e.g., 65536+ events in the same millisecond with no physical clock advance), the packed representation will alias to a previous timestamp, **breaking total ordering**. The HybridClock `now()` method increments logical without any bound check.

**FIND-C02** `HybridTimestamp.java:17-19:Medium:No validation on constructor arguments`
The constructor accepts negative wallTime and negative logical values without any validation. A negative logical counter would produce incorrect comparisons and corrupt packed values.

### HybridClock.java

**FIND-C03** `HybridClock.java:31-32:Critical:Logical counter overflow is unbounded`
In `now()`, if the physical clock never advances (stuck clock, VM pause, simulation), `lastLogical` increments unboundedly. Since `lastLogical` is `int`, it will overflow from `Integer.MAX_VALUE` to `Integer.MIN_VALUE`, causing the returned timestamp to be **less than** all prior timestamps. This violates the documented monotonicity guarantee ("result > all previously returned timestamps from this clock"). Combined with FIND-C01, the 16-bit packing truncation makes this exploitable at just 65536 events/ms.

Recommended fix: add an overflow check:
```java
if (lastLogical == Integer.MAX_VALUE) {
    throw new IllegalStateException("HLC logical counter overflow");
}
```

**FIND-C04** `HybridClock.java:26:High:Synchronized on every now() call is a potential bottleneck`
`now()` is `synchronized`, which is fine for infrequent writes as documented, but if used on the read path (e.g., stamping every read with a causal timestamp), this becomes a contention point under high throughput. The comment says "infrequent (on writes, not reads)" which is an assumption that should be enforced or documented at the call site.

**FIND-C05** `HybridClock.java:42-55:Medium:receive() does not validate incoming timestamp`
The `receive()` method accepts any `HybridTimestamp` including one with a wallTime far in the future. A malicious or buggy peer could send a timestamp with `wallTime = Long.MAX_VALUE`, permanently advancing the local clock to the far future, making all subsequent physical clock values irrelevant. This is a clock-skew amplification attack. There should be a maximum acceptable drift check (e.g., reject messages with wallTime > physicalTime + maxDrift).

### BuggifyRuntime.java

**FIND-C06** `BuggifyRuntime.java:14:Critical:Shared mutable RandomGenerator without synchronization`
`random` is a `private static RandomGenerator` that is accessed from `shouldFire()` which can be called from multiple threads concurrently. `L64X128MixRandom` is **not thread-safe** (see JDK docs: "instances of SplittableGenerator, such as L64X128MixRandom, are not thread-safe"). Concurrent calls to `nextDouble()` can produce incorrect values or internal corruption.

**FIND-C07** `BuggifyRuntime.java:20-22:High:enableSimulationMode ignores the seed parameter`
```java
public static void enableSimulationMode(long seed) {
    simulationMode = true;
    random = RandomGenerator.of("L64X128MixRandom");
    enabledPoints.clear();
}
```
The `seed` parameter is accepted but never used. The `RandomGenerator.of()` call creates a randomly-seeded generator, defeating the purpose of deterministic simulation testing. This means fault injection is non-reproducible -- a critical property for FoundationDB-style simulation.

**FIND-C08** `BuggifyRuntime.java:13:Medium:simulationMode is volatile but random is not safely published`
There is a data race between `enableSimulationMode()` writing to `random` and `shouldFire()` reading it. While `simulationMode` is `volatile`, the write to `random` on line 21 is not ordered with respect to the volatile write on line 20 (the volatile write happens first). A thread could see `simulationMode = true` but still read the old `random` reference. The volatile write should be the last operation, or `random` should also be volatile.

### FileStorage.java

**FIND-C09** `FileStorage.java:46-59:High:put() is not atomic -- crash during write leaves corrupt file`
`put()` opens the file with `TRUNCATE_EXISTING` then writes. If the process crashes between truncation and the completion of write+fsync, the file will be empty or partially written. For Raft state (currentTerm, votedFor), this means the node could lose its vote or term on restart, violating Raft safety.

Recommended fix: Write to a temp file, fsync, then atomically rename (write-rename pattern), similar to what `renameLog()` already supports.

**FIND-C10** `FileStorage.java:47:High:Key used directly as filename -- path traversal vulnerability`
The key is used directly in `directory.resolve(key + ".dat")`. A key containing `../` or absolute path components could write to arbitrary filesystem locations. For example, `put("../../etc/cron.d/evil", data)` would write outside the storage directory. The key should be sanitized or validated.

**FIND-C11** `FileStorage.java:114:High:readLog casts file size to int -- files > 2GB will cause overflow`
```java
ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
```
If the WAL file exceeds `Integer.MAX_VALUE` bytes (~2GB), the cast to `int` produces a negative number, causing `IllegalArgumentException` or allocating a tiny buffer leading to data loss. While unlikely in normal operation, a runaway append loop or missing log compaction could hit this.

**FIND-C12** `FileStorage.java:123-126:Medium:readLog silently stops on partial trailing frame`
```java
while (buffer.remaining() >= 4) {
    int length = buffer.getInt();
    if (buffer.remaining() < length + 4) {
        throw new IOException("Truncated WAL entry...");
```
If a crash occurred mid-write, the WAL could have a partial frame. The code throws an IOException on a truncated entry, which is converted to UncheckedIOException and propagates up. This is arguably correct (fail-fast on corruption), but the alternative design used by most WAL implementations is to truncate the partial trailing entry and continue, since the last entry is the one most likely to be incomplete after a crash.

**FIND-C13** `FileStorage.java:123:High:readLog does not validate that length is non-negative`
After reading `int length = buffer.getInt()`, if the WAL is corrupted and `length` is negative, `new byte[length]` on line 129 will throw `NegativeArraySizeException` -- an unchecked exception that will crash the caller with a confusing error message instead of a clear corruption diagnostic.

**FIND-C14** `FileStorage.java:162-172:Medium:renameLog uses ATOMIC_MOVE which may not be supported on all filesystems`
`ATOMIC_MOVE` is best-effort on some filesystems (e.g., across mount points). If it fails, the `IOException` is thrown, but the WAL rewrite operation is left in an inconsistent state (temp file exists, original intact). This is acceptable since the caller can retry.

### InMemoryStorage.java

**FIND-C15** `InMemoryStorage.java:40-46:Medium:readLog returns shallow copies of byte arrays`
`readLog()` copies the list but does not clone the individual byte arrays. The caller could mutate the returned byte arrays, corrupting the internal log. Compare with `get()` which correctly clones. However, since this is test-only storage, severity is reduced.

### NodeId.java

**FIND-C16** `NodeId.java:7:Low:No validation that id is non-negative`
NodeId accepts any integer, including negative values. While not strictly wrong, cluster node IDs are conventionally non-negative.

---

## Module 2: configd-observability

### InvariantMonitor.java

**FIND-C17** `InvariantMonitor.java:119-126:High:checkAll() stops on first failure in test mode`
In test mode, `check()` throws `AssertionError` on the first violation. This means `checkAll()` will stop after the first failing invariant, never checking the remaining ones. In production mode this is fine (all are checked), but in tests the developer only sees one failure at a time, which can mask correlated failures.

**FIND-C18** `InvariantMonitor.java:75:Low:Parameter name "invariantName" is not validated for blank in check()`
The `register()` method validates that `invariantName` is not blank (line 103), but `check()` only validates non-null (line 76). A blank string could be passed to `check()` directly, creating metric names like `"invariant.violation."` (trailing dot).

### SloTracker.java

**FIND-C19** `SloTracker.java:102-120:High:compliance() is O(n) in deque size per query -- no amortization`
Every call to `compliance()` iterates the entire remaining deque. Under high event rates (e.g., 10k events/sec with a 1-hour window = 36M events), this is O(36M) per compliance query. Combined with `snapshot()` calling `statusFor()` for every SLO, this becomes O(SLO_count * events_per_window).

**FIND-C20** `SloTracker.java:198-203:Medium:Eviction race in concurrent scenario`
`evict()` uses `peekFirst()`/`pollFirst()` in a loop. Two concurrent threads calling `compliance()` could both peek the same element, both decide it's old, but only one successfully polls it. The other thread's poll returns a different (still-valid) element and discards it. This causes valid events to be prematurely evicted. However, since the eviction criteria is time-based and events are monotonically ordered, the worst case is evicting one extra event at the boundary, which is acceptable for approximate SLO tracking.

**FIND-C21** `SloTracker.java:63-66:Medium:defineSlo replaces event history but has TOCTOU with concurrent recordEvent`
When `defineSlo()` is called for an existing SLO, it creates a new deque (line 65) and puts it in the map. A concurrent `recordEvent()` could have already retrieved the old deque reference (line 180) and append to the stale deque. This event would be lost. In practice, SLO redefinition at runtime is rare.

### MetricsRegistry.java (DefaultHistogram)

**FIND-C22** `MetricsRegistry.java:276-278:Critical:Histogram record() has a race between cursor increment and buffer write`
```java
long idx = cursor.getAndIncrement();
buffer[(int) (idx % capacity)] = value;
totalCount.incrementAndGet();
```
Thread A calls `getAndIncrement()` obtaining index N, then is preempted. Thread B obtains index N+1, writes its value, increments totalCount. Thread C now calls `percentile()`, sees `totalCount >= capacity`, reads the buffer -- but slot N still contains the old/stale value (Thread A hasn't written yet). The percentile computation includes stale data. For monitoring purposes this is acceptable but not documented.

**FIND-C23** `MetricsRegistry.java:276:High:cursor can overflow long -- wraps to negative after Long.MAX_VALUE`
`cursor.getAndIncrement()` will eventually overflow (after ~9.2 quintillion recordings). When it wraps to `Long.MIN_VALUE`, `(int)(idx % capacity)` produces a **negative array index**, throwing `ArrayIndexOutOfBoundsException` and crashing any thread recording metrics. In practice, at 1M records/sec this takes ~292 million years, so severity is theoretical.

**FIND-C24** `MetricsRegistry.java:358-380:Medium:percentile() snapshot is not consistent with count`
`percentile()` reads `totalCount` then `cursor` at different times. Between these reads, more values may have been recorded, so the snapshot may be inconsistent. Again, acceptable for monitoring.

### ProductionSloDefinitions.java

**FIND-C25** `ProductionSloDefinitions.java:26:High:30-day window SLO will accumulate unbounded events in memory`
```java
tracker.defineSlo("control.plane.availability", 0.99999, Duration.ofDays(30));
tracker.defineSlo("edge.read.availability", 0.999999, Duration.ofDays(30));
```
With a 30-day window and high event rates, the `ConcurrentLinkedDeque` in `SloTracker` will hold up to 30 days of events in memory. At 10k events/sec, this is ~26 billion events, consuming hundreds of GB of memory. The eviction is lazy (only on compliance query), so if compliance is queried infrequently, memory grows without bound.

### BurnRateAlertEvaluator.java

**FIND-C26** `BurnRateAlertEvaluator.java:23:Medium:sinks list is not thread-safe`
`sinks` is an `ArrayList` that is modified by `addSink()` and iterated by `evaluate()`. If `addSink()` is called concurrently with `evaluate()`, a `ConcurrentModificationException` may be thrown. Should use `CopyOnWriteArrayList` or synchronize.

**FIND-C27** `BurnRateAlertEvaluator.java:63-64:Medium:AlertSink.fire() exception crashes the evaluation loop`
If any `sink.fire()` throws an exception, the remaining sinks are not notified and the remaining SLOs are not evaluated. Should catch exceptions per-sink.

### PropagationLivenessMonitor.java

**FIND-C28** `PropagationLivenessMonitor.java:29:Medium:updateLeaderCommit uses set() not compareAndSet -- can go backwards`
If called from multiple threads (e.g., during leadership changes), `leaderCommitIndex.set(commitIndex)` can set the commit index to a value lower than the current one, causing false-positive lag violations for edges that are actually caught up.

### PrometheusExporter.java

**FIND-C29** `PrometheusExporter.java:67-78:Low:sanitizeName silently drops characters that are not alphanumeric, underscore, colon, dot, or dash`
Characters outside the allowed set are silently dropped (no replacement). For example, a metric name "a b" becomes "ab". This is unlikely but could cause metric name collisions.

**FIND-C30** `PrometheusExporter.java:43:Medium:Histogram export uses TYPE summary but Prometheus summary and histogram have different semantics`
The exporter declares histograms as `# TYPE ... summary` but only emits quantile lines without `_sum` and `_count` labels that Prometheus expects for summaries. This will confuse Prometheus scraping and may produce incorrect rate calculations.

---

## Module 3: configd-control-plane-api

### AuthInterceptor.java

**FIND-C31** `AuthInterceptor.java:50-55:Low:authenticate returns Denied for blank tokens but does not distinguish missing vs invalid`
Minor -- the error message "missing auth token" is used for both null and blank tokens, which is acceptable.

### AclService.java

**FIND-C32** `AclService.java:41-48:Critical:grant() stores a mutable HashMap inside a ConcurrentHashMap -- concurrent reads during iteration are unsafe`
```java
acls.compute(prefix, (k, principalMap) -> {
    if (principalMap == null) {
        principalMap = new HashMap<>();
    }
    principalMap.put(principal, EnumSet.copyOf(permissions));
    return principalMap;
});
```
While `compute()` is atomic with respect to the ConcurrentHashMap entry, the value (`HashMap`) is not thread-safe. `isAllowed()` calls `acls.get(bestPrefix)` to get the HashMap, then iterates it (lines 96-102) **outside any lock**. A concurrent `grant()` modifying the same HashMap can cause `ConcurrentModificationException` or corrupt data during the iteration.

Fix: Use `ConcurrentHashMap<String, ConcurrentHashMap<String, Set<Permission>>>` or return defensive copies.

**FIND-C33** `AclService.java:83-93:High:isAllowed longest-prefix match iterates all ACL prefixes -- O(n) per authorization check`
For every authorization check, `isAllowed()` iterates all registered prefixes. With many ACL rules, this becomes a linear scan on every API request. Should use a trie or sorted prefix structure.

**FIND-C34** `AclService.java:96-98:Critical:TOCTOU race between finding bestPrefix and reading its value`
```java
String bestPrefix = null;
for (String prefix : acls.keySet()) { ... }  // find best prefix
Map<String, Set<Permission>> principalMap = acls.get(bestPrefix);  // may return null
```
Between the `keySet()` iteration and the `acls.get()`, a concurrent `revoke()` could remove the prefix (since `revoke` returns null when the principalMap is empty, removing the entry). The `principalMap` would be null, and the method correctly returns `false`. However, this means an ACL that existed during the prefix scan could disappear, causing a spurious denial. More critically, a concurrent `grant()` could add a **longer** prefix after the scan completed, which would be missed, allowing access that should have been denied by the more specific (longer) prefix.

### RateLimiter.java

**FIND-C35** `RateLimiter.java:17:Critical:Rate limiter is global, not per-client`
The `RateLimiter` is a single instance shared across all clients. A single client can exhaust the entire rate limit budget, denying service to all other clients. This is a trivial denial-of-service vector. Per-client rate limiting (keyed by principal or IP) is required for production use.

**FIND-C36** `RateLimiter.java:62:Medium:tryAcquire() without count calls tryAcquire(1) which acquires a lock`
The no-arg `tryAcquire()` delegates to the synchronized `tryAcquire(int)`. This is fine but means every rate limit check contends on the same lock. Under high write throughput (10k/s target), this could become a bottleneck.

### ConfigReadService.java

**FIND-C37** `ConfigReadService.java:70-72:Critical:Failed linearizable read returns NOT_FOUND instead of an error`
```java
if (leadershipConfirmer != null && !leadershipConfirmer.confirmLeadership()) {
    return ReadResult.NOT_FOUND;
}
```
When leadership confirmation fails (this node is not the leader or cannot reach a quorum), the method returns `NOT_FOUND`. This is **semantically incorrect** -- the caller cannot distinguish between "key does not exist" and "cannot confirm this read is linearizable". A client receiving NOT_FOUND may delete local cache entries or take destructive action based on the false belief that the key was deleted. This should return a distinct error (e.g., `ReadResult.UNAVAILABLE` or throw an exception).

**FIND-C38** `ConfigReadService.java:57-59:Medium:leadershipConfirmer can be null but linearizableRead does not fail fast`
If `leadershipConfirmer` is null, `linearizableRead()` silently falls back to a stale read (the `if` guard on line 71 is skipped). This means a misconfigured system where the confirmer is accidentally null will silently serve stale data labeled as "linearizable". The method should throw if the confirmer is null since the caller explicitly requested linearizable semantics.

### ConfigWriteService.java

**FIND-C39** `ConfigWriteService.java:104:Medium:Key length check allocates a byte array on every write for validation`
```java
if (key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
```
This allocates a temporary byte array on every put() call just to check the length. Could use `key.length() * 3 > 1024` as a fast-path check (UTF-8 encodes each char in at most 3 bytes for BMP, 4 for supplementary) to avoid allocation in the common case.

**FIND-C40** `ConfigWriteService.java:170:High:encodeCommand uses 2-byte key length -- keys > 32KB will silently truncate`
```java
buf[pos++] = (byte) (keyBytes.length >> 8);
buf[pos++] = (byte) keyBytes.length;
```
The key length is encoded as a 2-byte (16-bit) unsigned value, supporting keys up to 65535 bytes. The validation on line 104 limits keys to 1024 bytes, so this is safe **as long as the validation is always called before encoding**. However, `delete()` does not perform key length validation (lines 141-158), so a key longer than 65535 bytes passed to `delete()` would have its length truncated in the encoding, causing the decoder to read incorrect data.

**FIND-C41** `ConfigWriteService.java:129:Medium:NotLeader result always passes null for leaderId`
```java
return new WriteResult.NotLeader(null);
```
The `NotLeader` result type has a `leaderId` field intended to help clients redirect to the current leader, but it always passes `null`. The client cannot follow the leader -- it must discover it through other means.

### AdminService.java

**FIND-C42** `AdminService.java:64-68:Medium:TOCTOU race in addNode/removeNode/transferLeadership`
```java
if (!stateProvider.isLeader()) {
    return new AdminResult.NotLeader(stateProvider.currentLeader());
}
boolean success = membershipChanger.addNode(node);
```
Between the `isLeader()` check and the `addNode()` call, this node could lose leadership. The `addNode()` call would then fail at the Raft level. This is not a correctness bug (the membership changer should handle it) but the error reporting is misleading -- the caller gets `Failure("Failed to add node")` instead of `NotLeader`.

### HealthService.java

**FIND-C43** `HealthService.java:60-74:High:readinessChecks list is not thread-safe`
`readinessChecks` is an `ArrayList` modified by `registerReadinessCheck()` and iterated by `readiness()`. Concurrent calls can throw `ConcurrentModificationException`. Should use `CopyOnWriteArrayList`.

**FIND-C44** `HealthService.java:109-111:Medium:detailed() is identical to readiness() -- provides no additional diagnostics`
The `detailed()` method simply delegates to `readiness()`, providing no additional information (e.g., memory usage, thread counts, Raft state details). It's a stub that could mislead operators into thinking they're getting a comprehensive diagnostic.

---

## Module 4: configd-server

### ServerConfig.java

**FIND-C45** `ServerConfig.java:110-111:Medium:authToken stored in a record field is visible via toString()`
Since `ServerConfig` is a record, the auto-generated `toString()` includes `authToken` in plain text. This means logging the config object (which is common at startup) will leak the auth token to log files.

### ConfigdServer.java

**FIND-C46** `ConfigdServer.java:96-103:High:Ed25519 key pair is generated fresh on every startup`
```java
KeyPairGenerator keyGen = KeyPairGenerator.getInstance("Ed25519");
KeyPair keyPair = keyGen.generateKeyPair();
configSigner = new ConfigSigner(keyPair);
```
The signing key is generated randomly each time the server starts, meaning:
1. Config signatures from previous incarnations cannot be verified after restart.
2. Different nodes in the cluster have different signing keys, so signatures cannot be verified across nodes.
3. There is no key persistence or key distribution mechanism.

**FIND-C47** `ConfigdServer.java:156-169:Critical:Auth bypass -- when authToken is null, ALL endpoints are unauthenticated`
```java
if (config.authEnabled()) {
    // ... create authInterceptor and aclService
}
```
When `authToken` is not configured (the default), both `authInterceptor` and `aclService` are `null`. The `HttpApiServer.ConfigHandler.checkAuth()` method returns `null` (allow) when `authInterceptor == null`. This means all write, delete, and admin operations are fully unauthenticated by default. The `--auth-token` flag is optional, so a deployment that forgets to set it is completely open.

**FIND-C48** `ConfigdServer.java:118-120:Medium:Stub transport silently drops all Raft messages`
```java
RaftTransport transport = (target, message) -> {
    // No-op: real transport wiring is done by the transport module
};
```
If the transport module fails to wire in the real transport, the Raft node will silently operate as a disconnected single node, never sending or receiving messages. There is no warning or health check that detects this condition.

**FIND-C49** `ConfigdServer.java:218-221:High:Uncaught exceptions in tick loop will kill the scheduled executor`
```java
tickExecutor.scheduleAtFixedRate(() -> {
    driver.tick();
    propagationMonitor.checkAll();
}, TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
```
If `driver.tick()` or `propagationMonitor.checkAll()` throws any unchecked exception, `ScheduledExecutorService.scheduleAtFixedRate` **silently stops scheduling future executions** (per JDK docs: "If any execution of the task encounters an exception, subsequent executions are suppressed"). The Raft tick loop stops, the node stops participating in consensus, and there is no alert or recovery. The tick body must be wrapped in a try-catch-log-and-continue.

**FIND-C50** `ConfigdServer.java:209-213:Medium:Tick executor thread is daemon -- JVM may exit before clean shutdown`
The tick thread is set as a daemon thread. If `main()` exits unexpectedly (e.g., InterruptedException on line 317), the JVM can terminate immediately without running the shutdown hook, because all remaining threads are daemon threads. The shutdown hook on line 224 would also not complete.

**FIND-C51** `ConfigdServer.java:224-227:Medium:Shutdown hook calls shutdown() which is not idempotent`
If `shutdown()` is called twice (e.g., via the shutdown hook and also from `main()` on line 318), the `httpApiServer.stop()` could be called on an already-stopped server. The JDK HttpServer's `stop()` does not document idempotency.

### HttpApiServer.java

**FIND-C52** `HttpApiServer.java:70-74:Critical:Health and metrics endpoints are unauthenticated by design but /metrics may leak sensitive information`
The `/health/live`, `/health/ready`, and `/metrics` endpoints do not go through `checkAuth()`. While health endpoints are typically public, the `/metrics` endpoint exposes internal metric names, counters, and invariant violation counts. An attacker can use this to fingerprint the system, detect configuration, and identify invariant violations without authentication.

**FIND-C53** `HttpApiServer.java:231:Medium:handlePut reads entire request body into memory without size limit`
```java
byte[] body = exchange.getRequestBody().readAllBytes();
```
While `ConfigWriteService.put()` validates `value.length > 1_048_576`, the body is already fully read into memory before that check. An attacker could send a multi-GB request body to cause OOM. The JDK `HttpServer` has a default max request size but it is very large.

**FIND-C54** `HttpApiServer.java:191:Medium:URL-decoded key may contain special characters not validated`
The key is extracted from the URL path via `path.substring(prefix.length())`. URL-encoded characters (e.g., `%2F` for `/`) are decoded by the HTTP server before the handler sees them. A key containing `/` would be misrouted by the HTTP server's path matching. However, the `createContext("/v1/config/")` prefix matching means any subpath is handled by ConfigHandler, so this is not exploitable for routing but could cause confusion with key semantics.

**FIND-C55** `HttpApiServer.java:153-162:Low:MetricsHandler duplicates Prometheus formatting logic instead of using PrometheusExporter`
The `/metrics` endpoint has its own Prometheus formatting logic that differs from `PrometheusExporter`. The handler does not suffix counters with `_total` and does not emit histogram percentile lines. This means the built-in `/metrics` endpoint produces non-standard Prometheus output while the `PrometheusExporter` class (in observability module) produces correct output -- but is not wired into the HTTP server.

---

## Mandatory Check Answers

### 1. HybridClock: Is it actually monotonic?
**NO**, under edge conditions. The logical counter can overflow (FIND-C03), causing timestamps to go backwards. Under normal operation with reasonable clock drift, it is monotonic. The CAS loop is not applicable here (synchronized is used, not CAS).

### 2. Storage: Are writes durable (fsync)?
**PARTIALLY**. FileStorage calls `channel.force(true)` after writes (lines 56, 96), which is correct. However, `put()` is not atomic (FIND-C09) -- crash during write corrupts data. The directory fsync in `sync()` is correct.

### 3. Auth: Is auth checked on EVERY endpoint?
**NO**. Health and metrics endpoints are unauthenticated (FIND-C52). More critically, auth is entirely disabled by default when `--auth-token` is not set (FIND-C47).

### 4. ACL: Is authorization checked after authentication?
**YES**, when enabled. `checkAuth()` in HttpApiServer correctly authenticates first, then checks ACL. However, the ACL implementation has concurrency bugs (FIND-C32, FIND-C34).

### 5. Rate limiting: Is it per-client?
**NO**. It is global (FIND-C35). A single client can exhaust the budget for all clients.

### 6. Health checks: Do they actually verify the system is healthy?
**PARTIALLY**. The only readiness check verifies Raft leadership. It does not check storage health, memory pressure, thread pool exhaustion, or transport connectivity. Liveness always returns true (by design). `detailed()` provides no additional diagnostics (FIND-C44).

### 7. InvariantMonitor: Does it detect violations? Does it crash production?
**YES** to both questions. It detects violations correctly. In production mode (`testMode=false`), violations are silently counted -- the system continues running. In test mode, it throws `AssertionError`. The dual-mode design is sound.

### 8. SLO tracking: Are SLOs correctly defined and measured?
**MOSTLY**. The SLO definitions match the documented targets. However, the 30-day window SLOs will consume unbounded memory (FIND-C25), and compliance computation is O(n) in event count (FIND-C19).

### 9. Buggify: Can fault injection leak into production?
**NO** -- `shouldFire()` returns `false` immediately when `simulationMode` is false. However, the seed is not used (FIND-C07), defeating deterministic replay, and the RandomGenerator is not thread-safe (FIND-C06).

### 10. Server startup: Is shutdown clean?
**MOSTLY**. Shutdown stops the HTTP server and tick executor with timeout. However, the tick loop can silently die from uncaught exceptions (FIND-C49), the shutdown hook is not idempotent (FIND-C51), and daemon threads may cause premature JVM exit (FIND-C50).

---

## Top 5 Findings by Risk (Action Required)

1. **FIND-C49 (Critical/High)**: Tick loop dies silently on any uncaught exception. The entire consensus mechanism stops. Fix: wrap tick body in try-catch.

2. **FIND-C47 (Critical)**: Auth disabled by default. Any deployment without `--auth-token` is fully open to unauthorized writes and deletes.

3. **FIND-C37 (Critical)**: Failed linearizable reads return NOT_FOUND instead of an error. Clients will incorrectly believe keys are deleted.

4. **FIND-C35 (Critical)**: Global rate limiter enables trivial DoS by a single client.

5. **FIND-C09 (High)**: Non-atomic put() in FileStorage can corrupt Raft persistent state on crash.
