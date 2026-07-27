package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class NoAuthAuthenticatorFactory implements AuthenticatorFactory {

    private static final Logger LOG = Logger.getLogger(NoAuthAuthenticatorFactory.class.getName());

    @Override
    public String type() {
        return "none";
    }

    @Override
    public Authenticator create(ConfigSource cfg) {
        LOG.log(Level.WARNING, "************************************************************");
        LOG.log(Level.WARNING, "AUTHENTICATION IS DISABLED (configd.auth.mode=none).");
        LOG.log(Level.WARNING, "Every caller is authenticated as the anonymous principal.");
        LOG.log(Level.WARNING, "Front this deployment with a trusted reverse proxy - do NOT expose it directly.");
        LOG.log(Level.WARNING, "************************************************************");
        return new NoAuthAuthenticator();
    }
}
