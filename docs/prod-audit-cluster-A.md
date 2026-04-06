# Production Audit — Cluster A (Hot-Path Modules)

**Date.** 2026-04-17. Commit `22d2bf3`.

**Scope.**
- `configd-edge-cache/src/main/java/io/configd/edge/**` (read-path; lock-free, no allocation, no logging required)
- `configd-config-store/src/main/java/io/configd/store/**` (HAMT, MVCC, Raft state machine, signing)
- `configd-common/src/main/java/io/configd/common/**` (utilities, HLC, Bytes, key/value codecs)

Corresponding `src/test/java` trees inspected for coverage-gap findings only.

**Does not duplicate** the V2 Remediation Register (`docs/verification/findings/F-0009.md` through `F-V7-01.md`). R-series residuals noted:
- R-01 (F-0052 distribution-hop verification) — out of scope for Cluster A.
- R-13 (AuditLogger missing) — out of scope.

If a hot-path problem related to those R items is newly introduced, it is filed here.

---

## Lines of Code Tally

| Module | LoC (main) |
|--------|------------|
| `configd-common/src/main/java` (11 files) | 747 |
| `configd-config-store/src/main/java` (14 files) | 2,939 |
| `configd-edge-cache/src/main/java` (9 files) | 1,328 |
| **Main total** | **5,014** |

Every file listed was read in full with the `Read` tool; the `wc -l` pass
confirms the LoC tally matches the file list enumerated from `find`.

Test trees inspected for coverage gaps but not counted toward the main LoC
number. Counts: `configd-common/src/test/java` 6 files, `configd-config-store/src/test/java`
12 files, `configd-edge-cache/src/test/java` 8 files.

## Severity Rubric

- **S0** — Data loss, ordering violation, security bypass, or undetected SLO breach.
- **S1** — GA-blocking: perf SLO miss, correctness regression, or missing observability for a known failure mode.
- **S2** — Hardening / UX degradation (error handling, operability, spec divergence that is safe but surprising).
- **S3** — Hygiene: style, test coverage, minor allocation on cold path.

## Hard rules checked (any violation ≥ S1)

1. No allocation on read path
2. No locks on read path (volatile + HAMT only)
3. No logging on read path
4. mTLS / signature verification on every cross-trust boundary
5. Bounded queues / executors everywhere
6. No `System.err.println` instead of structured logger
7. Time uses HLC, not raw `System.currentTimeMillis` for ordering
8. No silent exception swallowing
9. No public mutable state
10. All error paths have a metric

---

## Summary Table

| ID | Sev | Area | One-liner |
|----|-----|------|-----------|
| PA-1001 | S0 | hot-path / correctness | `VersionedConfigStore.put/delete/applyBatch` stamps values with raw `System.currentTimeMillis()`, not HLC — causal ordering broken on wall-clock skew |
| PA-1002 | S0 | hot-path / correctness | `ConfigStateMachine.restoreSnapshot` discards original HLC timestamps; all restored entries get the same wall-clock time |
| PA-1003 | S0 | spec / write-path | `CommandCodec.encodePut/encodeDelete` unchecked `(short)keyBytes.length` cast silently truncates keys > 65535 bytes |
| PA-1004 | S0 | security | `ConfigStateMachine.signCommand` silently downgrades to unsigned on signing failure — edge nodes reject the delta and gap-recover, no metric escalates the fault |
| PA-1005 | S1 | concurrency | `BuggifyRuntime` uses non-thread-safe `L64X128MixRandom` across threads; the static `random` field is also swapped without synchronization |
| PA-1006 | S1 | observability | `DeltaApplier` rejects (unsigned / invalid-sig / replay) log but have no metric — rule 10 violation |
| PA-1007 | S1 | memory / write-path | `ConfigStateMachine.apply` allocates a full `ReadResult` per PUT via `store.get(put.key())` for invariant check, then a `ConfigMutation.Put` whose compact constructor clones the value bytes again |
| PA-1008 | S1 | durability | `SigningKeyStore.generateAndWrite` writes the keypair via `Files.write` without fsync or parent-directory fsync; crash loses the fresh key and next boot mints a new identity |
| PA-1009 | S1 | durability | `FileStorage.renameLog` performs atomic rename but never fsyncs the parent directory — rename not durable across power loss |
| PA-1010 | S1 | memory | `FileStorage.readLog` casts `long fileSize` to `int` — silently truncates WAL files > 2 GiB |
| PA-1011 | S1 | security | `ConfigDelta.signature()` record accessor returns a defensive copy on every call; `DeltaApplier.offer` calls it once per delta, producing a hot-path 64-byte allocation and a timing channel on signature content |
| PA-1012 | S1 | observability | `DeltaApplier` uses `java.util.logging` with string concatenation before level filter; a flood of invalid deltas amplifies to full allocation cost |
| PA-1013 | S1 | correctness | `HybridTimestamp.packed()` silently loses data when `wallTime >= 2^48`; `fromPacked` cannot reconstitute it — also inconsistent with `HybridClock.encode` which is identical but documented |
| PA-1014 | S2 | memory / cold-path | `ConfigSigner.sign/verify` call `Signature.getInstance("Ed25519")` per invocation — provider lookup on every signature check on the edge |
| PA-1015 | S2 | memory / cold-path | `ConfigStateMachine.snapshot()` materializes two `ArrayList`s (keys, values) and a full ByteBuffer — peak 3× heap for large snapshots |
| PA-1016 | S2 | silent swallow | `SigningKeyStore.generateAndWrite` swallows `UnsupportedOperationException` from `setPosixFilePermissions` without log/metric |
| PA-1017 | S2 | correctness / edge | `StalenessTracker` constructor does `clock.nanoTime() - (DISCONNECTED_THRESHOLD_MS + 1) * 1_000_000L`; if `nanoTime()` is near `Long.MIN_VALUE`, this underflows to a large positive `stalenessMs` |
| PA-1018 | S2 | API hygiene | `VersionedValue.value()` record accessor clones on every call — any caller that does not know to use `valueUnsafe()` silently pays a byte-array copy |
| PA-1019 | S2 | API hygiene | `LocalConfigStore.metrics()` calls `store.snapshot().size()` which is O(1) but inspects `ConfigSnapshot` constructed freshly — four volatile reads performed independently so version/staleness/count are not a consistent point-in-time |
| PA-1020 | S2 | write-path | `LocalConfigStore.applyDelta` uses wall clock for the `VersionedValue.timestamp`, same root cause as PA-1001 but on the edge apply path |
| PA-1021 | S2 | security | `ConfigSigner` creates a new `Signature` per call "for thread safety" — no defensive verify-payload size limit, so any large `byte[]` a peer hands us causes a large `SunEC` allocation |
| PA-1022 | S2 | correctness | `BloomFilter.murmurHash3` mixes only low-byte of each char (`h ^= key.charAt(i)`); non-ASCII keys get degraded distribution and higher false-positive rate |
| PA-1023 | S2 | dead code | `SigningKeyStore.writeForTest` calls `PosixFilePermissions.fromString("rw-------")` and discards the result — test file gets default perms |
| PA-1024 | S2 | durability | `FileStorage.appendToLog` opens a new `FileChannel` + allocates a new `CRC32` + `ByteBuffer` per append — no fsync-on-create-only, so first append after WAL rollover does not directory-fsync |
| PA-1025 | S3 | test gap | No concurrency test for `ConfigSigner` despite Javadoc claim of thread safety |
| PA-1026 | S3 | test gap | No allocation regression test for the cursor-bound `LocalConfigStore.get(key, cursor)` path — only the non-cursor variant is verified |
| PA-1027 | S3 | hygiene | `DeltaApplier` has no `@VisibleForTesting` or package-private accessor for `highestSeenEpoch` to reset in tests; constructor always reads from `client.currentVersion()` |
| PA-1028 | S3 | hygiene | `ConfigStateMachine.lastSignature/lastEpoch/lastNonce` are returned via the raw Record API (defensive clone); call sites allocate repeatedly in the distribution hop |
| PA-1029 | S3 | test gap | `BuggifyRuntime` has no concurrent-call test; the thread-safety hole in PA-1005 slipped past review because no test exercises it |
| PA-1030 | S3 | hygiene | `Compactor.compact` reads `history.size()` twice in the loop to guard against racing adds, but under sustained concurrent adds the loop can spin unnecessarily; no yield / back-off |
| PA-1031 | S3 | hygiene | `PrefixSubscription.prefixes()` always allocates a fresh `LinkedHashSet` wrapping the COW set — metrics path calls this in `EdgeConfigClient.metrics()` on every invocation |
| PA-1032 | S3 | hygiene | `Buggify` annotation exists in `configd-common` but has no processor / retention policy wiring; annotation is never read at runtime |
| PA-1033 | S3 | correctness | `PoisonPillDetector.recordSuccess` removes failure counter unconditionally but does not un-quarantine a key — a previously poisoned key cannot self-heal without manual `release()` |
| PA-1034 | S3 | hygiene | `ConfigValidator.findLongestPrefixValidator` worst-case O(map size) on pathological prefix hierarchies with same floor key but mismatching startsWith — rare but unbounded |
| PA-1035 | S3 | hygiene | `HybridClock` hot-path `now()` CAS loop has no bounded retry or jitter — under extreme contention it can spin indefinitely (possible in microbenchmarks with >>cores threads) |

