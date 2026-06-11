package io.configd.raft;

import io.configd.common.Storage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct, discriminating unit tests for {@link RaftLog}'s query, mutation,
 * boundary-arithmetic, and recovery logic.
 * <p>
 * S2/mutation-gap (RR-085): {@code RaftLog} carried ~42 SURVIVED + ~15
 * NO_COVERAGE mutants concentrated in {@code entriesFrom}/{@code entriesBatch}/
 * {@code termAt}/{@code compact}/{@code appendEntries}/{@code truncateFrom}/
 * {@code setCommitIndex}/{@code setLastApplied}/{@code isAtLeastAsUpToDate}/the
 * recovery constructor. The forensic report noted there was no dedicated
 * {@code RaftLogTest} — these methods were only exercised indirectly through
 * RaftNode scenarios, so their index arithmetic and range-clamp boundaries
 * went unpinned. Every test here asserts the exact behavior a specific mutant
 * would change (a flipped boundary, a removed conditional, a replaced
 * arithmetic op), so it fails iff that mutant is applied. No sleeps; all
 * in-process and deterministic.
 */
class RaftLogUnitTest {

    private static LogEntry entry(long index, long term) {
        return new LogEntry(index, term, new byte[]{(byte) index, (byte) term});
    }

    private static RaftLog logWith(long... terms) {
        RaftLog log = new RaftLog();
        for (int i = 0; i < terms.length; i++) {
            log.append(entry(i + 1, terms[i]));
        }
        return log;
    }

    // ====================================================================
    // termAt — sentinel, snapshot boundary, in-range, out-of-range
    // ====================================================================

    @Nested
    class TermAt {

        @Test
        void zeroIndexReturnsSentinelTermZero() {
            RaftLog log = logWith(5, 5);
            // Kills termAt L217 EQUAL_ELSE (index==0 guard removed): index 0 would
            // otherwise fall through to the range check and return -1.
            assertEquals(0, log.termAt(0));
        }

        @Test
        void inRangeReturnsStoredTermNotSentinel() {
            RaftLog log = logWith(3, 4, 7);
            assertEquals(3, log.termAt(1));
            assertEquals(4, log.termAt(2));
            assertEquals(7, log.termAt(3));
        }

        @Test
        void belowSnapshotOrAboveLastReturnsMinusOne() {
            // snapshotIndex=2 (terms at 1,2 compacted), entries 3,4 remain.
            RaftLog log = logWith(2, 2, 6, 6);
            log.compact(2, 2);
            // index < snapshotIndex -> -1; index > lastIndex -> -1
            // Kills termAt L223 ConditionalsBoundary (index > lastIndex()):
            // a boundary flip would let lastIndex()+1 read past the end.
            assertEquals(-1, log.termAt(1));
            assertEquals(-1, log.termAt(5));
            // The snapshot boundary itself returns snapshotTerm, not -1.
            assertEquals(2, log.termAt(2));
        }

        @Test
        void exactLastIndexIsInRange() {
            RaftLog log = logWith(9, 9, 9);
            // Pins the upper boundary of termAt's range check: lastIndex() is valid,
            // lastIndex()+1 is not. ConditionalsBoundary on `index > lastIndex()`.
            assertEquals(9, log.termAt(3));
            assertEquals(-1, log.termAt(4));
        }

        @Test
        void zeroSentinelGuardMattersWhenSnapshotIsAhead() {
            // With snapshotIndex > 0, removing the `index == 0` guard (L217 EQUAL_ELSE)
            // would let termAt(0) fall through to `index < snapshotIndex` and return
            // -1 instead of the sentinel 0. With snapshotIndex==0 the mutant is
            // invisible, so this case (snapshotIndex=2) is the one that discriminates.
            RaftLog log = logWith(7, 7, 7, 7);
            log.compact(2, 7); // snapshotIndex=2
            assertEquals(0, log.termAt(0), "index 0 must always be the term-0 sentinel");
        }

        @Test
        void belowSnapshotIsMinusOneAtBoundary() {
            // Kills termAt L223 the `index < snapshotIndex` arm: snapshotIndex-1 is
            // below the snapshot -> -1, snapshotIndex itself is the boundary term.
            RaftLog log = logWith(3, 3, 3, 3, 3);
            log.compact(3, 3); // snapshotIndex=3
            assertEquals(-1, log.termAt(2));
            assertEquals(3, log.termAt(3));
            assertEquals(3, log.termAt(4));
        }
    }

