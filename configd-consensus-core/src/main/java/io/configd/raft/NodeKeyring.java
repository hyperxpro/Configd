package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import io.configd.raft.KeyringCodec.Keyring;

import javax.crypto.SecretKey;
import java.io.Closeable;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The public node-keyring facade: load / mint / unseal / rotate the persisted, versioned
 * {@code raft-keyring} (frozen-format §2.6 / §A2). It exists only in the at-rest ENCRYPTION posture
 * (the GCM path); the keyring holds the independent random per-term roots that decouple encryption
 * from the signing key, so rotating either the term or the signing key is non-destructive by
 * construction.
 *
 * <p>The boot caller ({@code ConfigdServer.buildEncryptingEnvelope}) supplies the two signing-key-
 * derived keys - {@code K_keyringMac} (authenticates the whole keyring file) and {@code KEK_wrap}
 * (AES-GCM-wraps each root) - plus a non-secret {@code nodeKeyId} bound into each root's AAD; this
 * facade never sees the signing key itself. It hides the dual-slot {@link KeyringFile} and the
 * {@link KeyringCodec} crypto behind a narrow, public surface.
 *
 * <h2>Boot</h2>
 * <ul>
 *   <li>no file ⇒ first boot / enable-encryption migration ⇒ mint a fresh random {@code root[1]};</li>
 *   <li>file present, ≥1 valid slot ⇒ load the highest {@code keyringSeq};</li>
 *   <li>file present, BOTH slots invalid ⇒ REFUSE ({@link IntegrityException}) - tamper OR a keyring
 *       sealed under a prior signing key (finish the rotation or restore the prior key). Never a
 *       silent re-mint that would orphan the old encrypted data.</li>
 * </ul>
 *
 * <h2>Rotation (crash-atomic, non-destructive)</h2>
 * <ul>
 *   <li>{@link #rotateTerm()} appends a fresh random {@code root[activeTerm+1]}, retains every old
 *       term, and persists via the dual-slot writer; new writes stamp the new term, old data still
 *       decrypts under its retained root.</li>
 *   <li>{@link #rewrapForNewSigningKey} rewraps every root under the new signing key's KEK and writes
 *       the new slot BEFORE the operator swaps {@code signing-key.bin} - roots unchanged, so all prior
 *       data still verifies; a crash on either side of the swap boots on the matching slot.</li>
 * </ul>
 *
 * <p>Not thread-safe (single boot thread; an admin rotation is a serialized operator action).
 */
public final class NodeKeyring implements Closeable {

    private final KeyringFile file;
    private final SecretKey kek;
    private final byte[] nodeKeyId;
    private final SecureRandom rng;

    private NodeKeyring(KeyringFile file, SecretKey kek, byte[] nodeKeyId, SecureRandom rng) {
        this.file = file;
        this.kek = kek;
        this.nodeKeyId = nodeKeyId;
        this.rng = rng;
    }

    /**
     * Opens the production {@code raft-keyring} in {@code dataDir}, minting a fresh keyring on first
     * boot. REFUSES (throws {@link IntegrityException}) if the file is present but no slot verifies
     * under {@code keyringMacKey} (tamper / prior-KEK).
     *
     * @param dataDir       the node data directory (holds the keyring beside the node-anchor)
     * @param keyringMacKey {@code K_keyringMac} = HKDF(signingKey, "configd/keyring-mac/v1")
     * @param kek           {@code KEK_wrap}    = HKDF(signingKey, "configd/keyring-wrap/v1") (AES-256)
     * @param nodeKeyId     the non-secret node/signing-key id bound into each root's AAD
     */
    public static NodeKeyring loadOrCreate(Path dataDir, SecretKey keyringMacKey, SecretKey kek,
                                           byte[] nodeKeyId) {
        KeyringFile file = KeyringFile.openInDirectory(dataDir,
                IntegrityEnvelope.keyringMac(Objects.requireNonNull(keyringMacKey, "keyringMacKey")));
        return loadOrCreate(file, kek, nodeKeyId, new SecureRandom());
    }

    /** Test seam: open over an explicit {@link AnchorIO} (crash-model backend) with a supplied RNG. */
    static NodeKeyring loadOrCreateOverIO(AnchorIO io, SecretKey keyringMacKey, SecretKey kek,
                                          byte[] nodeKeyId, SecureRandom rng) {
        return loadOrCreate(KeyringFile.openOverIO(io, IntegrityEnvelope.keyringMac(keyringMacKey)),
                kek, nodeKeyId, rng);
    }

    private static NodeKeyring loadOrCreate(KeyringFile file, SecretKey kek, byte[] nodeKeyId,
                                            SecureRandom rng) {
        Objects.requireNonNull(kek, "kek");
        byte[] nodeId = Objects.requireNonNull(nodeKeyId, "nodeKeyId").clone();
        if (!file.existedAtOpen()) {
            byte[] root1 = new byte[KeyringCodec.KEYRING_ROOT_LEN];
            rng.nextBytes(root1);
            try {
                file.bootstrap(KeyringCodec.bootstrap(kek, nodeId, root1, rng));
            } finally {
                Arrays.fill(root1, (byte) 0);
            }
        } else if (!file.hasValidRecord()) {
            file.close();
            throw new IntegrityException("raft-keyring is present but no slot verifies under the current"
                    + " signing key - refusing to start. Either the keyring was tampered, or a"
                    + " signing-key rotation is half-finished: complete the rotation or restore the prior"
                    + " signing key. Never a silent re-mint (that would orphan the encrypted data).");
        }
        return new NodeKeyring(file, kek, nodeId, rng);
    }

    /** The term new writes stamp. */
    public int activeTerm() {
        return file.current().activeTerm();
    }

    /** The number of retained terms currently in the keyring (observability / tests). */
    public int termCount() {
        return file.current().entries().size();
    }

    /**
     * Unseals EVERY retained root into a {@link RootKey} keyed by its term. The caller owns the
     * returned handles (they hold live key material) and should build the {@code SegmentKeyManager}
     * from them; transient unwrapped bytes are zeroed here before they escape.
     *
     * @param reference the non-secret KEK reference stamped into each {@link KeyId} (loggable)
     */
    public List<RootKey> unsealRootKeys(String reference) {
        Map<Integer, byte[]> roots = KeyringCodec.unsealRoots(kek, nodeKeyId, file.current());
        List<RootKey> out = new ArrayList<>(roots.size());
        for (Map.Entry<Integer, byte[]> e : roots.entrySet()) {
            byte[] material = e.getValue();
            try {
                out.add(new RootKey(material, new KeyId("local", reference, e.getKey())));
            } finally {
                Arrays.fill(material, (byte) 0);
            }
        }
        return out;
    }

    /**
     * Term rotation: append a fresh random {@code root[activeTerm+1]}, retain all old terms, and
     * persist via the crash-atomic dual-slot writer. Returns the new root as a {@link RootKey} (its
     * {@code keyId().version()} is the new active term) so the caller can install it into the running
     * {@code SegmentKeyManager} ({@code rotateTo}). Persist happens FIRST, so a crash never installs a
     * term the keyring did not durably record.
     *
     * @param reference the non-secret KEK reference stamped into the new root's {@link KeyId}
     */
    public RootKey rotateTerm(String reference) {
        byte[] newRoot = new byte[KeyringCodec.KEYRING_ROOT_LEN];
        rng.nextBytes(newRoot);
        try {
            Keyring next = KeyringCodec.appendTerm(kek, nodeKeyId, file.current(), newRoot, rng);
            file.write(next); // persist FIRST (crash-atomic); RootKey below takes its own clone
            return new RootKey(newRoot, new KeyId("local", reference, next.activeTerm()));
        } finally {
            Arrays.fill(newRoot, (byte) 0);
        }
    }

    /**
     * Signing-key rotation (rewrap-before-swap): rewrap every root under the new signing key's KEK
     * (re-binding the node-id AAD) and write the new slot sealed under the new {@code K_keyringMac} -
     * BEFORE the operator swaps {@code signing-key.bin}. Roots are UNCHANGED, so all prior data still
     * verifies. After this returns, swap the signing key then restart: a crash before the swap boots
     * on the old slot (old key active), a crash after boots on the new slot (new key active); no
     * window loses a key.
     *
     * @param newKeyringMacKey the new signing key's {@code K_keyringMac}
     * @param newKek           the new signing key's {@code KEK_wrap} (AES-256)
     * @param newNodeKeyId     the new signing key's node id (bound into each rewrapped root's AAD)
     */
    public void rewrapForNewSigningKey(SecretKey newKeyringMacKey, SecretKey newKek, byte[] newNodeKeyId) {
        Keyring rewrapped = KeyringCodec.rewrapUnderNewKek(
                kek, nodeKeyId, Objects.requireNonNull(newKek, "newKek"), newNodeKeyId, file.current(), rng);
        file.writeRewrapSlot(rewrapped,
                IntegrityEnvelope.keyringMac(Objects.requireNonNull(newKeyringMacKey, "newKeyringMacKey")));
    }

    /** Arms the next {@code n} keyring data-syncs to throw (crash-during-rotation test seam). */
    void armSyncFailure(int n) {
        file.armSyncFailure(n);
    }

    long syncFaultsFired() {
        return file.syncFaultsFired();
    }

    @Override
    public void close() {
        file.close();
        Arrays.fill(nodeKeyId, (byte) 0);
    }
}
