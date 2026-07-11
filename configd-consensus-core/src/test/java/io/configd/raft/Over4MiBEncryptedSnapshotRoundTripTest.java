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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A genuinely &gt; 4 MiB snapshot exercised across BOTH the paths a small snapshot never hits
 * together: the multi-chunk InstallSnapshot wire transfer AND the whole-blob AES-256-GCM
 * encryption at rest.
 *
 * <h2>As-built truth this test pins (read before editing)</h2>
 * Encryption and chunking are <em>orthogonal</em> in Configd and never nest:
 * <ul>
 *   <li><b>At rest</b>, the entire snapshot blob is enveloped <em>once</em> in
 *       {@code RaftLog.serializeSnapshot} via {@code integrity.wrap(SNAP_MAGIC, gid, blob)} — one
 *       {@code algId=2} GCM record with a single {@code keyTerm} and a single {@code segmentId} for
 *       the whole payload. There is no per-chunk envelope.</li>
 *   <li><b>On the wire</b>, {@code RaftNode.sendSnapshotChunk} slices the <em>raw</em>
 *       state-machine bytes ({@code stateMachine.snapshot()}), NOT the enveloped blob, into
 *       {@code snapshotChunkBytes} (default 1 MiB) pieces. Wire chunks carry no envelope; the wire is
 *       the transport's concern (TLS/mTLS in production, plus the frame CRC). Each node re-encrypts
 *       the reassembled blob independently under its own at-rest key when it persists it.</li>
 * </ul>
 * So "a &gt; 4 MiB chunked encrypted snapshot" is faithfully realized as two composed guarantees:
 * (1) the wire transfer of a &gt; 4 MiB payload spans multiple chunks and reassembles byte-for-byte
 * (below, {@link #realWireTransferOfOver4MiBSnapshotSpansChunksAndReassemblesByteIdentical}); and
 * (2) the whole blob is GCM-encrypted at rest, round-trips byte-for-byte, is tamper-refused, and a
 * key-term rotation stamps a fresh {@code keyTerm} while old-term blobs still decrypt (below,
 * {@link #over4MiBSnapshotAtRestIsGcmEncryptedRoundTripsTamperRefusedAndTermRotates}).
 */
class Over4MiBEncryptedSnapshotRoundTripTest {

    private static final NodeId N1 = NodeId.of(1);

    /** The frozen single-frame ceiling the chunked transfer lifts (mirrors RaftNode.MAX_SNAPSHOT_CHUNK_BYTES). */
    private static final int FOUR_MIB = 4 * 1024 * 1024;
    /** A payload strictly larger than the single-frame ceiling, so a multi-chunk transfer is REQUIRED. */
    private static final int OVER_FOUR_MIB = FOUR_MIB + 1_000_003; // ~5.01 MiB (a prime-ish tail, not chunk-aligned)

    /** Snapshot storage key -> FileStorage file name (RaftLog.SNAPSHOT_BLOB_KEY = "raft-log.snapshot"). */
    private static final String BLOB_FILE = "raft-log.snapshot.dat";

    // v3 envelope fixed offsets (IntegrityEnvelope): header 8 + scopeId 4 -> keyTerm at 12; algId at 6.
    private static final int ALG_ID_OFFSET = 6;
    private static final int KEY_TERM_OFFSET = 8 + IntegrityEnvelope.SCOPE_ID_SIZE; // 12

    /** A deterministic blob whose first bytes are a recognizable plaintext sentinel. */
    private static byte[] blob(int size, String sentinel) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i * 2_654_435_761L + 7) & 0xFF); // Knuth-multiplicative spread; non-trivial bytes
        }
        byte[] s = sentinel.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(s, 0, b, 0, s.length);
        return b;
    }

    /** A single-term encrypting (AES-256-GCM) envelope over a fresh random root at {@code term}. */
    private static SegmentKeyManager keyManagerAtTerm(int term) {
        byte[] material = new byte[32];
        for (int i = 0; i < material.length; i++) {
            material[i] = (byte) (0x11 * term + i);
        }
        return new SegmentKeyManager(new RootKey(material, new KeyId("local", "gate5-kid", term)));
    }

    private static RootKey rootAtTerm(int term) {
        byte[] material = new byte[32];
        for (int i = 0; i < material.length; i++) {
            material[i] = (byte) (0x22 * term + i);
        }
        return new RootKey(material, new KeyId("local", "gate5-kid", term));
    }

    private static int keyTermOf(byte[] enveloped) {
        return ByteBuffer.wrap(enveloped, KEY_TERM_OFFSET, IntegrityEnvelope.KEY_TERM_SIZE).getInt();
    }

    /** Extracts the state-machine {@code data} field from a decrypted snapshot blob
     *  (RaftLog.serializeSnapshot layout: {@code [idx:8][term:8][dataLen:4][data][cfgLen:4][cfg?]}). */
    private static byte[] snapshotDataOf(byte[] plain) {
        ByteBuffer buf = ByteBuffer.wrap(plain);
        buf.getLong();               // lastIncludedIndex
        buf.getLong();               // lastIncludedTerm
        int dataLen = buf.getInt();
        byte[] data = new byte[dataLen];
        buf.get(data);
        return data;
    }

    /** Recomputes the envelope's CRC32C trailer so a tamper survives the corruption guard and only the
     *  GCM tag can catch it (mirrors SnapshotIntegrityTest.recomputeEnvelopeCrc). */
    private static void recomputeEnvelopeCrc(byte[] raw) {
        CRC32C crc = new CRC32C();
        crc.update(raw, 0, raw.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(raw, raw.length - IntegrityEnvelope.CRC_SIZE, IntegrityEnvelope.CRC_SIZE)
                .putInt((int) crc.getValue());
    }

    // =======================================================================
    // (1) The multi-chunk WIRE transfer of a > 4 MiB snapshot (chunking guarantee)
    // =======================================================================

    /**
     * A leader takes a genuinely &gt; 4 MiB snapshot and streams it to a lagging follower over the REAL
     * ack-driven {@code sendSnapshotChunk} path at the production default 1 MiB chunk size. The payload
     * cannot fit one frame (&gt; the 4 MiB single-frame ceiling), so it MUST span several chunks; the
     * follower reassembles a byte-for-byte-identical blob. This is the path a small (single-chunk)
     * snapshot never exercises.
     */
    @Test
    void realWireTransferOfOver4MiBSnapshotSpansChunksAndReassemblesByteIdentical() {
        InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
        cluster.electLeader(N1);
        RaftNode leader = cluster.nodes.get(N1);
        NodeId healthy = NodeId.of(2);
        NodeId lagging = NodeId.of(3);
        // Production default chunk size (1 MiB) — NOT the tiny test override — so this is the real
        // multi-chunk split a > 4 MiB payload forces in production.

        // Commit + compact with only {N1,N2} active, so N3 falls behind a snapshot boundary.
        Set<NodeId> active = Set.of(N1, healthy);
        for (int i = 0; i < 5; i++) {
            leader.propose(new byte[]{(byte) i});
        }
        for (int r = 0; r < 10; r++) {
            cluster.deliverMessagesTo(active);
        }
        for (int i = 0; i < 51; i++) {
            leader.tick();
        }
        for (int r = 0; r < 5; r++) {
            cluster.deliverMessagesTo(active);
        }

        byte[] leaderSnapshot = blob(OVER_FOUR_MIB, "OVER-4MIB-SNAPSHOT-SENTINEL");
        cluster.stateMachines.get(N1).snapshotData = leaderSnapshot;
        assertTrue(leader.triggerSnapshot(), "leader must take the > 4 MiB snapshot");
        assertTrue(leaderSnapshot.length > FOUR_MIB,
                "the payload must exceed the 4 MiB single-frame ceiling so chunking is required");

        // Prime the first chunk to N3 with one tick burst, then drive the ack-based transfer WITHOUT
        // further leader ticks: each chunk is re-sent on receipt of the follower's ack (not on a tick),
        // and not ticking the leader keeps CheckQuorum from stepping it down mid-transfer. We harvest the
        // distinct chunk offsets the leader emits to N3 each delivery step.
        InstallSnapshotTest.TestStateMachine sm3 = cluster.stateMachines.get(lagging);
        InstallSnapshotTest.TestTransport leaderTransport = cluster.transports.get(N1);
        cluster.transports.values().forEach(InstallSnapshotTest.TestTransport::clear);
        for (int t = 0; t < 51; t++) {
            leader.tick();
        }
        Set<Integer> distinctOffsets = new HashSet<>();
        int maxChunkLen = 0;
        for (int round = 0; round < 2000 && sm3.restoredFrom == null; round++) {
            for (InstallSnapshotRequest c :
                    leaderTransport.messagesTo(lagging, InstallSnapshotRequest.class)) {
                distinctOffsets.add(c.offset());
                maxChunkLen = Math.max(maxChunkLen, c.data().length);
            }
            cluster.deliverAllMessages(1); // one hop: a chunk to N3, N3's ack back -> leader enqueues the next chunk
        }

        assertNotNull(sm3.restoredFrom, () -> "the lagging follower must install the > 4 MiB snapshot"
                + " [leaderRole=" + leader.role() + " leaderSnapIdx=" + leader.log().snapshotIndex()
                + " offsets=" + distinctOffsets + " n3snapIdx=" + cluster.logs.get(lagging).snapshotIndex() + "]");
        assertArrayEquals(leaderSnapshot, sm3.restoredFrom,
                "the multi-chunk reassembled snapshot must be byte-for-byte identical to the leader's");
        assertEquals(leader.log().snapshotIndex(), cluster.logs.get(lagging).snapshotIndex(),
                "the follower installs at the leader's snapshot index");
        assertTrue(distinctOffsets.size() >= 5,
                "a > 4 MiB payload at 1 MiB chunks must span >= 5 chunks; saw offsets=" + distinctOffsets);
        assertTrue(maxChunkLen <= RaftNode.MAX_SNAPSHOT_CHUNK_BYTES,
                "no single chunk may exceed the 4 MiB per-chunk frame ceiling; maxChunkLen=" + maxChunkLen);
    }

    // =======================================================================
    // (2) The > 4 MiB blob ENCRYPTED at rest (encryption + integrity + keyTerm guarantee)
    // =======================================================================

    @Test
    void over4MiBSnapshotAtRestIsGcmEncryptedRoundTripsTamperRefusedAndTermRotates(@TempDir Path root)
            throws Exception {
        String sentinel = "AT-REST-PLAINTEXT-SENTINEL-must-not-appear";
        byte[] payloadTerm1 = blob(OVER_FOUR_MIB, sentinel);

        // Two independent at-rest stores sharing ONE term-versioned key manager (as the node's single
        // SegmentKeyManager serves every group). Term 1 mints root[1].
        SegmentKeyManager km = keyManagerAtTerm(1);
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(km);

        Path dirA = root.resolve("shardA");
        Storage storageA = Storage.file(dirA);
        RaftLog logA = new RaftLog(storageA, env);
        logA.persistSnapshot(new SnapshotState(payloadTerm1, 100, 3));

        byte[] rawTerm1 = Files.readAllBytes(dirA.resolve(BLOB_FILE));
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, rawTerm1[ALG_ID_OFFSET],
                "a > 4 MiB snapshot written with encryption ON must be an AES-256-GCM envelope");
        assertEquals(1, keyTermOf(rawTerm1), "the blob is stamped with the active keyTerm (1)");
        assertTrue(rawTerm1.length > FOUR_MIB, "the encrypted blob spans well past the 4 MiB ceiling");
        assertFalse(new String(rawTerm1, StandardCharsets.ISO_8859_1).contains(sentinel),
                "the at-rest ciphertext must not contain the plaintext sentinel");

        // Decrypts, integrity-verifies, and round-trips through the SAME envelope the recovery path
        // uses (env.unwrap = readSnapshotBlob's crypto; gid 0 = the default RaftLog scope).
        byte[] decrypted = env.unwrap(RaftArtifactMagic.SNAP_MAGIC, 0, rawTerm1);
        assertArrayEquals(payloadTerm1, snapshotDataOf(decrypted),
                "the decrypted > 4 MiB snapshot must be byte-for-byte the original");

        // A tampered ciphertext byte is refused even with the CRC repaired - only the GCM tag catches it.
        byte[] tampered = rawTerm1.clone();
        int ctByte = tampered.length / 2; // deep inside the ciphertext, past the GCM prefix
        tampered[ctByte] ^= 0x40;
        recomputeEnvelopeCrc(tampered); // survive the corruption guard; force the AUTH tag to be the catcher
        Files.write(dirA.resolve(BLOB_FILE), tampered, StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(IntegrityException.class, () -> new RaftLog(storageA, env),
                "recovery must REFUSE a tampered encrypted snapshot (fail closed), not load attacker state");

        // Key-term rotation: a new snapshot stamps the new term; an old-term blob still decrypts.
        km.rotateTo(rootAtTerm(2));
        assertEquals(2, km.activeTerm(), "the manager now writes on term 2");

        byte[] payloadTerm2 = blob(OVER_FOUR_MIB, "TERM-2-SNAPSHOT");
        Path dirB = root.resolve("shardB");
        Storage storageB = Storage.file(dirB);
        RaftLog logB = new RaftLog(storageB, env);
        logB.persistSnapshot(new SnapshotState(payloadTerm2, 200, 4));

        byte[] rawTerm2 = Files.readAllBytes(dirB.resolve(BLOB_FILE));
        assertEquals(IntegrityEnvelope.ALG_AES256_GCM, rawTerm2[ALG_ID_OFFSET]);
        assertEquals(2, keyTermOf(rawTerm2), "post-rotation writes stamp the new keyTerm (2)");
        assertArrayEquals(payloadTerm2, snapshotDataOf(env.unwrap(RaftArtifactMagic.SNAP_MAGIC, 0, rawTerm2)),
                "the term-2 > 4 MiB snapshot round-trips under the rotated manager");

        // The rotated manager RETAINS root[1], so an untouched term-1 blob still decrypts — the
        // non-destructive-rotation property, on a > 4 MiB snapshot.
        assertArrayEquals(payloadTerm1, snapshotDataOf(env.unwrap(RaftArtifactMagic.SNAP_MAGIC, 0, rawTerm1)),
                "an old-term > 4 MiB snapshot still decrypts after the key term rotated (non-destructive)");
    }
}
