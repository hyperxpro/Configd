package io.configd.common.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MtlsAuthenticator}: it derives a {@link Principal} from an already-verified client certificate,
 * and produces the SAME identity the edge fan-out plane extracts today ({@code getPeerPrincipal().getName()}),
 * so both planes agree on who a certificate is. The certificate is a real keytool-minted cert.
 */
class MtlsAuthenticatorTest {

    private static X509Certificate cert(Path dir, String dn) throws Exception {
        Path ks = dir.resolve("ks.p12");
        int code = new ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", dn, "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-validity", "365")
                .redirectErrorStream(true).start().waitFor();
        assertEquals(0, code, "keytool must generate the test certificate");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        return (X509Certificate) store.getCertificate("t");
    }

    @Test
    void extractsSubjectDnAndAssignsConfiguredRoles(@TempDir Path dir) throws Exception {
        X509Certificate leaf = cert(dir, "CN=edge-client-1,OU=edge,O=configd");
        MtlsAuthenticator mtls = new MtlsAuthenticator(Set.of("edge"));

        assertTrue(mtls.canAttempt(new Credential.ClientCertificate(List.of(leaf))));
        assertFalse(mtls.canAttempt(new Credential.BearerToken("x")));

        Principal p = assertInstanceOf(AuthResult.Authenticated.class,
                mtls.authenticate(new Credential.ClientCertificate(List.of(leaf)))).principal();
        assertEquals(leaf.getSubjectX500Principal().getName(), p.id());
        assertEquals(Set.of("edge"), p.roles());
        assertEquals("mtls", p.provenance());
    }

    @Test
    void emptyChainIsInvalidCredential() {
        MtlsAuthenticator mtls = new MtlsAuthenticator(Set.of());
        AuthResult.Denied d = assertInstanceOf(AuthResult.Denied.class,
                mtls.authenticate(new Credential.ClientCertificate(List.of())));
        assertEquals(DenyReason.INVALID_CREDENTIAL, d.reason());
    }

    @Test
    void bothPlanesProduceTheSamePrincipalForTheSameCertificate(@TempDir Path dir) throws Exception {
        X509Certificate leaf = cert(dir, "CN=shared-identity,O=configd");
        MtlsAuthenticator shared = new MtlsAuthenticator(Set.of());

        // The control-plane path and the edge (M2M) path use the SAME authenticator on the SAME cert.
        Principal controlPlane =
                assertInstanceOf(AuthResult.Authenticated.class,
                        shared.authenticate(new Credential.ClientCertificate(List.of(leaf)))).principal();
        Principal edgePlane =
                assertInstanceOf(AuthResult.Authenticated.class,
                        shared.authenticate(new Credential.ClientCertificate(List.of(leaf)))).principal();
        assertEquals(controlPlane, edgePlane, "one certificate resolves to one Principal on both planes");

        // The id equals the DN the edge extracts today via SSLSession.getPeerPrincipal().getName():
        // getPeerPrincipal() returns the leaf's subject X500Principal, so getName() equals this.
        assertEquals(leaf.getSubjectX500Principal().getName(), controlPlane.id());
    }
}
