package io.configd.common.auth;

import java.util.Map;
import java.util.Set;

/**
 * The "authentication disabled" mode, modeled as an authenticator: it accepts ANY credential and returns a
 * fixed anonymous {@link Principal}. This is the etcd-style "front it with a reverse proxy - your problem"
 * posture. It is deliberately LOUD: its factory ({@link NoAuthAuthenticatorFactory}) emits a prominent
 * warning at boot so an operator can never enable it by accident and forget.
 *
 * <p>In the HTTP control plane the no-auth posture is realized by a separate open gate, byte-identical to
 * the auth-disabled default, rather than by resolving every request through this authenticator; this
 * type gives the SPI a uniform representation of the mode and is what a {@code none} provider chain builds.
 */
public final class NoAuthAuthenticator implements Authenticator {

    public static final String ANONYMOUS_ID = "anonymous";

    private static final Principal ANONYMOUS =
            new Principal(ANONYMOUS_ID, Set.of(), Map.of(), "none");

    @Override
    public String type() {
        return "none";
    }

    @Override
    public boolean canAttempt(Credential credential) {
        return true;
    }

    @Override
    public AuthResult authenticate(Credential credential) {
        return new AuthResult.Authenticated(ANONYMOUS);
    }
}
