package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.SegmentKeyManager;
import io.configd.common.Storage;
import io.configd.common.WalContainer;
import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team: reorder, splice, duplicate, gap, and interior-rollback attacks performed on the real
 * on-disk {@code raft-log.wal} bytes of already-committed records, then recovered through the real
 * {@link RaftLog} constructor. Every committed frame is authentic and stays byte-for-byte intact
 * (its FileStorage frame CRC and its inner envelope MAC/tag both verify); only the order, membership,
 * or content of the frames on disk is changed, exactly as a filesystem-write adversary would.
 * <p>
 * Two recovery layers do the catching, in order:
 * <ul>
 *   <li><b>position checks</b> (contiguity + term monotonicity) catch index permutations, gaps, and
 *       duplicates - the "reorder/splice-by-index" family; and</li>
 *   <li><b>the per-record hash chain</b> catches an index-preserving, term-monotonic content
 *       substitution (an interior record rolled back to an older authentic version) - the
 *       {@link #interiorStaleContentSpliceRefused_hmac} family, which the position checks alone miss.</li>
 * </ul>
 * A front truncation of the WAL head is caught by neither layer above (the surviving suffix still
 * chains correctly to itself); the head-anchor snapshot-join check closes that gap - see
 * {@link #frontTruncationFabricatesPhantomCompactionDocumentsGap}.
 */
class RaftLogPhysicalReorderRedteamTest {

    private static final int GID = 0;

    // A TRUE physical reorder of already-committed frames, written through the real
    // RaftLog.append() path (so RaftLog itself chains them), then two complete frames swapped on disk.

    @Test
    void physicalReorderViaRealRaftLogAppendPathRefused(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();

        RaftLog writer = new RaftLog(Storage.file(tempDir), env, GID);
        writer.append(new LogEntry(1, 1, "one".getBytes(StandardCharsets.UTF_8)));
        writer.append(new LogEntry(2, 1, "two".getBytes(StandardCharsets.UTF_8)));
        writer.append(new LogEntry(3, 2, "three".getBytes(StandardCharsets.UTF_8)));

        // Read the real .wal; physically SWAP the first two complete frames. Each frame is byte-for-byte
        // unchanged (inner MAC AND outer frame CRC both still verify) - only their on-disk ORDER changed,
        // so the embedded indices now read 2,1,3.
        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        assertEquals(3, frames.size(), "expected exactly three committed frames on disk");
        List<byte[]> swapped = new ArrayList<>(frames);
        swapped.set(0, frames.get(1));
        swapped.set(1, frames.get(0));
        Files.write(walPath(tempDir), reassemble(header(wal), swapped), StandardOpenOption.TRUNCATE_EXISTING);

        // Recovery must REFUSE on contiguity (which runs before the chain) even though every frame verifies.
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("contiguity"),
                "a physical frame swap of committed records must be refused on recovery, got: " + ex.getMessage());
    }


    @Test
    void duplicatedCommittedFrameRefused(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 1, "a");
        w.append(2, 1, "b");
        w.append(3, 1, "c");

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> dup = new ArrayList<>(frames);
        dup.add(2, frames.get(1)); // duplicate index-2's frame => indices 1,2,2,3
        Files.write(walPath(tempDir), reassemble(header(wal), dup), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("contiguity"),
                "a duplicated committed record must be refused, got: " + ex.getMessage());
    }

    @Test
    void deletedMiddleCommittedFrameGapRefused(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 1, "a");
        w.append(2, 1, "b");
        w.append(3, 1, "c");
        w.append(4, 1, "d");

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> gapped = new ArrayList<>(frames);
        gapped.remove(2); // drop index-3's frame => indices 1,2,4
        Files.write(walPath(tempDir), reassemble(header(wal), gapped), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("contiguity"),
                "a middle-frame deletion (index gap) must be refused, got: " + ex.getMessage());
    }

    /**
     * (c) Substitute one committed record's WHOLE frame with another authentic committed frame from a
     * DIFFERENT index => REFUSE (contiguity). The substituted frame authenticates perfectly (its own
     * MAC/scope are intact); only its embedded index betrays it against the position it now occupies.
     */
    @Test
    void wholeFrameSubstitutionFromDifferentIndexRefused(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 1, "a");
        w.append(2, 1, "b");
        w.append(3, 1, "c");

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> subst = new ArrayList<>(frames);
        subst.set(2, frames.get(0)); // put index-1's authentic frame where index-3 sat => indices 1,2,1
        Files.write(walPath(tempDir), reassemble(header(wal), subst), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("contiguity"),
                "substituting a different-index authentic frame must be refused, got: " + ex.getMessage());
    }

    @Test
    void physicalTermRegressionRefused(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 5, "a");
        w.append(2, 3, "b"); // a term drop Raft never writes

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("term regression"),
                "a mid-log term regression must be refused, got: " + ex.getMessage());
    }


    /**
     * A legal committed log is {@code [1/t1, 2/t2, 3/t3]}. An adversary who kept an OLD authentic frame
     * for index 2 (an earlier term-1 conflict-overwritten version) splices it back over the current
     * index-2 frame. The result {@code [1/t1, 2/t1(OLD), 3/t3]} has CONTIGUOUS indices and NON-DECREASING
     * terms, and the stale record authenticates (its own MAC + scopeId are genuine) - so the position
     * checks pass. The HASH CHAIN catches it: the stale frame's {@code prevHash} does not match the hash
     * of index 1's record, so the chain link breaks and recovery REFUSES. (Keyed HMAC posture.)
     */
    @Test
    void interiorStaleContentSpliceRefused_hmac(@TempDir Path tempDir) throws Exception {
        assertInteriorSpliceRefused(tempDir, hmacEnvelope());
    }

    @Test
    void interiorStaleContentSpliceRefused_gcm(@TempDir Path tempDir) throws Exception {
        assertInteriorSpliceRefused(tempDir, gcmEnvelope());
    }

    private void assertInteriorSpliceRefused(Path tempDir, IntegrityEnvelope env) throws Exception {
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 1, "v1");
        w.append(2, 2, "v2-CURRENT");
        w.append(3, 3, "v3");

        // The adversary's captured stale frame: index 2 at the EARLIER term 1 with the OLD command,
        // authentic under the same key + scopeId, but chaining from a DIFFERENT (here genesis) prevHash
        // than index 1's actual record hash - so it does not link into this log.
        byte[] staleEnv = env.wrap(RaftArtifactMagic.WALE_MAGIC, GID,
                ChainedWal.inner(2, 1, ChainedWal.GENESIS, "v2-STALE-ROLLED-BACK".getBytes(StandardCharsets.UTF_8)));
        byte[] staleFrame = frameOf(staleEnv);

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> spliced = new ArrayList<>(frames);
        spliced.set(1, staleFrame);
        Files.write(walPath(tempDir), reassemble(header(wal), spliced), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("chain break"),
                "an interior stale-content splice must be refused by the hash chain, got: " + ex.getMessage());
    }

    /**
     * The adversary tries to REPAIR the chain: re-stamp the stale index-2 frame's {@code prevHash} to
     * index-1's actual record hash so the chain link WOULD verify. Under HMAC the {@code prevHash} is in
     * the MAC input, so editing it (even with a repaired envelope + frame CRC) breaks the record's own
     * MAC - recovery refuses at the envelope layer, before the chain is even checked. "Unforgeable in
     * place": the chain link cannot be re-pointed without the key.
     */
    @Test
    void restampedInteriorPrevHashStillRefused_hmac(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        byte[] hashOfIndex1 = w.append(1, 1, "v1");
        w.append(2, 2, "v2-CURRENT");
        w.append(3, 3, "v3");

        byte[] staleEnv = env.wrap(RaftArtifactMagic.WALE_MAGIC, GID,
                ChainedWal.inner(2, 1, ChainedWal.GENESIS, "v2-STALE-ROLLED-BACK".getBytes(StandardCharsets.UTF_8)));
        restampPlaintextPrevHash(staleEnv, hashOfIndex1);
        byte[] staleFrame = frameOf(staleEnv);

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> spliced = new ArrayList<>(frames);
        spliced.set(1, staleFrame);
        Files.write(walPath(tempDir), reassemble(header(wal), spliced), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("MAC"),
                "re-stamping the chain prevHash must break the record MAC, got: " + ex.getMessage());
    }

    /**
     * The GCM analogue: the {@code prevHash} rides INSIDE the ciphertext, so an attacker cannot re-stamp
     * it in place at all - flipping the ciphertext byte that carries it fails the GCM tag. "Unforgeable
     * in place" under encryption.
     */
    @Test
    void restampedInteriorPrevHashStillRefused_gcm(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = gcmEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 1, "v1");
        w.append(2, 2, "v2-CURRENT");
        w.append(3, 3, "v3");

        // The stale frame is a valid GCM envelope; flip the ciphertext byte that encrypts the prevHash
        // (plaintext offset 16 -> ciphertext offset ENC_PREFIX(44)+16) and repair both CRCs. The tag fails.
        byte[] staleEnv = env.wrap(RaftArtifactMagic.WALE_MAGIC, GID,
                ChainedWal.inner(2, 1, ChainedWal.GENESIS, "v2-STALE-ROLLED-BACK".getBytes(StandardCharsets.UTF_8)));
        staleEnv[44 + 16] ^= 0x01;
        repairEnvelopeCrc(staleEnv);
        byte[] staleFrame = frameOf(staleEnv);

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> spliced = new ArrayList<>(frames);
        spliced.set(1, staleFrame);
        Files.write(walPath(tempDir), reassemble(header(wal), spliced), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("authentication failed"),
                "editing the encrypted prevHash must fail the GCM tag, got: " + ex.getMessage());
    }

    // False-positive boundaries: legitimate shapes MUST still recover cleanly (the checks refuse
    // tampering, not health). A false REFUSE here would be a liveness bug.

    @Test
    void legitimateCompactionRecoversCleanly(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(5, 1, "e");
        w.append(6, 1, "f");
        w.append(7, 1, "g");
        w.setSnapshot(4, 1); // anchor snapshotIndex == firstIndex - 1 (legal join)

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(3, log.size());
        assertEquals(7, log.lastIndex());
        assertEquals(4, log.snapshotIndex());
    }

    @Test
    void equalTermRunRecoversCleanly(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 5, "a");
        w.append(2, 5, "b");
        w.append(3, 5, "c");

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(3, log.size());
        assertEquals(5, log.lastTerm());
    }

    @Test
    void singleRecordWalRecoversCleanly(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        new ChainedWal.Writer(storage, env, GID).append(1, 1, "only");

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(1, log.size());
        assertEquals(1, log.lastIndex());
    }


    @Test
    void frontTruncationFabricatesPhantomCompactionDocumentsGap(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        for (int i = 1; i <= 5; i++) {
            w.append(i, 1, "v" + i);
        }

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> truncated = new ArrayList<>(frames.subList(2, frames.size())); // drop indices 1,2
        Files.write(walPath(tempDir), reassemble(header(wal), truncated), StandardOpenOption.TRUNCATE_EXISTING);

        // The surviving suffix [3,4,5] chains correctly (the hash chain cannot catch a front
        // truncation), but the anchor still names snapshotIndex=0, so the WAL's new first index 3
        // fails the snapshot-join check (firstIndex == anchor.snapshotIndex + 1). Front truncation
        // cannot masquerade as a phantom compaction: recovery REFUSES.
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("front-truncation") || ex.getMessage().contains("phantom"),
                "Gate 3a anchor must refuse the front-truncation phantom compaction, got: " + ex.getMessage());
    }


    private static IntegrityEnvelope hmacEnvelope() {
        return SnapshotIntegrityTest.keyedEnvelope();
    }

    private static IntegrityEnvelope gcmEnvelope() {
        byte[] rootBytes = new byte[32];
        Arrays.fill(rootBytes, (byte) 0x6b);
        RootKey root = new RootKey(rootBytes, new KeyId("local", "test", 1));
        return IntegrityEnvelope.encrypting(new SegmentKeyManager(root));
    }

    // Helpers: real FileStorage frame arithmetic ([len:4][data:len][crc32c:4] after an 8-byte
    // WalContainer header), used to attack the on-disk bytes exactly as a filesystem adversary would.

    private static Path walPath(Path tempDir) {
        return tempDir.resolve("raft-log.wal");
    }

    private static byte[] header(byte[] wal) {
        return Arrays.copyOfRange(wal, 0, WalContainer.HEADER_SIZE);
    }

    private static List<byte[]> readFrames(byte[] wal) {
        List<byte[]> out = new ArrayList<>();
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

    private static byte[] frameOf(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        ByteBuffer b = ByteBuffer.allocate(4 + data.length + 4);
        b.putInt(data.length);
        b.put(data);
        b.putInt((int) crc.getValue());
        return b.array();
    }

    /**
     * Re-stamps the plaintext {@code prevHash} inside an HMAC envelope (offset
     * header(8)+scopeId(4)+index(8)+term(8) = 28) and repairs the envelope's own CRC32C. Only valid for
     * the keyed posture, where the payload is plaintext.
     */
    private static void restampPlaintextPrevHash(byte[] env, byte[] newPrevHash) {
        System.arraycopy(newPrevHash, 0, env, IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE + 8 + 8,
                newPrevHash.length);
        repairEnvelopeCrc(env);
    }

    private static void repairEnvelopeCrc(byte[] env) {
        CRC32C crc = new CRC32C();
        crc.update(env, 0, env.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(env).putInt(env.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }
}
