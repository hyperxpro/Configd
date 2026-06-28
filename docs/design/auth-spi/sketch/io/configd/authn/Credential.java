package io.configd.authn;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The transport-abstract credential an {@link Authenticator} verifies. Sealed over the shapes that cover the
 * two built mechanisms AND every named future provider (authenticator-spi.md §3): a credential is a
 * <em>wire/transport</em> concern, not a provider-SDK concern, so the set is closed (exhaustive at the
 * transport layer) without blocking the open {@link Authenticator} interface that out-of-tree modules
 * implement. OIDC → {@link BearerToken}, LDAP → {@link Password}, Kubernetes TokenReview → {@link BearerToken},
 * cloud-IAM → {@link Headers}.
 *
 * <p>Design artifact (auth-SPI). NOT production code. The secret-bearing shapes redact in {@code toString}
 * (RA-3) — the built "never echo the token" rule made structural.
 */
public sealed interface Credential
        permits Credential.CertChain, Credential.BearerToken, Credential.Password, Credential.Headers {

    /**
     * mTLS — the <b>already-verified</b> peer chain. <b>NORMATIVE:</b> a {@code CertChain} MUST be constructed
     * only from a TLS session that <em>required and completed</em> client-certificate verification
     * (the built {@code setNeedClientAuth(true)} gate, built-reality.md §1.2). The {@link Authenticator} is
     * <b>NOT</b> the verification point — it reads identity off an already-trusted chain and does no path
     * validation. Feeding an unverified/self-asserted cert here is a wiring bug that defeats authentication
     * (authenticator-spi.md §7, RA-6). {@code toString} shows the chain length only.
     */
    record CertChain(List<X509Certificate> chain) implements Credential {
        public CertChain {
            chain = List.copyOf(chain);
        }
        @Override
        public String toString() {
            return "CertChain[" + chain.size() + " cert(s)]";
        }
    }

    /** A bearer / JWT token (opaque to the transport; redacted in toString). */
    record BearerToken(String token) implements Credential {
        public BearerToken {
            Objects.requireNonNull(token, "token");
        }
        @Override
        public String toString() {
            return "BearerToken[<redacted " + token.length() + " chars>]";
        }
    }

    /** Username + a wipeable secret (LDAP bind). The secret is {@code char[]}, never a {@code String}. */
    record Password(String username, char[] secret) implements Credential {
        public Password {
            Objects.requireNonNull(username, "username");
            secret = secret.clone();
        }
        @Override
        public String toString() {
            return "Password[user=" + username + ", secret=<redacted " + secret.length + " chars>]";
        }
    }

    /** A generic signed-header carrier (cloud-IAM / custom). Values redacted. */
    record Headers(Map<String, String> headers) implements Credential {
        public Headers {
            headers = Map.copyOf(headers);
        }
        @Override
        public String toString() {
            return "Headers[keys=" + headers.keySet() + "]";
        }
    }
}