---

## Findings

### S0 — Ordering / correctness / data-integrity violations

### PA-1001 — VersionedConfigStore stamps values with raw wall-clock, not HLC (S0)
**Location:** `configd-config-store/src/main/java/io/configd/store/VersionedConfigStore.java:100, 120, 143`
**Category:** hot-path
**Evidence:**
```java
public void put(String key, byte[] value, long sequence) {
    ...
    long timestamp = clock.currentTimeMillis();
    VersionedValue vv = new VersionedValue(value, sequence, timestamp);
```
Same pattern in `delete` (line 120) and `applyBatch` (line 143).
**Impact:** `VersionedValue.timestamp` is consumed by downstream readers (edge metrics, staleness computations, audit surfaces) to reason about write time. Because the writer uses a raw wall clock, a 500 ms reverse NTP step on the leader produces two consecutive `VersionedValue`s where `v2.timestamp < v1.timestamp` even though `v2.version > v1.version`. Any consumer that sorts by timestamp (replication catch-up, cross-region merge, audit reconstruction) observes reordering. The Cluster-A scope explicitly forbids this (Rule 7: "Time uses HLC, not raw `System.currentTimeMillis` for ordering"). S0 because the store's monotonic-version invariant is the only thing holding; any external tool that trusts `VersionedValue.timestamp` silently breaks.
**Fix direction:** Inject a `HybridClock` and write `HybridClock.physicalOf(hlc.now())` into the `VersionedValue.timestamp` field, or widen the field to a packed HLC long and re-type `VersionedValue.timestamp()` accordingly. Keep the wall-clock field as an advisory secondary field if desired. Existing `HybridClock.now()` is zero-allocation and returns a packed long.
**Owner:** config-store

### PA-1002 — restoreSnapshot discards original timestamps, stamps everything with restore-time wall clock (S0)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:334, 366`
**Category:** hot-path
**Evidence:**
```java
long timestamp = clock.currentTimeMillis();
for (int i = 0; i < entryCount; i++) {
    ...
    VersionedValue vv = new VersionedValue(value, restoredSequence, timestamp);
    data = data.put(key, vv);
}
```
**Impact:** After an `InstallSnapshot` is applied, every restored key carries the same timestamp — the wall-clock time at the moment of restore. The `VersionedValue.timestamp` field is therefore meaningless post-restore. Any cross-key ordering a consumer derived from the original timestamps is lost. Combined with PA-1001 this is a silent SLO breach against the V2 contract claim that `VersionedValue.timestamp` reflects write time. The snapshot binary format (line 31-36) stores only `[key][value]`, not `[key][value][timestamp]`, so there is no way to reconstruct the original — the *format* itself is lossy. S0 because the audit trail is corrupted on every snapshot restore, and operators have no signal.
**Fix direction:** Extend the snapshot format to include per-entry `[timestamp:long]` (8 bytes × N). Bump the snapshot-format version byte. Fall back to `clock.currentTimeMillis()` only when reading an old-format snapshot, with a metric `configd.snapshot.legacy_format` to detect the compatibility path is in use.
**Owner:** config-store