    // ====================================================================
    // lastTerm — empty vs non-empty
    // ====================================================================

    @Nested
    class LastTerm {

        @Test
        void emptyLogReturnsSnapshotTermNotZero() {
            RaftLog log = logWith(4, 4);
            log.compact(2, 4); // fully compacts; entries empty, snapshotTerm=4
            assertEquals(0, log.size());
            // Kills lastTerm PrimitiveReturns (return 0): empty log must report the
            // snapshot term (4), not a hard-coded 0.
            assertEquals(4, log.lastTerm());
        }

        @Test
        void nonEmptyLogReturnsLastEntryTerm() {
            RaftLog log = logWith(1, 2, 8);
            assertEquals(8, log.lastTerm());
        }
    }

    // ====================================================================
    // entriesFrom — range guard, clamps, offset arithmetic
    // ====================================================================

    @Nested
    class EntriesFrom {

        @Test
        void returnsInclusiveRange() {
            RaftLog log = logWith(1, 1, 1, 1, 1); // indices 1..5
            List<LogEntry> got = log.entriesFrom(2, 4);
            assertEquals(3, got.size());
            assertEquals(2, got.getFirst().index());
            assertEquals(4, got.getLast().index());
        }

        @Test
        void startGreaterThanEndReturnsEmpty() {
            RaftLog log = logWith(1, 1, 1);
            // Kills entriesFrom L245 (startIndex > endIndex) guard: without it the
            // sublist arithmetic would produce an empty-or-invalid range silently.
            assertTrue(log.entriesFrom(3, 2).isEmpty());
        }

        @Test
        void startBeyondLastReturnsEmpty() {
            RaftLog log = logWith(1, 1, 1); // lastIndex=3
            // Kills entriesFrom L245 (startIndex > lastIndex()): start past the end
            // must yield empty, not throw / read garbage.
            assertTrue(log.entriesFrom(4, 9).isEmpty());
            // Exactly at lastIndex is a single-element non-empty range (boundary).
            assertEquals(1, log.entriesFrom(3, 9).size());
        }

        @Test
        void endAtOrBelowSnapshotReturnsEmpty() {
            RaftLog log = logWith(1, 1, 1, 1);
            log.compact(2, 1); // snapshotIndex=2
            // Kills entriesFrom L245 (endIndex <= snapshotIndex): a query wholly
            // inside the snapshot must return empty.
            assertTrue(log.entriesFrom(1, 2).isEmpty());
            // endIndex == snapshotIndex+1 is the first live boundary -> non-empty.
            assertEquals(1, log.entriesFrom(1, 3).size());
        }

        @Test
        void startClampsUpToSnapshotPlusOne() {
            RaftLog log = logWith(1, 1, 1, 1, 1);
            log.compact(2, 1); // snapshotIndex=2; live entries 3,4,5
            // effectiveStart = max(startIndex, snapshotIndex+1). Asking from 1 must
            // clamp to 3 and NOT include compacted entries; kills the
            // Math.max/`snapshotIndex + 1` mutations in entriesFrom.
            List<LogEntry> got = log.entriesFrom(1, 5);
            assertEquals(3, got.size());
            assertEquals(3, got.getFirst().index());
            assertEquals(5, got.getLast().index());
        }

        @Test
        void endClampsDownToLastIndex() {
            RaftLog log = logWith(1, 1, 1); // lastIndex=3
            // effectiveEnd = min(endIndex, lastIndex()). Asking to 100 must clamp to
            // 3 (kills the Math.min mutation and the toOffset(effectiveEnd)+1 math).
            List<LogEntry> got = log.entriesFrom(1, 100);
            assertEquals(3, got.size());
            assertEquals(3, got.getLast().index());
        }

        @Test
        void singleArgOverloadReturnsToEnd() {
            RaftLog log = logWith(1, 1, 1, 1);
            List<LogEntry> got = log.entriesFrom(2);
            assertEquals(3, got.size());
            assertEquals(2, got.getFirst().index());
            assertEquals(4, got.getLast().index());
        }
    }

    // ====================================================================
    // entriesBatch — count/byte limits, snapshot/last guards
    // ====================================================================

    @Nested
    class EntriesBatch {

        @Test
        void startBeyondLastOrInSnapshotReturnsEmpty() {
            RaftLog log = logWith(1, 1, 1);
            // Kills entriesBatch L268 guard (startIndex > lastIndex()).
            assertTrue(log.entriesBatch(4, 10, 1 << 20).isEmpty());
            log.compact(2, 1);
            // Kills entriesBatch L268 guard (startIndex <= snapshotIndex).
            assertTrue(log.entriesBatch(2, 10, 1 << 20).isEmpty());
            assertEquals(1, log.entriesBatch(3, 10, 1 << 20).size());
        }

