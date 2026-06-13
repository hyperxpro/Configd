# RR-005 re-verification (Workstream B-rest) — both halves still OPEN

The S4 resume-memory carried a caution: *"ConfigdServer tick loop ALREADY calls
`compactor.compact()` every 1000 ticks — re-verify RR-005's 'compaction unreachable' half
before assuming."* This note re-verifies against current code (`a4535b5`). **Verdict: the
caution was a false alarm — RR-005 (1) is still real; RR-005 (2) is still real.**

## (1) Raft-log compaction is STILL unreachable in the wired server — CONFIRMED OPEN

The `compactor.compact()` call in the server tick loop (`ConfigdServer.java:656`, guarded by
`COMPACTION_INTERVAL_TICKS`, comment *"Compact snapshot history every ~10 seconds"*) is the
**snapshot-retention `Compactor`** (`configd-config-store` `io.configd.store.Compactor`,
constructed at `:357`): a sliding window of historical `ConfigSnapshot`s kept for delta
computation (`addSnapshot`/`compact` retention). **It does not touch the Raft WAL.**

Raft-log compaction is `RaftNode.triggerSnapshot()` (`:425`) → `RaftLog` snapshot + WAL-prefix
truncation. Repo-wide, the only caller of `triggerSnapshot()` is the **circular**
`sendInstallSnapshot` (`RaftNode.java:1730`): it snapshots only when a peer's `nextIndex` already
points past a compacted entry — which cannot happen until compaction has already occurred. There
is **no** size- or interval-based trigger anywhere in `configd-server` (grep for
`triggerSnapshot` / WAL-size / snapshot-interval in `configd-server/src/main` → zero hits). So in
the running server the WAL grows for the life of the process.

RR-003 (S2) made compaction *safe-if-triggered* (persist-before-truncate + durable-prefix), but
explicitly left RR-005 (1) open — RR-003's tests call `triggerSnapshot()` directly, so the
unreachability is never exercised. That remains true.

## (2) `(int) fileSize` truncation cast — CONFIRMED OPEN

`FileStorage.java:128` `ByteBuffer.allocate((int) fileSize)` on the WAL recovery read path
(`RaftLog` ctor). For a WAL ≥ 2^31 bytes the `long → int` cast truncates (or yields a negative),
so recovery reads the wrong length / throws an opaque `IllegalArgumentException` at boot — a
cluster-wide crash-loop time bomb that (1) makes inevitable, since nothing bounds WAL growth.
(A single `ByteBuffer.allocate` also cannot exceed `~Integer.MAX_VALUE` regardless — the read
path must stream or fail loudly with a clear, actionable error, not silently mis-size.)

## The fix shape (owed, red/green)

Two independent, coupled fixes:
1. **Wire a Raft-log compaction trigger** into the server: call `raftNode.triggerSnapshot()` from
   the tick loop on a size/applied-index threshold (e.g. `lastApplied − snapshotIndex >
   compactionThreshold`, a named config with a metric), analogous to the retention
   `compactor.compact()` already there. Red/green: pre-fix the WAL/applied grows without bound and
   `snapshotIndex` never advances in the wired server; post-fix compaction fires at the threshold.
   Must preserve RR-003's persist-before-truncate + `durable_prefix_no_gap` (compaction is now
   *reachable*, so the durable-prefix invariant gets genuinely exercised end-to-end for the first
   time — re-run `SnapshotCrashRecoveryTest` semantics through the wired trigger).
2. **Harden the recovery read**: replace the `(int) fileSize` cast with a long-safe path — fail
   loudly with a clear message above the JVM array limit (and, with (1) bounding growth, the
   condition should be unreachable in practice; the guard is the backstop).

## Status

RR-005 stays **OPEN** with the re-verification recorded. This is an availability/crash-loop P1
(WAL-grows-forever + 2 GiB boot failure), not a safety violation — it does not halt the matrix,
but it is owed before gate-4. The fix touches the consensus tick loop (the H-009 zombie-tick
surface) and the RR-003 durable-prefix path, so it is scoped as its own red/green increment with
second-agent reproduction.