### PA-1003 — CommandCodec write path silently truncates keys > 65535 bytes (S0)
**Location:** `configd-config-store/src/main/java/io/configd/store/CommandCodec.java:63, 83`
**Category:** spec
**Evidence:**
```java
ByteBuffer buf = ByteBuffer.allocate(1 + 2 + keyBytes.length + 4 + value.length);
buf.put(TYPE_PUT);
buf.putShort((short) keyBytes.length);  // silent narrowing
buf.put(keyBytes);
```
F-0013 (closed) widened the snapshot format to 4-byte lengths, but the Raft-command encoder was never updated.
**Impact:** A 70 000-byte key produces `(short) 70000 == 4464`. The encoder still writes 70 000 raw key bytes, but the decoder reads 4464 and mis-aligns the rest of the batch. The commit is poisoned: every follower decodes a different command, breaks the Raft log-matching property, and the state machine applies divergent data across replicas. This is the classic F-0013 class of bug re-introduced on the write path. S0 — breaks State Machine Safety.
**Fix direction:** Widen the key-length header to 4 bytes (with bounds check against `MAX_SNAPSHOT_KEY_LEN = 1 MiB`) — same remediation as F-0013. Add a pre-encode assertion `keyBytes.length <= 65535` on the legacy path, and bump the command-type byte to `0x04/0x05/0x06` for the widened variant with a one-release-window fallback decoder.
**Owner:** config-store

### PA-1004 — signCommand silently downgrades to unsigned on signer failure (S0)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:440-465`
**Category:** security
**Evidence:**
```java
try {
    ...
    lastSignature = signer.sign(buf.array());
    lastEpoch = epoch;
    lastNonce = nonce;
} catch (GeneralSecurityException e) {
    LOG.log(Level.WARNING, "Failed to sign applied command", e);
    lastSignature = null;
    lastEpoch = 0L;
    lastNonce = null;
}
```
**Impact:** When signing throws (expired key, crypto provider failure, HSM outage), the apply path swallows the error and nullifies `lastSignature`. The state machine continues to advance — the write is durable and applied to the store — but the delta leaving the leader has no signature. Edges with `DeltaApplier(verifier)` configured will reject it as `UNSIGNED_REJECTED`, request a full snapshot, and the snapshot itself is unsigned too (F-0052 only signs deltas). Net effect: a silent security downgrade that operators only detect by noticing edge gap-storms. There is no metric on this path, no back-pressure on the apply thread, and no `InvariantChecker` wiring. S0 because this is exactly the "security claim vs. actual wiring" regression class that F-0052 was meant to close.
**Fix direction:** Introduce `configd.sign.failure` counter and bump it in the catch. Escalate after N consecutive failures by transitioning the node to read-only (refuse to serve `apply` until operator intervention). At minimum, propagate to the apply-thread so Raft itself halts — a leader that cannot sign should step down rather than continue producing unsigned commits.
**Owner:** config-store

---

### S1 — GA-blocking

### PA-1005 — BuggifyRuntime uses non-thread-safe RNG concurrently (S1)
**Location:** `configd-common/src/main/java/io/configd/common/BuggifyRuntime.java:15-43`
**Category:** concurrency
**Evidence:**
```java
private static RandomGenerator random =
        RandomGeneratorFactory.of("L64X128MixRandom").create(0L);
...
public static boolean shouldFire(String pointId, double probability) {
    if (!simulationMode) return false;
    boolean enabled = enabledPoints.computeIfAbsent(pointId,
        k -> random.nextDouble() < 0.5);
    if (!enabled) return false;
    return random.nextDouble() < probability;
}
```
**Impact:** `L64X128MixRandom` (a `SplittableGenerator`) is explicitly documented as non-thread-safe by the JDK. Multiple Raft / replication / edge threads calling `Buggify.shouldFire(...)` concurrently in simulation mode can corrupt the generator's internal state, producing `NaN`, biased values, or undefined behaviour. Worse: `enableSimulationMode` swaps the `random` field without synchronization, so callers in flight may see the old generator, the new generator, or null (under a concurrent race). In simulation / chaos runs this means seeds are *not* reproducible even when set — violating the "same seed = same execution" invariant advertised in `docs/prr/chaos-report.md`. The prior verification-run audit `verification/code-audit/common-observability-api-server/audit-report.md` already flagged this; it has not been fixed. S1 because determinism is a stated property.
**Fix direction:** Replace the static field with `ThreadLocal<RandomGenerator>` seeded from the master seed, or wrap access in a lock. Alternatively, forbid simulation mode in multi-threaded tests and assert single-threaded entry.
**Owner:** common

### PA-1006 — DeltaApplier rejection paths log but have no metric (S1)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java:141-177`
**Category:** observability
**Evidence:**
```java
if (verifier == null && signature != null) {
    LOG.warning("Rejecting signed delta [...]: no verifier configured");
    return ApplyResult.UNSIGNED_REJECTED;
}
...
return ApplyResult.SIGNATURE_INVALID;
...
return ApplyResult.REPLAY_REJECTED;
```
**Impact:** Rule 10 explicitly requires every error path to carry a metric. `DeltaApplier` is the primary cross-trust boundary on the read side — every delta from the distribution service passes through it. If an attacker forges signed deltas, or if a misconfigured verifier rejects legitimate ones, operators have no numeric signal: they must tail JUL logs. Under an active attack (flood of invalid signatures) the log path allocates strings for every rejection (see PA-1012). S1 because this is an observability gap on a known failure mode (F-0052 scope).
**Fix direction:** Wire a `Metrics` interface (functional, like `InvariantChecker`) into the constructor. Increment per-result counters: `configd.edge.delta.rejected{reason="unsigned"|"sig_invalid"|"replay"|"stale"|"gap"}`. Consider rate-limiting the log line to avoid amplification.
**Owner:** edge-cache

### PA-1007 — apply-path double-allocates per PUT (S1)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:221, 229`
**Category:** memory
**Evidence:**
```java
ReadResult existing = store.get(put.key());  // allocates ReadResult on hit
if (existing.found()) {
    invariantChecker.check("per_key_order", seq > existing.version(), ...);
}
store.put(put.key(), put.value(), seq);
signCommand(command);
notifyListeners(List.of(new ConfigMutation.Put(put.key(), put.value())), seq);
//                       ^^^                       ^^^
//            byte[] clone inside ConfigMutation.Put compact constructor
//                   because put.value() itself already clones (via
//                   DecodedCommand.Put canonical accessor), and then the
//                   new ConfigMutation.Put clones again.
```
**Impact:** Steady-state leader commit is in the 100-400K ops/s range per ADR-0012. Each PUT allocates: (a) a `ReadResult` even though only `version()` is read, (b) a `java.util.List` (`List.of` allocates a fresh `ImmutableCollections$List1`), (c) a `ConfigMutation.Put` whose compact constructor clones the value bytes, and (d) if `DecodedCommand.Put.value()` is the record-canonical accessor it hands back the decode buffer reference. For a 1 KiB value at 400 K ops/s that is ~400 MB/s of pointless GC pressure. S1 because it is measurable throughput loss on the hot write path against the stated SLO.
**Fix direction:** Use `store.getInto` with a reusable scratch buffer for the invariant check. Pre-allocate a single-element `List<ConfigMutation>` wrapper (or add `notifyListeners(ConfigMutation mutation, long version)` overload that does not wrap). Keep the byte-clone only at the trust boundary (decode) — internal consumers use `valueUnsafe`.
**Owner:** config-store

