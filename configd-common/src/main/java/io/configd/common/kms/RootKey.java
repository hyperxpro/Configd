package io.configd.common.kms;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.Destroyable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * The live per-node root key handle - the ONE place live key material exists in
 * the at-rest encryption stack. Unsealed once at boot by a {@link KmsProvider},
 * cached for the node's lifetime, and used only to derive per-segment data keys.
 *
 * <h2>Why not {@code byte[]} or {@link SecretKeySpec}</h2>
 * A root key must be promptly and reliably zeroed (a stray copy lands in heap
 * dumps, core dumps, or swap - CWE-316). The JCA's standard symmetric type does
 * not deliver that on this runtime: {@code SecretKeySpec.destroy()} inherits the
 * {@code Destroyable} default that <em>throws</em> {@code DestroyFailedException},
 * leaves {@code isDestroyed()} false, and {@code getEncoded()} still returns the
 * bytes afterwards ([JDK-8160206], unimplemented as of JDK 25). This class is
 * therefore <em>not</em> a {@code SecretKeySpec}; it owns a private {@code byte[]}
 * that it <em>actually</em> zeroes on {@link #destroy()}.
 *
 * <h2>Zeroization reality (honest boundary)</h2>
 * {@link #destroy()} zeroes the backing array, and {@link #withMaterial} zeroes
 * the transient clone it hands out. This defends heap/core dumps and swap of a
 * <em>stopped or crashed</em> process. Two residuals no JVM key type can close:
 * <ul>
 *   <li>a copying GC may leave a stale copy of the array elsewhere on the heap
 *       between the last write and the wipe; and</li>
 *   <li>{@link #toSecretKey(String)} materialises a {@code SecretKeySpec} for the
 *       JCA {@code Cipher}/HKDF consumer whose bytes CANNOT be wiped (JDK-8160206)
 *       - so it must be transient, never retained.</li>
 * </ul>
 * Wiping is best-effort against a live-process adversary; it does not defend an
 * attacker reading a <em>running</em> node's RAM (the threat boundary every
 * at-rest scheme draws). Prefer deriving short-lived per-segment DEKs over using
 * the root key directly, so live material is scoped and transient.
 */
public final class RootKey implements AutoCloseable, Destroyable {

    private final byte[] material; // owned; zeroed on destroy()/close()
    private final KeyId keyId;     // non-secret identity (loggable)
    private volatile boolean destroyed;

    /**
     * Wraps freshly-unsealed key material. The array is defensively copied and
     * owned by this handle; the caller SHOULD zero its own copy after this call.
     *
     * @param material the live root-key bytes (typically 32 for AES-256), non-null
     * @param keyId    the non-secret identity of the KEK that produced it
     */
    public RootKey(byte[] material, KeyId keyId) {
        Objects.requireNonNull(material, "material");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.material = material.clone();
    }

    /** The non-secret identity of the KEK that produced this root key. */
    public KeyId keyId() {
        return keyId;
    }

    /** The key length in bytes (non-secret). */
    public int length() {
        ensureLive();
        return material.length;
    }

    /**
     * Scoped, conspicuous access to a CLONE of the raw key bytes (Tink-style: the
     * live backing array never escapes). The clone is zeroed after {@code use}
     * returns - on every path, including exceptions - so a consumer such as an
     * HKDF derivation gets the bytes for exactly the duration of the call and no
     * stray copy outlives it.
     *
     * @param use a function invoked with a transient clone of the key bytes
     * @return the function's result
     */
    public <R> R withMaterial(Function<byte[], R> use) {
        ensureLive();
        byte[] copy = material.clone();
        try {
            return use.apply(copy);
        } finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    /**
     * A TRANSIENT JCA bridge for a {@code Cipher}/HKDF consumer. The returned
     * {@link SecretKeySpec} makes an independent, un-wipeable heap copy of the key
     * (JDK-8160206), so it MUST NOT be retained beyond the immediate crypto call.
     * Prefer {@link #withMaterial} where a {@code byte[]} suffices.
     *
     * @param algorithm the JCA algorithm name (e.g. {@code "AES"})
     * @return a short-lived {@link SecretKey} view of this root key
     */
    public SecretKey toSecretKey(String algorithm) {
        ensureLive();
        return new SecretKeySpec(material, algorithm);
    }

    @Override
    public void destroy() {
        // Idempotent and never throws (unlike the Destroyable default). Genuinely
        // zeroes the backing array - the whole reason this is not a SecretKeySpec.
        Arrays.fill(material, (byte) 0);
        destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    /** try-with-resources scopes the live key's lifetime; delegates to {@link #destroy()}. */
    @Override
    public void close() {
        destroy();
    }

    @Override
    public String toString() {
        // Redacted: identity + destroyed flag, NEVER the bytes.
        return "RootKey[keyId=" + keyId + ", destroyed=" + destroyed + "]";
    }

    private void ensureLive() {
        if (destroyed) {
            throw new IllegalStateException("root key has been destroyed (use-after-wipe): " + keyId);
        }
    }
}
