package io.configd.kms;

import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.Destroyable;

/**
 * A <b>live</b> per-node root key, returned by {@link KmsProvider#unwrap}. This is
 * the sensitive type: it holds plaintext key material and therefore is
 * <b>{@link AutoCloseable} + {@link Destroyable}, is wiped on {@link #close()},
 * and is never logged.</b>
 *
 * <p>Design-research artifact (KMS-SPI). The wipe / lifecycle / redaction logic is
 * real (it is memory hygiene, not cryptography); there is no crypto here.
 *
 * <p><b>Why this is NOT a {@code javax.crypto.SecretKeySpec} (the §3 crux).</b>
 * Empirically verified on this project's runtime (Corretto JDK 25):
 * {@code SecretKeySpec} does <em>not</em> override {@code destroy()}, so it inherits
 * the {@link Destroyable} default, which <em>throws</em> {@code DestroyFailedException}
 * and leaves the key fully readable via {@code getEncoded()} afterwards
 * (JDK-8160206, open since 2016). A key type that cannot be wiped is the wrong type
 * for a root key. {@code RootKey} owns its own {@code byte[]} and actually zeroes it.
 *
 * <p><b>Intended use — structural "wipe after use":</b>
 * <pre>{@code
 *   try (RootKey root = provider.unwrap(wrapped)) {     // ONE boot-time KMS call
 *       deriveAndInstallSegmentKeys(root);              // derive DEKs locally
 *   }                                                    // root wiped here, deterministically
 * }</pre>
 * try-with-resources makes "wipe after use" a property of the code's shape, not a
 * thing each caller must remember.
 *
 * <p><b>The JCA bridge is honest about its limit.</b> {@link #toSecretKey} exists
 * because {@code javax.crypto.Cipher}/{@code Mac}/{@code KDF} consume a
 * {@link SecretKey}. The returned {@code SecretKeySpec} <em>cannot be wiped</em> (see
 * above), so the contract is: use it transiently, never retain it, let it be
 * collected — while {@code RootKey}'s own bytes remain authoritative and ARE wiped on
 * {@link #close()}. This is the best the JVM/JCA allows today and is named rather
 * than hidden.
 */
public final class RootKey implements AutoCloseable, Destroyable {

    private final byte[] material;   // owned; wiped on close()
    private final KeyId keyId;       // non-secret identity of the KEK that produced it
    private volatile boolean destroyed;

    /**
     * Takes ownership of {@code material} (NOT defensively copied — the caller, a
     * provider's {@code unwrap}, must hand over the only reference and keep none).
     */
    public RootKey(byte[] material, KeyId keyId) {
        if (material == null || keyId == null) {
            throw new NullPointerException("material/keyId");
        }
        if (material.length < 16) {
            throw new IllegalArgumentException("root key too short: " + material.length + " bytes");
        }
        this.material = material;
        this.keyId = keyId;
    }

    /** The non-secret identity of the KEK that unsealed this root key. Loggable. */
    public KeyId keyId() {
        return keyId;
    }

    /** Length in bytes (non-secret), without exposing the material. */
    public int length() {
        ensureLive();
        return material.length;
    }

    /**
     * Materialises a transient JCA {@link SecretKey} for a {@code Cipher}/{@code Mac}/
     * {@code KDF}. <b>Do not retain the result</b> — it is an un-wipeable
     * {@code SecretKeySpec} (JDK-8160206); this {@code RootKey} stays authoritative and
     * is wiped on {@link #close()}. Prefer deriving short-lived per-segment data keys
     * over using the root key directly.
     */
    public SecretKey toSecretKey(String algorithm) {
        ensureLive();
        return new SecretKeySpec(material, algorithm);
    }

    /**
     * Runs {@code use} with a read-only view of the raw material and returns its
     * result, without ever handing out a long-lived reference to the array. The view
     * is valid only for the duration of the call. (Closest the JVM gets to Tink's
     * token-gated {@code SecretKeyAccess}: raw access is possible but deliberately
     * scoped and conspicuous at the call site.)
     */
    public <R> R withMaterial(java.util.function.Function<byte[], R> use) {
        ensureLive();
        // Hand a clone so `use` cannot stash the live backing array; the clone is the
        // caller's to wipe. (A real impl may pass the live array under a stricter
        // access token — see key-material-types.md §"scoped access".)
        return use.apply(material.clone());
    }

    /** Deterministically zeroes the key material. Idempotent. Never throws. */
    @Override
    public void destroy() {
        Arrays.fill(material, (byte) 0);
        destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    /** {@link AutoCloseable} — wipes the key (so try-with-resources scopes its lifetime). */
    @Override
    public void close() {
        destroy();
    }

    private void ensureLive() {
        if (destroyed) {
            throw new IllegalStateException("RootKey already destroyed (use-after-wipe)");
        }
    }

    /** Redacted — identity only, never the bytes (no accidental leak via logs/exceptions). */
    @Override
    public String toString() {
        return "RootKey[keyId=" + keyId + ", destroyed=" + destroyed + ']';
    }
}