### PA-1008 — SigningKeyStore does not fsync the generated key file (S1)
**Location:** `configd-config-store/src/main/java/io/configd/store/SigningKeyStore.java:111-150`
**Category:** security / durability
**Evidence:**
```java
Files.write(path, buf.array(),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
// ← no channel.force(true); no parent-directory fsync
try {
    Files.setPosixFilePermissions(path, ...);
} catch (UnsupportedOperationException ignored) {}
```
**Impact:** When a new leader bootstraps with no pre-provisioned key file, it generates one, writes it without fsync, and proceeds to sign commits. On a crash-and-restart in that window the file is absent or truncated. `loadOrCreate` then either re-generates (new `keyId`, breaks the F-0052 signature chain for all prior deltas) or throws on a short file. The chain-of-custody advertised by F-0052 is therefore best-effort on the first boot. S1 — security posture silently downgraded on power loss.
**Fix direction:** Replace `Files.write` with an explicit `FileChannel` open, `force(true)`, then `Files.move` the temp file into place, then fsync the parent directory (see `FileStorage.put` line 66-70 for the pattern that already exists in common).
**Owner:** config-store

### PA-1009 — FileStorage.renameLog does not fsync parent directory (S1)
**Location:** `configd-common/src/main/java/io/configd/common/FileStorage.java:186-197`
**Category:** durability
**Evidence:**
```java
@Override
public void renameLog(String fromLogName, String toLogName) {
    Path from = directory.resolve(fromLogName + ".wal");
    Path to = directory.resolve(toLogName + ".wal");
    try {
        Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING);
    } catch (IOException e) { ... }
    // no sync() call afterwards
}
```
**Impact:** ATOMIC_MOVE renames the directory entries but the entry change itself is only durable after a directory fsync. F-0012 (closed) added a directory fsync after `truncateFrom` precisely because of this class of bug; `renameLog` is the twin path used by WAL compaction and the fsync is absent. Under power loss after a successful rename, the old name can reappear, the new name can vanish, or both entries can coexist. S1 because the compactor / WAL rewrite paths rely on this primitive being durable.
**Fix direction:** Call `sync()` after the `Files.move` succeeds (the existing helper at line 200 already performs directory fsync).
**Owner:** common

### PA-1010 — FileStorage.readLog truncates WAL files > 2 GiB (S1)
**Location:** `configd-common/src/main/java/io/configd/common/FileStorage.java:122-134`
**Category:** correctness
**Evidence:**
```java
long fileSize = channel.size();
if (fileSize == 0) return Collections.emptyList();
ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
while (buffer.hasRemaining()) {
    if (channel.read(buffer) == -1) break;
}
```
**Impact:** For a WAL that has grown past `Integer.MAX_VALUE` bytes, the `(int) fileSize` cast wraps to a negative number and `ByteBuffer.allocate` throws `NegativeArraySizeException` or (if the file is just under 4 GiB) silently reads only the low 32 bits of the size — losing the rest of the log. A 2 GiB WAL is reachable after a few hours at steady write throughput if compaction is stalled. S1 — data loss on recovery path.
**Fix direction:** Stream-decode the framed format rather than allocating the full file. Keep a `maxReplayBytes` cap and error out explicitly if exceeded, with a metric `configd.wal.oversize` so operators can intervene.
**Owner:** common

### PA-1011 — ConfigDelta.signature() accessor clones on every call (S1)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigDelta.java:95, 102` and caller `DeltaApplier.offer:136`
**Category:** memory / security
**Evidence:**
```java
// ConfigDelta
@Override
public byte[] signature() {
    return signature != null ? signature.clone() : null;
}
// DeltaApplier
byte[] signature = delta.signature();   // ~64 bytes clone per delta
```
**Impact:** Every delta arriving at the edge triggers a 64-byte Ed25519 signature clone, even before the verifier runs. At steady state of 400 K deltas/s distributed to 1000 edges this is 25 GB/s of GC pressure *per cluster*. Additionally, the clone pattern is implemented via `byte[].clone()` which uses `Arrays.copyOf` — not a constant-time operation for any side-channel-sensitive use. A timing side-channel on signature content is plausible. S1 for GC pressure; S2 for the side-channel concern independently.
**Fix direction:** Add a package-private `signatureUnsafe()` accessor that returns the internal reference (mirror `valueUnsafe()` convention used elsewhere in the module). Have `DeltaApplier` use it. Document the pattern.
**Owner:** config-store

### PA-1012 — DeltaApplier warn-logs concatenate strings eagerly (S1)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java:142, 151, 158, 163, 173`
**Category:** observability / memory
**Evidence:**
```java
LOG.warning("Rejecting signed delta [" + delta.fromVersion()
        + " -> " + delta.toVersion()
        + "]: no verifier configured on this DeltaApplier");
```
**Impact:** JUL's `warning(String)` evaluates the concatenation *before* checking the log level. Under a flood of forged deltas (the exact scenario F-0052 protects against), each rejection allocates a StringBuilder plus the interpolated Long / Long / String temporaries. Adversary can therefore turn rejection into an amplified GC-pressure attack even though the verifier correctly says "no". S1 for an observability path that doubles as an availability risk.
**Fix direction:** Use parameterized `LOG.log(Level.WARNING, () -> "...", e)` with a `Supplier<String>` so the string is only built if the level is enabled, or switch to SLF4J / JUL's parameterized variant. Add rate-limiting (one log every N ms per rejection reason).
**Owner:** edge-cache

