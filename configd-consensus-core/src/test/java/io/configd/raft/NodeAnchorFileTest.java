package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dual-slot {@link NodeAnchorFile} writer + codec - the node-level parallel of
 * {@link AnchorFileTest}: one-time preallocation, write-the-stale-slot, highest-valid-{@code seq} read,
 * torn/tampered-slot fallback, both-slots-invalid vs first-boot, the container-header guard, and the
 * fail-closed sync seam. It uses {@code NODE_ANCHOR_MAGIC} + {@code NODE_SCOPE} + the 92-byte payload.
 */
class NodeAnchorFileTest {

    private static IntegrityEnvelope keyed() {
        return SnapshotIntegrityTest.keyedEnvelope();
    }

    /** An AES-256-GCM encrypting envelope over a FIXED root (re-derives the same DEKs across reopen). */
    private static IntegrityEnvelope encrypting() {
        byte[] rootBytes = new byte[32];
        java.util.Arrays.fill(rootBytes, (byte) 0x6B);
        io.configd.common.kms.RootKey root = new io.configd.common.kms.RootKey(
                rootBytes, new io.configd.common.kms.KeyId("local", "test", 1));
        return IntegrityEnvelope.encrypting(new io.configd.common.SegmentKeyManager(root));
    }

    private static byte[] hash(int fill) {
        byte[] h = new byte[NodeAnchorRecord.HASH_LEN];
        java.util.Arrays.fill(h, (byte) fill);
        return h;
    }

    private static NodeAnchorRecord mint(long epoch, int n, byte[] digest) {
        return NodeAnchorRecord.fresh(epoch, n, digest);
    }

    @Test
    void bootstrapLaysDownPreallocated1032ByteFile(@TempDir Path dir) throws Exception {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        assertFalse(na.existedAtOpen(), "no node-anchor file yet");
        na.bootstrap(mint(1L, 1, hash(0x10)));

        NodeAnchorRecord r = na.current();
        assertEquals(1L, r.nodeAnchorSeq(), "fresh node-anchor bootstraps at seq 1");
        assertEquals(1L, r.topologyEpoch());
        assertEquals(1, r.shardCount());
        assertArrayEquals(hash(0x10), r.shardAnchorDigest());
        na.close();

        assertEquals(AnchorFile.FILE_SIZE,
                Files.size(dir.resolve(NodeAnchorFile.NODE_ANCHOR_FILE_NAME)),
                "the node-anchor file must be preallocated to 1032 bytes");
    }

    @Test
    void writeRoundTripAcrossReopen(@TempDir Path dir) {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        na.bootstrap(mint(2L, 3, hash(0x01)));
        na.write(na.current().withAuditAndDigest(64L, hash(0x22), hash(0x33)));
        long seqAfter = na.current().nodeAnchorSeq();
        na.close();

        NodeAnchorFile reopened = NodeAnchorFile.openInDirectory(dir, keyed());
        assertTrue(reopened.existedAtOpen());
        assertTrue(reopened.hasValidRecord());
        NodeAnchorRecord r = reopened.current();
        assertEquals(2L, r.topologyEpoch());
        assertEquals(3, r.shardCount());
        assertEquals(64L, r.auditRecordCount());
        assertArrayEquals(hash(0x22), r.auditHeadHash());
        assertArrayEquals(hash(0x33), r.shardAnchorDigest());
        assertEquals(seqAfter, r.nodeAnchorSeq(), "reopen recovers the highest-seq slot");
        reopened.close();
    }

    @Test
    void reopenPicksTheHighestValidSeq(@TempDir Path dir) {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        na.bootstrap(mint(1L, 1, hash(0)));                       // seq 1 -> slot 0
        na.write(na.current().withAuditAndDigest(1L, hash(1), hash(1)));  // seq 2 -> slot 1
        na.write(na.current().withAuditAndDigest(2L, hash(2), hash(2)));  // seq 3 -> slot 0
        na.write(na.current().withAuditAndDigest(3L, hash(3), hash(3)));  // seq 4 -> slot 1
        assertEquals(4L, na.current().nodeAnchorSeq());
        na.close();

        NodeAnchorFile reopened = NodeAnchorFile.openInDirectory(dir, keyed());
        assertEquals(4L, reopened.current().nodeAnchorSeq());
        assertEquals(3L, reopened.current().auditRecordCount());
        reopened.close();
    }

    @Test
    void tornLiveSlotFallsBackToTheStaleSlot(@TempDir Path dir) throws Exception {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        na.bootstrap(mint(1L, 1, hash(0)));                          // seq 1 -> slot 0
        na.write(na.current().withAuditAndDigest(1L, hash(9), hash(9)));     // seq 2 -> slot 1 (live)
        na.close();

        // Corrupt slot 1 (the live, higher-seq slot). Recovery must fall back to slot 0 (seq 1).
        corruptByte(dir, AnchorFile.SLOT1_OFFSET + AnchorFile.RECORD_LEN_PREFIX + 20);

        NodeAnchorFile reopened = NodeAnchorFile.openInDirectory(dir, keyed());
        assertTrue(reopened.hasValidRecord(), "the untouched stale slot must survive a torn live slot");
        assertEquals(1L, reopened.current().nodeAnchorSeq(), "recovery falls back to the valid stale slot");
        reopened.close();
    }

