package io.configd.authn.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;

import dasniko.testcontainers.keycloak.KeycloakContainer;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Credential;
import io.configd.common.auth.DenyReason;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-IdP proof against a Keycloak container: a genuine {@code client_credentials} access token validates
 * through the authenticator; claims map to Configd roles; the audience check and expiry reject; and - the
 * marquee assertion - a live signing-key roll exercises the JWKS-rotation defence end to end
 * (unknown-new-kid refetch, overlap validation, and post-rotation rejection of the retired kid).
 *
 * <p>Flag-guarded on {@code -Dconfigd.it.containers=true} so it is skipped in the normal reactor (it needs a
 * Docker daemon and pulls the Keycloak image). Keycloak serves plain HTTP in the container, so the JWKS
 * source is built directly via the in-package builder (the config layer's https requirement is a separate,
 * unit-tested boot policy); everything else - signature verification, claims, rotation - is the production
 * path against a real authorization server. Keycloak stamps {@code typ: JWT} on access tokens rather
 * than {@code at+jwt}, so the relaxed {@code requireTypeAtJwt=false} mode is used here - a genuine
 * example of the real-world IdP behavior that relaxed mode exists to accommodate.
 */
@EnabledIfSystemProperty(named = "configd.it.containers", matches = "true")
final class KeycloakOidcIT {

    private static final String REALM = "configd";
    private static final String CLIENT_ID = "configd-svc";
    private static final String CLIENT_SECRET = "configd-secret";
    private static final String AUDIENCE = "configd-api";
    private static final String KEY_PROVIDER_TYPE = "org.keycloak.keys.KeyProvider";

    private static KeycloakContainer keycloak;
    private static String issuer;
    private static String jwksUri;
    private static String tokenEndpoint;

    @BeforeAll
    static void startKeycloak() {
        keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.4")
                .withRealmImportFile("/configd-realm.json");
        keycloak.withStartupTimeout(Duration.ofMinutes(5));
        keycloak.start();
        issuer = keycloak.getAuthServerUrl() + "/realms/" + REALM;
        jwksUri = issuer + "/protocol/openid-connect/certs";
        tokenEndpoint = issuer + "/protocol/openid-connect/token";
    }

