package io.configd.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the consensus-path {@code Storage::sync} call on the WAL-rewrite
 * path is load-bearing for durability.
 * <p>
 * {@code RaftLog.compact} (and {@code truncateFrom}) rewrites the WAL via
 * {@code rewriteWal()} and then calls {@code storage.sync()} (the directory
 * fsync) precisely to make the rename-style WAL deletion/replacement durable.
 * A "removed call to Storage::sync" mutation at those sites cannot be caught
 * by a wrapper over {@code FileStorage} or a plain in-memory map - an atomic
 * rename is visible to a same-directory reopen whether or not the directory was
 * fsynced (a blind spot that {@code RaftLogWalTest.truncateFromPersistsDurablyAcrossRestart}
 * suffered from by reopening the same in-process directory; and that the existing
 * {@code SnapshotCrashRecoveryTest} cells do NOT exercise - verified: removing the
 * {@code compact} sync leaves all 6 of them green).
 * <p>
 * {@link CrashStorage} models the hazard faithfully: rename-style mutations
 * ({@code truncateLog}/{@code renameLog}) are durable ONLY after the following
 * {@code sync()}; a {@link CrashStorage#crash()} reverts any rename still
 * awaiting that {@code sync()}. So deleting the {@code sync()} after a WAL rewrite
 * leaves the rewrite non-durable and lost on crash - recovery then sees the STALE
 * pre-compaction WAL, dropping the snapshot boundary and re-exposing already-compacted
 * entries.
 * <p>
 * We exercise the FULL-COMPACTION shape (snapshot covers the whole WAL ->
 * {@code rewriteWal} deletes the WAL via a rename-style {@code truncateLog} with
 * NO trailing append). This isolates the trailing {@code sync()} cleanly. (The
 * conflict-{@code truncateFrom} shape is NOT used here: it does
 * {@code truncateLog(tmp)} -> {@code appendToLog(tmp)} -> {@code renameLog} and a
 * self-durable append following a deferred truncate of the same log is a
 * modelling corner CrashStorage does not capture for that path - the compaction
 * path is the faithful and sufficient pinning of the sync failure paths.)
 */
class WalSyncCrashTest {

    private static byte[] cmd(String s) {
        return s.getBytes();
    }

    @Test
    void compactionWalDeletionSurvivesCrashRestart() {
        CrashStorage storage = new CrashStorage();
        RaftLog log = new RaftLog(storage);

        // Seed a 3-entry WAL (self-durable appendToLog writes).
        log.append(new LogEntry(1, 1, cmd("a")));
        log.append(new LogEntry(2, 1, cmd("b")));
        log.append(new LogEntry(3, 1, cmd("c")));
        assertEquals(3, log.lastIndex());

        // Snapshot covering the WHOLE log, then compact. persistSnapshot's put is
        // self-durable; compact() rewrites the (now empty) WAL - a rename-style
        // truncateLog of WAL_NAME - and storage.sync() makes that deletion durable.
        log.persistSnapshot(new SnapshotState(new byte[]{42}, 3, 1, null));
        log.compact(3, 1);
        assertEquals(3, log.snapshotIndex(), "snapshot boundary advanced to 3");
        assertEquals(0, storage.recoveredView().readLog("raft-log").size(),
                "with sync the WAL deletion is durable: zero WAL frames on the platter");

        storage.crash();
        RaftLog recovered = new RaftLog(storage.recoveredView());

        assertEquals(3, recovered.snapshotIndex(),
                "RR-086: the snapshot boundary must survive the crash — a removed compact() "
                        + "sync reverts the WAL deletion and recovery falls back to snapshotIndex 0");
        assertNull(recovered.entryAt(1),
                "RR-086: the compacted-away entry at index 1 must not reappear after recovery");
        assertNull(recovered.entryAt(2),
                "RR-086: the compacted-away entry at index 2 must not reappear after recovery");
        assertTrue(recovered.lastIndex() >= 3, "lastIndex must be at least the snapshot boundary");
    }
}
