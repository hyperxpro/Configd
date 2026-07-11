package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests at-rest integrity of the Raft snapshot blob.
 * <p>
 * Builds a real KEYED {@link RaftLog} over a {@link io.configd.common.FileStorage}
 * temp dir, persists a snapshot (compacting the WAL prefix so the blob is the sole
 * record of [1..S]), flips ONE byte of the on-disk {@code raft-log.snapshot}
 * payload (and, for the strongest variant, recomputes the envelope's CRC32C so only
 * the HMAC catches it), and constructs a new RaftLog - recovery REFUSES (throws
 * {@link IntegrityException}) rather than loading the attacker's state.
 * <p>
 * The same attack against the unauthenticated code is captured succeeding in
 * {@link Pa2021VulnerabilityCaptureTest}.
 */
class SnapshotIntegrityTest {

    private static final NodeId NODE = NodeId.of(1);
    private static final int ELECTION_TICKS = 400;
    private static final String BLOB_FILE = "raft-log.snapshot.dat";
    private static final RaftNode.InvariantChecker THROWING = (name, condition, message) -> {
        if (!condition) {
            throw new AssertionError("Invariant violated [" + name + "]: " + message);
        }
    };
    private static final RaftTransport NO_PEERS = (target, message) -> { };

    /** A keyed integrity envelope with a fixed test key. */
    static IntegrityEnvelope keyedEnvelope() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0x5a);
        return new IntegrityEnvelope(new SecretKeySpec(k, "HmacSHA256"));
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
    void tamperedSnapshotPayloadByteIsRefused(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);

        node.propose(KvStateMachine.put("secret", "AAAA"));
        node.triggerSnapshot();
        assertEquals("AAAA", sm.snapshotState().get("secret"));

        // Adversary (no key) flips a value byte in the on-disk blob payload.
        Path blob = tempDir.resolve(BLOB_FILE);
        byte[] raw = Files.readAllBytes(blob);
        flipFirst(raw, (byte) 'A', (byte) 'B');
        Files.write(blob, raw, StandardOpenOption.TRUNCATE_EXISTING);

        // Recovery must REFUSE - fail loud, do not load the tampered state.
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env));
        assertTrue(ex.getMessage().contains("CRC32C") || ex.getMessage().contains("MAC"),
                "expected a corruption/tamper refusal, got: " + ex.getMessage());
    }

    @Test
    void tamperedSnapshotWithRecomputedCrcIsRefusedByMac(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);

        node.propose(KvStateMachine.put("secret", "AAAA"));
        node.triggerSnapshot();

        // Stronger attacker: flip a payload byte AND fix the CRC32C - only the HMAC
        // (which the attacker cannot forge without the key) can catch this.
        Path blob = tempDir.resolve(BLOB_FILE);
        byte[] raw = Files.readAllBytes(blob);
        flipFirst(raw, (byte) 'A', (byte) 'B');
        recomputeEnvelopeCrc(raw);
        Files.write(blob, raw, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env));
        assertTrue(ex.getMessage().contains("MAC"),
                "CRC was repaired; only the MAC should refuse — got: " + ex.getMessage());
    }

    @Test
    void forgedFormatVersionIsRefused(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);

        node.propose(KvStateMachine.put("k", "v"));
        node.triggerSnapshot();

        // Roll the envelope formatVersion (short at offset 4) back to 1, fix CRC.
        Path blob = tempDir.resolve(BLOB_FILE);
        byte[] raw = Files.readAllBytes(blob);
        raw[4] = 0;
        raw[5] = 1;
        recomputeEnvelopeCrc(raw);
        Files.write(blob, raw, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env));
        assertTrue(ex.getMessage().contains("formatVersion"),
                "expected a version refusal, got: " + ex.getMessage());
    }

    @Test
    void downgradeToAlgNoneIsRefused(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);

        node.propose(KvStateMachine.put("k", "v"));
        node.triggerSnapshot();

        // Strip-the-MAC: force algId (offset 6) to NONE, fix CRC. The keyed reader
        // refuses the downgrade even though the bytes are now CRC-consistent.
        Path blob = tempDir.resolve(BLOB_FILE);
        byte[] raw = Files.readAllBytes(blob);
        raw[6] = IntegrityEnvelope.ALG_NONE;
        recomputeEnvelopeCrc(raw);
        Files.write(blob, raw, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env));
        assertTrue(ex.getMessage().contains("downgrade"),
                "expected a downgrade refusal, got: " + ex.getMessage());
    }

    /**
     * Install-snapshot shares the SAME integrity code path as a local snapshot:
     * {@code handleInstallSnapshot} persists the received blob via
     * {@code log.persistSnapshot(installed)} - the identical call
     * {@code triggerSnapshot} makes - so the blob is written
     * through {@code RaftLog.serializeSnapshot} (keyed wrap) and recovered through
     * {@code RaftLog.readSnapshotBlob} (keyed unwrap). This test drives the real
     * install path on a follower, then tampers the persisted blob and asserts
     * recovery refuses - proving a forged installed snapshot is rejected on the
     * same path as a local one (NOT a parallel test-only wiring).
     */
    @Test
    void forgedInstalledSnapshotIsRefusedOnRecovery(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        NodeId leader = NodeId.of(2);
        RaftConfig config = RaftConfig.of(NODE, Set.of(leader));
        RaftNode follower = new RaftNode(config, log, NO_PEERS, sm,
                RandomGenerator.of("L64X128MixRandom"), storage, THROWING, env);

        // Leader installs a snapshot (the follower's handleInstallSnapshot path).
        // Build a non-empty state-machine snapshot so the restore is meaningful.
        KvStateMachine src = new KvStateMachine();
        src.apply(1, 1, KvStateMachine.put("installed", "AAAA"));
        byte[] snapData = src.snapshot();
        InstallSnapshotRequest req = new InstallSnapshotRequest(
                1, leader, 10, 1, 0, snapData, true);
        follower.handleMessage(req);
        assertEquals("AAAA", sm.snapshotState().get("installed"),
                "follower restored the installed snapshot");

        // The blob was persisted by handleInstallSnapshot -> log.persistSnapshot.
        Path blob = tempDir.resolve(BLOB_FILE);
        byte[] raw = Files.readAllBytes(blob);
        flipFirst(raw, (byte) 'A', (byte) 'B');
        recomputeEnvelopeCrc(raw);
        Files.write(blob, raw, StandardOpenOption.TRUNCATE_EXISTING);

        // Recovery over the forged installed blob refuses on the shared path.
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env));
        assertTrue(ex.getMessage().contains("MAC"),
                "forged installed snapshot must be refused by the MAC, got: " + ex.getMessage());
    }

    @Test
    void untamperedKeyedSnapshotRecoversCleanly(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);

        node.propose(KvStateMachine.put("a", "1"));
        node.propose(KvStateMachine.put("b", "2"));
        node.triggerSnapshot();

        // No tamper: a fresh keyed RaftLog + node restores the committed state.
        RaftLog log2 = new RaftLog(storage, env);
        KvStateMachine sm2 = new KvStateMachine();
        bootLeader(storage, env, log2, sm2);
        assertEquals("1", sm2.snapshotState().get("a"));
        assertEquals("2", sm2.snapshotState().get("b"));
    }

    private static void flipFirst(byte[] data, byte from, byte to) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == from) {
                data[i] = to;
                return;
            }
        }
        throw new IllegalStateException("byte " + from + " not found to flip");
    }

    private static void recomputeEnvelopeCrc(byte[] enveloped) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(enveloped, 0, enveloped.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(enveloped)
                .putInt(enveloped.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }
}
