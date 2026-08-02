package io.configd.authn.oidc;

import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.AuthenticatorFactory;
import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OidcModuleWiringTest {

    private static final String P = "configd.auth.oidc.issuer.kc.";

    private static Map<String, String> validConfig() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put(P + "uri", "https://idp.example/realms/configd");
        cfg.put(P + "audience", "configd-api");
        cfg.put(P + "jwksUri", "https://idp.example/realms/configd/protocol/openid-connect/certs");
        return cfg;
    }

    @Test
    void serviceLoaderDiscoversTheOidcFactory() {
        List<String> types = ServiceLoader.load(AuthenticatorFactory.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(AuthenticatorFactory::type)
                .collect(Collectors.toList());
        assertTrue(types.contains("oidc"), "META-INF/services must advertise the oidc factory; got " + types);
    }

    @Test
    void chainBuildWiresOidcByName() {
        AuthenticatorChain chain = AuthenticatorChain.build(List.of("oidc"), new MapConfigSource(validConfig()));
        assertEquals(List.of("oidc"), chain.providerTypes());
    }

    @Test
    void oidcBeforeBearerInAChainIsAccepted() {
        AuthenticatorChain chain = AuthenticatorChain.build(List.of("oidc", "bearer"),
                new MapConfigSource(withBearer(validConfig())));
        assertEquals(List.of("oidc", "bearer"), chain.providerTypes());
    }

    @Test
    void httpIssuerUriIsRefused() {
        Map<String, String> cfg = validConfig();
        cfg.put(P + "uri", "http://idp.example/realms/configd");
        assertThrows(ConfigException.class, () -> OidcIssuerConfig.parse(new MapConfigSource(cfg), "kc"));
    }

    @Test
    void symmetricOrNoneAlgorithmInAllowlistIsRefused() {
        for (String bad : List.of("none", "HS256")) {
            Map<String, String> cfg = validConfig();
            cfg.put(P + "algs", "RS256," + bad);
            assertThrows(ConfigException.class,
                    () -> OidcIssuerConfig.parse(new MapConfigSource(cfg), "kc"),
                    "algorithm '" + bad + "' must be refused on a resource server");
        }
    }

    @Test
    void missingRequiredKeysAreRefused() {
        Map<String, String> noAudience = validConfig();
        noAudience.remove(P + "audience");
        assertThrows(ConfigException.class,
                () -> OidcIssuerConfig.parse(new MapConfigSource(noAudience), "kc"));

        Map<String, String> noUri = validConfig();
        noUri.remove(P + "uri");
        assertThrows(ConfigException.class, () -> OidcIssuerConfig.parse(new MapConfigSource(noUri), "kc"));
    }

    @Test
    void issuerNamesAreEnumeratedFromTheKeyspace() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("configd.auth.oidc.issuer.keycloak.uri", "https://a.example");
        cfg.put("configd.auth.oidc.issuer.keycloak.audience", "api");
        cfg.put("configd.auth.oidc.issuer.entra.uri", "https://b.example");
        assertEquals(Set.of("keycloak", "entra"),
                OidcAuthenticatorFactory.issuerNames(new MapConfigSource(cfg)));
    }

    private static Map<String, String> withBearer(Map<String, String> cfg) {
        cfg.put("configd.auth.bearer.token", "static-admin-token-value-1234567890");
        return cfg;
    }

    private record MapConfigSource(Map<String, String> values) implements ConfigSource {
        @Override
        public Optional<String> getString(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Set<String> keysWithPrefix(String prefix) {
            return values.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
        }
    }
}
