package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

import java.util.Set;

public final class MtlsAuthenticatorFactory implements AuthenticatorFactory {

    @Override
    public String type() {
        return "mtls";
    }

    @Override
    public Authenticator create(ConfigSource cfg) {
        return new MtlsAuthenticator(Set.copyOf(cfg.getList("configd.auth.mtls.roles")));
    }
}