### PA-1013 — HybridTimestamp.packed silently loses data at wallTime >= 2^48 (S1)
**Location:** `configd-common/src/main/java/io/configd/common/HybridTimestamp.java:28-34`
**Category:** correctness
**Evidence:**
```java
public long packed() {
    return (wallTime << 16) | (logical & 0xFFFF);
}

public static HybridTimestamp fromPacked(long packed) {
    return new HybridTimestamp(packed >>> 16, (int) (packed & 0xFFFF));
}
```
**Impact:** If `wallTime >= 2^48 (2.8e14 ms)`, the top 16 bits are silently truncated. 2^48 ms is year 10889 so not a calendar concern, but the same `packed()` encoding is used as the *transport* representation in some caller paths and could be populated with a non-wall-clock value (e.g., a test harness, a deterministic simulation clock that passes a large long). Unlike `HybridClock.encode` the sibling method in `HybridClock.java:58`, `HybridTimestamp.packed()` provides no upper-bound assertion and no negative-result indication. Two values that differ only in bits 48-63 pack to the same long, violating injectivity. S1 because deterministic-simulation tests routinely pass large constants and the bug surfaces as a silent collision.
**Fix direction:** Add `assert (wallTime & ~((1L << 48) - 1)) == 0` or throw `IllegalStateException` — inconsistent behaviour between `HybridTimestamp.packed` and `HybridClock.encode` (both have the same bug actually; they should share a helper and both check). Document the 48-bit ceiling in Javadoc.
**Owner:** common

---

### S2 — Hardening / UX

### PA-1014 — ConfigSigner calls Signature.getInstance per invocation (S2)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigSigner.java:56, 73`
**Category:** memory / hot-path
**Evidence:**
```java
public byte[] sign(byte[] data) throws GeneralSecurityException {
    ...
    Signature sig = Signature.getInstance(ALGORITHM);   // provider lookup
    sig.initSign(signingKey);
    sig.update(data);
    return sig.sign();
}
```
**Impact:** `Signature.getInstance` performs a JCA provider lookup on every call. The Javadoc justifies per-call construction by "Signature objects are not thread-safe", but the right fix is `ThreadLocal<Signature>` or a small pool. On the edge verify path (called per delta) this is a few microseconds overhead and ~500 B of JCA-internal temporaries per call. For a control plane pushing 100K deltas/s fanned out to 1K edges, this is measurable.
**Fix direction:** `ThreadLocal<Signature>` per algorithm, reset-on-use via `sig.initVerify(key)`. Measure and document.
**Owner:** config-store

### PA-1015 — snapshot() peaks at 3× heap for large state (S2)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:270-303`
**Category:** memory
**Evidence:**
```java
List<byte[]> keys = new ArrayList<>();
List<byte[]> values = new ArrayList<>();
snap.data().forEach((key, vv) -> {
    keys.add(key.getBytes(StandardCharsets.UTF_8));
    values.add(vv.valueUnsafe());
});
...
int size = 8 + 4;
for (int i = 0; i < keys.size(); i++) { size += 4 + keys.get(i).length + 4 + values.get(i).length; }
ByteBuffer buf = ByteBuffer.allocate(size);
```
**Impact:** Taking a snapshot of 100 M entries at 256 B average value produces: (a) ~24 GB of byte[] references in `values` (though values share internal data), (b) ~2.4 GB of key byte arrays from `getBytes(UTF_8)`, (c) another ~26 GB allocated contiguously for the `ByteBuffer`. That is a 2× peak heap over the HAMT itself, before GC sees it. Large clusters can OOM during snapshot take. S2 — cold path, but operators typically trigger it during incident response.
**Fix direction:** Stream the snapshot to an `OutputStream` (or `FileChannel`) in one pass; do not materialize intermediate lists. Use `HamtMap.forEach` with a callback that encodes directly.
**Owner:** config-store

### PA-1016 — setPosixFilePermissions failure silently swallowed (S2)
**Location:** `configd-config-store/src/main/java/io/configd/store/SigningKeyStore.java:142-147`
**Category:** security / observability
**Evidence:**
```java
try {
    Files.setPosixFilePermissions(path,
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
} catch (UnsupportedOperationException ignored) {
    // non-POSIX filesystem
}
```
**Impact:** Rule 8 (no silent exception swallowing). On a non-POSIX filesystem the signing key file is world-readable by default. The comment acknowledges the case but there is no log line and no metric — operators on Windows / exFAT have no signal that their private key is world-readable. S2.
**Fix direction:** Log at WARN level ("private key file created with platform-default permissions; verify manually") and increment `configd.sign.key_perms_unverified`. Consider failing startup when a non-POSIX FS is detected and no override is supplied.
**Owner:** config-store

### PA-1017 — StalenessTracker initializer can underflow on extreme nanoTime (S2)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java:80`
**Category:** correctness
**Evidence:**
```java
// Initialize to a very old time so initial state is DISCONNECTED
this.lastUpdateNanos = clock.nanoTime() - (DISCONNECTED_THRESHOLD_MS + 1) * 1_000_000L;
```
**Impact:** `System.nanoTime()` has arbitrary origin; the JDK docs explicitly warn that the returned value can be negative. On a freshly started JVM whose nanoTime is close to `Long.MIN_VALUE + (DISCONNECTED_THRESHOLD_MS + 1) * 1_000_000L`, this subtraction overflows to a large positive long. Then `stalenessMs()` at line 152 computes `now - lastUpdateNanos` which again overflows — resulting staleness is reported as a small number, not a large one, so the tracker reports `CURRENT` instead of `DISCONNECTED` at startup. Platform-dependent; rare, but deterministic enough to hit in chaos testing. S2.
**Fix direction:** Store `lastUpdateNanos` as a signed delta only, and use `Long.max(elapsedNanos, 0)` in `stalenessMs()`. Or initialize `lastUpdateNanos = clock.nanoTime()` and set an explicit `initialised` flag.
**Owner:** edge-cache

### PA-1018 — VersionedValue.value() silently clones the bytes (S2)
**Location:** `configd-config-store/src/main/java/io/configd/store/VersionedValue.java:36-38`
**Category:** api
**Evidence:**
```java
@Override
public byte[] value() {
    return value.clone();
}
```
**Impact:** Java records expose a canonical accessor by the component name, but this class overrides it to `clone()`. Callers who read `vv.value()` reasonably expecting a reference get a 512 B copy instead. Any new callsite that forgets to use `valueUnsafe()` silently pays. This is a footgun — the distinction between `value()` and `valueUnsafe()` is enforced only by code review. S2 hygiene.
**Fix direction:** Mark the record component as an internal `byte[] bytes` and expose *only* two explicit methods: `ByteBuffer asReadOnlyBuffer()` (safe zero-copy) and `byte[] copy()`. Remove the `value()` override confusion. Or at minimum, annotate `@Deprecated` on `value()` and redirect all intra-module callers to `valueUnsafe`.
**Owner:** config-store

