package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.Storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Test kit for building properly hash-chained authenticated WAL records on disk, mirroring
 * {@link RaftLog}'s per-record chain ({@code recordHash = SHA-256([index][term][prevHash][command])},
 * genesis {@code prevHash} = 32 zero bytes). Used by the recovery-check and physical-reorder red-team
 * tests, which need to craft on-disk WAL shapes the normal append path never writes (an index gap, a
 * term regression, a spliced-in stale frame) while keeping every OTHER record a valid chain link so
 * the recovery check under test is the one that fires.
 */
final class ChainedWal {

    static final byte[] GENESIS = new byte[32];

    private ChainedWal() {
    }

    /** The authenticated-posture inner payload: {@code [index:8][term:8][prevHash:32][command]}. */
    static byte[] inner(long index, long term, byte[] prevHash, byte[] command) {
        ByteBuffer b = ByteBuffer.allocate(8 + 8 + 32 + command.length);
        b.putLong(index);
        b.putLong(term);
        b.put(prevHash);
        b.put(command);
        return b.array();
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * A stateful writer that appends properly-chained committed records to a {@link Storage}'s
     * {@code raft-log} WAL under a fixed scope, tracking the running chain head exactly as RaftLog does.
     */
    static final class Writer {
        private final Storage storage;
        private final IntegrityEnvelope env;
        private final int gid;
        private byte[] chainHead = GENESIS.clone();

        Writer(Storage storage, IntegrityEnvelope env, int gid) {
            this.storage = storage;
            this.env = env;
            this.gid = gid;
        }

        /** Appends a chained record and returns its hash (the new chain head). */
        byte[] append(long index, long term, String command) {
            byte[] payload = inner(index, term, chainHead, command.getBytes(StandardCharsets.UTF_8));
            storage.appendToLog("raft-log", env.wrap(RaftArtifactMagic.WALE_MAGIC, gid, payload));
            chainHead = sha256(payload);
            return chainHead;
        }

        byte[] chainHead() {
            return chainHead;
        }
    }
}
