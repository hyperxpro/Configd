package io.configd.common.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatorModesTest {

    @Test
    void noAuthAcceptsEveryoneAsAnonymous() {
        NoAuthAuthenticator none = new NoAuthAuthenticator();
        assertTrue(none.canAttempt(new Credential.BearerToken("anything")));
        AuthResult r = none.authenticate(new Credential.BearerToken("anything"));
        Principal p = assertInstanceOf(AuthResult.Authenticated.class, r).principal();
        assertEquals(NoAuthAuthenticator.ANONYMOUS_ID, p.id());
        assertEquals("none", p.provenance());
    }

    @Test
    void bearerAcceptsMatchAndHardRejectsMismatch() {
        BearerTokenAuthenticator bearer = new BearerTokenAuthenticator("s3cret", "root", Set.of());
        assertTrue(bearer.canAttempt(new Credential.BearerToken("x")));
        assertFalse(bearer.canAttempt(new Credential.BasicCredential("u", "p".toCharArray())));

        Principal p = assertInstanceOf(AuthResult.Authenticated.class,
                bearer.authenticate(new Credential.BearerToken("s3cret"))).principal();
        assertEquals("root", p.id());
        assertEquals("bearer", p.provenance());

        // A catch-all: any non-matching token is a HARD reject (INVALID_CREDENTIAL), never a fall-through.
        AuthResult.Denied d = assertInstanceOf(AuthResult.Denied.class,
                bearer.authenticate(new Credential.BearerToken("wrong")));
        assertEquals(DenyReason.INVALID_CREDENTIAL, d.reason());
    }

    @Test
    void basicVerifiesHashedPasswordAndAssignsRoles() {
        String hash = BasicAuthPasswords.hash("correct horse".toCharArray());
        BasicAuthenticator basic = new BasicAuthenticator(
                Map.of("alice", new BasicAuthenticator.User(hash, Set.of("reader"))));

        Principal p = assertInstanceOf(AuthResult.Authenticated.class,
                basic.authenticate(new Credential.BasicCredential("alice", "correct horse".toCharArray()))).principal();
        assertEquals("alice", p.id());
        assertEquals(Set.of("reader"), p.roles());
        assertEquals("basic", p.provenance());
    }

    @Test
    void basicRejectsWrongPasswordAndUnknownUser() {
        String hash = BasicAuthPasswords.hash("right".toCharArray());
        BasicAuthenticator basic = new BasicAuthenticator(
                Map.of("alice", new BasicAuthenticator.User(hash, Set.of())));

        AuthResult.Denied wrongPass = assertInstanceOf(AuthResult.Denied.class,
                basic.authenticate(new Credential.BasicCredential("alice", "wrong".toCharArray())));
        assertEquals(DenyReason.INVALID_CREDENTIAL, wrongPass.reason());

        AuthResult.Denied unknown = assertInstanceOf(AuthResult.Denied.class,
                basic.authenticate(new Credential.BasicCredential("bob", "whatever".toCharArray())));
        assertEquals(DenyReason.INVALID_CREDENTIAL, unknown.reason());
    }

    @Test
    void pbkdf2HashRoundTripsAndRejectsWrong() {
        String hash = BasicAuthPasswords.hash("p@ssw0rd".toCharArray());
        assertTrue(BasicAuthPasswords.isValidHash(hash));
        assertTrue(hash.startsWith("pbkdf2-sha256$"));
        assertTrue(BasicAuthPasswords.verify(hash, "p@ssw0rd".toCharArray()));
        assertFalse(BasicAuthPasswords.verify(hash, "p@ssw0rD".toCharArray()));
        assertFalse(BasicAuthPasswords.verify("not-a-hash", "x".toCharArray()), "malformed stored hash fails closed");
        // A fresh hash of the same password uses a fresh salt, so the stored strings differ.
        assertFalse(hash.equals(BasicAuthPasswords.hash("p@ssw0rd".toCharArray())));
    }
}