### PA-1019 — EdgeConfigClient.metrics is not a consistent point-in-time read (S2)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/EdgeConfigClient.java:194-202`
**Category:** api
**Evidence:**
```java
public EdgeMetrics metrics() {
    return new EdgeMetrics(
            store.currentVersion(),                    // volatile read #1
            stalenessTracker.stalenessMs(),            // volatile reads #2-3
            stalenessTracker.currentState(),           // volatile reads #4-5 (again!)
            subscriptions.prefixes().size(),            // COW copy + allocation
            store.snapshot().size()                    // volatile read #6
    );
}
```
**Impact:** Six volatile reads over an interval during which the writer can advance both snapshot and staleness. Javadoc acknowledges the race but does not mitigate — and `subscriptions.prefixes()` in `PrefixSubscription` line 84 allocates a `LinkedHashSet` *and* an unmodifiable wrapper every call. Under a metrics scrape at 10 Hz × 1K edges this is 10 K unnecessary allocations/s. S2 because monitoring paths are supposed to be cheap.
**Fix direction:** Read `currentSnapshot` once, derive `currentVersion` and `snapshot.size()` from it. Read `stalenessTracker.lastUpdateNanos` once. Expose `subscriptions.size()` directly. Combine into a single allocation of `EdgeMetrics`.
**Owner:** edge-cache

### PA-1020 — LocalConfigStore.applyDelta stamps with wall clock (S2)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java:241`
**Category:** hot-path
**Evidence:**
```java
long timestamp = clock.currentTimeMillis();
for (ConfigMutation mutation : delta.mutations()) {
    switch (mutation) {
        case ConfigMutation.Put put -> {
            VersionedValue vv = new VersionedValue(put.valueUnsafe(), delta.toVersion(), timestamp);
```
**Impact:** Same root cause as PA-1001 but on the edge apply path. The edge rewrites the leader's timestamp with its own wall clock — so the `VersionedValue.timestamp` observable from the edge is the *edge wall clock time at application*, not the leader commit time. Two edges served the same delta produce different `timestamp` values. S2 because edge consumers rarely read `timestamp`, but the spec claim that timestamp reflects the HLC of the write is falsified.
**Fix direction:** Propagate the leader's HLC through the delta (bind it into the batch payload or into the `VersionedValue` format), and have the edge use it verbatim. Fall back to `clock.currentTimeMillis()` only when the delta predates the extension.
**Owner:** edge-cache

### PA-1021 — ConfigSigner.verify accepts unbounded payload size (S2)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigSigner.java:70-83`
**Category:** security
**Evidence:**
```java
public boolean verify(byte[] data, byte[] signature) throws GeneralSecurityException {
    Objects.requireNonNull(data, "data must not be null");
    Objects.requireNonNull(signature, "signature must not be null");
    Signature sig = Signature.getInstance(ALGORITHM);
    sig.initVerify(verifyKey);
    sig.update(data);
    ...
}
```
**Impact:** An attacker who can deliver a delta to the edge can send a multi-MiB `signingPayload` before the verifier runs. `Signature.update` internally buffers the whole message and hashes it; a 100 MiB payload causes ~100 MiB of temporary allocations and CPU even though the signature is obviously wrong. Resource-exhaustion DoS. S2 for the edge; S1 if the edge runs alongside customer traffic and Ed25519 verify time is load-bearing.
**Fix direction:** Add a `MAX_VERIFY_PAYLOAD` cap (e.g., `MAX_BATCH_COUNT * (MAX_KEY + MAX_VALUE) + overhead`) and short-circuit with `return false` and a metric before calling `update`.
**Owner:** config-store

### PA-1022 — BloomFilter.murmurHash3 ignores high byte of non-ASCII chars (S2)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/BloomFilter.java:113-130`
**Category:** correctness
**Evidence:**
```java
private static int murmurHash3(String key, int seed) {
    int h = seed;
    for (int i = 0; i < key.length(); i++) {
        h ^= key.charAt(i);      // only low 16 bits of char are consumed
        h *= 0xcc9e2d51;
        ...
    }
    ...
}
```
**Impact:** `key.charAt(i)` returns a `char` (16-bit), but `murmurHash3` feeds it via `h ^= ...` which already uses 16 bits. This is actually fine for Unicode BMP. BUT the hash update is per-char rather than per-UTF-8-byte, and strings outside the BMP (surrogate pairs) hash to the same value as their two surrogate halves in any order. Collision rate rises noticeably on emoji-heavy key spaces. S2 — not a correctness failure (Bloom still rejects correctly if not in set; false positives just rise) but a distribution regression relative to the stated 0.82 % FPR.
**Fix direction:** Hash `key.getBytes(UTF_8)` instead (one allocation per insert/lookup — acceptable on the `mightContain` path) or switch to JDK `String.hashCode()` plus a spreader.
**Owner:** edge-cache

### PA-1023 — SigningKeyStore.writeForTest never applies permissions (S2)
**Location:** `configd-config-store/src/main/java/io/configd/store/SigningKeyStore.java:170`
**Category:** api / dead-code
**Evidence:**
```java
Files.write(path, buf.array(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
PosixFilePermissions.fromString("rw-------");
```
**Impact:** The final line parses a permission string and discards the result. The test helper therefore never restricts the written file. Test keys end up with default permissions. Not a production bug, but demonstrates that no reviewer ran the helper end-to-end. S2 because this is a `security` test helper that does not do the security thing.
**Fix direction:** `Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))`. Or delete the line.
**Owner:** config-store

### PA-1024 — FileStorage.appendToLog opens/closes a FileChannel per append (S2)
**Location:** `configd-common/src/main/java/io/configd/common/FileStorage.java:89-114`
**Category:** durability / performance
**Evidence:**
```java
@Override
public void appendToLog(String logName, byte[] data) {
    Path file = directory.resolve(logName + ".wal");
    try (FileChannel channel = FileChannel.open(file, CREATE, WRITE, APPEND)) {
        CRC32 crc = new CRC32();
        crc.update(data);
        int crcValue = (int) crc.getValue();
        ByteBuffer frame = ByteBuffer.allocate(4 + data.length + 4);
        ...
        channel.force(true);
    }
}
```
**Impact:** Per-append `FileChannel.open` incurs a syscall + metadata walk; per-append `CRC32` + `ByteBuffer.allocate` incur GC pressure. At WAL append rates typical of Raft leaders (thousands/s) this is measurable throughput loss. Additionally, `CREATE` on first append creates the file but there is no parent-directory fsync afterwards — if the first append succeeds and the process crashes, the directory entry may be absent on replay. S2 because `configd-consensus-core` Cluster B has its own `RaftLog` path that bypasses this one; this class is reached mainly through `InMemoryStorage`'s cousin for sub-components.
**Fix direction:** Cache `FileChannel` per `logName` on the storage instance, reuse a thread-local `CRC32` (reset each time), and reuse a sized `ByteBuffer` (grow on demand). Fsync the directory once after the CREATE path (track first-append per log name).
**Owner:** common

