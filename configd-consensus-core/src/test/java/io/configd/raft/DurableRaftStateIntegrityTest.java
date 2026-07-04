package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies at-rest integrity of {@code raft.persistent_state}.
 * <p>
 * A forged {@code votedFor} (flipped to another valid node id, with no valid MAC -
 * the attacker has no key) must be REFUSED on load: loading it would let a restarted
 * node believe it voted for a different candidate in a term, violating Election
 * Safety. A keyless legacy (pre-envelope) raw file still loads (back-compat).
 * <p>
 * {@code raft.persistent_state} is an atomic-rename artifact (never torn), so any
 * structurally-complete MAC mismatch is unambiguously tamper - always fail loud.
 */
class DurableRaftStateIntegrityTest {

    private static final String STATE_FILE = "raft.persistent_state.dat";

    /** Forged votedFor under a keyed codec is refused. */
    @Test
    void forgedVotedForIsRefused(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        DurableRaftState state = new DurableRaftState(storage, env);
        state.setTermAndVote(5, NodeId.of(2));

        // Adversary flips the votedFor int inside the envelope payload from 2 to 3
        // (a different valid voter) and recomputes the envelope CRC32C - only the
        // HMAC, which they cannot forge, catches it.
        Path file = tempDir.resolve(STATE_FILE);
        byte[] raw = Files.readAllBytes(file);
        // v3 payload begins after header(8) + scopeId(4).
        int payloadStart = IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE;
        // payload layout: [term:8][votedFor:4]; votedFor int at payload offset 8.
        ByteBuffer.wrap(raw).putInt(payloadStart + 8, 3);
        recomputeEnvelopeCrc(raw);
        Files.write(file, raw, StandardOpenOption.TRUNCATE_EXISTING);

        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> new DurableRaftState(storage, env));
        assertTrue(ex.getMessage().contains("MAC"),
                "expected a tamper (MAC) refusal, got: " + ex.getMessage());
    }

    /** A flipped term (with recomputed CRC) is likewise refused by the MAC. */
    @Test
    void forgedTermIsRefused(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        DurableRaftState state = new DurableRaftState(storage, env);
        state.setTermAndVote(5, NodeId.of(2));

        Path file = tempDir.resolve(STATE_FILE);
        byte[] raw = Files.readAllBytes(file);
        // v3 payload begins after header(8) + scopeId(4); term is the first payload long.
        ByteBuffer.wrap(raw).putLong(IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE, 99L); // term 5 -> 99
        recomputeEnvelopeCrc(raw);
        Files.write(file, raw, StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(IntegrityException.class, () -> new DurableRaftState(storage, env));
    }

    /** Untampered keyed state round-trips across restart. */
    @Test
    void untamperedKeyedStateSurvivesRestart(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        IntegrityEnvelope env = SnapshotIntegrityTest.keyedEnvelope();
        new DurableRaftState(storage, env).setTermAndVote(7, NodeId.of(3));

        DurableRaftState reloaded = new DurableRaftState(storage, env);
        assertEquals(7L, reloaded.currentTerm());
        assertEquals(NodeId.of(3), reloaded.votedFor());
    }

    /**
     * Back-compat: a legacy raw (12-byte non-enveloped) state file is
     * still loaded by a KEYLESS codec. This is the migration path for nodes upgraded
     * before authentication is turned on.
     */
    @Test
    void keylessLoadsLegacyRawState(@TempDir Path tempDir) throws Exception {
        Storage storage = Storage.file(tempDir);

        // Write a legacy 12-byte [term:8][votedFor:4] file directly (no envelope).
        ByteBuffer legacy = ByteBuffer.allocate(12);
        legacy.putLong(4L);
        legacy.putInt(2);
        storage.put("raft.persistent_state", legacy.array());

        // A keyless codec reads it (null-return back-compat path).
        DurableRaftState state = new DurableRaftState(storage); // keyless default
        assertEquals(4L, state.currentTerm());
        assertEquals(NodeId.of(2), state.votedFor());
    }

    /** A fresh node (no state file) starts at term 0 with no vote under a keyed codec. */
    @Test
    void keyedFreshNodeStartsAtTermZero(@TempDir Path tempDir) {
        Storage storage = Storage.file(tempDir);
        DurableRaftState state = new DurableRaftState(storage, SnapshotIntegrityTest.keyedEnvelope());
        assertEquals(0L, state.currentTerm());
        assertNull(state.votedFor());
    }

    private static void recomputeEnvelopeCrc(byte[] enveloped) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(enveloped, 0, enveloped.length - IntegrityEnvelope.CRC_SIZE);
        ByteBuffer.wrap(enveloped)
                .putInt(enveloped.length - IntegrityEnvelope.CRC_SIZE, (int) crc.getValue());
    }
}