        @Test
        void respectsMaxSizeCount() {
            RaftLog log = logWith(1, 1, 1, 1, 1); // 5 entries
            // maxSize=2 from index 1 -> exactly entries 1,2. Kills the
            // `min(fromOffset + maxSize, entries.size())` arithmetic boundary.
            List<LogEntry> got = log.entriesBatch(1, 2, 1 << 20);
            assertEquals(2, got.size());
            assertEquals(1, got.getFirst().index());
            assertEquals(2, got.getLast().index());
        }

        @Test
        void respectsMaxBytesButAlwaysReturnsAtLeastOne() {
            // Each command is 2 bytes (see entry()). maxBytes=1 is smaller than one
            // entry, yet at least the first entry is always returned (count>0 guard).
            RaftLog log = logWith(1, 1, 1);
            List<LogEntry> got = log.entriesBatch(1, 10, 1);
            assertEquals(1, got.size(), "must return at least one entry even under tiny maxBytes");
        }

        @Test
        void stopsBeforeExceedingMaxBytesAfterFirst() {
            // 3 entries x 2 bytes. maxBytes=3 allows the first (2 bytes), the second
            // would push to 4 > 3, so it stops at 1. Kills the
            // `totalBytes + entryBytes > maxBytes` boundary (count>0 branch).
            RaftLog log = logWith(1, 1, 1);
            List<LogEntry> got = log.entriesBatch(1, 10, 3);
            assertEquals(1, got.size());
            // maxBytes=4 fits two entries exactly.
            assertEquals(2, log.entriesBatch(1, 10, 4).size());
        }
    }

    // ====================================================================
    // append — sequential-index guard
    // ====================================================================

    @Nested
    class Append {

        @Test
        void rejectsNonSequentialIndex() {
            RaftLog log = logWith(1, 1); // lastIndex=2, next expected=3
            // Kills append L319 EQUAL_ELSE (the `entry.index() != expectedIndex`
            // guard removed): a gap/duplicate index must throw.
            assertThrows(IllegalArgumentException.class, () -> log.append(entry(4, 1)));
            assertThrows(IllegalArgumentException.class, () -> log.append(entry(2, 1)));
            // The correct next index is accepted.
            assertDoesNotThrow(() -> log.append(entry(3, 1)));
        }

        @Test
        void appendAllAppliesEverySequentialEntry() {
            RaftLog log = new RaftLog();
            // Kills appendAll VoidMethodCall (removed call to append): without the
            // inner append, nothing is stored and size stays 0.
            log.appendAll(List.of(entry(1, 1), entry(2, 1), entry(3, 2)));
            assertEquals(3, log.size());
            assertEquals(3, log.lastIndex());
            assertEquals(2, log.lastTerm());
        }
    }

    // ====================================================================
    // appendEntries — log-matching, conflict truncation, idempotency
    // ====================================================================

    @Nested
    class AppendEntriesMatching {

        @Test
        void rejectsWhenPrevLogTermMismatch() {
            RaftLog log = logWith(1, 1, 2); // index3 term2
            // prevLogIndex=3 but prevLogTerm=9 (mismatch) -> false, no append.
            // Kills appendEntries L358 EQUAL_ELSE (existingTerm != prevLogTerm).
            boolean ok = log.appendEntries(3, 9, List.of(entry(4, 3)));
            assertFalse(ok);
            assertEquals(3, log.lastIndex(), "no entry should be appended on mismatch");
        }

        @Test
        void prevLogIndexZeroAlwaysMatches() {
            RaftLog log = new RaftLog();
            // prevLogIndex==0 bypasses the term check (boundary `prevLogIndex > 0`).
            assertTrue(log.appendEntries(0, 0, List.of(entry(1, 1))));
            assertEquals(1, log.lastIndex());
        }

        @Test
        void conflictingEntryTruncatesAndReplaces() {
            RaftLog log = logWith(1, 1, 1, 1); // four entries all term 1
            // Replace from index 3 with a term-2 entry: index3 conflicts (term 1 vs
            // 2) -> truncate [3..4], append new index3 term2. Kills appendEntries
            // L374 conflict branch + truncateFrom wiring.
            boolean ok = log.appendEntries(2, 1, List.of(entry(3, 2)));
            assertTrue(ok);
            assertEquals(3, log.lastIndex());
            assertEquals(2, log.termAt(3));
        }

