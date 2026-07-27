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
 * Per-issuer RFC 9068 validator. Security: algorithm allowlist (RFC 8725), JWKS-only key selection
 * (no jku/x5u), iss-exact-match, aud-required. JWKS outage = Unavailable (503), not bad credential (401).
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
     * Returns Authenticated carrying token's own exp (for connection closing), or typed rejection.
     */
    AuthResult validate(SignedJWT jwt) {
        JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
        if (alg == null || !allowedAlgorithms.contains(alg)) {
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "signature algorithm not permitted for issuer " + issuerUri + ": " + alg);
        }

        JWTClaimsSet claims;
        try {
            claims = processor.process(jwt, null);
        } catch (RateLimitReachedException e) {
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "signing key not resolvable for issuer " + issuerUri + " (JWKS refresh rate-limited)");
        } catch (KeySourceException e) {
            return new AuthResult.Unavailable(
                    "JWKS unavailable for issuer " + issuerUri + ": " + e.getClass().getSimpleName());
        } catch (BadJOSEException e) {
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "access token rejected for issuer " + issuerUri + ": " + e.getMessage());
        } catch (JOSEException e) {
            return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL,
                    "access token processing failed for issuer " + issuerUri + ": " + e.getClass().getSimpleName());
        }

        String subject = claims.getSubject(); // required-present, so non-null here
        Principal principal = new Principal(issuerUri + "#" + subject, roleMapper.rolesOf(claims),
                auditAttributes(claims), "oidc");
        long credentialExpiresAtMillis = claims.getExpirationTime().getTime(); // exp required-present
        return new AuthResult.Authenticated(principal, credentialExpiresAtMillis);
    }

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

    private static JOSEObjectTypeVerifier<SecurityContext> typeVerifier(boolean requireTypeAtJwt) {
        if (requireTypeAtJwt) {
            return new DefaultJOSEObjectTypeVerifier<>(
                    new JOSEObjectType("at+jwt"), new JOSEObjectType("application/at+jwt"));
        }
        Set<JOSEObjectType> allowed = new HashSet<>();
        allowed.add(new JOSEObjectType("at+jwt"));
        allowed.add(new JOSEObjectType("application/at+jwt"));
        allowed.add(JOSEObjectType.JWT);
        allowed.add(null);
        return new DefaultJOSEObjectTypeVerifier<>(allowed);
    }
}