    @AfterAll
    static void stopKeycloak() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }


    @Test
    void realAccessTokenValidatesWithMappedRolesAndCarriesExp() throws Exception {
        String token = clientCredentialsToken();
        AuthResult result = authenticator(AUDIENCE, 5_000L, 1L).authenticate(new Credential.BearerToken(token));

        AuthResult.Authenticated authed = assertInstanceOf(AuthResult.Authenticated.class, result);
        assertTrue(authed.principal().id().startsWith(issuer + "#"), "principal id is iss#sub");
        assertEquals("oidc", authed.principal().provenance());
        assertTrue(authed.principal().roles().contains("admin"),
                "the service account's configd-admins realm role must map to Configd 'admin'; got "
                        + authed.principal().roles());
        long expClaim = SignedJWT.parse(token).getJWTClaimsSet().getExpirationTime().getTime();
        assertEquals(expClaim, authed.credentialExpiresAtMillis(), "the token's own exp is carried (exp seam)");
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        String token = clientCredentialsToken();
        AuthResult result = authenticator("some-other-api", 5_000L, 1L)
                .authenticate(new Credential.BearerToken(token));
        assertDenied(result);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        RealmResource realm = admin();
        RealmRepresentation rep = realm.toRepresentation();
        int original = rep.getAccessTokenLifespan() == null ? 60 : rep.getAccessTokenLifespan();
        try {
            rep.setAccessTokenLifespan(2);
            realm.update(rep);
            String token = clientCredentialsToken();
            Thread.sleep(4_000L); // outlive the 2s lifespan
            // Zero clock-skew leeway here: the default 60s skew would (correctly) still admit a token only
            // seconds past exp, so an expiry assertion needs either a >60s wait or no leeway. Use no leeway.
            assertDenied(authenticator(AUDIENCE, 5_000L, 1L, 0)
                    .authenticate(new Credential.BearerToken(token)));
        } finally {
            rep.setAccessTokenLifespan(original);
            realm.update(rep);
        }
    }


    @Test
    void signingKeyRollIsToleratedAndRetiredKidIsRejected() throws Exception {
        OidcAuthenticator auth = authenticator(AUDIENCE, 1_000L, 1L);

        // A token under the current (original) signing kid; validating it also warms the JWKS cache.
        String tokenA = clientCredentialsToken();
        String kidA = kidOf(tokenA);
        assertInstanceOf(AuthResult.Authenticated.class, auth.authenticate(new Credential.BearerToken(tokenA)));

        RealmResource realm = admin();
        String realmId = realm.toRepresentation().getId();
        String originalKeyComponentId = firstRsaGeneratedComponentId(realm, realmId);

        // Roll: add a higher-priority active RSA signing key -> Keycloak signs new tokens under a new kid.
        addRsaSigningKey(realm, realmId, "rotated-rsa", 200);
        Thread.sleep(3_000L); // let Keycloak activate the new key and start signing with it

        String tokenB = clientCredentialsToken();
        String kidB = kidOf(tokenB);
        assertNotEquals(kidA, kidB, "the signing kid must actually have rolled");

        // The unknown new kid forces a rate-limited JWKS refetch, after which tokenB validates...
        assertInstanceOf(AuthResult.Authenticated.class, auth.authenticate(new Credential.BearerToken(tokenB)));
        // ...and during the overlap window the old-kid tokenA still validates (both keys in the JWKS).
        assertInstanceOf(AuthResult.Authenticated.class, auth.authenticate(new Credential.BearerToken(tokenA)));

        // Retire the original key: its kid leaves the JWKS.
        realm.components().component(originalKeyComponentId).remove();
        Thread.sleep(2_500L); // outlive the 1s JWKS cache TTL so the next access refetches {kidB}

        assertInstanceOf(AuthResult.Authenticated.class, auth.authenticate(new Credential.BearerToken(tokenB)));
        assertDenied(auth.authenticate(new Credential.BearerToken(tokenA)));
    }


    private OidcAuthenticator authenticator(String audience, long jwksTtlMillis, long rateLimitMillis)
            throws Exception {
        return authenticator(audience, jwksTtlMillis, rateLimitMillis, 60);
    }

    private OidcAuthenticator authenticator(String audience, long jwksTtlMillis, long rateLimitMillis,
                                            int clockSkewSeconds) throws Exception {
        OidcIssuerConfig.JwksSettings settings = new OidcIssuerConfig.JwksSettings(
                jwksTtlMillis, 500L, 100_000L, rateLimitMillis, 60_000L, 2_000, 3_000, 65_536);
        JWKSource<SecurityContext> source = OidcIssuerConfig.buildJwkSource(new URL(jwksUri), settings);
        ClaimsRoleMapper mapper = new ClaimsRoleMapper("realm_access.roles",
                Map.of("configd-admins", "admin", "configd-readers", "reader"), "");
        // Keycloak stamps typ:JWT, not at+jwt -> relaxed typ mode; RS256 signing keys only.
        OidcIssuerValidator validator = new OidcIssuerValidator(
                issuer, audience, Set.of(JWSAlgorithm.RS256), false, clockSkewSeconds, source, mapper);
        return new OidcAuthenticator(Map.of(issuer, validator));
    }

    private String clientCredentialsToken() throws Exception {
        String form = "grant_type=client_credentials&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET;
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("token endpoint returned " + response.statusCode() + ": " + response.body());
        }
        return JSONObjectUtils.getString(JSONObjectUtils.parse(response.body()), "access_token");
    }

    private static String kidOf(String token) throws Exception {
        return SignedJWT.parse(token).getHeader().getKeyID();
    }

    private static RealmResource admin() {
        return keycloak.getKeycloakAdminClient().realm(REALM);
    }

    private static String firstRsaGeneratedComponentId(RealmResource realm, String realmId) {
        List<ComponentRepresentation> keyProviders = realm.components().query(realmId, KEY_PROVIDER_TYPE);
        return keyProviders.stream()
                .filter(c -> "rsa-generated".equals(c.getProviderId()))
                .map(ComponentRepresentation::getId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no rsa-generated key provider in the realm"));
    }

    private static void addRsaSigningKey(RealmResource realm, String realmId, String name, int priority) {
        ComponentRepresentation component = new ComponentRepresentation();
        component.setName(name);
        component.setParentId(realmId);
        component.setProviderId("rsa-generated");
        component.setProviderType(KEY_PROVIDER_TYPE);
        MultivaluedHashMap<String, String> config = new MultivaluedHashMap<>();
        config.putSingle("priority", Integer.toString(priority));
        config.putSingle("enabled", "true");
        config.putSingle("active", "true");
        config.putSingle("keySize", "2048");
        component.setConfig(config);
        realm.components().add(component);
    }

    private static void assertDenied(AuthResult result) {
        AuthResult.Denied denied = assertInstanceOf(AuthResult.Denied.class, result);
        assertEquals(DenyReason.INVALID_CREDENTIAL, denied.reason());
    }
}
