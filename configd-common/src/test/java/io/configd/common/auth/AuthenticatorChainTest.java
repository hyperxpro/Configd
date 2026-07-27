package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatorChainTest {

    private static final Credential CRED = new Credential.BearerToken("x");

    private static final class TestAuthenticator implements Authenticator {
        private final String type;
        private final Class<? extends Credential> handles;
        private final Function<Credential, AuthResult> behavior;
        private final RuntimeException canAttemptFault;

        TestAuthenticator(String type, Class<? extends Credential> handles,
                          Function<Credential, AuthResult> behavior, RuntimeException canAttemptFault) {
            this.type = type;
            this.handles = handles;
            this.behavior = behavior;
            this.canAttemptFault = canAttemptFault;
        }

        static TestAuthenticator returning(String type, AuthResult r) {
            return new TestAuthenticator(type, Credential.BearerToken.class, c -> r, null);
        }

        static TestAuthenticator throwingOnAuth(String type) {
            return new TestAuthenticator(type, Credential.BearerToken.class, c -> {
                throw new IllegalStateException("boom");
            }, null);
        }

        static TestAuthenticator throwingOnCanAttempt(String type) {
            return new TestAuthenticator(type, Credential.BearerToken.class, c -> null, new RuntimeException("dispatch boom"));
        }

        @Override public String type() { return type; }

        @Override public boolean canAttempt(Credential c) {
            if (canAttemptFault != null) throw canAttemptFault;
            return handles.isInstance(c);
        }

        @Override public AuthResult authenticate(Credential c) { return behavior.apply(c); }
    }

    private static AuthResult.Authenticated auth(String id) {
        return new AuthResult.Authenticated(new Principal(id, Set.of(), "test"));
    }

    private static AuthResult.Denied denied(DenyReason reason) {
        return new AuthResult.Denied(reason, reason.name());
    }

    @Test
    void firstAcceptanceWins() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                TestAuthenticator.returning("a", auth("alice")),
                TestAuthenticator.returning("b", auth("bob"))));
        assertEquals("alice", assertInstanceOf(AuthResult.Authenticated.class, chain.resolve(CRED)).principal().id());
    }

    @Test
    void invalidCredentialHardStops_neverFallsThroughToAWeakerAuthenticator() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                TestAuthenticator.returning("owner", denied(DenyReason.INVALID_CREDENTIAL)),
                TestAuthenticator.returning("weaker", auth("should-never-win"))));
        AuthResult.Denied d = assertInstanceOf(AuthResult.Denied.class, chain.resolve(CRED));
        assertEquals(DenyReason.INVALID_CREDENTIAL, d.reason());
    }

    @Test
    void notThisAuthenticatorContinues() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                TestAuthenticator.returning("issuerA", denied(DenyReason.NOT_THIS_AUTHENTICATOR)),
                TestAuthenticator.returning("issuerB", auth("carol"))));
        assertEquals("carol", assertInstanceOf(AuthResult.Authenticated.class, chain.resolve(CRED)).principal().id());
    }

    @Test
    void unavailableStopsFailClosed_neverFallsThrough() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                TestAuthenticator.returning("down", new AuthResult.Unavailable("backend down")),
                TestAuthenticator.returning("weaker", auth("should-never-win"))));
        assertInstanceOf(AuthResult.Unavailable.class, chain.resolve(CRED));
    }

    @Test
    void throwableFromAuthenticateFailsClosed() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                TestAuthenticator.throwingOnAuth("buggy"),
                TestAuthenticator.returning("weaker", auth("should-never-win"))));
        assertInstanceOf(AuthResult.Unavailable.class, chain.resolve(CRED));
    }

    @Test
    void throwableFromCanAttemptFailsClosed() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                TestAuthenticator.throwingOnCanAttempt("buggy"),
                TestAuthenticator.returning("weaker", auth("should-never-win"))));
        assertInstanceOf(AuthResult.Unavailable.class, chain.resolve(CRED));
    }

    @Test
    void typeDispatchSkipsNonHandlers() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                new TestAuthenticator("certOnly", Credential.ClientCertificate.class, c -> auth("cert"), null),
                TestAuthenticator.returning("bearer", auth("token"))));
        assertEquals("token", assertInstanceOf(AuthResult.Authenticated.class, chain.resolve(CRED)).principal().id());
    }

    @Test
    void exhaustedChainDefaultDenies() {
        AuthenticatorChain chain = new AuthenticatorChain(List.of(
                TestAuthenticator.returning("a", denied(DenyReason.NOT_THIS_AUTHENTICATOR))));
        AuthResult.Denied d = assertInstanceOf(AuthResult.Denied.class, chain.resolve(CRED));
        assertEquals(DenyReason.NO_CREDENTIAL, d.reason());
    }

    @Test
    void emptyChainAndNullCredentialAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatorChain(List.of()));
        AuthenticatorChain chain = new AuthenticatorChain(List.of(TestAuthenticator.returning("a", auth("x"))));
        assertThrows(NullPointerException.class, () -> chain.resolve(null));
    }

    private static ConfigSource cfg(Map<String, String> m) {
        return new ConfigSource() {
            @Override public Optional<String> getString(String key) {
                return Optional.ofNullable(m.get(key));
            }
            @Override public Set<String> keysWithPrefix(String prefix) {
                return m.keySet().stream().filter(k -> k.startsWith(prefix)).collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
        };
    }

    @Test
    void fromConfigReadsModeAndProviders() {
        assertTrue(AuthenticatorChain.fromConfig(cfg(Map.of())).isEmpty(), "no config -> no SPI chain");
        assertEquals(List.of("mtls"), AuthenticatorChain.configuredProviders(cfg(Map.of("configd.auth.mode", "mtls"))));
        // providers list overrides mode
        assertEquals(List.of("mtls", "bearer"),
                AuthenticatorChain.configuredProviders(cfg(Map.of(
                        "configd.auth.mode", "basic", "configd.auth.providers", "mtls,bearer"))));
    }

    @Test
    void buildsBuiltinProvidersInOrder() {
        AuthenticatorChain chain = AuthenticatorChain.build(List.of("mtls", "bearer"),
                cfg(Map.of("configd.auth.bearer.token", "tok")));
        assertEquals(List.of("mtls", "bearer"), chain.providerTypes());
    }

    @Test
    void unknownProviderFailsLoud() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AuthenticatorChain.build(List.of("mtls", "oidc"), cfg(Map.of())));
        assertTrue(e.getMessage().contains("oidc"));
    }

    @Test
    void duplicateProviderIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> AuthenticatorChain.build(List.of("mtls", "mtls"), cfg(Map.of())));
    }

    @Test
    void noneMixedWithOtherProvidersIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> AuthenticatorChain.build(List.of("mtls", "none"), cfg(Map.of())));
        assertThrows(IllegalStateException.class,
                () -> AuthenticatorChain.build(List.of("none", "bearer"), cfg(Map.of("configd.auth.bearer.token", "t"))));
    }

    @Test
    void basicFactoryFailsClosedOnEmptyOrMalformedStore() {
        assertThrows(RuntimeException.class, () -> AuthenticatorChain.build(List.of("basic"), cfg(Map.of())));
        assertThrows(RuntimeException.class, () -> AuthenticatorChain.build(List.of("basic"),
                cfg(Map.of("configd.auth.basic.users", "alice:not-a-valid-hash:reader"))));
    }

    @Test
    void noAuthModeEmitsALoudWarning() {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(NoAuthAuthenticatorFactory.class.getName());
        java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            AuthenticatorChain.build(List.of("none"), cfg(Map.of()));
        } finally {
            logger.removeHandler(handler);
        }
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == java.util.logging.Level.WARNING
                        && r.getMessage() != null && r.getMessage().contains("DISABLED")),
                "building a 'none' chain must emit a loud auth-disabled WARNING");
    }

    @Test
    void oidcShapedTokenFailsClosedWhenNoOidcAuthenticatorPresent() {
        // A JWT-looking bearer token, chain = mtls,bearer (no oidc). The catch-all bearer HARD-rejects it
        // (not the static token) -> INVALID_CREDENTIAL (401). It is NEVER silently accepted via a weaker path.
        AuthenticatorChain chain = AuthenticatorChain.build(List.of("mtls", "bearer"),
                cfg(Map.of("configd.auth.bearer.token", "the-real-admin-token")));
        AuthResult r = chain.resolve(new Credential.BearerToken("eyJhbGciOiJSUzI1NiJ9.forged.sig"));
        AuthResult.Denied d = assertInstanceOf(AuthResult.Denied.class, r);
        assertEquals(DenyReason.INVALID_CREDENTIAL, d.reason());
    }
}
