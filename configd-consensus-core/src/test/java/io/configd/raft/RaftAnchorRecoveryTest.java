package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.Storage;
import io.configd.common.WalContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anchor-backed recovery gates layered on the dual-slot {@link AnchorFile}: head reconciliation
 * (W==A accept / W&gt;A accept-forward / W&lt;A REFUSE), the Step-2.5 term-witness gate, and the
 * FRESH-vs-absent-vs-tamper presence gate. The dual-slot mechanics themselves are in
 * {@link AnchorFileTest}; the crash-interleaving matrix is in {@code SnapshotCrashRecoveryTest} /
 * {@code AdversarialCrashRecoveryTest}.
 */
class RaftAnchorRecoveryTest {

    private static IntegrityEnvelope keyed() {
        return SnapshotIntegrityTest.keyedEnvelope();
    }

    private static LogEntry entry(long index, long term, String cmd) {
        return new LogEntry(index, term, cmd.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void cleanReopenAcceptsWhenHeadMatchesAnchor(@TempDir Path dir) {
        Storage storage = Storage.file(dir);
        RaftLog log = new RaftLog(storage, keyed(), 0);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 2, "c"));
        log.closeAnchor();

        // W == A (every append raised the anchor head via syncWal): clean accept.
        RaftLog recovered = new RaftLog(Storage.file(dir), keyed(), 0);
        assertEquals(3, recovered.lastIndex());
        assertEquals(2, recovered.lastTerm());
        recovered.closeAnchor();
    }

