package io.configd.common.kms;

import java.util.Objects;

/**
 * The non-secret identity of a KEK (key-encryption key) plus its keyring term.
 * <p>
 * A {@code KeyId} makes every {@link WrappedKey} self-describing, so any node -
 * or a future leader, or a restoring node - selects the right key on read with
 * zero coordination (the Vault term / Cockroach {@code key_id} / K8s
 * {@code key_id} self-describing-key lesson). The {@code version} carries the
 * keyring <em>term</em> for O(1) rotation: new writes use the current term, old
 * terms are retained so old data still decrypts.
 * <p>
 * This type is non-secret: it is safe to persist and safe to log. It carries no
 * key material.
 *
 * @param providerType the KMS provider discriminator ({@code "local"}, {@code "aws-kms"}, ...)
 * @param reference     the provider-specific KEK reference (a signing-key id for
 *                      {@code local}; a CMK ARN for a cloud KMS)
 * @param version       the keyring term (rotation generation), {@code >= 1}
 */
public record KeyId(String providerType, String reference, int version) {

    public KeyId {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(reference, "reference");
        if (version < 1) {
            throw new IllegalArgumentException("keyring term (version) must be >= 1, was " + version);
        }
    }

    @Override
    public String toString() {
        // Non-secret identity: fully loggable, in a "provider:reference#term" shape
        // operators can grep in logs.
        return providerType + ":" + reference + "#" + version;
    }
}