---

### S3 — Hygiene

### PA-1025 — No concurrency test for ConfigSigner (S3)
**Location:** `configd-config-store/src/test/java/io/configd/store/ConfigSignerTest.java`
**Category:** test
**Evidence:** Javadoc for `ConfigSigner` claims "instances are safe for concurrent use"; test class has only single-threaded round-trip tests.
**Impact:** Any regression that breaks per-call `Signature` construction (e.g., introducing a shared `Signature` field for performance) will pass the test suite. S3.
**Fix direction:** Add a `@Test` that fans out 8 threads × 10 K sign/verify round trips and asserts all results are valid.
**Owner:** config-store

### PA-1026 — No allocation regression test for cursor-bound LocalConfigStore.get (S3)
**Location:** `configd-edge-cache/src/test/java/io/configd/edge/LocalConfigStoreTest.java`
**Category:** test
**Evidence:** The allocation regression tests in `VersionedConfigStoreAllocationTest` cover the non-cursor `getInto` path only. `LocalConfigStore.get(key, cursor)` at line 141 has no equivalent test.
**Impact:** A future change that allocates a `VersionCursor` internally, or that routes through `InvariantMonitor` on the happy path, would not regress any test. S3.
**Fix direction:** Extend `VersionedConfigStoreAllocationTest` with a cursor-bound hit-path measurement (8 KB budget, same shape as the existing tests).
**Owner:** edge-cache

### PA-1027 — DeltaApplier has no testing hook to reset highestSeenEpoch (S3)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java:86`
**Category:** api / test
**Evidence:** Only way to reset `highestSeenEpoch` is to construct a new `DeltaApplier`. The constructor seeds from `client.currentVersion()`, not from the epoch persisted with the prior snapshot.
**Impact:** After a full snapshot load (which may itself have been signed at some epoch E), the new applier starts at `highestSeenEpoch = 0`, so the very next signed delta at `epoch = E - 1` would be accepted as a valid replay. Partial regression of F-0052 under the snapshot-recovery path.
**Fix direction:** Persist `highestSeenEpoch` in the snapshot (add a field to `ConfigSnapshot` or carry in a sidecar). On `loadSnapshot`, seed the applier with the stored value. Add a test that drives a snapshot-recover-then-replay sequence.
**Owner:** edge-cache

### PA-1028 — ConfigStateMachine.last{Signature,Epoch,Nonce} clone on every read (S3)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:498-516`
**Category:** memory
**Evidence:**
```java
public byte[] lastSignature() {
    return lastSignature != null ? lastSignature.clone() : null;
}
public byte[] lastNonce() {
    return lastNonce != null ? lastNonce.clone() : null;
}
```
**Impact:** The server layer reads these after every `apply` to attach to the outgoing delta. Each attach allocates ~80 bytes per commit. At 400 K commits/s this is ~32 MB/s of transient GC churn on the leader. S3 — measurable but small.
**Fix direction:** Expose `lastSignatureUnsafe()` and document the "do not mutate" contract.
**Owner:** config-store

### PA-1029 — No concurrent-call test for BuggifyRuntime (S3)
**Location:** `configd-common/src/test/java/io/configd/common/BuggifyRuntimeTest.java`
**Category:** test
**Evidence:** Test file exercises `shouldFire` sequentially only.
**Impact:** The thread-safety hole in PA-1005 is not exercised by any test, so the fix (when it lands) has no regression harness.
**Fix direction:** Add a concurrent test that enables simulation mode and has N threads call `shouldFire` — assert no exception and, if the fix uses ThreadLocal seeded from the master, assert determinism per-thread.
**Owner:** common

### PA-1030 — Compactor.compact can spin under concurrent adds (S3)
**Location:** `configd-config-store/src/main/java/io/configd/store/Compactor.java:139-155`
**Category:** concurrency
**Evidence:**
```java
public int compact() {
    int removed = 0;
    while (history.size() > retentionCount) {
        var oldest = history.firstEntry();
        if (oldest == null) break;
        if (history.size() > retentionCount) {
            if (history.remove(oldest.getKey()) != null) {
                removed++;
            }
        }
    }
    return removed;
}
```
**Impact:** Under a pathological producer that adds snapshots faster than compact can remove, the loop can spin. No back-pressure, no yield. S3 — unlikely in practice (compactor and producer race only briefly) but defensive design calls for a bounded iteration count.
**Fix direction:** Add `maxIterations = retentionCount * 2`; break and return early if hit. The caller can re-schedule.
**Owner:** config-store

### PA-1031 — PrefixSubscription.prefixes allocates per call (S3)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/PrefixSubscription.java:83-85`
**Category:** memory
**Evidence:**
```java
public Set<String> prefixes() {
    return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(prefixes));
}
```
**Impact:** Every caller (including `EdgeConfigClient.metrics` in PA-1019) allocates a fresh LinkedHashSet + unmodifiable wrapper. S3.
**Fix direction:** Expose `size()` directly. For `prefixes()`, return `Collections.unmodifiableSet(prefixes)` — the underlying COW set already provides safe iteration.
**Owner:** edge-cache

### PA-1032 — @Buggify annotation has no processor or retention-policy impact (S3)
**Location:** `configd-common/src/main/java/io/configd/common/Buggify.java`
**Category:** api
**Evidence:** Annotation declared `@Retention(RUNTIME)`, `@Target({METHOD, LOCAL_VARIABLE})` but no code reads it — `BuggifyRuntime.shouldFire` is invoked imperatively. The annotation is dead.
**Impact:** Operators and reviewers reading the annotated code assume runtime reflection drives the fault injection. It does not. Misleading. S3.
**Fix direction:** Either delete the annotation or wire up a tiny byte-code agent / AspectJ aspect that reads it. Preferred: delete.
**Owner:** common

