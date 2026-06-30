# configd-replication-engine — Idiomatic-Java Quality Pass: §2 PROPOSALS (review-only)

**Outcome of this pass: ZERO source edits applied.** Every `src/main` file in this module is on the
§2 NO-TOUCH list (consensus / owner-threading / routing / guard / flow-control / snapshot-wire), and the
code is already clean and idiomatic — no unused imports, no dead code, no missing `@Override`, no raw
types, no redundant casts, no swallowed exceptions, no resource leaks. The items below are the only
observations worth recording; each is **deliberately NOT applied** because it lives inside a §2 zone
(measured/proven property) and/or needs a benchmark or a behavioral judgement to clear. They are recorded
for the divergence-analyst and for future (post-measurement) work.

Files reviewed (all §2 NO-TOUCH): `MultiRaftDriver`, `OwnerExecutorPool`, `ReplicationPipeline`,
`ShardMap`, `StaticShardMap`, `CrossShardWriteGuard`, `CrossShardBatchException`, `FlowController`,
`SnapshotTransfer`.

Verification baseline (no edits): `test-compile` PASS; module `test` **142/142 PASS** (0 failures /
0 errors / 0 skipped), incl. routing oracle `StaticShardMapTest` 12/0 and guard oracle
`CrossShardWriteGuardTest` 7/0.

---

## P1 — `MultiRaftDriver.java:489` — inline FQN `java.util.concurrent.ExecutionException` (cosmetic)

```java
} catch (java.util.concurrent.ExecutionException e) {
```

Inside `runOnOwnerAwait(...)`, this is the only fully-qualified type reference in the module; every other
`java.util.concurrent.*` type is imported. Replacing it with `import java.util.concurrent.ExecutionException;`
+ the short name would be byte-identical at the class-file level (an import and an FQN resolve to the same
binary type reference; imports emit no bytecode).

**Why NOT applied:** `runOnOwnerAwait` is the *uninterruptible rehoming-handoff barrier* — the single most
reliability-critical thread of code in the module (red-team Finding 1). The FQN may be a deliberate local
choice to keep the import list focused on the hot-path types. The cost/benefit of editing the
owner-threading file for a pure cosmetic does not clear the §2 bar. **Defer.** If ever applied, a
divergence-analyst need only confirm the catch still binds `ExecutionException` and the handler body is
unchanged.

## P2 — `SnapshotTransfer.java:303` — presize the reassembly `ByteArrayOutputStream` (allocation nit)

```java
ByteArrayOutputStream out = new ByteArrayOutputStream();   // grows by doubling as chunks are written
```

In `assemble(SnapshotReceiveState)` the final size is already known exactly — it is
`state.expectedOffset` (the running sum of accepted chunk lengths). Constructing
`new ByteArrayOutputStream(state.expectedOffset)` would let the receiver reassemble the snapshot in a
single backing array instead of repeatedly doubling+copying, while producing **byte-identical** output
(`toByteArray()` returns the same bytes regardless of internal capacity).

**Why NOT applied:** `SnapshotTransfer` is a §2 snapshot-streaming (wire + consensus) file and snapshot
assembly throughput is measurement-adjacent. This is an allocation-profile change, however benign, so per
the brief it is a PROPOSE, not an apply. It needs the EC2 measurement to run on the unchanged baseline
first. **Defer** to post-measurement. (`expectedOffset` is an `int`; it already bounds the total, so no
overflow concern beyond what the existing accumulator carries.)

## P3 — `CrossShardBatchException.java:33` — `keyToShard()` returns the internal map (encapsulation nit)

```java
public Map<String, Integer> keyToShard() { return keyToShard; }   // direct reference, mutable, escapes
```

The accessor hands out the same `LinkedHashMap` instance stored on the exception, so a caller could mutate
the rejection detail. The defensive-copy idiom would be to wrap/copy it.

**Why NOT applied (and a real trap to flag):** this is a §2 cross-shard-guard *decision/identity* type, and
"hardening" it is **not** behavior-preserving here.
- The detail map is intentionally a `LinkedHashMap` built in key order (see `CrossShardWriteGuard.shardOf`),
  and that order is reflected in the exception message and the accessor. `Map.copyOf(...)` does **not**
  preserve iteration order, so a naive copy would *change observable order* — a real divergence.
- The only order-preserving hardening would be
  `Collections.unmodifiableMap(new LinkedHashMap<>(keyToShard))` at construction. That is still an
  object-identity/mutability change a test could observe.

So this is explicitly a behavioral change, not a safe nit. **Defer / likely decline.** Recorded only so a
reviewer doesn't "fix" it without realizing the order constraint.

---

## Reviewed and explicitly LEFT ALONE (not defects — documented intent)

- **`OwnerExecutorPool.awaitTermination(timeout, unit)`** applies `timeout` to each owner **sequentially**,
  so worst-case wall time is `N × timeout`. The javadoc already states "best-effort, sequential" — this is
  documented intent, not a bug. No change.
- **`CrossShardWriteGuard` / `routeCoalescedHeartbeat` / `drainHeartbeats`** use indexed/entry iteration
  over small collections; the loops are guard-logic / owner-thread paths (§2) and the inputs are tiny
  (batch keys, per-peer heartbeats). No idiomatic rewrite warranted.
- **`SnapshotChunk` record** hand-rolls `equals`/`hashCode`/`toString` precisely because it carries a
  `byte[]` (default record equality is reference-based for arrays). The overrides are correct and necessary
  — leave as-is.
- **`SnapshotTransfer.assemble` `catch (IOException)` → `UncheckedIOException`** is correct: it preserves
  the cause and the inline comment explains the unreachable-by-contract catch. Not an anti-pattern.
- **No unused imports / dead code / missing `@Override`** anywhere in the module — nothing to clean.
