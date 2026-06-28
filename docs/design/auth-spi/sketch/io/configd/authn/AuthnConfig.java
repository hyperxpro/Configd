package io.configd.authn;

import java.util.Optional;

/**
 * The {@code configd.authn.*} configuration, as a tiny functional view (mirrors the KMS sketch's
 * {@code KmsConfig}). Keeps the sketch standalone and lets a real wiring back it with the server's config.
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 */
@FunctionalInterface
public interface AuthnConfig {

    Optional<String> get(String key);

    default String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }
}
