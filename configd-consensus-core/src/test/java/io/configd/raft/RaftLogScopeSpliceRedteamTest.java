package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.NodeId;
import io.configd.common.SegmentKeyManager;
import io.configd.common.Storage;
import io.configd.common.WalContainer;
import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftLogScopeSpliceRedteamTest {

    private static final int VICTIM_GID = 0;
    private static final int FOREIGN_GID = 1;

    /** The FileStorage log NAME (appendToLog / readLog append the {@code .wal} suffix themselves). */
    private static final String WAL_LOG = "raft-log";
    /** The on-disk file names (for direct byte surgery). */
    private static final String WAL_FILE = "raft-log.wal";
    private static final String SNAP_FILE = "raft-log.snapshot.dat";

    @Test
    void crossShardWalRecordRefused_hmac(@TempDir Path tempDir) {
        assertCrossShardWalRefused(tempDir, hmacEnvelope());
    }

    @Test
    void crossShardWalRecordRefused_gcm(@TempDir Path tempDir) {
        assertCrossShardWalRefused(tempDir, gcmEnvelope());
    }

    private void assertCrossShardWalRefused(Path tempDir, IntegrityEnvelope env) {
        Storage storage = Storage.file(tempDir);
        // An authentic record authored under the FOREIGN shard's scope, written into THIS shard's WAL.
        storage.appendToLog(WAL_LOG, env.wrap(RaftArtifactMagic.WALE_MAGIC, FOREIGN_GID, entry(1, 1, "foreign")));

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, VICTIM_GID));
        assertTrue(ex.getMessage().contains("scope mismatch"),
                "a cross-shard WAL record must be refused on replay, got: " + ex.getMessage());
    }

    @Test
    void crossShardSnapshotBlobRefused_hmac(@TempDir Path tempDir) throws Exception {
        assertCrossShardSnapshotRefused(tempDir, hmacEnvelope());
    }

    @Test
    void crossShardSnapshotBlobRefused_gcm(@TempDir Path tempDir) throws Exception {
        assertCrossShardSnapshotRefused(tempDir, gcmEnvelope());
    }

    private void assertCrossShardSnapshotRefused(Path tempDir, IntegrityEnvelope env) throws Exception {
        // Produce a foreign (gid=FOREIGN) snapshot blob in its OWN dir, so its anchor does not shadow
        // the victim's (a foreign anchor in the shared dir would itself refuse first).
        Path foreignDir = tempDir.resolve("foreign");
        Storage foreignStorage = Storage.file(foreignDir);
        new RaftLog(foreignStorage, env, FOREIGN_GID)
                .persistSnapshot(new SnapshotState("foreign-state".getBytes(StandardCharsets.UTF_8), 5, 2, null));
        byte[] foreignBlob = Files.readAllBytes(foreignDir.resolve(SNAP_FILE));

        // The victim shard has its OWN valid (gid=VICTIM) anchor; plant the foreign blob over it so the
        // reload's snapshot scope assert is the thing that fires.
        Path victimDir = tempDir.resolve("victim");
        Storage victimStorage = Storage.file(victimDir);
        new RaftLog(victimStorage, env, VICTIM_GID).closeAnchor();
        Files.write(victimDir.resolve(SNAP_FILE), foreignBlob);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(victimDir), env, VICTIM_GID));
        assertTrue(ex.getMessage().contains("scope mismatch"),
                "a cross-shard snapshot blob must be refused on reload, got: " + ex.getMessage());
    }

    @Test
    void crossShardRaftStateRefused_hmac(@TempDir Path tempDir) {
        assertCrossShardStateRefused(tempDir, hmacEnvelope());
    }

    @Test
    void crossShardRaftStateRefused_gcm(@TempDir Path tempDir) {
        assertCrossShardStateRefused(tempDir, gcmEnvelope());
    }

    private void assertCrossShardStateRefused(Path tempDir, IntegrityEnvelope env) {
        Storage storage = Storage.file(tempDir);
        // A foreign shard persists its term/vote into the shared anchor under scopeId=FOREIGN_GID;
        // currentTerm/votedFor live in the per-shard anchor.
        RaftLog foreign = new RaftLog(storage, env, FOREIGN_GID);
        foreign.persistTermVote(3, 7);
        foreign.closeAnchor();

        // The victim shard reading the SAME storage refuses: both anchor slots carry
        // scopeId=FOREIGN_GID, so neither authenticates for VICTIM_GID - a present-but-both-invalid
        // anchor is a REFUSE (the anchor scope assert catches the cross-shard raft-state splice).
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, VICTIM_GID));
        assertTrue(ex.getMessage().contains("both slots invalid") || ex.getMessage().contains("scope"),
                "a cross-shard raft-state (anchor) artifact must be refused on load, got: " + ex.getMessage());
    }

    // Attack 4 - in-place scopeId forge ("unforgeable in place"). Re-stamp the foreign record's scopeId
    // to the victim's gid so the reader assert passes, and repair every CRC so the byte layer accepts
    // the frame - the record must STILL be refused, now by the MAC (HMAC) / GCM tag (encrypting),
    // because scopeId is authenticated. Proven on the WAL record and the snapshot blob, both postures.

    @Test
    void inPlaceScopeForgeOnWalRecordStillRefused_hmac(@TempDir Path tempDir) throws Exception {
        assertWalScopeForgeRefused(tempDir, hmacEnvelope(), "MAC");
    }

    @Test
    void inPlaceScopeForgeOnWalRecordStillRefused_gcm(@TempDir Path tempDir) throws Exception {
        assertWalScopeForgeRefused(tempDir, gcmEnvelope(), "authentication failed");
    }

    private void assertWalScopeForgeRefused(Path tempDir, IntegrityEnvelope env, String expectedInMessage)
            throws Exception {
        Storage storage = Storage.file(tempDir);
        storage.appendToLog(WAL_LOG, env.wrap(RaftArtifactMagic.WALE_MAGIC, FOREIGN_GID, entry(1, 1, "foreign")));

        // Re-stamp scopeId 1 -> 0 inside the single committed frame; repair the envelope's own CRC32C
        // AND the outer FileStorage frame CRC, so BOTH the frame layer and the envelope's CRC accept it.
        // The only remaining defense is the MAC/tag, which was computed over scopeId=1.
        byte[] wal = Files.readAllBytes(tempDir.resolve(WAL_FILE));
        List<byte[]> frames = readFrames(wal);
        frames.set(0, forgeScopeInFrame(frames.get(0), VICTIM_GID, /*repairEnvelopeCrc=*/true));
        Files.write(tempDir.resolve(WAL_FILE), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, VICTIM_GID));
        assertTrue(ex.getMessage().contains(expectedInMessage),
                "re-stamping scopeId must break authentication (expected '" + expectedInMessage
                        + "'), got: " + ex.getMessage());
    }

    @Test
    void inPlaceScopeForgeOnSnapshotBlobStillRefused_hmac(@TempDir Path tempDir) throws Exception {
        assertSnapshotScopeForgeRefused(tempDir, hmacEnvelope(), "MAC");
    }

    @Test
    void inPlaceScopeForgeOnSnapshotBlobStillRefused_gcm(@TempDir Path tempDir) throws Exception {
        assertSnapshotScopeForgeRefused(tempDir, gcmEnvelope(), "authentication failed");
    }

    private void assertSnapshotScopeForgeRefused(Path tempDir, IntegrityEnvelope env, String expectedInMessage)
            throws Exception {
        // Foreign blob produced in its own dir (its foreign anchor must not shadow the victim's).
        Path foreignDir = tempDir.resolve("foreign");
        new RaftLog(Storage.file(foreignDir), env, FOREIGN_GID)
                .persistSnapshot(new SnapshotState("foreign-state".getBytes(StandardCharsets.UTF_8), 5, 2, null));

        // The snapshot blob is a raw envelope in a .dat file (no outer FileStorage frame), so we re-stamp
        // scopeId in the envelope and repair the envelope's own CRC32C - the MAC/tag is all that is left.
        byte[] blob = Files.readAllBytes(foreignDir.resolve(SNAP_FILE));
        forgeScopeInEnvelope(blob, VICTIM_GID);

        // Plant the forged blob over a victim shard that has its OWN valid (gid=VICTIM) anchor, so the
        // forged scopeId PASSES the reader assert and only the MAC/GCM tag is left to catch it.
        Path victimDir = tempDir.resolve("victim");
        new RaftLog(Storage.file(victimDir), env, VICTIM_GID).closeAnchor();
        Files.write(victimDir.resolve(SNAP_FILE), blob);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(victimDir), env, VICTIM_GID));
        assertTrue(ex.getMessage().contains(expectedInMessage),
                "re-stamping the snapshot scopeId must break authentication (expected '" + expectedInMessage
                        + "'), got: " + ex.getMessage());
    }


    /** A v2-layout envelope (formatVersion rolled 3 -> 2), CRC repaired, fed to a v3 reader => REFUSE. */
    @Test
    void formatVersionDowngradeRefused_hmac(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        storage.appendToLog(WAL_LOG, env.wrap(RaftArtifactMagic.WALE_MAGIC, VICTIM_GID, entry(1, 1, "a")));

        byte[] wal = Files.readAllBytes(tempDir.resolve(WAL_FILE));
        List<byte[]> frames = readFrames(wal);
        frames.set(0, rollVersionInFrame(frames.get(0), (short) 2));
        Files.write(tempDir.resolve(WAL_FILE), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, VICTIM_GID));
        assertTrue(ex.getMessage().contains("formatVersion"),
                "a rolled-back envelope formatVersion must be refused, got: " + ex.getMessage());
    }

    /**
     * A scopeId bit-flip with a STALE envelope CRC (only the outer frame CRC repaired) surfaces as a
     * CRC/corruption error, NOT a scope-mismatch - proving the CRC runs BEFORE the scope assert
     * (FrameCodec discipline: a header bit-flip reads as corruption, not a misleading scope error).
     */
    @Test
    void scopeBitFlipWithStaleCrcReportsCorruptionBeforeScope_hmac(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        storage.appendToLog(WAL_LOG, env.wrap(RaftArtifactMagic.WALE_MAGIC, VICTIM_GID, entry(1, 1, "a")));

        byte[] wal = Files.readAllBytes(tempDir.resolve(WAL_FILE));
        List<byte[]> frames = readFrames(wal);
        frames.set(0, forgeScopeInFrame(frames.get(0), FOREIGN_GID, /*repairEnvelopeCrc=*/false));
        Files.write(tempDir.resolve(WAL_FILE), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, VICTIM_GID));
        assertTrue(ex.getMessage().contains("CRC32C"),
                "a scopeId flip with a stale envelope CRC must report corruption before scope, got: "
                        + ex.getMessage());
    }


    private static IntegrityEnvelope hmacEnvelope() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x5a);
        return new IntegrityEnvelope(new SecretKeySpec(k, "HmacSHA256"));
    }

    private static IntegrityEnvelope gcmEnvelope() {
        byte[] rootBytes = new byte[32];
        Arrays.fill(rootBytes, (byte) 0x6b);
        RootKey root = new RootKey(rootBytes, new KeyId("local", "test", 1));
        return IntegrityEnvelope.encrypting(new SegmentKeyManager(root));
    }


    private static byte[] entry(long index, long term, String command) {
        byte[] c = command.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + 8 + c.length);
        b.putLong(index);
        b.putLong(term);
        b.put(c);
        return b.array();
    }

    private static byte[] header(byte[] wal) {
        return Arrays.copyOfRange(wal, 0, WalContainer.HEADER_SIZE);
    }

    private static List<byte[]> readFrames(byte[] wal) {
        List<byte[]> out = new java.util.ArrayList<>();
        int pos = WalContainer.HEADER_SIZE;
        while (pos + 4 <= wal.length) {
            int len = ByteBuffer.wrap(wal, pos, 4).getInt();
            int frameLen = 4 + len + 4;
            if (len < 0 || pos + frameLen > wal.length) {
                break;
            }
            out.add(Arrays.copyOfRange(wal, pos, pos + frameLen));
            pos += frameLen;
        }
        return out;
    }

    private static byte[] reassemble(byte[] hdr, List<byte[]> frames) {
        int total = hdr.length + frames.stream().mapToInt(f -> f.length).sum();
        ByteBuffer b = ByteBuffer.allocate(total);
        b.put(hdr);
        for (byte[] f : frames) {
            b.put(f);
        }
        return b.array();
    }

    /** Frames raw envelope bytes as FileStorage does: {@code [len:4][data][crc32c:4]}. */
    private static byte[] frameOf(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        ByteBuffer b = ByteBuffer.allocate(4 + data.length + 4);
        b.putInt(data.length);
        b.put(data);
        b.putInt((int) crc.getValue());
        return b.array();
    }

    private static byte[] envelopeOf(byte[] frame) {
        int len = ByteBuffer.wrap(frame, 0, 4).getInt();
        return Arrays.copyOfRange(frame, 4, 4 + len);
    }

    private static byte[] forgeScopeInFrame(byte[] frame, int newScope, boolean repairEnvelopeCrc) {
        byte[] env = envelopeOf(frame);
        ByteBuffer.wrap(env).putInt(IntegrityEnvelope.HEADER_SIZE, newScope);
        if (repairEnvelopeCrc) {
            repairEnvelopeCrc(env);
        }
        return frameOf(env);
    }

    private static void forgeScopeInEnvelope(byte[] env, int newScope) {
        ByteBuffer.wrap(env).putInt(IntegrityEnvelope.HEADER_SIZE, newScope);
        repairEnvelopeCrc(env);
    }

    private static byte[] rollVersionInFrame(byte[] frame, short newVersion) {
        byte[] env = envelopeOf(frame);
        ByteBuffer.wrap(env).putShort(4, newVersion); // formatVersion follows the 4-byte magic
        repairEnvelopeCrc(env);
        return frameOf(env);
    }

    private static void repairEnvelopeCrc(byte[] env) {
        CRC32C crc = new CRC32C();
        crc.update(env, 0, env.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(env).putInt(env.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }
}
