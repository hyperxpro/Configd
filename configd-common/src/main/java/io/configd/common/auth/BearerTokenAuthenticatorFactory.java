package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

public final class BearerTokenAuthenticatorFactory implements AuthenticatorFactory {

    @Override
    public String type() {
        return "bearer";
    }

    @Override
    public Authenticator create(ConfigSource cfg) {
        String token = cfg.getRequiredString("configd.auth.bearer.token");
        String principal = cfg.getString("configd.auth.bearer.principal").orElse("root");
        return new BearerTokenAuthenticator(token, principal, java.util.Set.copyOf(cfg.getList("configd.auth.bearer.roles")));
    }
}
