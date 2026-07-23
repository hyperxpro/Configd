package io.configd.server;

import io.configd.common.kms.RootKey;
import io.configd.raft.NodeKeyring;

import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Backs the ADMIN-gated {@code POST /v1/admin/keyring/rotate} endpoint with a durable, non-destructive
 * at-rest keyring TERM rotation.
 *
 * <p>The persisted {@code raft-keyring} is NOT held open at runtime (the boot path loads it into an
 * immutable {@code SegmentKeyManager} and closes it), so a rotation re-opens it fresh, appends a new
 * per-term root, advances the active term, persists (crash-atomic dual-slot), and closes. Because the boot
 * reads the keyring's {@code activeTerm} to select the write term, new writes adopt the new term after the
 * next (rolling) restart; old-term data still decrypts because every retained root loads. This is the
 * rotate-then-restart model - there is deliberately no live hot-swap of the running key manager.
 *
 * <p>This holder retains the small custody-AGNOSTIC capability the boot path derives - the keyring
 * data-dir, the two derived keyring keys ({@code K_keyringMac} authenticates the file, {@code KEK_wrap}
 * wraps each root), the node key id, and the loggable key reference. Retaining the DERIVED wrap/mac keys
 * (not the custodian's secret) means a rotation never needs to re-unseal an external custodian, and it does
 * not materially worsen in-memory secret exposure: the actual roots/DEKs are already resident in the live
 * key manager for the write path. Rotations are serialized by a lock so only one runs at a time.
 */
final class KeyringRotator implements AdminApiHandler.KeyringRotationAdmin {

    private final Path dataDir;
    private final SecretKey keyringMac;
    private final SecretKey kek;
    private final byte[] nodeKeyId;
    private final String reference;
    private final ReentrantLock lock = new ReentrantLock();

    KeyringRotator(Path dataDir, SecretKey keyringMac, SecretKey kek, byte[] nodeKeyId, String reference) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.keyringMac = Objects.requireNonNull(keyringMac, "keyringMac");
        this.kek = Objects.requireNonNull(kek, "kek");
        this.nodeKeyId = Objects.requireNonNull(nodeKeyId, "nodeKeyId").clone();
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    /**
     * Performs one durable, non-destructive term rotation and returns the new active term. Serialized: a
     * concurrent rotation waits for the in-flight one so the persisted keyring is never mutated by two
     * openers at once.
     */
    @Override
    public int rotate() {
        lock.lock();
        try (NodeKeyring keyring = NodeKeyring.loadOrCreate(dataDir, keyringMac, kek, nodeKeyId)) {
            // rotateTerm persists the new root FIRST (crash-atomic), then returns it. We do NOT install it
            // into any live manager (rotate-then-restart), so we destroy the returned handle immediately.
            RootKey appended = keyring.rotateTerm(reference);
            appended.destroy();
            return keyring.activeTerm();
        } finally {
            lock.unlock();
        }
    }
}
