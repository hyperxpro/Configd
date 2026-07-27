package io.configd.kms.vault;

import io.configd.common.config.ConfigSource;
import io.configd.common.kms.KmsBootContext;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsProviderFactory;

public final class VaultKmsProviderFactory implements KmsProviderFactory {

    @Override
    public String type() {
        return VaultTransitKmsProvider.TYPE;
    }

    @Override
    public KmsProvider create(ConfigSource cfg, KmsBootContext ctx) {
        return new VaultTransitKmsProvider(VaultConfig.parse(cfg, ctx));
    }
}
