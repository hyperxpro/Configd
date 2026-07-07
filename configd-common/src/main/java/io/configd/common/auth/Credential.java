package io.configd.common.auth;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;

/**
 * The transport-abstract material an {@link Authenticator} verifies to establish identity - an mTLS peer
 * certificate chain, a bearer/JWT token, or HTTP Basic user+password. It is a closed (sealed) set because
 * a credential is a wire/transport concern, not a provider-SDK concern: a remote provider (OIDC, LDAP,
 * cloud-IAM) consumes one of these existing shapes rather than inventing its own (OIDC and Kubernetes
 * token-review both ride {@link BearerToken}). Adding a genuinely new single-shot shape (e.g. signed
 * headers) is therefore a small, versioned core addition, exactly like adding a wire frame - not a
 * per-provider fork.
 *
 * <p>Every case redacts in {@code toString} so a credential never reaches a log or audit line.
 *
 * <p><b>Not covered (by design):</b> multi-leg challenge-response mechanisms (Kerberos/SPNEGO, SCRAM/SASL,
 * WebAuthn) and SAML's redirect flow are not expressible as a one-shot {@code authenticate(Credential)};
 * their gap is the interaction model, so they are a deferred interface-level extension, not a missing case
 * here. Signed-header schemes (a {@code Headers} case) are the most likely next single-shot addition.
 */
public sealed interface Credential
        permits Credential.ClientCertificate, Credential.BearerToken, Credential.BasicCredential {

    /**
     * Best-effort wipe of any in-memory secret material once verification is done: zeroes the Basic
     * password {@code char[]} so it does not linger on the heap. A no-op for the other cases (a
     * {@link BearerToken} is a String, which the JVM cannot wipe in place; an mTLS chain carries no
     * secret). Idempotent and safe to call in a {@code finally}.
     */
    default void wipeSecret() {
        if (this instanceof BasicCredential b) {
            b.wipe();
        }
    }

    /**
     * An ALREADY-VERIFIED mTLS peer certificate chain (the TLS stack ran client-cert verification). The
     * {@link Authenticator} reads identity off it (Subject DN / SAN); it does NOT re-validate the chain -
     * verification is the transport's job, and feeding an unverified chain here is a wiring bug.
     */
    record ClientCertificate(List<X509Certificate> chain) implements Credential {
        public ClientCertificate {
            Objects.requireNonNull(chain, "chain");
            chain = List.copyOf(chain);
        }

        /** The leaf (end-entity) certificate - the peer's own certificate - or {@code null} if the chain is empty. */
        public X509Certificate leaf() {
            return chain.isEmpty() ? null : chain.get(0);
        }

        @Override
        public String toString() {
            return "ClientCertificate[chainLength=" + chain.size() + "]";
        }
    }

    /** An {@code Authorization: Bearer <token>} value (opaque token or a JWT). Redacted in {@code toString}. */
    record BearerToken(String token) implements Credential {
        public BearerToken {
            Objects.requireNonNull(token, "token");
        }

        @Override
        public String toString() {
            return "BearerToken[length=" + token.length() + "]";
        }
    }

    /**
     * HTTP Basic (RFC 7617) username + password. The secret is a {@code char[]} so a caller can wipe it
     * after verification rather than leaving it interned in the String pool. Redacted in {@code toString}.
     */
    record BasicCredential(String username, char[] password) implements Credential {
        public BasicCredential {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }

        /** Overwrites the password {@code char[]} with NULs. Call after verification via {@link #wipeSecret()}. */
        void wipe() {
            java.util.Arrays.fill(password, '\0');
        }

        @Override
        public String toString() {
            return "BasicCredential[username=" + username + ", passwordLength=" + password.length + "]";
        }
    }
}
