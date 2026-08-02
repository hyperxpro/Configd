package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.common.WalContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies at-rest integrity of the WAL records, and the critical torn-vs-tamper disambiguation.
 * <ul>
 *   <li><b>Tamper:</b> a COMPLETE, FileStorage-CRC32C-valid WAL frame whose inner
 *       integrity envelope HMAC fails - replay REFUSES (throws). A write-access
 *       attacker recomputes the per-frame CRC32C trivially (the frame CRC is corruption-only,
 *       not authentication), so the HMAC is the control that catches them.</li>
 *   <li><b>Torn:</b> a genuinely truncated trailing record (a partial frame from a
 *       crash mid-append) is dropped by FileStorage's torn-tail rule BEFORE the
 *       envelope is ever checked - recovery succeeds with the prior entries.</li>
 * </ul>
 */
class WalRecordIntegrityTest {

    private static final NodeId NODE = NodeId.of(1);
    private static final int ELECTION_TICKS = 400;
    private static final RaftNode.InvariantChecker THROWING = (name, condition, message) -> {
        if (!condition) {
            throw new AssertionError("Invariant violated [" + name + "]: " + message);
        }
    };
    private static final RaftTransport NO_PEERS = (target, message) -> { };

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
    void tamperedCompleteWalRecordIsRefused(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);
        node.propose(KvStateMachine.put("k", "AAAA"));
        assertEquals("AAAA", sm.snapshotState().get("k"));

        // Flip a value byte inside the WAL record's envelope payload and recompute
        // the OUTER FileStorage frame CRC32 so readLog accepts the frame - only the
        // INNER envelope HMAC can detect the tamper.
        Path wal = tempDir.resolve("raft-log.wal");
        byte[] raw = Files.readAllBytes(wal);
        tamperWalRecomputingFrameCrc(raw, (byte) 'A', (byte) 'B');
        Files.write(wal, raw, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new RaftLog(storage, env));
        assertTrue(ex.getMessage().contains("MAC") || ex.getMessage().contains("CRC32C"),
                "expected a tamper refusal, got: " + ex.getMessage());
    }

    @Test
    void tornTrailingWalRecordIsToleratedAndPriorEntriesRecover(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        RaftLog log = new RaftLog(storage, env);
        KvStateMachine sm = new KvStateMachine();
        RaftNode node = bootLeader(storage, env, log, sm);
        node.propose(KvStateMachine.put("a", "1"));
        node.propose(KvStateMachine.put("b", "2"));
        Map<String, String> committedBeforeTear = sm.snapshotState();
        assertEquals(Map.of("a", "1", "b", "2"), committedBeforeTear);

        // Append a partial frame - a length header claiming more bytes than follow,
        // exactly what a crash mid-appendToLog leaves behind. FileStorage.readLog
        // drops this BEFORE the integrity envelope is consulted.
        Path wal = tempDir.resolve("raft-log.wal");
        byte[] tornFrame = new byte[]{0, 0, 0, 64, 1, 2, 3}; // claims len=64, only 3 follow
        Files.write(wal, tornFrame, StandardOpenOption.APPEND);

        RaftLog log2 = new RaftLog(storage, env);
        KvStateMachine sm2 = new KvStateMachine();
        bootLeader(storage, env, log2, sm2);
        Map<String, String> recovered = sm2.snapshotState();
        assertTrue(recovered.entrySet().containsAll(committedBeforeTear.entrySet()),
                "torn-tail recovery dropped a committed entry: expected superset of "
                        + committedBeforeTear + " but got " + recovered);
        assertEquals("1", recovered.get("a"));
        assertEquals("2", recovered.get("b"));
    }


    /**
     * Walks the {@code [len][data][crc32c]} FileStorage frames (after the 8-byte container header),
     * flips the first {@code from} byte inside a frame's integrity-envelope PAYLOAD (skipping the
     * envelope header so we tamper the protected bytes, not the magic), and rewrites that frame's
     * trailing CRC32C so FileStorage.readLog accepts it - leaving only the inner envelope HMAC able
     * to detect the tamper.
     */
    private static void tamperWalRecomputingFrameCrc(byte[] wal, byte from, byte to) {
        ByteBuffer buf = ByteBuffer.wrap(wal);
        buf.position(WalContainer.HEADER_SIZE);
        while (buf.remaining() >= 8) {
            int len = buf.getInt();
            if (len < 0 || buf.remaining() < len + 4) {
                break;
            }
            int dataStart = buf.position();
            // Skip the envelope header + scopeId (magic+version+algId+reserved+scopeId) so we flip
            // a PAYLOAD byte, not a header/scope byte (a header/scope flip would surface as a
            // wrong-magic/version/scope refusal rather than the MAC-mismatch we are proving).
            int searchStart = dataStart + IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE;
            boolean flipped = false;
            for (int i = searchStart; i < dataStart + len; i++) {
                if (wal[i] == from) {
                    wal[i] = to;
                    flipped = true;
                    break;
                }
            }
            buf.position(dataStart + len);
            int crcPos = buf.position();
            buf.getInt(); // skip stored crc
            if (flipped) {
                CRC32C crc = new CRC32C();
                crc.update(wal, dataStart, len);
                ByteBuffer.wrap(wal).putInt(crcPos, (int) crc.getValue());
                return;
            }
        }
        throw new IllegalStateException("byte " + from + " not found in any WAL frame");
    }
}