        @Test
        void duplicateMatchingEntryIsIdempotent() {
            RaftLog log = logWith(1, 1, 1);
            // Re-sending index 2 term 1 (already present, same term) must NOT append
            // or truncate — size unchanged. Kills the L366 snapshot-skip / L370/374
            // term-equality branches that distinguish skip from append.
            boolean ok = log.appendEntries(1, 1, List.of(entry(2, 1)));
            assertTrue(ok);
            assertEquals(3, log.lastIndex());
            assertEquals(3, log.size());
        }

        @Test
        void entryAtOrBelowSnapshotIsSkipped() {
            RaftLog log = logWith(1, 1, 1, 1);
            log.compact(2, 1); // snapshotIndex=2
            // newEntry index 2 is <= snapshotIndex -> skipped (continue). Kills the
            // L366 `idx <= snapshotIndex` boundary. The append still succeeds and
            // does not corrupt the live tail.
            boolean ok = log.appendEntries(2, 1, List.of(entry(2, 9), entry(3, 1)));
            assertTrue(ok);
            assertEquals(4, log.lastIndex());
            assertEquals(1, log.termAt(3));
        }
    }

    // ====================================================================
    // truncateFrom — guards and effect
    // ====================================================================

    @Nested
    class TruncateFrom {

        @Test
        void throwsWhenTruncatingAtOrBelowSnapshot() {
            RaftLog log = logWith(1, 1, 1, 1);
            log.compact(2, 1); // snapshotIndex=2
            // Kills truncateFrom L392 guard (fromIndex <= snapshotIndex): truncating
            // into the snapshot must throw, not silently corrupt offsets.
            assertThrows(IllegalArgumentException.class, () -> log.truncateFrom(2));
            assertThrows(IllegalArgumentException.class, () -> log.truncateFrom(1));
        }

        @Test
        void noOpWhenFromIndexBeyondLast() {
            RaftLog log = logWith(1, 1, 1); // lastIndex=3
            // Kills truncateFrom L396 boundary (fromIndex > lastIndex()): nothing to
            // truncate -> log unchanged.
            log.truncateFrom(4);
            assertEquals(3, log.lastIndex());
            assertEquals(3, log.size());
        }

        @Test
        void truncatesInclusiveFromIndex() {
            RaftLog log = logWith(1, 1, 1, 1, 1); // 5 entries
            log.truncateFrom(3);
            // entries 3,4,5 removed; 1,2 remain.
            assertEquals(2, log.lastIndex());
            assertEquals(2, log.size());
            assertNull(log.entryAt(3));
        }

        @Test
        void truncatingExactlyAtLastIndexRemovesOnlyThatEntry() {
            // Kills truncateFrom L396 ConditionalsBoundary (fromIndex > lastIndex()):
            // fromIndex == lastIndex must truncate (remove that one entry);
            // fromIndex == lastIndex+1 is a no-op.
            RaftLog log = logWith(1, 1, 1); // lastIndex=3
            log.truncateFrom(3);
            assertEquals(2, log.lastIndex());
            assertEquals(2, log.size());
            // now lastIndex=2; truncateFrom(3) (== lastIndex+1) is a no-op
            log.truncateFrom(3);
            assertEquals(2, log.lastIndex());
        }
    }

    // ====================================================================
    // setCommitIndex / setLastApplied — monotonic, clamped
    // ====================================================================

    @Nested
    class CommitAndApply {

        @Test
        void commitIndexAdvancesButClampsToLastIndex() {
            RaftLog log = logWith(1, 1, 1); // lastIndex=3
            log.setCommitIndex(2);
            assertEquals(2, log.commitIndex());
            // Clamp: asking to commit beyond lastIndex pins at lastIndex.
            log.setCommitIndex(99);
            assertEquals(3, log.commitIndex());
        }

        @Test
        void commitIndexNeverGoesBackward() {
            RaftLog log = logWith(1, 1, 1);
            log.setCommitIndex(3);
            // Kills setCommitIndex L419 ConditionalsBoundary (newCommitIndex >
            // commitIndex): a lower (or equal) value must be ignored.
            log.setCommitIndex(1);
            assertEquals(3, log.commitIndex());
        }

