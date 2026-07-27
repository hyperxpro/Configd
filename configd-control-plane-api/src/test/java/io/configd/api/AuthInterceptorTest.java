package io.configd.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthInterceptorTest {

    private static final AuthInterceptor.AuthResult VALID_RESULT =
            new AuthInterceptor.AuthResult.Authenticated("alice", Set.of("admin"));

    private final AuthInterceptor interceptor = new AuthInterceptor(token -> {
        if ("valid-token".equals(token)) {
            return VALID_RESULT;
        }
        return new AuthInterceptor.AuthResult.Denied("invalid token");
    });

    @Test
    void nullTokenIsDenied() {
        var result = interceptor.authenticate(null);
        assertInstanceOf(AuthInterceptor.AuthResult.Denied.class, result);
        assertEquals("missing auth token",
                ((AuthInterceptor.AuthResult.Denied) result).reason());
    }

    @Test
    void emptyTokenIsDenied() {
        var result = interceptor.authenticate("");
        assertInstanceOf(AuthInterceptor.AuthResult.Denied.class, result);
        assertEquals("missing auth token",
                ((AuthInterceptor.AuthResult.Denied) result).reason());
    }

    @Test
    void blankTokenIsDenied() {
        var result = interceptor.authenticate("   ");
        assertInstanceOf(AuthInterceptor.AuthResult.Denied.class, result);
        assertEquals("missing auth token",
                ((AuthInterceptor.AuthResult.Denied) result).reason());
    }

    @Test
    void validTokenIsAuthenticated() {
        var result = interceptor.authenticate("valid-token");
        assertInstanceOf(AuthInterceptor.AuthResult.Authenticated.class, result);

        var auth = (AuthInterceptor.AuthResult.Authenticated) result;
        assertEquals("alice", auth.principal());
        assertTrue(auth.roles().contains("admin"));
    }

    @Test
    void invalidTokenIsDenied() {
        var result = interceptor.authenticate("bad-token");
        assertInstanceOf(AuthInterceptor.AuthResult.Denied.class, result);
        assertEquals("invalid token",
                ((AuthInterceptor.AuthResult.Denied) result).reason());
    }

    @Test
    void nullValidatorThrows() {
        assertThrows(NullPointerException.class,
                () -> new AuthInterceptor(null));
    }

    @Test
    void authResultIsSealed() {
        AuthInterceptor.AuthResult authenticated =
                new AuthInterceptor.AuthResult.Authenticated("bob", Set.of());
        AuthInterceptor.AuthResult denied =
                new AuthInterceptor.AuthResult.Denied("nope");

        assertInstanceOf(AuthInterceptor.AuthResult.Authenticated.class, authenticated);
        assertInstanceOf(AuthInterceptor.AuthResult.Denied.class, denied);
    }

    // Authenticated defensively hardens its roles set: a pluggable TokenValidator cannot hand the
    // authz path a null / mutable / aliased set.

    @Test
    void authenticatedRejectsNullRoles() {
        assertThrows(NullPointerException.class,
                () -> new AuthInterceptor.AuthResult.Authenticated("p", null));
    }

    @Test
    void authenticatedRolesAreImmutableAndDecoupledFromSource() {
        var source = new HashSet<String>();
        source.add("admin");
        var auth = new AuthInterceptor.AuthResult.Authenticated("p", source);

        assertThrows(UnsupportedOperationException.class, () -> auth.roles().add("root"));
        source.add("root");
        assertEquals(Set.of("admin"), auth.roles(),
                "roles() must be a snapshot taken at construction, not an alias of the caller's set");
    }
}
