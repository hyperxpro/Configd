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

import javax.crypto.spec.SecretKeySpec;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOCUSED re-verification of the per-record WAL hash chain that closes the interior stale-content
 * rollback the earlier Gate 2a pass surfaced. The chain binds each authenticated record to
 * {@code prevHash = SHA-256(predecessor payload)}, stored INSIDE the envelope's authenticated body
 * ({@code [index][term][prevHash][command]}), so a splice that keeps a valid incoming link is still
 * caught by the SUCCESSOR's authenticated prevHash, and re-linking the successor breaks its MAC/tag.
 * <p>
 * All attacks run on REAL on-disk bytes through the real {@link RaftLog} recovery path, in BOTH the
 * keyed (HMAC) and encrypting (GCM) postures. {@link ChainedWal} is the chaining test kit.
 */
class RaftLogHashChainRedteamTest {

    private static final int GID = 0;

    // ---------------------------------------------------------------------------------------------
    // 1. The interior stale-content splice (my prior finding) now REFUSES - the SUCCESSOR's
    //    authenticated prevHash still binds the ORIGINAL interior record, so recovery detects a break.
    // ---------------------------------------------------------------------------------------------

    @Test
    void interiorStaleSpliceRefusedByChain_hmac(@TempDir Path tempDir) throws Exception {
        assertInteriorSpliceRefused(tempDir, hmacEnvelope());
    }

    @Test
    void interiorStaleSpliceRefusedByChain_gcm(@TempDir Path tempDir) throws Exception {
        assertInteriorSpliceRefused(tempDir, gcmEnvelope());
    }