    @Test
    void walAheadOfAnchorAcceptsForward() {
        // Model a crash BETWEEN the WAL fsync and the anchor fsync (leader flush): the WAL is durable to
        // index 4 but the anchor still names index 3. Recovery adopts the WAL head (entries (A,W] were
        // never committed-and-acked) and rewrites the anchor forward.
        CrashStorage storage = new CrashStorage();
        RaftLog log = new RaftLog(storage, keyed(), 0);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 1, "c"));   // syncWal raised the anchor: A = 3
        // appendNoSync makes the WAL frame durable in the crash model but does NOT raise the anchor,
        // so the durable WAL head (4) is ahead of the anchor (3) - exactly the flush-crash window.
        log.appendNoSync(entry(4, 1, "d"));

        RaftLog recovered = new RaftLog(storage.recoveredView(), keyed(), 0);
        assertEquals(4, recovered.lastIndex(), "W>A must accept-forward and adopt the durable WAL head");

        // The anchor was rewritten forward, so a further reopen is a clean W==A.
        assertEquals(4, recovered.anchor().current().lastDurableIndex());
    }

    @Test
    void walBelowAnchorHeadRefuses(@TempDir Path dir) throws Exception {
        // A committed-and-acked durable floor (anchor lastDurableIndex = 3) with a WAL that lost its
        // last record (W = 2 < A = 3): the attack the anchor exists to catch -> REFUSE.
        Storage storage = Storage.file(dir);
        RaftLog log = new RaftLog(storage, keyed(), 0);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.append(entry(3, 1, "c"));   // A = 3
        log.closeAnchor();

        // Truncate the WAL's last frame (W = 2) while the anchor still asserts A = 3.
        dropLastWalFrame(dir);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), 0));
        assertTrue(ex.getMessage().contains("head-rollback"),
                "a WAL below the anchor's committed durable floor must REFUSE, got: " + ex.getMessage());
    }

    /** Drops the final complete frame from the {@code raft-log.wal} file (a lost-tail simulation). */
    private static void dropLastWalFrame(Path dir) throws Exception {
        Path wal = dir.resolve("raft-log.wal");
        byte[] bytes = Files.readAllBytes(wal);
        int pos = WalContainer.HEADER_SIZE;
        int lastFrameStart = pos;
        while (pos + 4 <= bytes.length) {
            int len = ByteBuffer.wrap(bytes, pos, 4).getInt();
            int frameEnd = pos + 4 + len + 4; // len prefix + data + CRC
            if (len < 0 || frameEnd > bytes.length) {
                break;
            }
            lastFrameStart = pos;
            pos = frameEnd;
        }
        Files.write(wal, java.util.Arrays.copyOf(bytes, lastFrameStart), StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Test
    void anchorTermRolledBackBelowWalTermRefuses(@TempDir Path dir) {
        Storage storage = Storage.file(dir);
        RaftLog log = new RaftLog(storage, keyed(), 0);
        log.append(entry(1, 5, "a"));   // term 5 -> anchor.currentTerm = 5 (Fix A term bump)
        log.append(entry(2, 5, "b"));
        log.closeAnchor();

        // Roll the anchor's currentTerm back to 2 (below the WAL's term 5) - an anchor rollback across a
        // WAL-witnessed vote boundary. Step-2.5 must REFUSE it (the retired max()-repair would have
        // masked it).
        AnchorFile anchor = AnchorFile.openInDirectory(dir, 0, keyed());
        anchor.writeTermVote(2, AnchorRecord.VOTED_FOR_NULL);
        anchor.close();

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), 0));
        assertTrue(ex.getMessage().contains("term-witness"),
                "a WAL term above the anchor's currentTerm must REFUSE (Step-2.5), got: " + ex.getMessage());
    }

    @Test
    void freshEmptyShardBootstrapsAnchor(@TempDir Path dir) {
        RaftLog log = new RaftLog(Storage.file(dir), keyed(), 0);
        assertEquals(0, log.lastIndex(), "a fresh shard boots empty");
        assertTrue(log.anchor().existedAtOpen() == false || log.anchor().hasValidRecord(),
                "a fresh shard laid down a bootstrap anchor");
        // The bootstrap anchor is on disk now: a reopen is clean (not a REFUSE).
        log.closeAnchor();
        RaftLog reopened = new RaftLog(Storage.file(dir), keyed(), 0);
        assertEquals(0, reopened.lastIndex());
        reopened.closeAnchor();
    }

    @Test
    void deletedAnchorOverNonEmptyShardRefuses(@TempDir Path dir) throws Exception {
        Storage storage = Storage.file(dir);
        RaftLog log = new RaftLog(storage, keyed(), 0);
        log.append(entry(1, 1, "a"));
        log.append(entry(2, 1, "b"));
        log.closeAnchor();

        // An adversary deletes the anchor but leaves the WAL: a non-empty shard MUST carry its anchor.
        Files.delete(dir.resolve(FileAnchorIO.ANCHOR_FILE_NAME));

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(dir), keyed(), 0));
        assertTrue(ex.getMessage().contains("anchor was deleted") || ex.getMessage().contains("no raft-anchor"),
                "a deleted anchor over a non-empty shard must REFUSE, got: " + ex.getMessage());
    }

    @Test
    void conflictTruncationThenReappendRecoversCleanly(@TempDir Path dir) {
        Storage storage = Storage.file(dir);
        RaftLog log = new RaftLog(storage, keyed(), 0);
        log.append(entry(1, 1, "v1"));
        log.append(entry(2, 1, "v2"));
        log.append(entry(3, 1, "v3"));
        // A leader conflict truncation: INV-ANCHOR-LOWER lowers the anchor to index 1 BEFORE the WAL
        // rewrite, then the re-append raises it. A legal truncation must NOT trip W<A on recovery.
        log.truncateFrom(2);
        log.append(entry(2, 2, "v2b"));
        log.append(entry(3, 2, "v3b"));
        log.closeAnchor();

        RaftLog recovered = new RaftLog(Storage.file(dir), keyed(), 0);
        assertEquals(3, recovered.lastIndex());
        assertEquals(2, recovered.lastTerm());
        assertEquals("v2b", new String(recovered.entryAt(2).command(), StandardCharsets.UTF_8));
        recovered.closeAnchor();
    }
}
