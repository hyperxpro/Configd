package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

/**
 * Built-in factory for the {@code bearer} (static shared-secret) mode. Reads {@code configd.auth.bearer.token}
 * (required, fail-closed if absent), {@code configd.auth.bearer.principal} (default {@code "root"}), and
 * {@code configd.auth.bearer.roles} (default none).
 */
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