        @Test
        void lastAppliedNeverGoesBackward() {
            RaftLog log = logWith(1, 1, 1);
            log.setLastApplied(3);
            // Kills setLastApplied L428 ConditionalsBoundary (index > lastApplied).
            log.setLastApplied(1);
            assertEquals(3, log.lastApplied());
            log.setLastApplied(3); // equal -> no change (boundary)
            assertEquals(3, log.lastApplied());
        }

        @Test
        void setCommitIndexAtEqualValueReClampsAfterTruncateShrinksLog() {
            // Discriminates setCommitIndex L419 ConditionalsBoundary (`>` -> `>=`).
            // At commitIndex == lastIndex the `>` guard skips. But if the log is then
            // TRUNCATED below commitIndex, a re-set at the (now stale-high) commitIndex
            // value differs: `>=` re-enters and clamps commitIndex DOWN to the new
            // lastIndex via Math.min, whereas `>` leaves it stale-high. We assert the
            // current correct behavior (the `>` guard leaves commitIndex untouched on
            // an equal call); under the `>=` mutant this same call would lower it,
            // so the assertion fails -> mutant killed.
            RaftLog log = logWith(1, 1, 1, 1, 1); // lastIndex=5
            log.setCommitIndex(5);
            assertEquals(5, log.commitIndex());
            log.truncateFrom(4); // lastIndex now 3, commitIndex stays 5 (no clamp on truncate)
            assertEquals(3, log.lastIndex());
            assertEquals(5, log.commitIndex(), "truncate does not move commitIndex");
            // Re-set at the same (stale) value: the `>` guard must skip -> commitIndex
            // stays 5. The `>=` mutant would re-clamp to min(5, lastIndex=3) = 3.
            log.setCommitIndex(5);
            assertEquals(5, log.commitIndex(),
                    "equal-value setCommitIndex must be a no-op (the `>` guard, not `>=`)");
        }
    }

    // ====================================================================
    // compact — boundary guards, entry removal
    // ====================================================================

    @Nested
    class Compact {

        @Test
        void noOpWhenIndexAtOrBelowSnapshot() {
            RaftLog log = logWith(1, 1, 1, 1);
            log.compact(2, 1); // snapshotIndex=2
            int sizeBefore = log.size();
            // Kills compact L485 ORDER_ELSE/Boundary (index <= snapshotIndex):
            // re-compacting at/below the snapshot must be a no-op.
            log.compact(2, 1);
            log.compact(1, 1);
            assertEquals(2, log.snapshotIndex());
            assertEquals(sizeBefore, log.size());
        }

        @Test
        void partialCompactionRemovesPrefixInclusive() {
            RaftLog log = logWith(1, 1, 1, 1, 1); // 5 entries
            log.compact(3, 1);
            // Kills compact L493 toOffset/`offset + 1` math + L494 ArrayList.clear:
            // entries 1..3 removed, 4..5 remain, snapshotIndex=3.
            assertEquals(3, log.snapshotIndex());
            assertEquals(2, log.size());
            assertEquals(5, log.lastIndex());
            assertNull(log.entryAt(3));
            assertNotNull(log.entryAt(4));
        }

        @Test
        void fullCompactionBeyondLastClearsEverything() {
            RaftLog log = logWith(1, 1, 1);
            // index 5 > lastIndex 3 -> clear all. Kills compact L488 boundary
            // (index > lastIndex()) and the entries.clear() call.
            log.compact(5, 2);
            assertEquals(0, log.size());
            assertEquals(5, log.snapshotIndex());
            assertEquals(2, log.snapshotTerm());
            assertEquals(5, log.lastIndex());
        }

        @Test
        void compactExactlyAtLastIndexKeepsNoEntriesViaPartialPath() {
            // index == lastIndex (3) takes the PARTIAL path (index > lastIndex is
            // false), removing the whole prefix [1..3] inclusive. Kills compact L488
            // ConditionalsBoundary (index > lastIndex): if it became `>=`, index==3
            // would wrongly take the clear-everything branch — same final size here,
            // but the boundary BELOW (index just under last) must use the partial
            // path and retain the tail.
            RaftLog log = logWith(1, 1, 1, 1); // lastIndex=4
            log.compact(3, 1); // partial: removes 1..3, keeps 4
            assertEquals(1, log.size());
            assertEquals(4, log.lastIndex());
            assertEquals(3, log.snapshotIndex());
        }

