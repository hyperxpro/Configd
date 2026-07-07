package io.configd.common.kms;

import java.util.Objects;

/**
 * The non-secret, boot-time context a {@link KmsProviderFactory} needs to construct a provider that
 * is not derivable from {@link io.configd.common.config.ConfigSource} alone.
 *
 * <p>Today that is exactly one thing: the {@code nodeId} an external custodian binds the seal to as
 * AEAD associated-data (so a sealed-root blob copied to a different node fails to unseal). It is the
 * loggable node/cluster identity, never key material. A provider MAY let the operator override the
 * bound context via its own config key (e.g. {@code configd.kms.vault.aadContext}); this is the
 * default when they do not.
 *
 * <p>The built-in {@code local} provider needs no context (its custody secret is the already-loaded
 * signing key), so it does not go through a factory at all - this carrier exists for the external
 * providers the SPI is genuinely wired for.
 *
 * @param nodeId the non-secret node/cluster identity to bind the seal to (default AEAD context)
 */
public record KmsBootContext(String nodeId) {

    public KmsBootContext {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
    }
}
