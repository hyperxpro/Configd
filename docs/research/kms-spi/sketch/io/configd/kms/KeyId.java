package io.configd.kms;

import java.util.Objects;

/**
 * Identity of the <em>key-encryption key</em> (KEK / master key) that sealed a
 * {@link WrappedKey}, plus the keyring {@code version} for rotation. This is
 * <b>non-secret metadata</b> — it is safe to persist alongside the wrapped key
 * and safe to log.
 *
 * <p>Design-research artifact (KMS-SPI). NOT production code.
 *
 * <p><b>Why a value type, not a {@code String}:</b> a {@code KeyId} is the
 * self-describing tag that lets any node / new leader / restoring node select the
 * correct key with zero coordination (prior art: Vault's 4-byte key <em>term</em>,
 * Cockroach's per-file {@code key_id}, K8s KMS-v2's {@code key_id}). Making it a
 * closed, typed carrier — rather than an untyped string smuggled around — is what
 * keeps rotation forward-compatible: a reader reads the {@code KeyId} first, then
 * selects the key version.
 *
 * @param providerType the {@link KmsProvider#type()} that owns the KEK
 *                     (e.g. {@code "local"}, {@code "aws-kms"}). Discriminates the
 *                     custody mechanism so the right provider interprets the blob.
 * @param reference    the provider-scoped KEK reference — a CMK ARN, a Key Vault key
 *                     URL, a Vault transit key name, or (for {@code local}) the
 *                     signing-key id. Non-secret by construction (a KMS key id is an
 *                     identifier, never the key material).
 * @param version      the keyring term / rotation generation. The active term
 *                     encrypts new writes; older terms are retained so existing
 *                     ciphertext (which embeds its {@code KeyId}) stays readable —
 *                     this is what makes master-key rotation O(1) (a rewrap, not a
 *                     bulk re-encrypt).
 */
public record KeyId(String providerType, String reference, int version) {

    public KeyId {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(reference, "reference");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0: " + version);
        }
    }

    /** Convenience for the common single-version case. */
    public static KeyId of(String providerType, String reference) {
        return new KeyId(providerType, reference, 1);
    }

    /** A loggable, stable identity string {@code providerType:reference#version}. */
    @Override
    public String toString() {
        return providerType + ':' + reference + '#' + version;
    }
}
