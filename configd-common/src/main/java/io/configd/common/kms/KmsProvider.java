package io.configd.common.kms;

/**
 * The KMS-provider SPI: custody and unseal of ONE per-node root key.
 *
 * <h2>The narrow job (what it deliberately is NOT)</h2>
 * The at-rest encryption design keeps the KMS <b>off the hot path</b>. A provider
 * does exactly one thing: <b>wrap</b> (seal a root key - setup/rotation) and
 * <b>unwrap</b> (unseal it - boot), plus key identity/versioning for rotation. It
 * exposes <b>no per-record {@code encrypt}/{@code decrypt}</b>: that omission is
 * load-bearing. A per-record method is exactly how an implementer would put a KMS
 * round-trip on the write/replay path and couple write-availability to an external
 * service. The data-plane cipher is the core's job, run on locally-derived
 * per-segment DEKs; the provider never sees a config record.
 *
 * <pre>
 *   KEK   (master: a cloud CMK/HSM, or - for {@code local} - the cluster signing key)
 *    |    the provider's domain: wrap / unwrap ONE per-node root key
 *    v
 *  RootKey (per-node, unsealed ONCE at boot, cached for the node's lifetime)
 *    |    the encryption layer's domain (NOT this SPI): HKDF per-segment DEKs locally
 *    v
 *   DEKs  (per-segment -> AES-256-GCM over WAL records / snapshot at the at-rest seam)
 * </pre>
 *
 * <h2>Availability + fail-closed contract (REQUIREMENTS on every implementer)</h2>
 * <ul>
 *   <li><b>R1 - KMS only at boot/setup:</b> a provider MUST NOT perform network/KMS
 *       I/O outside {@link #generateRootKey()}, {@link #wrap(RootKey)},
 *       {@link #unwrap(WrappedKey)} and {@link #healthCheck()}. There is no
 *       per-record method to put a KMS call on.</li>
 *   <li><b>R2 - lifetime cache:</b> the core calls {@link #unwrap} once, caches the
 *       {@link RootKey}, drops the provider reference and {@link #close()}s it. After
 *       unseal there is no live provider handle a per-op call could reach - "KMS off
 *       the hot path" is structural, not merely documented.</li>
 *   <li><b>R3 - fail closed, never fall back:</b> if a configured provider throws
 *       {@link KmsUnavailableException} at boot, the node REFUSES TO START. It MUST
 *       NOT fall back to no encryption (that silently voids the at-rest guarantee)
 *       nor silently to a different provider.</li>
 *   <li><b>R4 - configured+unreachable-at-boot vs running-blip:</b> unreachable at
 *       boot -> fail closed (R3); a blip while already running is invisible because
 *       the provider is never re-invoked (R2).</li>
 *   <li><b>R5 - no interactive unseal:</b> auto-unseal only; a provider MUST NOT
 *       block on human input. A config store is on the availability path and cannot
 *       sit sealed waiting for an operator.</li>
 * </ul>
 *
 * <p>The interface is deliberately NOT sealed: the whole point is extensibility to
 * any KMS without forking the core. The built-in {@link LocalDerivedKmsProvider}
 * satisfies R1-R5 trivially (nothing external to be unavailable).
 */
public interface KmsProvider extends AutoCloseable {

    /** Discovery discriminator: {@code "local"}, {@code "aws-kms"}, ... */
    String type();

    KeyId currentKeyId();

    /**
     * Generates (or, for {@code local}, deterministically derives) a fresh per-node
     * root key and its sealed carrier. Called ONCE at provisioning.
     *
     * @return the live {@link RootKey} plus the {@link WrappedKey} to persist
     * @throws KmsUnavailableException if the backend is unreachable (fail closed)
     */
    Provisioned generateRootKey() throws KmsUnavailableException;

    /**
     * Re-seals a root key under the current KEK (rotation / rewrap - rare). For
     * {@code local} this returns the (non-secret) re-derivation descriptor.
     *
     * @throws KmsUnavailableException if the backend is unreachable (fail closed)
     */
    WrappedKey wrap(RootKey rootKey) throws KmsUnavailableException;

    /**
     * Unseals a persisted {@link WrappedKey} into a live {@link RootKey}. This is
     * the ONE runtime call (once, at boot).
     *
     * @throws KmsUnavailableException if the backend is unreachable - the caller
     *                                 MUST fail closed (refuse to start), never fall back
     */
    RootKey unwrap(WrappedKey wrapped) throws KmsUnavailableException;

    default void healthCheck() throws KmsUnavailableException {
    }

    /** Releases the KMS client (NOT the root key, which the core now owns). */
    @Override
    default void close() {
    }

    record Provisioned(RootKey rootKey, WrappedKey wrapped) {}
}
