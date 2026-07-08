package io.configd.common.auth;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The functional CRL-file {@link RevocationChecker} against a REAL revoked certificate: a CA issues a
 * client cert, a CRL revokes its serial, and the checker reports {@code REVOKED}; a CRL that does not list
 * it reports {@code GOOD}; a missing or stale CRL degrades to {@code UNKNOWN} (never throws) so the
 * {@link RevocationPolicy} mode decides the fail-open/fail-closed posture.
 */
class CrlFileRevocationCheckerTest {

    private static Path dir;
    private static X509Certificate revokedLeaf;
    private static Path revokedCrl;
    private static Path emptyCrl;
    private static long revokedCrlNextUpdateMillis;

    @BeforeAll
    static void generate() throws Exception {
        dir = Files.createTempDirectory("configd-crl-checker-");
        Path caKs = dir.resolve("ca.p12");
        Path clientKs = dir.resolve("client.p12");
        Path clientCsr = dir.resolve("client.csr");
        Path clientPem = dir.resolve("client.pem");
        Path caPem = dir.resolve("ca.pem");
        revokedCrl = dir.resolve("revoked.crl");
        emptyCrl = dir.resolve("empty.crl");

        // A CA (self-signed, CA:true) issues a client cert; the client keystore holds the client+CA chain.
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=configd-test-ca,O=configd-test", "-ext", "bc:c",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3",
                "-storetype", "PKCS12", "-keystore", caKs.toString(), "-storepass", "changeit", "-keypass", "changeit");
        keytool("-genkeypair", "-alias", "client", "-dname", "CN=edge-client,O=configd-test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3",
                "-storetype", "PKCS12", "-keystore", clientKs.toString(), "-storepass", "changeit", "-keypass", "changeit");
        keytool("-certreq", "-alias", "client", "-keystore", clientKs.toString(), "-storepass", "changeit",
                "-file", clientCsr.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-infile", clientCsr.toString(), "-outfile", clientPem.toString(), "-rfc", "-validity", "3");
        keytool("-exportcert", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-rfc", "-file", caPem.toString());
        keytool("-importcert", "-alias", "ca", "-file", caPem.toString(), "-keystore", clientKs.toString(),
                "-storepass", "changeit", "-noprompt");
        keytool("-importcert", "-alias", "client", "-file", clientPem.toString(), "-keystore", clientKs.toString(),
                "-storepass", "changeit", "-noprompt");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(clientKs)) {
            ks.load(in, "changeit".toCharArray());
        }
        revokedLeaf = (X509Certificate) ks.getCertificate("client");

        // A CRL revoking the leaf's serial, and an empty CRL that revokes nothing (both CA-signed).
        keytool("-gencrl", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-id", revokedLeaf.getSerialNumber().toString() + ":1", "-file", revokedCrl.toString(), "-validity", "3");
        keytool("-gencrl", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-file", emptyCrl.toString(), "-validity", "3");

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(revokedCrl)) {
            revokedCrlNextUpdateMillis = ((X509CRL) cf.generateCRL(in)).getNextUpdate().getTime();
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanup() throws Exception {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort
                }
            });
        }
    }

    @Test
    void reportsRevokedWhenTheSerialIsOnAFreshCrl() {
        CrlFileRevocationChecker checker = new CrlFileRevocationChecker(revokedCrl);
        assertEquals(RevocationStatus.REVOKED, checker.check(revokedLeaf, List.of(revokedLeaf)),
                "the leaf's serial is on the fresh CRL -> REVOKED");
    }

    @Test
    void reportsGoodWhenTheSerialIsNotOnAFreshCrl() {
        CrlFileRevocationChecker checker = new CrlFileRevocationChecker(emptyCrl);
        assertEquals(RevocationStatus.GOOD, checker.check(revokedLeaf, List.of(revokedLeaf)),
                "an empty (but fresh) CRL does not list the leaf -> GOOD");
    }

    @Test
    void reportsUnknownWhenTheCrlFileIsMissing() {
        CrlFileRevocationChecker checker = new CrlFileRevocationChecker(dir.resolve("does-not-exist.crl"));
        assertEquals(RevocationStatus.UNKNOWN, checker.check(revokedLeaf, List.of(revokedLeaf)),
                "a missing CRL is the responder-down analogue -> UNKNOWN (never throws)");
    }

    @Test
    void reportsUnknownWhenTheCrlIsStale() {
        CrlFileRevocationChecker checker = new CrlFileRevocationChecker(revokedCrl);
        // Evaluated at a time past the CRL's own nextUpdate -> stale -> UNKNOWN (not a stale REVOKED/GOOD).
        assertEquals(RevocationStatus.UNKNOWN, checker.checkAt(revokedLeaf, revokedCrlNextUpdateMillis + 1),
                "past nextUpdate the CRL is stale -> UNKNOWN");
        // ...but BEFORE nextUpdate the same file still reports the definite answer.
        assertEquals(RevocationStatus.REVOKED, checker.checkAt(revokedLeaf, revokedCrlNextUpdateMillis - 1),
                "before nextUpdate the CRL is honoured -> REVOKED");
    }

    private static void keytool(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "keytool";
        System.arraycopy(args, 0, cmd, 1, args.length);
        int rc = new ProcessBuilder(cmd).redirectErrorStream(true).inheritIO().start().waitFor();
        assertTrue(rc == 0, "keytool failed: " + String.join(" ", cmd));
    }
}
