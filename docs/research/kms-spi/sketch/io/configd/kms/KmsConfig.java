package io.configd.kms;

import java.util.Optional;

/**
 * Read-only access to the {@code configd.raft.encryption.kms.*} configuration a
 * provider factory needs (e.g. {@code ...kms.aws.cmkArn}, {@code ...kms.aws.region}).
 * Backed in-tree by system properties + {@code CONFIGD_*} env fallbacks, matching the
 * existing {@code configd.*} tunable convention.
 *
 * <p>Design-research artifact (KMS-SPI). NOT production code.
 *
 * <p>Deliberately does NOT expose the cluster signing key: only the built-in
 * {@link LocalDerivedKmsProvider} needs it as IKM, and it is wired in-core with direct
 * access. Third-party factories receive configuration only — never key material.
 */
public interface KmsConfig {

    /** The value for {@code key}, or empty if unset. */
    Optional<String> get(String key);

    /** The value for {@code key}, or {@code orElse} if unset. */
    default String get(String key, String orElse) {
        return get(key).orElse(orElse);
    }

    /** Required value, or an {@link IllegalStateException} naming the missing key. */
    default String require(String key) {
        return get(key).orElseThrow(() -> new IllegalStateException(
                "required KMS config key is unset: " + key));
    }
}
