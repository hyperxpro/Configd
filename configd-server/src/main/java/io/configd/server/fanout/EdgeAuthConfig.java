package io.configd.server.fanout;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.Credential;
import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.common.auth.MtlsAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Set;


public record EdgeAuthConfig(AuthenticatorChain chain, int preAuthMaxFrameBytes,
                             int maxAuthTokenBytes, long defaultTokenTtlMs, CredentialExpiryPolicy expiryPolicy) {

    
    public EdgeAuthConfig(AuthenticatorChain chain, int preAuthMaxFrameBytes, int maxAuthTokenBytes,
                          long defaultTokenTtlMs) {
        this(chain, preAuthMaxFrameBytes, maxAuthTokenBytes, defaultTokenTtlMs, CredentialExpiryPolicy.DEFAULTS);
    }

    
    static final int MAX_BASIC_USERNAME_BYTES = 256;
    
    static final int MAX_BASIC_PASSWORD_CHARS = 1024;

    
    private static final MtlsAuthenticator EDGE_MTLS = new MtlsAuthenticator(Set.of());

    public EdgeAuthConfig {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(expiryPolicy, "expiryPolicy");
        if (preAuthMaxFrameBytes <= 0) {
            throw new IllegalArgumentException("preAuthMaxFrameBytes must be positive: " + preAuthMaxFrameBytes);
        }
        if (maxAuthTokenBytes <= 0) {
            throw new IllegalArgumentException("maxAuthTokenBytes must be positive: " + maxAuthTokenBytes);
        }
        if (defaultTokenTtlMs <= 0) {
            throw new IllegalArgumentException("defaultTokenTtlMs must be positive: " + defaultTokenTtlMs);
        }
    }

    
    AuthResult resolveFrameCredential(Credential credential) {
        return chain.resolve(credential);
    }

    
    boolean mtlsConfigured() {
        return chain.providerTypes().contains("mtls");
    }

    
    AuthResult authenticateClientCertificate(List<X509Certificate> verifiedChain) {
        if (!mtlsConfigured()) {
            // Defense in depth: a presented cert must not auto-authenticate on a token-only edge (that
            // would admit any trust-store cert). Callers already gate on mtlsConfigured(); this fails
            // closed if a future caller forgets.
            return new AuthResult.Denied(io.configd.common.auth.DenyReason.NOT_THIS_AUTHENTICATOR,
                    "mTLS is not a configured edge authenticator");
        }
        return EDGE_MTLS.authenticate(new Credential.ClientCertificate(verifiedChain));
    }

    
    long staticTokenCloseDeadlineMillis(long nowMillis) {
        return nowMillis + defaultTokenTtlMs;
    }

    
    long tokenCloseDeadlineMillis(AuthResult.Authenticated authenticated, long nowMillis) {
        long credentialExpiresAtMillis = authenticated.credentialExpiresAtMillis();
        if (credentialExpiresAtMillis == AuthResult.NO_EXPIRY) {
            return staticTokenCloseDeadlineMillis(nowMillis);
        }
        return expiryPolicy.serverCloseDeadlineMillis(credentialExpiresAtMillis);
    }

    
    boolean credentialWithinCaps(Credential credential) {
        return switch (credential) {
            case Credential.BearerToken t ->
                    t.token().getBytes(StandardCharsets.UTF_8).length <= maxAuthTokenBytes;
            case Credential.BasicCredential b ->
                    b.username().getBytes(StandardCharsets.UTF_8).length <= MAX_BASIC_USERNAME_BYTES
                            && b.password().length <= MAX_BASIC_PASSWORD_CHARS;
            case Credential.ClientCertificate ignored -> false;
        };
    }
}
