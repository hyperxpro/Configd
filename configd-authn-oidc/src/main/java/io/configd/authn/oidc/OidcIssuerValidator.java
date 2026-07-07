package io.configd.authn.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.DenyReason;
import io.configd.common.auth.Principal;

import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates an RFC 9068 JWT access token from ONE pinned issuer and produces an {@link AuthResult}. This is
 * the per-request hot path; in steady state it is CPU-only (a local signature verify against a cached key
 * plus claim checks) - the only I/O is a rare, rate-limited JWKS refresh owned by the injected
 * {@link JWKSource}.
 *
 * <p>The security-load-bearing decisions (RFC 8725 / RFC 9068):
 * <ul>
 *   <li><b>Algorithm allowlist.</b> The verifier is built with an explicit set of asymmetric algorithms
 *       ({@code RS256, ES256} by default). The header {@code alg} is checked against it up front, and the
 *       nimbus {@link JWSVerificationKeySelector} is itself constructed with that same set, so an HMAC
 *       verifier can never be reached with a JWKS public key (the RS/HS confusion attack) and {@code
 *       alg:none} is never in the set.</li>
 *   <li><b>Key selection is JWKS-only.</b> Keys come exclusively from the operator-pinned JWKS via the
 *       injected source; a token-supplied {@code jku}/{@code x5u} header is never consulted (SSRF, RFC 8725
 *       §3.10) - this is structural, the key selector has no code path to a token-named URL.</li>
 *   <li><b>Claims.</b> {@code iss} exact-match, {@code aud} must contain the configured API identifier,
 *       {@code exp}/{@code nbf} with a bounded clock-skew leeway, and {@code iss}/{@code sub}/{@code aud}/
 *       {@code exp} required-present.</li>
 *   <li><b>Fail-closed error mapping.</b> A JWKS backend outage with no usable cached key is
 *       {@link AuthResult.Unavailable} (retryable / 503-class), NOT a 401 - a down IdP is not a bad
 *       credential. Every credential-level failure is {@link DenyReason#INVALID_CREDENTIAL} (a hard 401).</li>
 * </ul>
 *
 * <p>Immutable after construction and thread-safe (nimbus {@code DefaultJWTProcessor} and the cached
 * {@link JWKSource} are safe for concurrent use).
 */
final class OidcIssuerValidator {

    private final String issuerUri;
    private final Set<JWSAlgorithm> allowedAlgorithms;
    private final DefaultJWTProcessor<SecurityContext> processor;
    private final ClaimsRoleMapper roleMapper;

    OidcIssuerValidator(String issuerUri, String audience, Set<JWSAlgorithm> allowedAlgorithms,
                        boolean requireTypeAtJwt, int clockSkewSeconds, JWKSource<SecurityContext> jwkSource,
                        ClaimsRoleMapper roleMapper) {
        this.issuerUri = Objects.requireNonNull(issuerUri, "issuerUri");
        this.allowedAlgorithms = Set.copyOf(allowedAlgorithms);
        this.roleMapper = Objects.requireNonNull(roleMapper, "roleMapper");
        this.processor = buildProcessor(issuerUri, Objects.requireNonNull(audience, "audience"),
                this.allowedAlgorithms, requireTypeAtJwt, clockSkewSeconds,
                Objects.requireNonNull(jwkSource, "jwkSource"));
    }

    String issuerUri() {
        return issuerUri;
    }

    /**
     * Validates an already-parsed signed JWT (its {@code iss} has already been matched to this issuer by
     * {@link OidcAuthenticator}). Returns {@link AuthResult.Authenticated} carrying the token's own {@code
     * exp} (so a long-lived connection can close when the token actually expires), or a typed rejection.
     */
    AuthResult validate(SignedJWT jwt) {
        // Defence in depth: reject a disallowed alg before touching key material. The key selector below
        // enforces the same set, but rejecting here keeps `alg:none`/HS* from reaching any verifier at all.
        JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
        if (alg == null || !allowedAlgorithms.contains(alg)) {
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "signature algorithm not permitted for issuer " + issuerUri + ": " + alg);
        }

        JWTClaimsSet claims;
        try {
            claims = processor.process(jwt, null);
        } catch (RateLimitReachedException e) {
            // The kid is not in the cached JWKS and a forced refetch was throttled by the cooldown. Per the
            // rotation policy an unknown kid within the cooldown is a FAST REJECT, not a fetch - and it must
            // NOT become a 503, or an unknown-kid spray could flip authentication to Unavailable. The backend
            // is not known-down (we simply declined to re-fetch right now), so this is a bad/unresolvable
            // credential (401), not a retryable outage.
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "signing key not resolvable for issuer " + issuerUri + " (JWKS refresh rate-limited)");
        } catch (KeySourceException e) {
            // The JWKS endpoint could not be consulted and no usable key was cached: the backend is
            // unavailable, not the credential bad. Fail closed as retryable - never a 401, never a downgrade.
            return new AuthResult.Unavailable(
                    "JWKS unavailable for issuer " + issuerUri + ": " + e.getClass().getSimpleName());
        } catch (BadJOSEException e) {
            // Bad signature, unknown kid (after a bounded refetch), wrong iss/aud, expired/nbf, missing
            // required claim, or wrong typ: the credential is owned by this issuer and is bad. Hard 401.
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "access token rejected for issuer " + issuerUri + ": " + e.getMessage());
        } catch (JOSEException e) {
            // A crypto/processing fault that is not a key-source outage. Treat as a bad credential (401)
            // rather than letting it propagate as a chain-faulting throwable (which becomes Unavailable).
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "access token processing failed for issuer " + issuerUri + ": " + e.getClass().getSimpleName());
        }

        String subject = claims.getSubject(); // required-present, so non-null here
        Principal principal = new Principal(issuerUri + "#" + subject, roleMapper.rolesOf(claims),
                auditAttributes(claims), "oidc");
        long credentialExpiresAtMillis = claims.getExpirationTime().getTime(); // exp required-present
        return new AuthResult.Authenticated(principal, credentialExpiresAtMillis);
    }

    /**
     * A small, curated set of non-secret claims recorded for audit/ABAC (the authorizing client and the
     * granted scope). Values may be sensitive, so {@link Principal#toString} prints only the keys; the raw
     * token never enters the principal.
     */
    private static Map<String, String> auditAttributes(JWTClaimsSet claims) {
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfString(attributes, "client_id", claims.getClaim("client_id"));
        putIfString(attributes, "azp", claims.getClaim("azp"));
        putIfString(attributes, "scope", claims.getClaim("scope"));
        return attributes;
    }

    private static void putIfString(Map<String, String> attributes, String key, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            attributes.put(key, s);
        }
    }

    private static DefaultJWTProcessor<SecurityContext> buildProcessor(
            String issuerUri, String audience, Set<JWSAlgorithm> allowedAlgorithms, boolean requireTypeAtJwt,
            int clockSkewSeconds, JWKSource<SecurityContext> jwkSource) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSTypeVerifier(typeVerifier(requireTypeAtJwt));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(allowedAlgorithms, jwkSource));

        JWTClaimsSet exactMatch = new JWTClaimsSet.Builder().issuer(issuerUri).build();
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                audience, exactMatch, Set.of("iss", "sub", "aud", "exp"));
        claimsVerifier.setMaxClockSkew(clockSkewSeconds);
        processor.setJWTClaimsSetVerifier(claimsVerifier);
        return processor;
    }

    /**
     * The {@code typ} header policy. Strict (RFC 9068): accept only {@code at+jwt}/{@code application/at+jwt}.
     * Relaxed (for IdPs that stamp a legacy {@code typ: JWT} or omit it): additionally accept {@code JWT} and
     * a missing type. Audience + the alg allowlist already block id-token substitution, so the relaxed mode
     * is a defence-in-depth softening, not the sole guard.
     */
    private static JOSEObjectTypeVerifier<SecurityContext> typeVerifier(boolean requireTypeAtJwt) {
        if (requireTypeAtJwt) {
            return new DefaultJOSEObjectTypeVerifier<>(
                    new JOSEObjectType("at+jwt"), new JOSEObjectType("application/at+jwt"));
        }
        Set<JOSEObjectType> allowed = new HashSet<>();
        allowed.add(new JOSEObjectType("at+jwt"));
        allowed.add(new JOSEObjectType("application/at+jwt"));
        allowed.add(JOSEObjectType.JWT);
        allowed.add(null); // a nimbus null entry means "a missing typ header is accepted"
        return new DefaultJOSEObjectTypeVerifier<>(allowed);
    }
}
