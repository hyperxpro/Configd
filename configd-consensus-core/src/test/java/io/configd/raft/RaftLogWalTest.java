package io.configd.raft;

import io.configd.common.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL recovery regression tests for {@link RaftLog}.
 * Uses {@link io.configd.common.FileStorage} with a temp directory to verify
 * that entries survive across RaftLog instances (simulating a process restart).
 */
class RaftLogWalTest {

    @Test
    void appendedEntriesAreRecoveredAfterRestart(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append entries in the first RaftLog instance
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "cmd-1".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "cmd-2".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 2, "cmd-3".getBytes(StandardCharsets.UTF_8)));

        // Simulate restart: create a new RaftLog with the same storage
        RaftLog log2 = new RaftLog(storage);

        assertEquals(3, log2.size());
        assertEquals(3, log2.lastIndex());
        assertEquals(2, log2.lastTerm());

        LogEntry e1 = log2.entryAt(1);
        assertNotNull(e1);
        assertEquals(1, e1.index());
        assertEquals(1, e1.term());
        assertArrayEquals("cmd-1".getBytes(StandardCharsets.UTF_8), e1.command());

        LogEntry e2 = log2.entryAt(2);
        assertNotNull(e2);
        assertEquals(2, e2.index());
        assertEquals(1, e2.term());
        assertArrayEquals("cmd-2".getBytes(StandardCharsets.UTF_8), e2.command());

        LogEntry e3 = log2.entryAt(3);
        assertNotNull(e3);
        assertEquals(3, e3.index());
        assertEquals(2, e3.term());
        assertArrayEquals("cmd-3".getBytes(StandardCharsets.UTF_8), e3.command());
    }

    @Test
    void truncationIsRecoveredAfterRestart(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append 5 entries, then truncate from index 3
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "b".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 1, "c".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(4, 1, "d".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(5, 1, "e".getBytes(StandardCharsets.UTF_8)));

        log1.truncateFrom(3);
        assertEquals(2, log1.lastIndex());

        // Simulate restart
        RaftLog log2 = new RaftLog(storage);

        assertEquals(2, log2.size());
        assertEquals(2, log2.lastIndex());
        assertEquals(1, log2.lastTerm());
        assertNotNull(log2.entryAt(1));
        assertNotNull(log2.entryAt(2));
        assertNull(log2.entryAt(3));
    }

    @Test
    void appendAfterTruncationIsRecoveredCorrectly(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append, truncate, then append new entries
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "x".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "y".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 1, "z".getBytes(StandardCharsets.UTF_8)));

        log1.truncateFrom(2);
        log1.append(new LogEntry(2, 2, "y2".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 2, "z2".getBytes(StandardCharsets.UTF_8)));

        // Simulate restart
        RaftLog log2 = new RaftLog(storage);

        assertEquals(3, log2.size());
        assertEquals(3, log2.lastIndex());

        LogEntry recovered2 = log2.entryAt(2);
        assertNotNull(recovered2);
        assertEquals(2, recovered2.term(), "Entry at index 2 should have the new term");
        assertArrayEquals("y2".getBytes(StandardCharsets.UTF_8), recovered2.command());

        LogEntry recovered3 = log2.entryAt(3);
        assertNotNull(recovered3);
        assertEquals(2, recovered3.term());
        assertArrayEquals("z2".getBytes(StandardCharsets.UTF_8), recovered3.command());
    }

    @Test
    void emptyLogRecovery(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Create and immediately "restart"
        new RaftLog(storage);
        RaftLog log2 = new RaftLog(storage);

        assertEquals(0, log2.size());
        assertEquals(0, log2.lastIndex());
    }

    @Test
    void compactionRecoveryInfersSnapshotIndex(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append 5 entries, compact through index 3
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "b".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 2, "c".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(4, 2, "d".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(5, 3, "e".getBytes(StandardCharsets.UTF_8)));

        log1.compact(3, 2);
        assertEquals(3, log1.snapshotIndex());
        assertEquals(2, log1.snapshotTerm());
        assertEquals(5, log1.lastIndex());
        assertEquals(2, log1.size()); // entries 4 and 5 remain

        // Simulate restart
        RaftLog log2 = new RaftLog(storage);

        // snapshotIndex should be inferred from first entry (index 4 → snapshotIndex = 3)
        assertEquals(3, log2.snapshotIndex());
        assertEquals(2, log2.snapshotTerm()); // recovered from SNAPSHOT_META_KEY
        assertEquals(5, log2.lastIndex());
        assertEquals(2, log2.size());

        // Verify entries are accessible with correct offset arithmetic
        assertNull(log2.entryAt(3)); // compacted
        LogEntry e4 = log2.entryAt(4);
        assertNotNull(e4);
        assertEquals(4, e4.index());
        assertEquals(2, e4.term());
        assertArrayEquals("d".getBytes(StandardCharsets.UTF_8), e4.command());

        LogEntry e5 = log2.entryAt(5);
        assertNotNull(e5);
        assertEquals(5, e5.index());
        assertEquals(3, e5.term());

        // termAt should work for snapshot boundary and live entries
        assertEquals(2, log2.termAt(3)); // snapshotTerm
        assertEquals(2, log2.termAt(4)); // live entry
        assertEquals(3, log2.termAt(5)); // live entry
        assertEquals(-1, log2.termAt(2)); // before snapshot — not available
    }

    @Test
    void compactionRecoveryAllowsSubsequentAppend(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append, compact, restart, then append more
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "b".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 1, "c".getBytes(StandardCharsets.UTF_8)));
        log1.compact(2, 1);

        RaftLog log2 = new RaftLog(storage);
        assertEquals(2, log2.snapshotIndex());
        assertEquals(3, log2.lastIndex());

        // Append new entry after recovery
        log2.append(new LogEntry(4, 2, "d".getBytes(StandardCharsets.UTF_8)));
        assertEquals(4, log2.lastIndex());
        assertEquals(2, log2.lastTerm());

        // Restart again to verify the new entry persisted
        RaftLog log3 = new RaftLog(storage);
        assertEquals(2, log3.snapshotIndex());
        assertEquals(4, log3.lastIndex());
        assertNotNull(log3.entryAt(4));
        assertEquals(2, log3.entryAt(4).term());
    }

    @Test
    void doubleCompactionRecovery(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append entries, compact, append more, compact again, restart
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "b".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 2, "c".getBytes(StandardCharsets.UTF_8)));
        log1.compact(2, 1);

        log1.append(new LogEntry(4, 2, "d".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(5, 3, "e".getBytes(StandardCharsets.UTF_8)));
        log1.compact(4, 2);

        assertEquals(4, log1.snapshotIndex());
        assertEquals(2, log1.snapshotTerm());
        assertEquals(5, log1.lastIndex());
        assertEquals(1, log1.size()); // only entry 5 remains

        // Simulate restart
        RaftLog log2 = new RaftLog(storage);

        assertEquals(4, log2.snapshotIndex());
        assertEquals(2, log2.snapshotTerm());
        assertEquals(5, log2.lastIndex());
        assertEquals(1, log2.size());
        assertEquals(2, log2.termAt(4)); // snapshotTerm
        assertEquals(3, log2.termAt(5)); // live entry
    }

    @Test
    void fullCompactionRecovery(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append and compact everything
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 2, "b".getBytes(StandardCharsets.UTF_8)));
        log1.compact(2, 2);

        assertEquals(2, log1.snapshotIndex());
        assertEquals(0, log1.size()); // all entries compacted

        // Simulate restart — empty WAL, but metadata contains snapshotIndex+snapshotTerm
        RaftLog log2 = new RaftLog(storage);

        // snapshotIndex and snapshotTerm recovered from metadata even with empty WAL
        assertEquals(2, log2.snapshotIndex());
        assertEquals(2, log2.snapshotTerm());
        assertEquals(2, log2.lastIndex()); // lastIndex() returns snapshotIndex when empty
        assertEquals(0, log2.size());

        // New entries start at snapshotIndex + 1 (no index re-use)
        log2.append(new LogEntry(3, 3, "c".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, log2.lastIndex());
        assertEquals(1, log2.size());
    }

    @Test
    void truncationAfterCompactionRecovery(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append 5 entries, compact through 2, restart
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "a".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "b".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 2, "c".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(4, 2, "d".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(5, 2, "e".getBytes(StandardCharsets.UTF_8)));
        log1.compact(2, 1);

        RaftLog log2 = new RaftLog(storage);
        assertEquals(2, log2.snapshotIndex());
        assertEquals(5, log2.lastIndex());

        // Truncate from index 4 (simulating conflict resolution)
        log2.truncateFrom(4);
        assertEquals(3, log2.lastIndex());
        assertEquals(1, log2.size()); // only entry 3 remains

        // Append replacement entries
        log2.append(new LogEntry(4, 3, "d2".getBytes(StandardCharsets.UTF_8)));
        assertEquals(4, log2.lastIndex());

        // Restart again to verify truncation + append persisted correctly
        RaftLog log3 = new RaftLog(storage);
        assertEquals(2, log3.snapshotIndex());
        assertEquals(4, log3.lastIndex());
        assertEquals(2, log3.entryAt(3).term());
        assertEquals(3, log3.entryAt(4).term()); // new term from replacement
        assertArrayEquals("d2".getBytes(StandardCharsets.UTF_8), log3.entryAt(4).command());
    }

    /**
     * Regression test for F-0012: truncateFrom() must persist correctly so that
     * a new RaftLog created from the same storage directory recovers the
     * truncated state.
     * <p>
     * Before the fix, truncateFrom() called rewriteWal() which uses renameLog()
     * but did not fsync the directory afterward. On Linux ext4, a crash after
     * renameLog() but before directory metadata sync could lose the truncation,
     * leaving stale entries that violate the log matching property on recovery.
     * <p>
     * After the fix, storage.sync() is called after rewriteWal() in truncateFrom(),
     * ensuring the directory rename is durable.
     * <p>
     * This test verifies the behavioral outcome: after truncateFrom() on a
     * WAL-backed RaftLog, creating a new RaftLog from the same storage directory
     * must recover exactly the truncated state (not the original entries).
     */
    @Test
    void truncateFromPersistsDurablyAcrossRestart(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);

        // Append 5 entries across two terms
        RaftLog log1 = new RaftLog(storage);
        log1.append(new LogEntry(1, 1, "alpha".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(2, 1, "bravo".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(3, 1, "charlie".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(4, 2, "delta".getBytes(StandardCharsets.UTF_8)));
        log1.append(new LogEntry(5, 2, "echo".getBytes(StandardCharsets.UTF_8)));

        assertEquals(5, log1.lastIndex());
        assertEquals(5, log1.size());

        // Truncate from index 3 — entries 3, 4, 5 are removed
        log1.truncateFrom(3);
        assertEquals(2, log1.lastIndex());
        assertEquals(2, log1.size());
        assertEquals(1, log1.termAt(1));
        assertEquals(1, log1.termAt(2));
        assertNull(log1.entryAt(3), "Entry 3 should be gone after truncation");
        assertNull(log1.entryAt(4), "Entry 4 should be gone after truncation");
        assertNull(log1.entryAt(5), "Entry 5 should be gone after truncation");

        // Simulate restart: create a new RaftLog backed by the same storage.
        // Before the F-0012 fix, the directory fsync was missing after rewriteWal().
        // This test verifies the truncated state is recovered correctly.
        RaftLog log2 = new RaftLog(storage);

        assertEquals(2, log2.lastIndex(),
                "Recovered log must have lastIndex=2 after truncateFrom(3)");
        assertEquals(2, log2.size(),
                "Recovered log must have exactly 2 entries");
        assertEquals(1, log2.lastTerm());

        // Verify the surviving entries are intact
        LogEntry e1 = log2.entryAt(1);
        assertNotNull(e1);
        assertEquals(1, e1.index());
        assertEquals(1, e1.term());
        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), e1.command());

        LogEntry e2 = log2.entryAt(2);
        assertNotNull(e2);
        assertEquals(2, e2.index());
        assertEquals(1, e2.term());
        assertArrayEquals("bravo".getBytes(StandardCharsets.UTF_8), e2.command());

        // Verify truncated entries are NOT present after recovery
        assertNull(log2.entryAt(3),
                "Entry 3 must not be recovered after truncateFrom(3)");
        assertNull(log2.entryAt(4),
                "Entry 4 must not be recovered after truncateFrom(3)");
        assertNull(log2.entryAt(5),
                "Entry 5 must not be recovered after truncateFrom(3)");

        // Verify we can append new entries at the truncation point after recovery
        log2.append(new LogEntry(3, 3, "charlie2".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, log2.lastIndex());
        assertEquals(3, log2.lastTerm());

        // Third restart to verify the new entry after truncation was also persisted
        RaftLog log3 = new RaftLog(storage);
        assertEquals(3, log3.lastIndex());
        assertEquals(3, log3.size());
        assertArrayEquals("charlie2".getBytes(StandardCharsets.UTF_8),
                log3.entryAt(3).command());
        assertEquals(3, log3.entryAt(3).term(),
                "New entry appended after truncation must have the new term");
    }

    /**
     * C-101 (iter-2) regression: when {@code Storage.appendToLog} throws
     * (ENOSPC, IOException), the in-memory entries list MUST NOT be mutated.
     * Otherwise the leader has a volatile entry that followers may have
     * fsync'd durably — a leader crash immediately after returning would lose
     * a committed entry on the leader's state machine. Pre-fix ordering was
     * {@code entries.add(entry); storage.appendToLog(...);} — this test would
     * have asserted {@code lastIndex() == 1} after the throw.
     */
    @Test
    void appendThrowsBeforeListMutationOnStorageFailure() {
        Storage failingStorage = new Storage() {
            @Override
            public void appendToLog(String logName, byte[] data) {
                throw new RuntimeException("simulated ENOSPC");
            }
            @Override
            public java.util.List<byte[]> readLog(String logName) { return java.util.List.of(); }
            @Override
            public void truncateLog(String logName) {}
            @Override
            public void renameLog(String fromLogName, String toLogName) {}
            @Override
            public void put(String key, byte[] value) {}
            @Override
            public byte[] get(String key) { return null; }
            @Override
            public void sync() {}
        };

        RaftLog log = new RaftLog(failingStorage);
        long lastIndexBefore = log.lastIndex();
        int sizeBefore = log.size();

        assertThrows(RuntimeException.class,
                () -> log.append(new LogEntry(1, 1, "doomed".getBytes(StandardCharsets.UTF_8))),
                "C-101: storage failure must propagate, not be swallowed");

        assertEquals(lastIndexBefore, log.lastIndex(),
                "C-101: lastIndex must NOT advance when storage append fails");
        assertEquals(sizeBefore, log.size(),
                "C-101: in-memory entries list must NOT contain the failed entry");
        assertNull(log.entryAt(1),
                "C-101: entryAt must report no entry at the failed index");
    }
}
