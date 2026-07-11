package io.configd.server.fanout;

import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.common.auth.RevocationChecker;
import io.configd.common.auth.RevocationMode;
import io.configd.common.auth.RevocationPolicy;
import io.configd.common.auth.RevocationStatus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link EdgeCertGate} wiring: the online-revocation admission decision (checker, then status, then
 * off/lax/strict) and the cert-{@code notAfter} close deadline. The lax-vs-strict decision itself is
 * proven exhaustively by {@code RevocationPolicyTest}; here we prove the gate threads a real chain to the
 * checker and honours the result, and that {@code OFF} never consults the checker (byte-identical). The
 * interior exemption is structural - the Raft transport never constructs one of these - so there is no
 * interior code path to reach a checker.
 */
class EdgeCertGateTest {

    private static X509Certificate leaf;

    @BeforeAll
    static void generateCert() throws Exception {
        Path dir = Files.createTempDirectory("configd-edge-certgate-");
        Path ks = dir.resolve("c.p12");
        int rc = new ProcessBuilder("keytool", "-genkeypair", "-alias", "c",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=edge-client,O=configd-test", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).inheritIO().start().waitFor();
        assertTrue(rc == 0, "keytool must generate the test cert");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        leaf = (X509Certificate) store.getCertificate("c");
        Files.deleteIfExists(ks);
        Files.deleteIfExists(dir);
    }

    private static EdgeCertGate gate(RevocationMode mode, RevocationChecker checker) {
        return new EdgeCertGate(new RevocationPolicy(mode, true, 3_000L), checker,
                CredentialExpiryPolicy.DEFAULTS, false);
    }

    @Test
    void offNeverConsultsTheChecker() {
        AtomicInteger calls = new AtomicInteger();
        EdgeCertGate g = gate(RevocationMode.OFF, (l, c) -> {
            calls.incrementAndGet();
            return RevocationStatus.REVOKED;
        });
        assertTrue(g.admit(List.of(leaf)), "OFF admits without a lookup");
        assertEquals(0, calls.get(), "OFF must never call the checker (byte-identical)");
    }

    @Test
    void laxAdmitsUnknownButRejectsRevoked() {
        assertTrue(gate(RevocationMode.LAX, (l, c) -> RevocationStatus.UNKNOWN).admit(List.of(leaf)),
                "lax fails open on an unreachable responder");
        assertTrue(gate(RevocationMode.LAX, (l, c) -> RevocationStatus.GOOD).admit(List.of(leaf)));
        assertFalse(gate(RevocationMode.LAX, (l, c) -> RevocationStatus.REVOKED).admit(List.of(leaf)),
                "lax still rejects a definite revoked");
    }

    @Test
    void strictRejectsUnknownAndRevokedAdmitsOnlyGood() {
        assertFalse(gate(RevocationMode.STRICT, (l, c) -> RevocationStatus.UNKNOWN).admit(List.of(leaf)),
                "strict fails closed on an unreachable responder");
        assertFalse(gate(RevocationMode.STRICT, (l, c) -> RevocationStatus.REVOKED).admit(List.of(leaf)));
        assertTrue(gate(RevocationMode.STRICT, (l, c) -> RevocationStatus.GOOD).admit(List.of(leaf)));
    }

    @Test
    void aNullCheckerUnderStrictIsUnknownFailClosed() {
        // No responder wired (the default) plus strict means every cert is UNKNOWN, so it is rejected.
        assertFalse(gate(RevocationMode.STRICT, null).admit(List.of(leaf)),
                "strict with no configured responder rejects every cert (documented foot-gun)");
        assertTrue(gate(RevocationMode.LAX, null).admit(List.of(leaf)),
                "lax with no configured responder fails open + alarms");
    }

    @Test
    void aThrowingCheckerIsTreatedAsUnknownNotPropagated() {
        RevocationChecker boom = (l, c) -> {
            throw new RuntimeException("responder blew up");
        };
        assertTrue(gate(RevocationMode.LAX, boom).admit(List.of(leaf)),
                "a throwing checker degrades to UNKNOWN (lax fails open), never propagates");
        assertFalse(gate(RevocationMode.STRICT, boom).admit(List.of(leaf)),
                "a throwing checker degrades to UNKNOWN (strict fails closed)");
    }

    @Test
    void certCloseDeadlineIsNoExpiryWhenEnforcementOff() {
        EdgeCertGate off = new EdgeCertGate(RevocationPolicy.OFF, null, CredentialExpiryPolicy.DEFAULTS, false);
        assertEquals(AuthState.NO_EXPIRY, off.certCloseDeadlineMillis(List.of(leaf)),
                "enforcement off => no active expiry (byte-identical)");
        assertFalse(off.enforcesCertExpiry());
    }

    @Test
    void certCloseDeadlineIsNotAfterPlusLeewayWhenEnforced() {
        CredentialExpiryPolicy leeway5s = new CredentialExpiryPolicy(
                0.20, 30_000L, 300_000L, 0.10, 300_000L, 3_600_000L, 5_000L);
        EdgeCertGate on = new EdgeCertGate(RevocationPolicy.OFF, null, leeway5s, true);
        long expected = leaf.getNotAfter().getTime() + 5_000L;
        assertEquals(expected, on.certCloseDeadlineMillis(List.of(leaf)),
                "enforcement on => close at notAfter + leeway");
        assertTrue(on.enforcesCertExpiry());
        assertEquals(AuthState.NO_EXPIRY, on.certCloseDeadlineMillis(List.of()),
                "an empty chain has no deadline");
    }
}