    @Test
    void bothSlotsInvalidYieldsNoValidRecord(@TempDir Path dir) throws Exception {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        na.bootstrap(mint(1L, 1, hash(0)));
        na.write(na.current().withAuditAndDigest(1L, hash(9), hash(9)));
        na.close();

        corruptByte(dir, AnchorFile.SLOT0_OFFSET + AnchorFile.RECORD_LEN_PREFIX + 20);
        corruptByte(dir, AnchorFile.SLOT1_OFFSET + AnchorFile.RECORD_LEN_PREFIX + 20);

        NodeAnchorFile reopened = NodeAnchorFile.openInDirectory(dir, keyed());
        assertTrue(reopened.existedAtOpen(), "the file is present");
        assertFalse(reopened.hasValidRecord(), "both slots invalid -> no valid record (tamper, caller REFUSEs)");
        reopened.close();
    }

    @Test
    void badContainerMagicIsRefusedLoud(@TempDir Path dir) throws Exception {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        na.bootstrap(mint(1L, 1, hash(0)));
        na.close();

        corruptByte(dir, 0); // flip the first container-magic byte
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> NodeAnchorFile.openInDirectory(dir, keyed()));
        assertTrue(ex.getMessage().contains("container magic"),
                "a garbled container magic must be a loud refuse, got: " + ex.getMessage());
    }

    @Test
    void unknownContainerVersionIsRefused(@TempDir Path dir) throws Exception {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        na.bootstrap(mint(1L, 1, hash(0)));
        na.close();

        Path file = dir.resolve(NodeAnchorFile.NODE_ANCHOR_FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        bytes[4] = 2; // fileVersion at offset 4 (after the 4-byte magic)
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> NodeAnchorFile.openInDirectory(dir, keyed()));
        assertTrue(ex.getMessage().contains("fileVersion"),
                "an unknown container fileVersion must be refused, got: " + ex.getMessage());
    }

    @Test
    void armedSyncFailureAbortsBeforeTheDurableBarrier(@TempDir Path dir) {
        // Fail-closed: a throwing node-anchor sync must abort BEFORE advancing the in-memory record, so
        // a refresh whose fsync fails leaves the previous durable record intact (retried next tick).
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, keyed());
        na.bootstrap(mint(1L, 1, hash(0)));
        long seqBefore = na.current().nodeAnchorSeq();

        na.armSyncFailure(1);
        assertThrows(UncheckedIOException.class,
                () -> na.write(na.current().withAuditAndDigest(1L, hash(7), hash(7))));
        assertEquals(seqBefore, na.current().nodeAnchorSeq(),
                "a throwing sync must NOT advance the in-memory record (fail-closed)");
        na.close();
    }

    @Test
    void encryptingPostureRoundTrips(@TempDir Path dir) {
        // The node-anchor authenticates under the SAME K_integrity as the WAL - including Layer C
        // (AES-256-GCM). The 92-B payload wraps to a 156-B GCM envelope, well under the 508-B slot.
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, encrypting());
        na.bootstrap(mint(3L, 2, hash(0xE1)));
        na.write(na.current().withAuditAndDigest(7L, hash(0xE2), hash(0xE3)));
        na.close();

        NodeAnchorFile reopened = NodeAnchorFile.openInDirectory(dir, encrypting());
        assertTrue(reopened.hasValidRecord(), "a GCM-enveloped node-anchor must round-trip");
        NodeAnchorRecord r = reopened.current();
        assertEquals(3L, r.topologyEpoch());
        assertEquals(2, r.shardCount());
        assertEquals(7L, r.auditRecordCount());
        assertArrayEquals(hash(0xE3), r.shardAnchorDigest());
        reopened.close();
    }

    @Test
    void encryptingSlotTamperIsRefusedOnReopen(@TempDir Path dir) throws Exception {
        NodeAnchorFile na = NodeAnchorFile.openInDirectory(dir, encrypting());
        na.bootstrap(mint(1L, 1, hash(0)));
        na.close();
        // Flip a ciphertext byte in slot 0: the GCM tag now fails, and (slot 1 being empty) there is no
        // valid record => the caller REFUSEs (fail-closed, exactly as under HMAC).
        corruptByte(dir, AnchorFile.SLOT0_OFFSET + AnchorFile.RECORD_LEN_PREFIX + 60);
        NodeAnchorFile reopened = NodeAnchorFile.openInDirectory(dir, encrypting());
        assertFalse(reopened.hasValidRecord(),
                "a tampered GCM slot must not authenticate (fail-closed)");
        reopened.close();
    }

    /** Flips one byte at the given file offset (a torn/tamper simulation). */
    private static void corruptByte(Path dir, int offset) throws Exception {
        Path file = dir.resolve(NodeAnchorFile.NODE_ANCHOR_FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        bytes[offset] ^= 0x5A;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