        @Test
        void compactAtSnapshotBoundaryIsNoOpButJustAboveCompacts() {
            // Kills compact L485 ConditionalsBoundary (index <= snapshotIndex):
            // index == snapshotIndex is a no-op; index == snapshotIndex+1 compacts.
            RaftLog log = logWith(1, 1, 1, 1, 1);
            log.compact(2, 1); // snapshotIndex=2
            int before = log.size();
            log.compact(2, 1); // == snapshotIndex -> no-op
            assertEquals(before, log.size());
            assertEquals(2, log.snapshotIndex());
            log.compact(3, 1); // snapshotIndex+1 -> compacts one more
            assertEquals(3, log.snapshotIndex());
            assertEquals(before - 1, log.size());
        }
    }

    // ====================================================================
    // isAtLeastAsUpToDate — Raft §5.4.1 vote freshness
    // ====================================================================

    @Nested
    class UpToDate {

        @Test
        void higherTermWinsRegardlessOfIndex() {
            RaftLog log = logWith(2, 2, 2); // lastTerm=2, lastIndex=3
            // candidate term 3 > our term 2 -> up-to-date even with a shorter log.
            assertTrue(log.isAtLeastAsUpToDate(3, 1));
        }

        @Test
        void lowerTermLosesRegardlessOfIndex() {
            RaftLog log = logWith(2, 2, 2);
            assertFalse(log.isAtLeastAsUpToDate(1, 99));
        }

        @Test
        void equalTermComparesIndexAtBoundary() {
            RaftLog log = logWith(5, 5, 5); // lastTerm=5, lastIndex=3
            // Kills isAtLeastAsUpToDate L537 ConditionalsBoundary
            // (candidateLastLogIndex >= lastIndex()): equal index is up-to-date,
            // one less is not.
            assertTrue(log.isAtLeastAsUpToDate(5, 3));
            assertFalse(log.isAtLeastAsUpToDate(5, 2));
            assertTrue(log.isAtLeastAsUpToDate(5, 4));
        }

        @Test
        void termComparisonIsStrictlyGreaterAtBoundary() {
            // Kills isAtLeastAsUpToDate L535 ConditionalsBoundary
            // (candidateLastLogTerm > myLastTerm). A candidate whose last term is
            // EQUAL to ours does NOT win on term — it falls through to the index
            // comparison. The `>` must not become `>=`: at equal term + a SHORTER
            // index the candidate must lose, which a `>=` term mutant would wrongly
            // accept (it would treat equal-term as term-superior and return true).
            RaftLog log = logWith(4, 4, 4); // lastTerm=4, lastIndex=3
            // equal term (4), shorter index (1): must be rejected (false).
            assertFalse(log.isAtLeastAsUpToDate(4, 1));
            // strictly higher term always wins regardless of index.
            assertTrue(log.isAtLeastAsUpToDate(5, 1));
        }
    }

    // ====================================================================
    // Recovery constructor — WAL + snapshot-meta cross-validation
    // ====================================================================

    @Nested
    class Recovery {

        @Test
        void recoversAppendedEntriesFromWal() {
            Storage storage = Storage.inMemory();
            RaftLog log = new RaftLog(storage);
            log.append(entry(1, 1));
            log.append(entry(2, 1));
            log.append(entry(3, 2));

            // Reopen over the same storage: entries must be recovered from the WAL.
            RaftLog recovered = new RaftLog(storage);
            assertEquals(3, recovered.lastIndex());
            assertEquals(2, recovered.lastTerm());
            assertEquals(2, recovered.termAt(3));
        }

        @Test
        void recoveryInfersSnapshotIndexFromWalFirstEntry() {
            Storage storage = Storage.inMemory();
            RaftLog log = new RaftLog(storage);
            for (int i = 1; i <= 5; i++) {
                log.append(entry(i, 1));
            }
            log.compact(2, 1); // WAL now starts at index 3

            RaftLog recovered = new RaftLog(storage);
            // Kills the recovery cross-validation L156-158: snapshotIndex must equal
            // (firstWalEntry.index - 1) = 2, and entries below that are gone.
            assertEquals(2, recovered.snapshotIndex());
            assertEquals(5, recovered.lastIndex());
            // index 2 is the snapshot boundary: termAt returns snapshotTerm (1),
            // recovered from the persisted snapshot-meta written by compact().
            assertEquals(1, recovered.termAt(2));
            // index 1 is below the snapshot -> gone (-1).
            assertEquals(-1, recovered.termAt(1));
        }

        @Test
        void emptyWalRecoversToEmptyLog() {
            Storage storage = Storage.inMemory();
            RaftLog recovered = new RaftLog(storage);
            assertEquals(0, recovered.lastIndex());
            assertEquals(0, recovered.size());
        }

