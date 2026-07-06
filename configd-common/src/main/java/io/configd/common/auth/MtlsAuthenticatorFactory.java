package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

import java.util.Set;

/**
 * Built-in factory for the {@code mtls} (verified client certificate) mode. Reads
 * {@code configd.auth.mtls.roles} - the roles granted to any verified certificate (default none, i.e.
 * identity only, matching the edge's current behavior).
 */
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
