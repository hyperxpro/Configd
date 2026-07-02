package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.NodeId;
import io.configd.common.SegmentKeyManager;
import io.configd.common.Storage;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises AES-256-GCM encryption at rest through the REAL RaftLog seam (WAL records +
 * snapshot blob on {@link Storage#file}). Proves the two properties the security lead + redteam
 * will check: (1) persisted bytes carry NO plaintext config value; (2) a tampered on-disk byte
 * fails closed on recovery. Plus the restart round-trip: encrypt -> persist -> reopen (re-derive
 * the same root) -> recover -> state identical.
 */
class RaftLogEncryptionTest {

    private static final NodeId NODE = NodeId.of(1);
    private static final int ELECTION_TICKS = 400;
    private static final String SECRET = "TOPSECRET-db-password-9f3a2b";
    private static final RaftNode.InvariantChecker THROWING = (name, condition, message) -> {
        if (!condition) {
            throw new AssertionError("Invariant violated [" + name + "]: " + message);
        }
    };
    private static final RaftTransport NO_PEERS = (target, message) -> { };

    /** A fresh encrypting envelope over a FIXED root - each call re-derives the same DEKs (restart-safe). */
    private static IntegrityEnvelope encryptingEnvelope() {
        return encryptingEnvelopeWithLegacy(null);
    }

    /** An encrypting envelope over the FIXED root that ALSO reads legacy algId=1 records (migration path). */
    private static IntegrityEnvelope encryptingEnvelopeWithLegacy(SecretKey legacyHmac) {
        byte[] rootBytes = new byte[32];
        Arrays.fill(rootBytes, (byte) 0x6B);
        RootKey root = new RootKey(rootBytes, new KeyId("local", "test", 1));
        return IntegrityEnvelope.encrypting(new SegmentKeyManager(root), legacyHmac);
    }

    /** A fixed keyed HMAC (algId=1) envelope + its key, for the pre-encryption phase of the mixed WAL. */
    private static SecretKey fixedHmacKey() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x5A);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static RaftNode bootLeader(Storage storage, IntegrityEnvelope env,
                                       RaftLog log, KvStateMachine sm) {
        RaftConfig config = RaftConfig.of(NODE, Set.of());
        RaftNode node = new RaftNode(config, log, NO_PEERS, sm,
                RandomGenerator.of("L64X128MixRandom"), storage, THROWING, env);
        for (int i = 0; i < ELECTION_TICKS && node.role() != RaftRole.LEADER; i++) {
            node.tick();
        }
        assertEquals(RaftRole.LEADER, node.role(), "single node must become leader");
        return node;
    }

    @Test
    void walOnDiskContainsNoPlaintext(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = encryptingEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);

        node.propose(KvStateMachine.put("db/password", SECRET));
        node.propose(KvStateMachine.put("api/token", SECRET + "-2"));
        assertEquals(SECRET, sm.snapshotState().get("db/password"));

        byte[] wal = Files.readAllBytes(tempDir.resolve("raft-log.wal"));
        String latin1 = new String(wal, StandardCharsets.ISO_8859_1);
        assertFalse(latin1.contains(SECRET), "the WAL leaked the plaintext secret value");
        assertFalse(latin1.contains("db/password"), "the WAL leaked the plaintext key name");
    }

    @Test
    void snapshotOnDiskContainsNoPlaintext(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = encryptingEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);

        node.propose(KvStateMachine.put("secret", SECRET));
        node.triggerSnapshot();

        byte[] blob = Files.readAllBytes(tempDir.resolve("raft-log.snapshot.dat"));
        String latin1 = new String(blob, StandardCharsets.ISO_8859_1);
        assertFalse(latin1.contains(SECRET), "the snapshot blob leaked the plaintext secret value");
    }

    @Test
    void restartRoundTripRecoversIdenticalState(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        RaftLog log = new RaftLog(storage, encryptingEnvelope());
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, encryptingEnvelope(), log, sm);
        node.propose(KvStateMachine.put("a", "1"));
        node.propose(KvStateMachine.put("b", SECRET));
        Map<String, String> before = sm.snapshotState();

        // RESTART: a fresh RaftLog + envelope re-derives the same root from the same key, so it
        // decrypts the WAL written by the prior instance and replays it into a fresh state machine.
        RaftLog log2 = new RaftLog(storage, encryptingEnvelope());
        KvStateMachine sm2 = new KvStateMachine();
        bootLeader(storage, encryptingEnvelope(), log2, sm2);
        Map<String, String> after = sm2.snapshotState();
        assertEquals(before, after, "recovered state must be byte-identical after a restart");
        assertEquals(SECRET, after.get("b"));
    }

    @Test
    void tamperedEncryptedWalRecordIsRefused(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        RaftLog log = new RaftLog(storage, encryptingEnvelope());
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, encryptingEnvelope(), log, sm);
        node.propose(KvStateMachine.put("k", "v"));

        // Flip a byte deep inside the ciphertext region and repair the OUTER FileStorage frame
        // CRC32 so readLog accepts the frame - recovery must still REFUSE (inner CRC32C or GCM tag).
        Path wal = tempDir.resolve("raft-log.wal");
        byte[] raw = Files.readAllBytes(wal);
        flipCipherByteRepairingFrameCrc(raw);
        Files.write(wal, raw, StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(IntegrityException.class, () -> new RaftLog(storage, encryptingEnvelope()));
    }

    @Test
    void nonEncryptingReaderCannotRecoverEncryptedWal(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        RaftLog log = new RaftLog(storage, encryptingEnvelope());
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, encryptingEnvelope(), log, sm);
        node.propose(KvStateMachine.put("k", "v"));

        // A keyless (or keyed-only) reader must refuse the algId=2 records rather than mis-parse them.
        assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, IntegrityEnvelope.keyless()));
    }

    @Test
    void mixedAlgId1AndAlgId2WalRecoversThroughTheRealSeam(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        SecretKey hmac = fixedHmacKey();

        // Phase 1 - pre-encryption: a keyed HMAC envelope writes algId=1 records.
        IntegrityEnvelope hmacEnv = new IntegrityEnvelope(hmac);
        RaftNode n1 = bootLeader(storage, hmacEnv, new RaftLog(storage, hmacEnv), new KvStateMachine());
        n1.propose(KvStateMachine.put("a", "1"));
        n1.propose(KvStateMachine.put("b", "2"));

        // Phase 2 - enable encryption: reopen with an encrypting envelope that carries the legacy HMAC
        // key. Recovery reads the algId=1 records; new proposals are appended as algId=2 -> mixed WAL.
        KvStateMachine sm2 = new KvStateMachine();
        RaftNode n2 = bootLeader(storage, encryptingEnvelopeWithLegacy(hmac),
                new RaftLog(storage, encryptingEnvelopeWithLegacy(hmac)), sm2);
        assertEquals("1", sm2.snapshotState().get("a"), "phase-2 recovery must read the legacy algId=1 records");
        n2.propose(KvStateMachine.put("c", SECRET));

        // The on-disk WAL is genuinely mixed: it carries BOTH algId=1 and algId=2 frames.
        assertEquals(Set.of((byte) IntegrityEnvelope.ALG_HMAC_SHA256, (byte) IntegrityEnvelope.ALG_AES256_GCM),
                algIdsInWal(Files.readAllBytes(tempDir.resolve("raft-log.wal"))),
                "the WAL must contain both a legacy HMAC record and an encrypted record");

        // Phase 3 - restart over the mixed WAL: every record recovers, per-record algId dispatch.
        KvStateMachine sm3 = new KvStateMachine();
        bootLeader(storage, encryptingEnvelopeWithLegacy(hmac),
                new RaftLog(storage, encryptingEnvelopeWithLegacy(hmac)), sm3);
        assertEquals(Map.of("a", "1", "b", "2", "c", SECRET), sm3.snapshotState(),
                "the mixed algId=1/algId=2 WAL must recover fully");
    }

    /** The distinct envelope algId bytes across all {@code [len][data][crc32]} frames of a WAL file. */
    private static Set<Byte> algIdsInWal(byte[] wal) {
        Set<Byte> algIds = new HashSet<>();
        ByteBuffer buf = ByteBuffer.wrap(wal);
        while (buf.remaining() >= 8) {
            int len = buf.getInt();
            if (len < IntegrityEnvelope.HEADER_SIZE || buf.remaining() < len + 4) {
                break;
            }
            int dataStart = buf.position();
            algIds.add(wal[dataStart + 6]); // algId is the 7th header byte: [magic:4][version:2][algId:1]
            buf.position(dataStart + len);
            buf.getInt(); // skip the frame CRC32
        }
        return algIds;
    }

    /**
     * Walks the {@code [len][data][crc32]} FileStorage frames, flips a byte inside the first frame's
     * ciphertext (well past the 40-byte encrypted prefix), and recomputes that frame's trailing CRC32.
     */
    private static void flipCipherByteRepairingFrameCrc(byte[] wal) {
        int len = ((wal[0] & 0xFF) << 24) | ((wal[1] & 0xFF) << 16) | ((wal[2] & 0xFF) << 8) | (wal[3] & 0xFF);
        int dataStart = 4;
        // 40 = header(8)+keyTerm(4)+segmentId(16)+nonce(12); flip a byte a few into the ciphertext.
        int flipAt = dataStart + 44;
        wal[flipAt] ^= 0x01;
        CRC32 crc = new CRC32();
        crc.update(wal, dataStart, len);
        int v = (int) crc.getValue();
        int crcPos = dataStart + len;
        wal[crcPos] = (byte) (v >>> 24);
        wal[crcPos + 1] = (byte) (v >>> 16);
        wal[crcPos + 2] = (byte) (v >>> 8);
        wal[crcPos + 3] = (byte) v;
    }
}
