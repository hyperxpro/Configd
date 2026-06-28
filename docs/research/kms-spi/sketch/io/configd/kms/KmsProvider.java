package io.configd.kms;

/**
 * Pluggable custody of a <b>per-node root key</b>. A {@code KmsProvider} does exactly
 * one job: <b>seal and unseal one small root key</b>. It is deliberately NOT a general
 * crypto API — there is no {@code encrypt(record)} / {@code decrypt(record)} method,
 * by design (see "the shape enforces the discipline" below).
 *
 * <p>Design-research artifact (KMS-SPI). NOT production code. Realises the
 * encryption-at-rest research's key-management axis: the built-in
 * {@link LocalDerivedKmsProvider} is Option B-minimal (HKDF from the signing key);
 * cloud implementations are Option D (KMS auto-unseal). The data-plane cipher
 * (AES-256-GCM over the WAL/snapshot at the ADR-0042 seam) is a separate concern that
 * runs on the locally-derived data keys — never here.
 *
 * <h2>The lifecycle (the only three moments a provider is touched)</h2>
 * <ol>
 *   <li><b>provision (once, setup):</b> {@link #generateRootKey()} establishes a fresh
 *       per-node root key and its persistable {@link WrappedKey}.</li>
 *   <li><b>unseal (once per boot):</b> {@link #unwrap(WrappedKey)} reconstructs the
 *       {@link RootKey}. <b>This is the only runtime call,</b> and it happens once,
 *       before the node serves.</li>
 *   <li><b>rotate (rare, operator-driven):</b> {@link #wrap(RootKey)} re-seals the root
 *       key under a new KEK version (rewrap; no bulk data re-encryption).</li>
 * </ol>
 *
 * <h2>The shape enforces the §4 availability discipline — implementers MUST honour it</h2>
 * <ul>
 *   <li><b>KMS is OFF the per-operation path.</b> There is no per-record method to put
 *       it on. The only runtime call is the one-time boot {@link #unwrap}. A provider
 *       MUST NOT perform network/KMS I/O except inside
 *       {@code generateRootKey}/{@code wrap}/{@code unwrap}/{@code healthCheck}.</li>
 *   <li><b>A running node holds the root key for its lifetime.</b> The core unseals
 *       once, caches the {@link RootKey}, and <em>drops its reference to the
 *       provider</em>. There is therefore no live handle on which to call the KMS
 *       per-op — "KMS off the hot path" is structural, not merely documented.</li>
 *   <li><b>Fail-closed, never fall back.</b> If the provider is configured but
 *       {@link #unwrap} throws {@link KmsUnavailableException} at boot, the node
 *       refuses to start. It must never downgrade to no-encryption or silently switch
 *       providers.</li>
 *   <li><b>No interactive unseal.</b> Auto-unseal only; a provider MUST NOT block on
 *       human input (no Shamir-style prompt) on the config-store availability path.</li>
 * </ul>
 *
 * <p>Implementations need not be thread-safe: every method is called from the single
 * boot/rotation path, never concurrently with node serving.
 */
public interface KmsProvider extends AutoCloseable {

    /**
     * The provider discriminator, e.g. {@code "local"}, {@code "aws-kms"},
     * {@code "azure-keyvault"}, {@code "gcp-kms"}, {@code "vault-transit"},
     * {@code "pkcs11"}. Matches {@link KeyId#providerType()} and the
     * {@code configd.raft.encryption.kms.provider} selection key.
     */
    String type();

    /**
     * The active KEK identity + keyring term. Used for observability and to drive
     * rotation: when this changes (operator rotated the CMK), new writes adopt the new
     * term while old ciphertext stays readable under its embedded {@link KeyId}.
     * Analogous to Kubernetes KMS-v2's pollable {@code Status.key_id}.
     */
    KeyId currentKeyId();

    /**
     * Establishes a fresh per-node root key and its persistable wrapped form (setup /
     * first boot). For a KMS this is {@code GenerateDataKey} (returns plaintext + a
     * ciphertext blob); for {@code local} it is a deterministic HKDF derivation.
     *
     * @throws KmsUnavailableException if the KMS is configured but unreachable
     */
    Provisioned generateRootKey() throws KmsUnavailableException;

    /**
     * Re-seals {@code rootKey} under the current KEK version (rotation / rewrap). For a
     * KMS this is {@code Encrypt}; for {@code local} it records the derivation
     * descriptor. Does NOT re-encrypt any data — only the small root key is rewrapped.
     *
     * @throws KmsUnavailableException if the KMS is configured but unreachable
     */
    WrappedKey wrap(RootKey rootKey) throws KmsUnavailableException;

    /**
     * <b>The boot-time unseal — the one runtime call.</b> Reconstructs the live
     * {@link RootKey} from its persisted {@link WrappedKey}. For a KMS this is a single
     * {@code Decrypt}; for {@code local} it re-derives from the signing key. Called
     * exactly once per node lifetime, before the node serves; the returned
     * {@link RootKey} is then cached by the core for the node's lifetime.
     *
     * @throws KmsUnavailableException if the KMS is configured but unreachable →
     *                                 the node fails closed (refuses to start)
     */
    RootKey unwrap(WrappedKey wrapped) throws KmsUnavailableException;

    /**
     * Optional boot-time reachability probe. The authoritative reachability test is the
     * {@link #unwrap} {@code Decrypt} itself; this exists for a clearer pre-flight error.
     * Default: no-op (the built-in {@code local} provider has nothing to probe).
     *
     * @throws KmsUnavailableException if the KMS is configured but unreachable
     */
    default void healthCheck() throws KmsUnavailableException {
        // no remote dependency by default
    }

    /** Releases any provider client (e.g. the KMS SDK client). Not the root key. */
    @Override
    default void close() {
        // no-op by default
    }

    /**
     * The result of {@link #generateRootKey()}: the live {@link RootKey} (to use then
     * wipe) and the {@link WrappedKey} (to persist). The caller persists
     * {@link #wrapped()} and consumes {@link #rootKey()} inside a try-with-resources.
     */
    record Provisioned(RootKey rootKey, WrappedKey wrapped) {
        public Provisioned {
            if (rootKey == null || wrapped == null) {
                throw new NullPointerException("rootKey/wrapped");
            }
        }
    }
}
