package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dual-slot {@link AnchorFile} writer + codec (the frozen anchor mechanism): the
 * one-time preallocation, the write-the-stale-slot protocol, highest-valid-{@code anchorSeq} read,
 * torn/tampered-slot fallback, both-slots-invalid vs FRESH, and the unauthenticated container-header
 * guard. The recovery gates that sit ON TOP of this (W&lt;A / W&gt;A / Step-2.5 / snapshot-join) are in
 * {@link RaftAnchorRecoveryTest}.
 */
class AnchorFileTest {

    private static final int GID = 0;

    private static IntegrityEnvelope keyed() {
        return SnapshotIntegrityTest.keyedEnvelope();
    }

    @Test
    void freshBootstrapLaysDownPreallocatedFile(@TempDir Path dir) throws Exception {
        AnchorFile anchor = AnchorFile.openInDirectory(dir, GID, keyed());
        assertFalse(anchor.existedAtOpen(), "no anchor file yet");
        anchor.bootstrapFresh();

        AnchorRecord r = anchor.current();
        assertEquals(1L, r.anchorSeq(), "fresh anchor bootstraps at seq 1");
        assertEquals(0L, r.currentTerm());
        assertEquals(AnchorRecord.VOTED_FOR_NULL, r.votedFor());
        assertEquals(0L, r.lastDurableIndex());
        assertEquals(0L, r.snapshotIndex());
        anchor.close();

        // File is fully preallocated at creation (header + both 512-B slots) so steady-state writes
        // never allocate - anchor-ENOSPC is impossible after boot.
        assertEquals(AnchorFile.FILE_SIZE,
                Files.size(dir.resolve(FileAnchorIO.ANCHOR_FILE_NAME)),
                "the anchor file must be preallocated to 1032 bytes");
    }

    @Test
    void termVoteAndDurableHeadRoundTripAcrossReopen(@TempDir Path dir) {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        a.bootstrapFresh();
        a.writeTermVote(5, 3);          // term 5, voted for node 3
        a.writeDurableHead(10, 5);      // durable head at index 10, term 5
        long seqAfterWrites = a.current().anchorSeq();
        a.close();

        AnchorFile reopened = AnchorFile.openInDirectory(dir, GID, keyed());
        assertTrue(reopened.existedAtOpen());
        assertTrue(reopened.hasValidRecord());
        AnchorRecord r = reopened.current();
        assertEquals(5L, r.currentTerm());
        assertEquals(3, r.votedFor());
        assertEquals(10L, r.lastDurableIndex());
        assertEquals(5L, r.lastDurableTerm());
        assertEquals(seqAfterWrites, r.anchorSeq(), "reopen recovers the highest-seq slot");
        reopened.close();
    }

    @Test
    void durableHeadWriteIsNoOpWhenUnchanged(@TempDir Path dir) {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        a.bootstrapFresh();
        a.writeDurableHead(4, 2);
        long seq = a.current().anchorSeq();
        a.writeDurableHead(4, 2); // same head - must not churn the seq / write a slot
        assertEquals(seq, a.current().anchorSeq(), "an unchanged head must not advance the anchorSeq");
        a.close();
    }

    @Test
    void reopenPicksTheHighestValidAnchorSeq(@TempDir Path dir) {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        a.bootstrapFresh();                     // seq 1 -> slot 0
        a.writeDurableHead(1, 1);               // seq 2 -> slot 1
        a.writeDurableHead(2, 1);               // seq 3 -> slot 0 (stale slot overwritten)
        a.writeDurableHead(3, 1);               // seq 4 -> slot 1
        assertEquals(4L, a.current().anchorSeq());
        a.close();

        AnchorFile reopened = AnchorFile.openInDirectory(dir, GID, keyed());
        assertEquals(4L, reopened.current().anchorSeq());
        assertEquals(3L, reopened.current().lastDurableIndex());
        reopened.close();
    }