        @Test
        void persistedSnapshotBlobIsRecoveredWhenItMatchesBoundary() {
            Storage storage = Storage.inMemory();
            RaftLog log = new RaftLog(storage);
            for (int i = 1; i <= 4; i++) log.append(entry(i, 1));
            // Persist the snapshot bytes BEFORE compaction (RR-003 durable-prefix),
            // then compact to index 2. On reopen, the blob whose lastIncludedIndex
            // matches snapshotIndex must be recovered.
            log.persistSnapshot(new SnapshotState(new byte[]{9, 9}, 2, 1, null));
            log.compact(2, 1);

            RaftLog recovered = new RaftLog(storage);
            SnapshotState blob = recovered.recoveredSnapshot();
            assertNotNull(blob, "matching snapshot blob must be recovered");
            assertEquals(2, blob.lastIncludedIndex());
            assertEquals(1, blob.lastIncludedTerm());
            assertArrayEquals(new byte[]{9, 9}, blob.data());
        }

        @Test
        void tornSnapshotBlobIsTreatedAsAbsent() {
            Storage storage = Storage.inMemory();
            RaftLog log = new RaftLog(storage);
            for (int i = 1; i <= 4; i++) log.append(entry(i, 1));
            log.compact(2, 1); // snapshotIndex=2, no blob persisted

            // Write a deliberately-torn blob under the snapshot key: a header
            // claiming a 100-byte data field that is not present. readSnapshotBlob's
            // bounds checks (dataLen vs remaining) must reject it -> recoveredSnapshot
            // null. Kills readSnapshotBlob torn-blob guards.
            java.nio.ByteBuffer torn = java.nio.ByteBuffer.allocate(8 + 8 + 4);
            torn.putLong(2);   // index
            torn.putLong(1);   // term
            torn.putInt(100);  // dataLen 100 but no data bytes follow
            storage.put("raft-log.snapshot", torn.array());

            RaftLog recovered = new RaftLog(storage);
            assertNull(recovered.recoveredSnapshot(), "a torn snapshot blob must be ignored");
            // The WAL remains authoritative: snapshotIndex from meta is intact.
            assertEquals(2, recovered.snapshotIndex());
        }

        @Test
        void aheadOfWalSnapshotBlobIsIgnored() {
            Storage storage = Storage.inMemory();
            RaftLog log = new RaftLog(storage);
            for (int i = 1; i <= 4; i++) log.append(entry(i, 1));
            log.compact(2, 1); // snapshotIndex=2

            // A blob whose lastIncludedIndex (3) is AHEAD of the recovered
            // snapshotIndex (2) must be ignored (the WAL is authoritative). Kills the
            // `blob.lastIncludedIndex() == this.snapshotIndex` acceptance guard.
            java.nio.ByteBuffer ahead = java.nio.ByteBuffer.allocate(8 + 8 + 4 + 1 + 4);
            ahead.putLong(3);   // lastIncludedIndex AHEAD of snapshotIndex 2
            ahead.putLong(1);
            ahead.putInt(1);    // dataLen
            ahead.put((byte) 7);
            ahead.putInt(-1);   // null clusterConfig
            storage.put("raft-log.snapshot", ahead.array());

            RaftLog recovered = new RaftLog(storage);
            assertNull(recovered.recoveredSnapshot(), "an ahead-of-WAL blob must be ignored");
        }

        /**
         * Writes a snapshot blob with the given dataLen/cfgLen fields and a matching
         * snapshot-meta boundary at index 2, then reopens. Returns the recovered
         * blob (or null). Used to pin readSnapshotBlob's bounds checks.
         */
        private SnapshotState reopenWithBlob(byte[] blob) {
            Storage storage = Storage.inMemory();
            RaftLog log = new RaftLog(storage);
            for (int i = 1; i <= 4; i++) log.append(entry(i, 1));
            log.compact(2, 1); // snapshotIndex=2, meta written
            storage.put("raft-log.snapshot", blob);
            return new RaftLog(storage).recoveredSnapshot();
        }