### PA-1033 — PoisonPillDetector.recordSuccess does not un-quarantine (S3)
**Location:** `configd-edge-cache/src/main/java/io/configd/edge/PoisonPillDetector.java:77-79`
**Category:** api
**Evidence:**
```java
public void recordSuccess(String key) {
    failureCounts.remove(key);
}
```
**Impact:** Once quarantined, a key is only re-enabled by explicit `release()`. A transient poisoning (e.g., a single corrupt value that was then republished as valid) will never self-heal — even after many successes, `isPoisoned` returns true. The comment at line 99 says "Call this after the root cause has been addressed", but operators often cannot distinguish transient from persistent failures. S3 UX.
**Fix direction:** After `recordSuccess`, also remove from `quarantined` if `failureCounts` is empty *and* a configurable number of consecutive successes have been observed.
**Owner:** edge-cache

### PA-1034 — ConfigValidator.findLongestPrefixValidator is unbounded (S3)
**Location:** `configd-config-store/src/main/java/io/configd/store/ConfigValidator.java:121-132`
**Category:** correctness
**Evidence:**
```java
Map.Entry<String, Validator> entry = prefixValidators.floorEntry(key);
while (entry != null) {
    if (key.startsWith(entry.getKey())) return entry.getValue();
    entry = prefixValidators.lowerEntry(entry.getKey());
}
```
**Impact:** Worst case with many near-miss prefixes, this scans O(map size). The comment claims "longest prefix" but the algorithm is actually "first `floor` entry that `startsWith` matches", which is not the longest on degenerate inputs.
**Fix direction:** Use a trie, or bound by prefix depth. For now, document the O(N) worst case and cap the registered validators at a reasonable count.
**Owner:** config-store

### PA-1035 — HybridClock.now CAS loop is unbounded under heavy contention (S3)
**Location:** `configd-common/src/main/java/io/configd/common/HybridClock.java:89-108`
**Category:** concurrency
**Evidence:**
```java
public long now() {
    long pt = physicalClock.currentTimeMillis();
    for (;;) {
        long cur = (long) STATE.getVolatile(this);
        ...
        if (STATE.compareAndSet(this, cur, next)) return next;
    }
}
```
**Impact:** With more calling threads than cores, the CAS loop can livelock in the worst case. Unlikely on real workloads, but present. S3.
**Fix direction:** Add a `Thread.onSpinWait()` or a bounded retry with `LockSupport.parkNanos(1)` fallback after N failed attempts.
**Owner:** common

---

## Cross-cutting observations

1. **Wall-clock creep.** Three separate call sites in the hot/warm write path (`VersionedConfigStore.put/delete/applyBatch`, `ConfigStateMachine.restoreSnapshot`, `LocalConfigStore.applyDelta`) use `Clock.currentTimeMillis()` when the invariant requires HLC. The `HybridClock` exists and is allocation-free but is not wired through the apply path.
2. **Record accessors that clone.** `VersionedValue.value()`, `ConfigDelta.signature()`, `ConfigDelta.nonce()`, `ConfigStateMachine.lastSignature()/lastNonce()` all override the canonical record accessor to `clone()` the backing byte[]. Pattern is consistent but creates silent per-call allocation for callers who forget to use the `*Unsafe` / internal alternative. Either commit to the `Unsafe` convention for every byte-backed record or stop exporting the record accessor at all.
3. **Durability half-pattern.** `FileStorage.put` does tmp-write + atomic rename + directory fsync (correct). `FileStorage.renameLog` and `SigningKeyStore.generateAndWrite` do a subset of the same pattern without the directory fsync. V2 R-series findings show the project knows how to do this correctly in one place; it is not reused everywhere.
4. **Error paths without metrics.** `DeltaApplier` (3 reject reasons), `ConfigStateMachine.signCommand` (catch-and-nullify), `SigningKeyStore` (swallowed `UnsupportedOperationException`) all log or silently proceed without a metric. Rule 10 is violated in several places.
5. **JUL with eager concatenation.** `DeltaApplier` uses `Logger.warning(String)` with `+`-concatenated arguments. Under an adversarial load, log-allocation dominates even when the level would filter. Parameterized or supplier-based API is not used.
6. **`Objects.requireNonNull` on read path.** Defensive and zero-allocation on the happy path, but the "message" string is allocated as a constant anyway — fine. Worth noting because any change to dynamic message strings would regress the zero-allocation claim.
7. **Signature payload binding is consistent** between `DeltaApplier.buildVerificationPayload` → `ConfigDelta.signingPayload` and `ConfigStateMachine.signCommand` canonicalization. F-0052 wiring is intact on the hot path; most findings are around the surrounding ergonomics, not the crypto itself.

---

## Owner rollup

| Owner | Count | S0 | S1 | S2 | S3 |
|-------|-------|----|----|----|----|
| config-store | 17 | 3 (PA-1001, PA-1002, PA-1003, PA-1004 — 4) | 4 (PA-1007, PA-1008, PA-1011) | 5 (PA-1014, PA-1015, PA-1016, PA-1018, PA-1021, PA-1023) | PA-1025, PA-1028, PA-1030, PA-1034 |
| edge-cache | 11 | 0 | PA-1006, PA-1012 | PA-1017, PA-1019, PA-1020, PA-1022 | PA-1026, PA-1027, PA-1031, PA-1033 |
| common | 7 | 0 | PA-1005, PA-1009, PA-1010, PA-1013 | PA-1024 | PA-1029, PA-1032, PA-1035 |

Concrete split (corrected):
- **config-store (14 findings):** S0 PA-1001, PA-1002, PA-1003, PA-1004 (4); S1 PA-1007, PA-1008, PA-1011 (3); S2 PA-1014, PA-1015, PA-1016, PA-1018, PA-1021, PA-1023 (6); S3 PA-1025, PA-1028, PA-1030, PA-1034 (4).
- **edge-cache (11 findings):** S1 PA-1006, PA-1012 (2); S2 PA-1017, PA-1019, PA-1020, PA-1022 (4); S3 PA-1026, PA-1027, PA-1031, PA-1033 (4).
- **common (10 findings):** S1 PA-1005, PA-1009, PA-1010, PA-1013 (4); S2 PA-1024 (1); S3 PA-1029, PA-1032, PA-1035 (3).

Total: 35 findings (4 S0 · 9 S1 · 11 S2 · 11 S3).

