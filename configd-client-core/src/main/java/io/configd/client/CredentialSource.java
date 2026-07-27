package io.configd.client;

import io.configd.common.auth.Credential;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Supplies credentials for Auth frames; can re-mint fresh credentials before expiry (OIDC/JWT, not static).
 * Each provide() returns credential + optional expiry. Expiry present: client schedules proactive REFRESH_AUTH.
 * Expiry absent: client relies on server CREDENTIAL_EXPIRED close. Bearer tokens are opaque.
 */
public interface CredentialSource {

    record Provided(Credential credential, Optional<Instant> expiresAt) {
        public Provided {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!(credential instanceof Credential.BearerToken || credential instanceof Credential.BasicCredential)) {
                throw new IllegalArgumentException(
                        "a framed credential must be a BearerToken or BasicCredential, not "
                                + credential.getClass().getSimpleName()
                                + " (a client certificate authenticates at the handshake, not in a frame)");
            }
        }
    }

    Provided provide();

    /**
     * No framed credential — mTLS or no-auth posture: client certificate at handshake is the credential.
     */
    static CredentialSource none() {
        return null; // an absent CredentialSource is the mTLS / no-auth posture; see ConfigdClientConfig
    }

    static CredentialSource staticBearer(String token) {
        Objects.requireNonNull(token, "token");
        return () -> new Provided(new Credential.BearerToken(token), Optional.empty());
    }

    /**
     * Static bearer token with known expiry: client schedules proactive REFRESH_AUTH before expiry.
     */
    static CredentialSource staticBearer(String token, Instant expiresAt) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
        return () -> new Provided(new Credential.BearerToken(token), Optional.of(expiresAt));
    }

    static CredentialSource basic(String username, char[] password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        char[] snapshot = password.clone();
        return () -> new Provided(new Credential.BasicCredential(username, snapshot.clone()), Optional.empty());
    }

    /**
     * Caller-driven source: deployment mints credential on each call (e.g., read OIDC token + exp).
     * How JWT clients refresh within the lead-time window.
     */
    static CredentialSource supplier(Supplier<Provided> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return supplier::get;
    }
}