    @Test
    void tornLiveSlotFallsBackToTheStaleSlot(@TempDir Path dir) throws Exception {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        a.bootstrapFresh();          // seq 1 -> slot 0
        a.writeDurableHead(1, 1);    // seq 2 -> slot 1 (this becomes the LIVE slot)
        a.close();

        // Corrupt slot 1 (the live, higher-seq slot). Its CRC/MAC now fails, so recovery must fall back
        // to slot 0 (seq 1, still valid) - the whole point of the dual-slot write-one-slot protocol.
        corruptByte(dir, AnchorFile.SLOT1_OFFSET + AnchorFile.RECORD_LEN_PREFIX + 20);

        AnchorFile reopened = AnchorFile.openInDirectory(dir, GID, keyed());
        assertTrue(reopened.hasValidRecord(), "the untouched stale slot must survive a torn live slot");
        assertEquals(1L, reopened.current().anchorSeq(), "recovery falls back to the valid stale slot");
        reopened.close();
    }

    @Test
    void bothSlotsInvalidYieldsNoValidRecord(@TempDir Path dir) throws Exception {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        a.bootstrapFresh();
        a.writeDurableHead(1, 1);
        a.close();

        // Corrupt BOTH slots: neither authenticates -> present-but-both-invalid (the caller REFUSEs).
        corruptByte(dir, AnchorFile.SLOT0_OFFSET + AnchorFile.RECORD_LEN_PREFIX + 20);
        corruptByte(dir, AnchorFile.SLOT1_OFFSET + AnchorFile.RECORD_LEN_PREFIX + 20);

        AnchorFile reopened = AnchorFile.openInDirectory(dir, GID, keyed());
        assertTrue(reopened.existedAtOpen(), "the file is present");
        assertFalse(reopened.hasValidRecord(), "both slots invalid -> no valid record (tamper, REFUSE)");
        reopened.close();
    }

    @Test
    void badContainerMagicIsRefusedLoud(@TempDir Path dir) throws Exception {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        a.bootstrapFresh();
        a.close();

        // Flip the first magic byte: the unauthenticated container header must fail closed on open.
        corruptByte(dir, 0);
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> AnchorFile.openInDirectory(dir, GID, keyed()));
        assertTrue(ex.getMessage().contains("container magic"),
                "a garbled container magic must be a loud refuse, got: " + ex.getMessage());
    }

    @Test
    void unknownContainerVersionIsRefused(@TempDir Path dir) throws Exception {
        AnchorFile a = AnchorFile.openInDirectory(dir, GID, keyed());
        a.bootstrapFresh();
        a.close();

        // fileVersion byte is at offset 4 (after the 4-byte magic); set it to an unknown 2.
        Path file = dir.resolve(FileAnchorIO.ANCHOR_FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        bytes[4] = 2;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> AnchorFile.openInDirectory(dir, GID, keyed()));
        assertTrue(ex.getMessage().contains("fileVersion"),
                "an unknown container fileVersion must be refused, got: " + ex.getMessage());
    }

    @Test
    void crossShardAnchorSlotDoesNotAuthenticateForAnotherGid(@TempDir Path dir) {
        // A gid=1 anchor's slots carry scopeId=1; a gid=0 reader over the same file finds no valid slot.
        AnchorFile foreign = AnchorFile.openInDirectory(dir, 1, keyed());
        foreign.bootstrapFresh();
        foreign.writeDurableHead(3, 1);
        foreign.close();

        AnchorFile victim = AnchorFile.openInDirectory(dir, 0, keyed());
        assertTrue(victim.existedAtOpen());
        assertFalse(victim.hasValidRecord(),
                "a foreign-scope anchor must not authenticate for a different gid (cross-shard splice)");
        victim.close();
    }

    /** Flips one byte at the given file offset (a torn/tamper simulation). */
    private static void corruptByte(Path dir, int offset) throws Exception {
        Path file = dir.resolve(FileAnchorIO.ANCHOR_FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        bytes[offset] ^= 0x5A;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
