package io.configd.client;

import io.configd.common.auth.Credential;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Supplies the credential the edge {@link io.configd.distribution.wire.EdgeFrame.Auth} frame presents, and
 * mints a <b>fresh</b> one for a proactive {@code REFRESH_AUTH} (§03 AU5-6). It is a supplier, not a value, so
 * a short-lived OIDC/JWT token can be re-minted before it expires rather than pinned for the client's life.
 *
 * <p>Each {@link #provide()} returns the current credential plus an <b>optional</b> expiry instant. When the
 * expiry is present, the client schedules a proactive {@code REFRESH_AUTH} in the lead-time window before it
 * (so the session is never cut off by {@code CREDENTIAL_EXPIRED}); when absent, the client presents the
 * credential and relies on the server's {@code CREDENTIAL_EXPIRED} close to trigger a reconnect (a static
 * token with an unknown server-side TTL).
 *
 * <p>A driver <b>MUST</b> treat a bearer token as opaque (§03 AU2-2): this type never parses it. A client
 * certificate is <b>not</b> a {@code CredentialSource} — it authenticates at the TLS handshake and is never
 * framed (§06 F6A-1); use {@link #none()} on an mTLS edge.
 */
public interface CredentialSource {

    /** A freshly-provided credential and its optional expiry. */
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

    /** Mints the credential to present now (for the initial {@code AUTH} or a proactive {@code REFRESH_AUTH}). */
    Provided provide();

    /**
     * No framed credential — the mTLS or no-auth posture. The edge client sends no {@code AUTH} frame; on an
     * mTLS edge the client certificate at the handshake is the credential.
     */
    static CredentialSource none() {
        return null; // an absent CredentialSource is the mTLS / no-auth posture; see ConfigdClientConfig
    }

    /** A static bearer token with no known expiry (relies on the server's CREDENTIAL_EXPIRED to reconnect). */
    static CredentialSource staticBearer(String token) {
        Objects.requireNonNull(token, "token");
        return () -> new Provided(new Credential.BearerToken(token), Optional.empty());
    }

    /**
     * A static bearer token with a known expiry {@code instant} — the client schedules a proactive
     * {@code REFRESH_AUTH} before it. Re-minting the same token before expiry extends the session (§03 AU4-6).
     */
    static CredentialSource staticBearer(String token, Instant expiresAt) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
        return () -> new Provided(new Credential.BearerToken(token), Optional.of(expiresAt));
    }

    /** HTTP Basic (RFC 7617), no known expiry. The password {@code char[]} is copied per {@link #provide()}. */
    static CredentialSource basic(String username, char[] password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        char[] snapshot = password.clone();
        return () -> new Provided(new Credential.BasicCredential(username, snapshot.clone()), Optional.empty());
    }

    /**
     * A caller-driven source — the deployment mints the credential (e.g. reads the current OIDC access token
     * and its {@code exp}) on each call. This is how a JWT client refreshes: the supplier returns the fresh
     * token and its expiry, and the client refreshes within the lead-time window.
     */
    static CredentialSource supplier(Supplier<Provided> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return supplier::get;
    }
}
