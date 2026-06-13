# EXP-006 — RR-005 FIX: Raft-log compaction reachable + long-safe WAL recovery read

- **Workstream:** B-rest (durability/recovery). **Register row:** RR-005 (P1, Durability/recovery), OPEN at pickup → RESOLVED.
- **Owner:** durability-engineer (consensus tick-loop change) · matrix arbitration: review-architect.
- **Status:** GREEN — both halves fixed; red/green on the core (compaction-reachability) cell; RR-003 durable-prefix regression re-run; second-agent reproduction.
- **Predecessor:** `rr-005-reverification.md` (commit `d171bf1`) confirmed both halves OPEN.

## 1. The two bugs (cited)

1. **Compaction unreachable in the wired server.** The only `RaftNode.triggerSnapshot()` caller
   was the circular `sendInstallSnapshot` (`RaftNode.java:1730`) — reachable only after a snapshot
   already exists. No size/interval trigger in `configd-server`. So a running leader never
   compacted and its WAL grew for the life of the process (the snapshot-RETENTION
   `Compactor.compact()` in the tick loop is unrelated — it never touches the WAL).
2. **`(int) fileSize` truncation.** `FileStorage.java:128` sized the WAL recovery buffer with a
   `long → int` cast; a ≥ 2 GiB WAL wrapped negative → opaque crash-loop at boot (made inevitable
   by (1)).

Governing contract: this is an availability/crash-loop P1 (not a safety violation — no committed
data is lost, so it does not halt the matrix), but it is owed before gate-4 and the fix touches
the consensus tick loop (H-009 zombie-tick surface) + the RR-003 durable-prefix path, so it was
handled P0-grade: **discriminating RED test written before any production change.**

## 2. The fix (4 modules, additive — `git diff --stat` 7 files, +151/−1)

| Module | Change |
|---|---|
| `configd-consensus-core` `RaftNode` | new `maybeCompact(long threshold)` → `triggerSnapshot()` when `lastApplied − snapshotIndex > threshold`. Reuses the unchanged RR-003 persist-before-truncate path. |
| `configd-replication-engine` `MultiRaftDriver` | new `maybeCompact(long)` fans the trigger out to every group (mirrors `tick()`). |
| `configd-server` `ConfigdServer` | tick loop calls `driver.maybeCompact(RAFT_LOG_COMPACTION_THRESHOLD = 10_000)` every tick (cheap O(groups); a group only snapshots when over the threshold). |
| `configd-common` `FileStorage` | `(int) fileSize` → `checkedLogReadSize(logName, fileSize)`: fail-loud `IllegalStateException` above `MAX_READABLE_LOG_BYTES = Integer.MAX_VALUE − 8` instead of silent truncation. |

The threshold is a constant for now; promoting it to a named config + metric (charter §6 rule 8)
is a recorded follow-up — not required to close RR-005 (1), which is about *reachability*.

## 3. Tests + red/green

| Cell | Test | Result |
|---|---|---|
| Compaction fires by threshold (the core) | `RaftLogCompactionTriggerTest.maybeCompactTriggersSnapshotOnlyAboveThreshold` (single-node leader, 20 applied entries: below threshold → no compaction; above → snapshotIndex advances, retained span ≤ threshold, WAL prefix truncated) | GREEN |
| Driver fan-out | `MultiRaftDriverTest.maybeCompactFansOutAndCompactsGroupsOverThreshold` | GREEN |
| Server wiring | `ConfigdServerTest.rr005_raftLogCompactionTriggerIsWiredInTickLoop` (source-guard, the find0050 pattern) + `serverStartsAndStopsCleanly` (wired server starts with the new tick-loop call) | GREEN |
| Long-safe recovery read | `FileStorageTest.checkedLogReadSizePassesBelowLimitAndFailsLoudAtOrAboveJvmArrayCap` (≤ limit passes; ≥ 2 GiB throws naming the log + RR-005; sanity: `(int) 2GiB` wraps negative) | GREEN (18) |
| **RR-003 regression** | `SnapshotCrashRecoveryTest` (6) — compaction is now reachable, so the durable-prefix path is exercised end-to-end; persist-before-truncate holds | GREEN |

**Mutation M-compact** — revert `RaftNode.maybeCompact` to `return false;` (the pre-fix
"compaction unreachable" state): `RaftLogCompactionTriggerTest` RED at the above-threshold
assertion (`must compact above the threshold ==> expected: <true> but was: <false>`).
Capture: `captures/exp-006-rr005-compaction-unreachable-RED.txt`. Reverted; GREEN.

Production source byte-clean after the capture (no mutation residue; `grep MUTATION` in
`*/src/main` empty).

## 4. Verdict

RR-005 → **RESOLVED**. Compaction is reachable in the wired server (tick-loop trigger, fan-out,
threshold policy) and the recovery read fails loud instead of silently truncating; RR-003's
durable-prefix invariant is preserved (the fix calls the unchanged `triggerSnapshot`). Follow-up
(non-blocking): promote `RAFT_LOG_COMPACTION_THRESHOLD` to a named config + a
`raft_log_compactions_total`-style metric (S6 observability lane).

## 5. Reproduction

```
./mvnw -o -pl configd-consensus-core test -Dtest='RaftLogCompactionTriggerTest,SnapshotCrashRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl configd-common test -Dtest='FileStorageTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl configd-replication-engine test -Dtest='MultiRaftDriverTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl configd-server test -Dtest='ConfigdServerTest#rr005_raftLogCompactionTriggerIsWiredInTickLoop+serverStartsAndStopsCleanly' -Dsurefire.failIfNoSpecifiedTests=false
# RED: revert RaftNode.maybeCompact body to `return false;` → RaftLogCompactionTriggerTest fails.
```
