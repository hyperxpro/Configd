package io.configd.server;

import io.configd.common.kms.RootKey;
import io.configd.raft.NodeKeyring;

import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;


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
