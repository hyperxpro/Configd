package io.configd.authn.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Credential;
import io.configd.common.auth.DenyReason;
import io.configd.common.auth.Principal;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.configd.authn.oidc.OidcTestSupport.AUDIENCE;
import static io.configd.authn.oidc.OidcTestSupport.ISSUER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OidcAuthenticatorTest {

    private static final Set<JWSAlgorithm> ALGS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

    private static OidcAuthenticator authenticatorFor(JWKSource<SecurityContext> source, boolean requireAtJwt,
                                                      ClaimsRoleMapper mapper) {
        OidcIssuerValidator validator = new OidcIssuerValidator(
                ISSUER, AUDIENCE, ALGS, requireAtJwt, 60, source, mapper);
        return new OidcAuthenticator(Map.of(ISSUER, validator));
    }

    private static ClaimsRoleMapper noRoles() {
        return new ClaimsRoleMapper(null, Map.of(), "");
    }

    private static AuthResult resolve(OidcAuthenticator auth, String token) {
        return auth.authenticate(new Credential.BearerToken(token));
    }

    private static JWKSource<SecurityContext> immutable(RSAKey rsa, ECKey ec) {
        return new ImmutableJWKSet<>(OidcTestSupport.publicJwks(rsa, ec));
    }


    @Test
    void rs256AccessTokenIsAccepted() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        long exp = (System.currentTimeMillis() / 1000L + 3600L) * 1000L;
        String token = OidcTestSupport.signRs256(rsa,
                OidcTestSupport.claims("svc-1").expirationTime(new Date(exp)).build());

        AuthResult result = resolve(auth, token);
        AuthResult.Authenticated authed = assertInstanceOf(AuthResult.Authenticated.class, result);
        Principal principal = authed.principal();
        assertEquals(ISSUER + "#svc-1", principal.id());
        assertEquals("oidc", principal.provenance());
        assertEquals(exp, authed.credentialExpiresAtMillis(), "the token's own exp must be carried (exp seam)");
        assertNotEquals(AuthResult.NO_EXPIRY, authed.credentialExpiresAtMillis());
    }

    @Test
    void es256AccessTokenIsAccepted() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        String token = OidcTestSupport.signEs256(ec, OidcTestSupport.claims("svc-ec").build());
        assertInstanceOf(AuthResult.Authenticated.class, resolve(auth, token));
    }


    @Test
    void algNoneIsRejected() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        String token = OidcTestSupport.algNone(OidcTestSupport.claims("svc-1").build());
        assertDenied(resolve(auth, token), DenyReason.INVALID_CREDENTIAL);
    }

    @Test
    void algConfusionHs256WithRsaPublicKeyIsRejected() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        String token = OidcTestSupport.hs256WithRsaPublicKey(rsa, OidcTestSupport.claims("svc-1").build());
        assertDenied(resolve(auth, token), DenyReason.INVALID_CREDENTIAL);
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        String token = OidcTestSupport.signRs256(rsa,
                OidcTestSupport.claims("svc-1").audience("some-other-api").build());
        assertDenied(resolve(auth, token), DenyReason.INVALID_CREDENTIAL);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        long past = System.currentTimeMillis() - 3_600_000;
        String token = OidcTestSupport.signRs256(rsa, OidcTestSupport.claims("svc-1")
                .expirationTime(new Date(past)).notBeforeTime(new Date(past - 60_000)).build());
        assertDenied(resolve(auth, token), DenyReason.INVALID_CREDENTIAL);
    }

    @Test
    void notYetValidTokenIsRejected() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        long future = System.currentTimeMillis() + 3_600_000;
        String token = OidcTestSupport.signRs256(rsa,
                OidcTestSupport.claims("svc-1").notBeforeTime(new Date(future)).build());
        assertDenied(resolve(auth, token), DenyReason.INVALID_CREDENTIAL);
    }

    @Test
    void badSignatureIsRejected() throws Exception {
        RSAKey serving = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        RSAKey forger = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("kA").generate();
        OidcAuthenticator auth = authenticatorFor(immutable(serving, ec), true, noRoles());

        String token = OidcTestSupport.signRs256(forger, OidcTestSupport.claims("svc-1").build());
        assertDenied(resolve(auth, token), DenyReason.INVALID_CREDENTIAL);
    }

    @Test
    void unknownKidIsRejected() throws Exception {
        RSAKey serving = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        RSAKey other = OidcTestSupport.rsaKey("kZ");
        OidcAuthenticator auth = authenticatorFor(immutable(serving, ec), true, noRoles());

        String token = OidcTestSupport.signRs256(other, OidcTestSupport.claims("svc-1").build());
        assertDenied(resolve(auth, token), DenyReason.INVALID_CREDENTIAL);
    }


    @Test
    void tokenForForeignIssuerIsNotThisAuthenticator() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        String token = OidcTestSupport.signRs256(rsa,
                OidcTestSupport.claims("svc-1").issuer("https://other-idp.example/realms/x").build());
        assertDenied(resolve(auth, token), DenyReason.NOT_THIS_AUTHENTICATOR);
    }

    @Test
    void opaqueBearerIsNotThisAuthenticator() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, noRoles());

        assertDenied(resolve(auth, "an-opaque-non-jwt-token"), DenyReason.NOT_THIS_AUTHENTICATOR);
    }


    @Test
    void missingTypIsRejectedWhenStrictButAcceptedWhenRelaxed() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        String token = OidcTestSupport.signRs256(rsa, OidcTestSupport.claims("svc-1").build(), null); // no typ

        assertDenied(resolve(authenticatorFor(immutable(rsa, ec), true, noRoles()), token),
                DenyReason.INVALID_CREDENTIAL);
        assertInstanceOf(AuthResult.Authenticated.class,
                resolve(authenticatorFor(immutable(rsa, ec), false, noRoles()), token));
    }


    @Test
    void nestedArrayRolesClaimIsMapped() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        ClaimsRoleMapper mapper = new ClaimsRoleMapper(
                "realm_access.roles", Map.of("configd-admins", "admin", "configd-readers", "reader"), "");
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, mapper);

        String token = OidcTestSupport.signRs256(rsa, OidcTestSupport.claims("svc-1")
                .claim("realm_access", Map.of("roles", List.of("configd-admins", "unmapped-role"))).build());

        AuthResult.Authenticated authed = assertInstanceOf(AuthResult.Authenticated.class, resolve(auth, token));
        assertEquals(Set.of("admin"), authed.principal().roles(), "allowlist map drops the unmapped role");
    }

    @Test
    void spaceDelimitedScopeClaimIsMapped() throws Exception {
        RSAKey rsa = OidcTestSupport.rsaKey("kA");
        ECKey ec = OidcTestSupport.ecKey("eA");
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("scope", Map.of(), "scope:"); // pass-through + prefix
        OidcAuthenticator auth = authenticatorFor(immutable(rsa, ec), true, mapper);

        String token = OidcTestSupport.signRs256(rsa,
                OidcTestSupport.claims("svc-1").claim("scope", "read write configd.admin").build());

        AuthResult.Authenticated authed = assertInstanceOf(AuthResult.Authenticated.class, resolve(auth, token));
        assertEquals(Set.of("scope:read", "scope:write", "scope:configd.admin"), authed.principal().roles());
    }


    @Test
    void unknownNewKidTriggersRefetchAndOldKidStillValidatesDuringOverlap() throws Exception {
        RSAKey keyA = OidcTestSupport.rsaKey("kA");
        RSAKey keyB = OidcTestSupport.rsaKey("kB");
        OidcTestSupport.CountingJWKSetSource source =
                new OidcTestSupport.CountingJWKSetSource(OidcTestSupport.publicJwks(keyA));
        OidcAuthenticator auth = authenticatorFor(policySource(source, 300_000L, 1L), true, noRoles());

        assertInstanceOf(AuthResult.Authenticated.class,
                resolve(auth, OidcTestSupport.signRs256(keyA, OidcTestSupport.claims("a").build())));
        int afterA = source.fetchCount();

        source.setJwks(OidcTestSupport.publicJwks(keyA, keyB));
        assertInstanceOf(AuthResult.Authenticated.class,
                resolve(auth, OidcTestSupport.signRs256(keyB, OidcTestSupport.claims("b").build())));
        assertTrue(source.fetchCount() > afterA, "an unknown kid must force a JWKS refetch");

        assertInstanceOf(AuthResult.Authenticated.class,
                resolve(auth, OidcTestSupport.signRs256(keyA, OidcTestSupport.claims("a2").build())));
    }

    @Test
    void retiredKeyAgesOutOfTheCacheAndIsRejected() throws Exception {
        RSAKey keyA = OidcTestSupport.rsaKey("kA");
        RSAKey keyB = OidcTestSupport.rsaKey("kB");
        OidcTestSupport.CountingJWKSetSource source =
                new OidcTestSupport.CountingJWKSetSource(OidcTestSupport.publicJwks(keyA));
        OidcAuthenticator auth = authenticatorFor(policySource(source, 300L, 1L), true, noRoles());

        assertInstanceOf(AuthResult.Authenticated.class,
                resolve(auth, OidcTestSupport.signRs256(keyA, OidcTestSupport.claims("a").build())));

        source.setJwks(OidcTestSupport.publicJwks(keyB));
        Thread.sleep(600L);

        assertInstanceOf(AuthResult.Authenticated.class,
                resolve(auth, OidcTestSupport.signRs256(keyB, OidcTestSupport.claims("b").build())));
        assertDenied(resolve(auth, OidcTestSupport.signRs256(keyA, OidcTestSupport.claims("a2").build())),
                DenyReason.INVALID_CREDENTIAL);
    }

    @Test
    void aBurstOfUnknownKidsDoesNotHammerTheJwksEndpoint() throws Exception {
        RSAKey keyA = OidcTestSupport.rsaKey("kA");
        OidcTestSupport.CountingJWKSetSource source =
                new OidcTestSupport.CountingJWKSetSource(OidcTestSupport.publicJwks(keyA));
        OidcAuthenticator auth = authenticatorFor(policySource(source, 300_000L, 30_000L), true, noRoles());

        resolve(auth, OidcTestSupport.signRs256(keyA, OidcTestSupport.claims("a").build()));
        int afterWarm = source.fetchCount();

        for (int i = 0; i < 8; i++) {
            RSAKey bogus = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("bogus-" + i).generate();
            assertDenied(resolve(auth, OidcTestSupport.signRs256(bogus, OidcTestSupport.claims("x").build())),
                    DenyReason.INVALID_CREDENTIAL);
        }
        int forcedRefetches = source.fetchCount() - afterWarm;
        assertTrue(forcedRefetches < 8,
                "a burst of 8 unknown kids must not cause one refetch each; got " + forcedRefetches);
    }


    @Test
    void jwksOutageWithColdCacheYieldsUnavailable() throws Exception {
        RSAKey keyA = OidcTestSupport.rsaKey("kA");
        JWKSetSource<SecurityContext> failing = new JWKSetSource<>() {
            @Override
            public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator e, long t, SecurityContext c)
                    throws KeySourceException {
                throw new KeySourceException("JWKS endpoint unreachable");
            }

            @Override
            public void close() {
            }
        };
        JWKSource<SecurityContext> source = JWKSourceBuilder.<SecurityContext>create(failing)
                .cache(300_000L, 5_000L).retrying(false).build();
        OidcAuthenticator auth = authenticatorFor(source, true, noRoles());

        String token = OidcTestSupport.signRs256(keyA, OidcTestSupport.claims("a").build());
        assertInstanceOf(AuthResult.Unavailable.class, resolve(auth, token));
    }

    private static JWKSource<SecurityContext> policySource(JWKSetSource<SecurityContext> src,
                                                           long ttlMillis, long rateLimitMillis) {
        return JWKSourceBuilder.<SecurityContext>create(src)
                .cache(ttlMillis, 5_000L)
                .refreshAheadCache(false) // deterministic: no background refresh thread, refresh strictly on demand
                .rateLimited(rateLimitMillis)
                .retrying(false)
                .build();
    }

    private static void assertDenied(AuthResult result, DenyReason expected) {
        AuthResult.Denied denied = assertInstanceOf(AuthResult.Denied.class, result);
        assertEquals(expected, denied.reason());
    }
}
