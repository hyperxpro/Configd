package io.configd.kms.vault;

import io.configd.common.config.ConfigSource;
import io.configd.common.kms.KmsBootContext;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsProviderFactory;

/**
 * The {@link java.util.ServiceLoader}-discovered factory for the Vault Transit KMS provider. It advertises
 * {@code type() == "vault-transit"}; selecting {@code configd.raft.encryption.kms.provider=vault-transit}
 * while this module is on the classpath registers the provider, and selecting it WITHOUT this module is a
 * fail-loud startup error in the boot path - never a silent downgrade to no encryption. The core never
 * compile-depends on this module; the Vault client lives here only (and is itself dependency-free -
 * java.net.http).
 */
public final class VaultKmsProviderFactory implements KmsProviderFactory {

    @Override
    public String type() {
        return VaultTransitKmsProvider.TYPE;
    }

    @Override
    public KmsProvider create(ConfigSource cfg, KmsBootContext ctx) {
        // Parse + validate config eagerly so a misconfiguration fails the boot here (no KMS I/O yet); the
        // first Vault call is healthCheck()/unwrap() at the boot seam.
        return new VaultTransitKmsProvider(VaultConfig.parse(cfg, ctx));
    }
}