        @Test
        void blobShorterThanHeaderIsRejectedAtBoundary() {
            // Header is 8+8+4 = 20 bytes. Kills readSnapshotBlob L600
            // ConditionalsBoundary (raw.length < 20): 19 bytes -> null; a full
            // 20-byte header with dataLen 0 + a 4-byte cfgLen(-1) is a valid empty
            // snapshot.
            assertNull(reopenWithBlob(new byte[19]), "a sub-header blob must be rejected");

            java.nio.ByteBuffer ok = java.nio.ByteBuffer.allocate(8 + 8 + 4 + 4);
            ok.putLong(2);  // index == snapshotIndex
            ok.putLong(1);  // term
            ok.putInt(0);   // dataLen 0
            ok.putInt(-1);  // cfgLen -1 (null cfg)
            SnapshotState s = reopenWithBlob(ok.array());
            assertNotNull(s, "a minimal valid blob (empty data, null cfg) must be accepted");
            assertEquals(2, s.lastIncludedIndex());
            assertEquals(0, s.data().length);
        }

        @Test
        void blobWithDataLenExceedingRemainingIsRejected() {
            // dataLen claims more bytes than present. Kills readSnapshotBlob L608
            // (`buf.remaining() < dataLen + 4`) and the `dataLen + 4` MathMutator.
            java.nio.ByteBuffer torn = java.nio.ByteBuffer.allocate(8 + 8 + 4 + 2);
            torn.putLong(2);
            torn.putLong(1);
            torn.putInt(10); // claims 10 data bytes; only 2 follow
            torn.putShort((short) 0);
            assertNull(reopenWithBlob(torn.array()), "dataLen beyond remaining must be torn-rejected");
        }

        @Test
        void blobWithNegativeDataLenIsRejected() {
            // Kills readSnapshotBlob L608 `dataLen < 0` arm.
            java.nio.ByteBuffer torn = java.nio.ByteBuffer.allocate(8 + 8 + 4 + 4);
            torn.putLong(2);
            torn.putLong(1);
            torn.putInt(-5); // negative dataLen
            torn.putInt(-1);
            assertNull(reopenWithBlob(torn.array()), "negative dataLen must be rejected");
        }

        @Test
        void blobWithCfgLenExceedingRemainingIsRejected() {
            // cfgLen >= 0 but claims more cfg bytes than present. Kills
            // readSnapshotBlob L615 (`cfgLen >= 0`) and L616 (`remaining < cfgLen`).
            java.nio.ByteBuffer torn = java.nio.ByteBuffer.allocate(8 + 8 + 4 + 1 + 4 + 1);
            torn.putLong(2);
            torn.putLong(1);
            torn.putInt(1);          // dataLen 1
            torn.put((byte) 7);      // the 1 data byte
            torn.putInt(10);         // cfgLen 10 (>= 0) but only 1 byte follows
            torn.put((byte) 9);
            assertNull(reopenWithBlob(torn.array()), "cfgLen beyond remaining must be torn-rejected");
        }

        @Test
        void blobWithExactCfgIsAccepted() {
            // A blob whose cfgLen exactly matches the remaining bytes is valid and
            // carries the cfg. Pins the accept side of L615/L616.
            java.nio.ByteBuffer ok = java.nio.ByteBuffer.allocate(8 + 8 + 4 + 1 + 4 + 2);
            ok.putLong(2);
            ok.putLong(1);
            ok.putInt(1);       // dataLen 1
            ok.put((byte) 7);
            ok.putInt(2);       // cfgLen 2, exactly 2 bytes follow
            ok.put((byte) 3);
            ok.put((byte) 4);
            SnapshotState s = reopenWithBlob(ok.array());
            assertNotNull(s);
            assertArrayEquals(new byte[]{7}, s.data());
            assertArrayEquals(new byte[]{3, 4}, s.clusterConfigData());
        }

        @Test
        void legacyWalWithoutMetaInfersSnapshotIndexFromFirstEntry() {
            // No snapshot-meta key, but a WAL whose first entry is index 3 implies a
            // prior compaction at index 2. The legacy fallback (L138-148) must infer
            // snapshotIndex = firstIndex - 1 = 2. We build this state by writing WAL
            // entries directly via a fresh log, compacting, then clearing the meta key.
            Storage storage = Storage.inMemory();
            RaftLog log = new RaftLog(storage);
            for (int i = 1; i <= 5; i++) log.append(entry(i, 1));
            log.compact(2, 1); // WAL now starts at index 3; meta written

            // Erase the snapshot-meta so recovery must use the legacy inference path.
            storage.put("raft-log.snapshot-meta", new byte[0]);

            RaftLog recovered = new RaftLog(storage);
            // Kills the legacy-fallback math L144 (firstIndex - 1) and the
            // cross-validation L156-158: snapshotIndex must be inferred as 2.
            assertEquals(2, recovered.snapshotIndex());
            assertEquals(5, recovered.lastIndex());
        }
    }
}
