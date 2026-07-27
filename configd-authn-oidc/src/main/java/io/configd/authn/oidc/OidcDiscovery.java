package io.configd.authn.oidc;

import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jose.util.Resource;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.Map;

/**
 * Resolves an issuer's {@code jwks_uri} once, at authenticator construction, from its published metadata
 * ({@code {issuer}/.well-known/openid-configuration}, the OIDC Discovery / RFC 8414 document). Discovery is a
 * convenience: the trust anchor is the operator-PINNED issuer, and the discovered {@code issuer} field MUST
 * equal it (the authorization-server mix-up defence, RFC 8414 §2 / RFC 9068 §4). An operator may instead
 * configure {@code jwksUri} directly and skip discovery entirely (recommended for production, so boot does
 * not depend on the IdP being reachable).
 *
 * <p>The fetch is bounded: {@code https}-only, connect/read timeouts, and a response size cap.
 */
final class OidcDiscovery {

    private OidcDiscovery() {
    }

    static URL resolveJwksUri(String issuerUri, int connectTimeoutMs, int readTimeoutMs, int sizeLimitBytes) {
        URL wellKnown = wellKnownUrl(issuerUri);
        DefaultResourceRetriever retriever =
                new DefaultResourceRetriever(connectTimeoutMs, readTimeoutMs, sizeLimitBytes);
        Map<String, Object> metadata;
        try {
            Resource resource = retriever.retrieveResource(wellKnown);
            metadata = JSONObjectUtils.parse(resource.getContent());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "OIDC discovery failed for issuer '" + issuerUri + "' at " + wellKnown + ": " + e, e);
        }

        String discoveredIssuer = string(metadata, "issuer");
        if (!issuerUri.equals(discoveredIssuer)) {
            throw new IllegalStateException("OIDC discovery issuer mismatch: pinned '" + issuerUri
                    + "' but the discovery document advertises '" + discoveredIssuer
                    + "' (authorization-server mix-up defence)");
        }
        String jwksUri = string(metadata, "jwks_uri");
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalStateException(
                    "OIDC discovery document for issuer '" + issuerUri + "' has no jwks_uri");
        }
        return OidcIssuerConfig.requireHttpsUrl("jwks_uri", jwksUri);
    }

    private static URL wellKnownUrl(String issuerUri) {
        String base = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        try {
            return new URL(base + "/.well-known/openid-configuration");
        } catch (MalformedURLException e) {
            throw new IllegalStateException("issuer is not a valid URL: '" + issuerUri + "'", e);
        }
    }

    private static String string(Map<String, Object> metadata, String key) {
        try {
            return JSONObjectUtils.getString(metadata, key);
        } catch (ParseException e) {
            return null;
        }
    }
}
