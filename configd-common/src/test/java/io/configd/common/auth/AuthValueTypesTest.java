package io.configd.common.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthValueTypesTest {

    @Test
    void bearerTokenRedactsInToString() {
        Credential.BearerToken t = new Credential.BearerToken("super-secret-value");
        assertFalse(t.toString().contains("super-secret-value"), "a token must never appear in toString");
        assertTrue(t.toString().contains("length="));
    }

    @Test
    void basicCredentialRedactsPassword() {
        Credential.BasicCredential c = new Credential.BasicCredential("alice", "hunter2".toCharArray());
        assertFalse(c.toString().contains("hunter2"), "a password must never appear in toString");
        assertTrue(c.toString().contains("username=alice"));
    }

    @Test
    void clientCertificateToStringHasNoCertBody() {
        Credential.ClientCertificate c = new Credential.ClientCertificate(java.util.List.of());
        assertEquals("ClientCertificate[chainLength=0]", c.toString());
    }

    @Test
    void principalIsImmutableAndDefensivelyCopied() {
        Set<String> roles = new HashSet<>(Set.of("admin", "reader"));
        Principal p = new Principal("user-1", roles, Map.of("tenant", "acme"), "basic");
        roles.add("mutated");
        assertEquals(Set.of("admin", "reader"), p.roles(), "roles must be a defensive copy, not aliased");
        assertThrows(UnsupportedOperationException.class, () -> p.roles().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> p.attributes().put("k", "v"));
    }

    @Test
    void principalRejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new Principal("  ", Set.of(), "mtls"));
        assertThrows(NullPointerException.class, () -> new Principal(null, Set.of(), "mtls"));
    }

    @Test
    void principalToStringShowsAttributeKeysNotValues() {
        Principal p = new Principal("u", Set.of("r"), Map.of("email", "secret@x.com"), "oidc");
        String s = p.toString();
        assertTrue(s.contains("attributeKeys=[email]"), "attribute KEYS are shown");
        assertFalse(s.contains("secret@x.com"), "attribute VALUES (possibly sensitive) are NOT shown");
    }

    @Test
    void deniedAndUnavailableCarryReasons() {
        AuthResult.Denied d = new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL, "nope");
        assertEquals(DenyReason.INVALID_CREDENTIAL, d.reason());
        AuthResult.Unavailable u = new AuthResult.Unavailable("backend down");
        assertEquals("backend down", u.reason());
    }
}