    private void assertInteriorSpliceRefused(Path tempDir, IntegrityEnvelope env) throws Exception {
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        byte[] h1 = w.append(1, 1, "v1");     // genesis record; h1 = H(r1 payload)
        w.append(2, 2, "v2-CURRENT");         // r2 (the record being rolled back)
        w.append(3, 3, "v3");                 // r3 binds prevHash = H(r2-CURRENT payload)

        // The adversary's stale index-2 frame: term 1, old command, but a VALID incoming link
        // (prevHash = h1, so it chains cleanly from the unchanged r1). Only the OUTGOING link - r3's
        // authenticated prevHash, which committed to r2-CURRENT - can catch it.
        byte[] staleInner = ChainedWal.inner(2, 1, h1, "v2-STALE-ROLLED-BACK".getBytes(StandardCharsets.UTF_8));
        byte[] staleFrame = frameOf(env.wrap(RaftArtifactMagic.WALE_MAGIC, GID, staleInner));

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        frames.set(1, staleFrame);
        Files.write(walPath(tempDir), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("chain break"),
                "the interior stale-content splice must now be refused by the chain, got: " + ex.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // 2. THE bypass hunt: try to make the spliced chain VALID by re-stamping the successor's prevHash
    //    to point at the spliced record. Because prevHash lives inside the authenticated body, this
    //    breaks the successor's MAC (HMAC) / GCM tag - you cannot forge a valid-chain-valid-auth
    //    rollback without the key.
    // ---------------------------------------------------------------------------------------------

    @Test
    void restampingSuccessorPrevHashBreaksAuth_hmac(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        byte[] h1 = w.append(1, 1, "v1");
        w.append(2, 2, "v2-CURRENT");
        w.append(3, 3, "v3");

        byte[] staleInner = ChainedWal.inner(2, 1, h1, "v2-STALE".getBytes(StandardCharsets.UTF_8));
        byte[] staleFrame = frameOf(env.wrap(RaftArtifactMagic.WALE_MAGIC, GID, staleInner));
        byte[] staleHash = ChainedWal.sha256(staleInner); // what r3's prevHash would need to become

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        frames.set(1, staleFrame);
        // Re-stamp r3's prevHash (HMAC posture: cleartext payload) to re-link the chain, then repair
        // BOTH CRCs so only the MAC is left to object. The MAC was computed over the ORIGINAL prevHash.
        frames.set(2, setHmacPayloadPrevHash(frames.get(2), staleHash));
        Files.write(walPath(tempDir), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("MAC"),
                "re-stamping the successor's prevHash must break its MAC, got: " + ex.getMessage());
    }

    @Test
    void restampingSuccessorPrevHashBreaksAuth_gcm(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = gcmEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        byte[] h1 = w.append(1, 1, "v1");
        w.append(2, 2, "v2-CURRENT");
        w.append(3, 3, "v3");

        byte[] staleInner = ChainedWal.inner(2, 1, h1, "v2-STALE".getBytes(StandardCharsets.UTF_8));
        byte[] staleFrame = frameOf(env.wrap(RaftArtifactMagic.WALE_MAGIC, GID, staleInner));

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        frames.set(1, staleFrame);
        // GCM posture: prevHash is ENCRYPTED, so the attacker cannot even choose its plaintext value -
        // flipping the ciphertext byte over the prevHash region (to try to re-link) fails the GCM tag.
        frames.set(2, flipGcmCiphertextPrevHashByte(frames.get(2)));
        Files.write(walPath(tempDir), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("authentication failed"),
                "altering the encrypted prevHash must fail the GCM tag, got: " + ex.getMessage());
    }

    /** Swapping two adjacent records (keeping their prevHashes) permutes indices => contiguity REFUSE. */
    @Test
    void adjacentRecordSwapRefusedByContiguity_hmac(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 1, "v1");
        w.append(2, 1, "v2");
        w.append(3, 1, "v3");

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        List<byte[]> swapped = new ArrayList<>(frames);
        swapped.set(1, frames.get(2)); // indices become 1,3,2
        swapped.set(2, frames.get(1));
        Files.write(walPath(tempDir), reassemble(header(wal), swapped), StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("contiguity"),
                "an adjacent-record swap must be refused (position check), got: " + ex.getMessage());
    }

    /**
     * DOCUMENTED RESIDUAL (not a chain hole): rolling the TAIL back to a genuinely-prior VALID chain
     * continuation that chains from the SAME unchanged prefix produces a self-consistent valid chain,
     * so recovery ACCEPTS it. The hash chain closes an interior splice whose successor is unchanged;
     * it cannot detect a rollback to a wholly prior valid chain state - that is the head-anchor's
     * monotonic-floor / AnchorWitness job (design residual (a), deferred to Gate 3). In Raft the
     * rolled-back suffix was necessarily UNCOMMITTED when it was overwritten (a committed entry is
     * never overwritten), so the anchor's durable floor is what bounds it, not the chain.
     */
    @Test
    void tailRollbackToPriorValidChainAccepted_documentsAnchorResidual(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        byte[] h1 = w.append(1, 1, "v1");   // stable prefix
        w.append(2, 3, "B2");               // current suffix at term 3
        w.append(3, 3, "B3");

        // A captured older suffix [A2@t2, A3@t2] that chains from the SAME r1 (a self-consistent
        // prior valid chain). Every frame is authentic; the chain links are all valid.
        byte[] a2Inner = ChainedWal.inner(2, 2, h1, "A2".getBytes(StandardCharsets.UTF_8));
        byte[] a2Frame = frameOf(env.wrap(RaftArtifactMagic.WALE_MAGIC, GID, a2Inner));
        byte[] h2a = ChainedWal.sha256(a2Inner);
        byte[] a3Inner = ChainedWal.inner(3, 2, h2a, "A3".getBytes(StandardCharsets.UTF_8));
        byte[] a3Frame = frameOf(env.wrap(RaftArtifactMagic.WALE_MAGIC, GID, a3Inner));

        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        frames.set(1, a2Frame);
        frames.set(2, a3Frame);
        Files.write(walPath(tempDir), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals("A2", new String(log.entryAt(2).command(), StandardCharsets.UTF_8),
                "RESIDUAL: a rollback to a prior VALID chain is accepted by the chain - the head anchor "
                        + "(Gate 3 monotonic floor) is what must bound it, not the per-record chain");
        assertEquals(2, log.entryAt(2).term(), "the suffix rolled back to the older term");
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Genesis fabrication: an index-1 first record whose prevHash is not GENESIS => REFUSE.
    // ---------------------------------------------------------------------------------------------

    @Test
    void genesisFabricationRefused_hmac(@TempDir Path tempDir) {
        assertGenesisFabricationRefused(tempDir, hmacEnvelope());
    }

    @Test
    void genesisFabricationRefused_gcm(@TempDir Path tempDir) {
        assertGenesisFabricationRefused(tempDir, gcmEnvelope());
    }

    private void assertGenesisFabricationRefused(Path tempDir, IntegrityEnvelope env) {
        Storage storage = Storage.file(tempDir);
        byte[] nonGenesis = new byte[32];
        Arrays.fill(nonGenesis, (byte) 0x11);
        // An authentic index-1 record whose prevHash is NOT the all-zero GENESIS (a fabricated head).
        byte[] inner = ChainedWal.inner(1, 1, nonGenesis, "cmd".getBytes(StandardCharsets.UTF_8));
        storage.appendToLog("raft-log", env.wrap(RaftArtifactMagic.WALE_MAGIC, GID, inner));

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(Storage.file(tempDir), env, GID));
        assertTrue(ex.getMessage().contains("genesis"),
                "a non-GENESIS prevHash on the index-1 record must be refused, got: " + ex.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // 4. No false-positives - legitimate shapes must RECOVER cleanly (a false chain break is a
    //    liveness bug, treated as first-class here).
    // ---------------------------------------------------------------------------------------------

    /** A legitimate compaction (firstIndex>1; the first survivor's prevHash refers to a compacted record). */
    @Test
    void legitimateCompactionRecoversCleanly_hmac(@TempDir Path tempDir) {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        RaftLog w = new RaftLog(storage, env, GID);
        for (int i = 1; i <= 5; i++) {
            w.append(new LogEntry(i, 1, ("v" + i).getBytes(StandardCharsets.UTF_8)));
        }
        w.compact(2, 1); // discard [1,2]; survivors [3,4,5], r3's prevHash now refers to compacted r2

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(3, log.size());
        assertEquals(3, log.entryAt(3).index());
        assertEquals("v5", new String(log.entryAt(5).command(), StandardCharsets.UTF_8));
    }

    /** A conflict-truncation then re-append (a new term overwrites the tail) must recover cleanly. */
    @Test
    void conflictTruncateThenAppendRecoversCleanly_hmac(@TempDir Path tempDir) {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        RaftLog w = new RaftLog(storage, env, GID);
        w.append(new LogEntry(1, 1, "v1".getBytes(StandardCharsets.UTF_8)));
        w.append(new LogEntry(2, 1, "v2".getBytes(StandardCharsets.UTF_8)));
        w.append(new LogEntry(3, 1, "v3".getBytes(StandardCharsets.UTF_8)));
        w.truncateFrom(2); // drop [2,3]
        w.append(new LogEntry(2, 2, "v2b".getBytes(StandardCharsets.UTF_8)));
        w.append(new LogEntry(3, 2, "v3b".getBytes(StandardCharsets.UTF_8)));

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(3, log.size());
        assertEquals("v2b", new String(log.entryAt(2).command(), StandardCharsets.UTF_8));
        assertEquals(2, log.lastTerm());
    }

    /** A single authenticated record must recover. */
    @Test
    void singleRecordRecoversCleanly_hmac(@TempDir Path tempDir) {
        IntegrityEnvelope env = hmacEnvelope();
        Storage storage = Storage.file(tempDir);
        new ChainedWal.Writer(storage, env, GID).append(1, 1, "only");

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(1, log.size());
        assertEquals("only", new String(log.entryAt(1).command(), StandardCharsets.UTF_8));
    }

    /** A clean multi-record authenticated WAL must recover, in both postures. */
    @Test
    void cleanMultiRecordRecoversCleanly_hmac(@TempDir Path tempDir) {
        assertCleanRecovers(tempDir, hmacEnvelope());
    }

    @Test
    void cleanMultiRecordRecoversCleanly_gcm(@TempDir Path tempDir) {
        assertCleanRecovers(tempDir, gcmEnvelope());
    }

    private void assertCleanRecovers(Path tempDir, IntegrityEnvelope env) {
        Storage storage = Storage.file(tempDir);
        ChainedWal.Writer w = new ChainedWal.Writer(storage, env, GID);
        w.append(1, 1, "a");
        w.append(2, 1, "b");
        w.append(3, 2, "c");

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(3, log.size());
        assertEquals("c", new String(log.entryAt(3).command(), StandardCharsets.UTF_8));
        assertEquals(2, log.lastTerm());
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Keyless divergence: a keyless WAL is byte-identical (no prevHash) and recovers with NO chain
    //    verification (keyless carries no adversarial guarantee by design).
    // ---------------------------------------------------------------------------------------------

    @Test
    void keylessWalHasNoPrevHashAndRecoversWithoutChainCheck(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        Storage storage = Storage.file(tempDir);
        RaftLog w = new RaftLog(storage, env, GID);
        w.append(new LogEntry(1, 1, "v1".getBytes(StandardCharsets.UTF_8)));
        w.append(new LogEntry(2, 1, "v2".getBytes(StandardCharsets.UTF_8)));

        // The on-disk keyless payload is [index:8][term:8][command] - 16 bytes + command, NO 32-byte
        // prevHash. Envelope = header(8) + scopeId(4) + payload + CRC(4); "v1" => 8+4+18+4 = 34-byte data.
        byte[] wal = Files.readAllBytes(walPath(tempDir));
        int firstDataLen = ByteBuffer.wrap(wal, WalContainer.HEADER_SIZE, 4).getInt();
        assertEquals(8 + 4 + (8 + 8 + 2) + 4, firstDataLen,
                "keyless record must carry NO 32-byte prevHash (byte-identical to the pre-chain format)");

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals(2, log.size());
        assertEquals("v2", new String(log.entryAt(2).command(), StandardCharsets.UTF_8));
    }

    /**
     * Corollary of keyless divergence: an interior content splice in a KEYLESS WAL is NOT caught (no
     * chain, no MAC) - the documented "keyless carries no adversarial guarantee" boundary. This is not
     * a finding; it confirms the chain is an authenticated-posture control only.
     */
    @Test
    void keylessInteriorSpliceIsNotCaught_documentsKeylessBoundary(@TempDir Path tempDir) throws Exception {
        IntegrityEnvelope env = IntegrityEnvelope.keyless();
        Storage storage = Storage.file(tempDir);
        RaftLog w = new RaftLog(storage, env, GID);
        w.append(new LogEntry(1, 1, "v1".getBytes(StandardCharsets.UTF_8)));
        w.append(new LogEntry(2, 1, "v2".getBytes(StandardCharsets.UTF_8)));
        w.append(new LogEntry(3, 1, "v3".getBytes(StandardCharsets.UTF_8)));

        // Keyless payload is [index][term][command] with no authenticator beyond CRC; forge index-2's
        // content and repair the frame CRC - recovery cannot object.
        byte[] wal = Files.readAllBytes(walPath(tempDir));
        List<byte[]> frames = readFrames(wal);
        byte[] forgedInner = ByteBuffer.allocate(8 + 8 + 8)
                .putLong(2).putLong(1).put("FORGED!!".getBytes(StandardCharsets.UTF_8)).array();
        frames.set(1, frameOf(env.wrap(RaftArtifactMagic.WALE_MAGIC, GID, forgedInner)));
        Files.write(walPath(tempDir), reassemble(header(wal), frames), StandardOpenOption.TRUNCATE_EXISTING);

        RaftLog log = new RaftLog(Storage.file(tempDir), env, GID);
        assertEquals("FORGED!!", new String(log.entryAt(2).command(), StandardCharsets.UTF_8),
                "keyless has no chain, so the forge is accepted - the documented keyless boundary");
        assertFalse(env.isKeyed() || env.isEncrypting(), "sanity: this is the keyless posture");
    }

    // ---------------------------------------------------------------------------------------------
    // Posture builders + on-disk byte helpers (identical frame arithmetic to FileStorage).
    // ---------------------------------------------------------------------------------------------

    private static IntegrityEnvelope hmacEnvelope() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x5a);
        return new IntegrityEnvelope(new SecretKeySpec(k, "HmacSHA256"));
    }

    private static IntegrityEnvelope gcmEnvelope() {
        byte[] rootBytes = new byte[32];
        Arrays.fill(rootBytes, (byte) 0x6b);
        RootKey root = new RootKey(rootBytes, new KeyId("local", "test", 1));
        return IntegrityEnvelope.encrypting(new SegmentKeyManager(root), null);
    }

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

    private static byte[] envelopeOf(byte[] frame) {
        int len = ByteBuffer.wrap(frame, 0, 4).getInt();
        return Arrays.copyOfRange(frame, 4, 4 + len);
    }

    private static void repairEnvelopeCrc(byte[] env) {
        CRC32C crc = new CRC32C();
        crc.update(env, 0, env.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(env).putInt(env.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }

    /**
     * HMAC posture: overwrite the prevHash (payload offset 16, i.e. envelope offset header(8)+scopeId(4)+16)
     * with a chosen 32-byte value, then repair the envelope CRC and re-frame. The MAC is left as-is
     * (attacker has no key), so recovery must reject on the MAC.
     */
    private static byte[] setHmacPayloadPrevHash(byte[] frame, byte[] newPrevHash) {
        byte[] env = envelopeOf(frame);
        int prevHashOff = IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE + 16;
        System.arraycopy(newPrevHash, 0, env, prevHashOff, 32);
        repairEnvelopeCrc(env);
        return frameOf(env);
    }

    /**
     * GCM posture: the prevHash is encrypted, so flip a ciphertext byte over the prevHash region
     * (ENC_PREFIX 44 + payload offset 16 = envelope offset 60), then repair the envelope CRC and
     * re-frame. The GCM tag must reject.
     */
    private static byte[] flipGcmCiphertextPrevHashByte(byte[] frame) {
        byte[] env = envelopeOf(frame);
        int prevHashCipherOff = 44 + 16; // ENC_PREFIX(44) + [index:8][term:8] => start of encrypted prevHash
        env[prevHashCipherOff] ^= 0x01;
        repairEnvelopeCrc(env);
        return frameOf(env);
    }
}
