# EXP-008 — ENOSPC (consensus-layer reaction) + short-read analysis (Workstream B-rest)

- **Workstream:** B-rest. **Register row:** none new — closes the kill-matrix ENOSPC + short-read cells.
- **Status:** ENOSPC cell GREEN (mutation-discriminated); short-read cells closed by analysis (the two real cases are already covered; FaultInjectingStorage only injects the benign one). No production change.

## ENOSPC during WAL append — consensus-layer reaction

The B1 self-test pins only that `FaultInjectingStorage.enospcAfterBytes` *throws*. This cell pins how
the consensus layer REACTS. Oracle (`storage-fault-layer-design.md §2` / arch §11): the disk-full
append is **surfaced** (not swallowed into a mute zombie), the log is **never silently advanced**
(no lost-but-acked write), and the node **recovers** once space returns — defined degradation, no
crash-loop, no silent loss.

**Test:** `StorageEnospcConsensusReactionTest.enospcOnWalAppendSurfacesAndNeverSilentlyAdvancesTheLog`
(configd-testkit — where `FaultInjectingStorage` lives): single-node leader over
`RaftLog(FaultInjectingStorage(inMemory))`; commit 3 healthy entries; arm
`enospcAfterBytes(bytesAppended())`; the next `propose`:
- (1) **surfaces** — `assertThrows(UncheckedIOException)`, message names ENOSPC;
- (2) **no silent advance** — `log.lastIndex()`/`commitIndex()` unchanged (the failed entry never
  entered the log);
- (3) **recovers** — after `enospcAfterBytes(-1)` (space reclaimed) the next `propose` is ACCEPTED.

**Load-bearing invariant:** `RaftLog.append` is **durable-first** — `storage.appendToLog(...)` BEFORE
`entries.add(...)` (`RaftLog.java:323-326`). An ENOSPC throw therefore leaves the in-memory log ==
the durable log (neither has the failed entry), so a later commit/replication can never pick up an
entry that was never durable.

**Mutation M-order** — swap to in-memory-before-durable: after ENOSPC the in-memory log advances past
durable → assertion (2) RED (`an ENOSPC append must NOT advance the log … expected: <4> but was: <5>`).
Capture: `captures/exp-008-enospc-durable-first-RED.txt`. Reverted + reinstalled; GREEN. Production
source byte-clean.

## ENOSPC during the snapshot write — WAL stays intact (no loss)

`StorageEnospcConsensusReactionTest.enospcDuringSnapshotWriteLeavesWalIntactNoLoss`: arm
`failNextWrites(1)` so the snapshot-blob `put` ENOSPCs, then `triggerSnapshot()`. Because
`triggerSnapshot` is **persist-before-truncate** (`persistSnapshot` puts the blob BEFORE
`compact` truncates the WAL prefix — RR-003), the failed blob write aborts the snapshot with the
**WAL prefix intact**: `triggerSnapshot` throws (surfaces), `snapshotIndex`/`lastIndex`/`commitIndex`
unchanged, no loss, no `durable_prefix_no_gap` on a later restart; once the disk recovers a later
`triggerSnapshot` succeeds and compacts. The ordering this relies on is the RR-003 invariant
(mutation-covered by `SnapshotCrashRecoveryTest`'s persist-after-compact revert); here the trigger is
an ENOSPC throw rather than a crash.

## Short-read on WAL recovery — closed by analysis (no redundant test)

`FaultInjectingStorage.shortReadLog` drops the **last** frame (B1 self-test: "short read must drop
the last frame"). At the `RaftLog` recovery level this is a **trailing truncation**, which is exactly
the **torn-tail** case: the dropped frame was never a fully-fsynced committed entry, so recovery
treats it as the uncommitted tail and discards it cleanly. That behavior is already pinned by
`SnapshotCrashRecoveryTest.recoversCleanlyFromTornFinalWalRecord` — a trailing short-read recovers
identically (no corruption, no crash, no fail-loud needed because nothing committed was lost).

The dangerous short-read is a **middle/non-contiguous** drop (a committed entry lost while later ones
survive) → a gap → must fail loud. That is caught by the **contiguity / `durable_prefix_no_gap`**
check, already proven by `gapDetectionFiresWhenSnapshotBlobUnrecoverable` and EXP-007 (the recovered
image is a gap regardless of how the bytes went missing). `FaultInjectingStorage` only injects the
benign trailing case; a middle-drop injector is a **non-blocking follow-up** if a dedicated
middle-drop test is later wanted — the *detection* it would exercise is already covered.

**Net:** both real short-read outcomes (benign trailing discard; dangerous middle-gap fail-loud) are
covered. Recorded honestly rather than adding a test that reduces to existing coverage.

## Reproduction

```
./mvnw -o -pl configd-testkit test -Dtest='StorageEnospcConsensusReactionTest' -Dsurefire.failIfNoSpecifiedTests=false
# RED: swap RaftLog.append to entries.add(entry) BEFORE storage.appendToLog(...), reinstall
#      consensus-core, re-run → the no-silent-advance assertion fails (expected 4, was 5).
```
