package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Authenticator;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.BasicAuthPasswords;
import io.configd.common.auth.Credential;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The SPI authenticator chain wired into the HTTP control plane through {@link AdminApiHandler}: HTTP Basic
 * resolution, the authenticated-but-unauthorized 403, the missing/invalid 401, and the new
 * Unavailable -&gt; 503 outcome. Driven directly against the decision core (as
 * {@link ReservedPrefixAdminGateTest} does), with the chain supplied via the SPI constructor.
 */
class AdminApiChainAuthTest {

    private static final class CapturingProposer implements ConfigWriteService.RaftProposer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ConfigWriteService.ProposeCommitResult propose(ConfigScope scope, List<String> keys, byte[] command) {
            calls.incrementAndGet();
            return new ConfigWriteService.ProposeCommitResult.Committed(1L);
        }
    }

    private static ConfigWriteService.RaftProposer proposer() {
        return new CapturingProposer();
    }

    private static AdminApiHandler handler(AclService acl, AuthenticatorChain chain) {
        ConfigWriteService writeService = new ConfigWriteService(proposer(), null, null);
        return new AdminApiHandler(new HealthService(), /* exporter */ null, new VersionedConfigStore(),
                writeService, /* readService */ null, /* authInterceptor */ null, acl,
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), /* auditLog */ null,
                /* replayGuard */ null, /* leadershipAdmin */ null, chain);
    }

    private static ConfigSourceStub cfg(Map<String, String> m) {
        return new ConfigSourceStub(m);
    }

    private record ConfigSourceStub(Map<String, String> m) implements io.configd.common.config.ConfigSource {
        @Override public Optional<String> getString(String key) {
            return Optional.ofNullable(m.get(key));
        }
        @Override public Set<String> keysWithPrefix(String prefix) {
            return m.keySet().stream().filter(k -> k.startsWith(prefix))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static AdminApiHandler.AdminRequest req(String method, String key, String authHeader, byte[] body) {
        final URI uri;
        try {
            uri = new URI(null, null, "/v1/config/" + key, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return new AdminApiHandler.AdminRequest() {
            @Override public String method() { return method; }
            @Override public URI uri() { return uri; }
            @Override public String header(String name) {
                return "Authorization".equalsIgnoreCase(name) ? authHeader : null;
            }
            @Override public byte[] body() { return body == null ? new byte[0] : body; }
        };
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static int status(AdminApiHandler h, String method, String key, String authHeader, String body)
            throws Exception {
        return h.handle(req(method, key, authHeader, body == null ? null : body.getBytes(StandardCharsets.UTF_8)))
                .status();
    }

    // ---- HTTP Basic via the chain ----------------------------------------------------------------

    @Test
    void basicChainResolvesAuthnAndAuthz() throws Exception {
        String aliceHash = BasicAuthPasswords.hash("wonderland".toCharArray());
        String bobHash = BasicAuthPasswords.hash("builder".toCharArray());
        AuthenticatorChain chain = AuthenticatorChain.build(List.of("basic"),
                cfg(Map.of("configd.auth.basic.users", "alice:" + aliceHash + ",bob:" + bobHash)));

        AclService acl = new AclService();
        acl.grant("", "alice", EnumSet.of(AclService.Permission.READ));               // read-only
        acl.grant("app.", "bob", EnumSet.of(AclService.Permission.READ, AclService.Permission.WRITE));
        AdminApiHandler h = handler(acl, chain);

        // No credential -> 401; wrong password -> 401 (authentication, not authorization).
        assertEquals(401, status(h, "GET", "app.x", null, null));
        assertEquals(401, status(h, "GET", "app.x", basic("alice", "WRONG"), null));

        // alice authenticates but has no WRITE -> 403 on PUT (authenticated-but-unauthorized).
        assertEquals(403, status(h, "PUT", "app.x", basic("alice", "wonderland"), "value"));

        // bob has WRITE under app. -> the write is authorized (reaches the committing proposer) -> 200.
        assertEquals(200, status(h, "PUT", "app.x", basic("bob", "builder"), "value"));

        // A read alice IS allowed: not a 401 and not a 403 (auth + READ both pass).
        int readStatus = status(h, "GET", "app.x", basic("alice", "wonderland"), null);
        assertNotEquals(401, readStatus);
        assertNotEquals(403, readStatus);
    }

    // ---- Unavailable -> 503 ----------------------------------------------------------------------

    @Test
    void unavailableAuthenticatorMapsTo503() throws Exception {
        Authenticator down = new Authenticator() {
            @Override public String type() { return "down"; }
            @Override public boolean canAttempt(Credential c) { return true; }
            @Override public AuthResult authenticate(Credential c) {
                return new AuthResult.Unavailable("identity backend unreachable");
            }
        };
        AdminApiHandler h = handler(new AclService(), new AuthenticatorChain(List.of(down)));
        assertEquals(503, status(h, "GET", "app.x", basic("u", "p"), null));
        assertEquals(503, status(h, "PUT", "app.x", basic("u", "p"), "v"));
    }

    // ---- mTLS chain over HTTP has no client cert to extract -> 401 (fail-closed) ------------------

    @Test
    void mtlsChainOverHttpWithoutClientCertIs401() throws Exception {
        AuthenticatorChain chain = AuthenticatorChain.build(List.of("mtls"), cfg(Map.of()));
        AdminApiHandler h = handler(new AclService(), chain);
        // A header credential (basic/bearer) does not match the mtls authenticator, and no peer cert is
        // present -> 401 (fail-closed).
        assertEquals(401, status(h, "GET", "app.x", basic("u", "p"), null));
        assertEquals(401, status(h, "GET", "app.x", "Bearer sometoken", null));
        assertEquals(401, status(h, "GET", "app.x", null, null));
    }

    @Test
    void mtlsChainOverHttpAuthenticatesAVerifiedClientCert() throws Exception {
        X509Certificate cert = generateCert("CN=admin-client,O=configd");
        AuthenticatorChain chain = AuthenticatorChain.build(List.of("mtls"), cfg(Map.of()));
        AclService acl = new AclService();
        acl.grant("app.", cert.getSubjectX500Principal().getName(),
                EnumSet.of(AclService.Permission.READ, AclService.Permission.WRITE));
        AdminApiHandler h = handler(acl, chain);

        // No Authorization header, but a verified client cert -> authenticate as the cert-DN principal.
        assertEquals(200, statusWithCert(h, "PUT", "app.x", cert, "value"));
        int get = statusWithCert(h, "GET", "app.x", cert, null);
        assertNotEquals(401, get);
        assertNotEquals(403, get);
    }

    private static X509Certificate generateCert(String dn) throws Exception {
        Path dir = Files.createTempDirectory("mtls-http-cert");
        Path ks = dir.resolve("ks.p12");
        int code = new ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", dn, "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-validity", "365")
                .redirectErrorStream(true).start().waitFor();
        if (code != 0) {
            throw new IllegalStateException("keytool failed to generate the test certificate");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        return (X509Certificate) store.getCertificate("t");
    }

    private static int statusWithCert(AdminApiHandler h, String method, String key, X509Certificate cert, String body)
            throws Exception {
        URI uri = new URI(null, null, "/v1/config/" + key, null, null);
        AdminApiHandler.AdminRequest r = new AdminApiHandler.AdminRequest() {
            @Override public String method() { return method; }
            @Override public URI uri() { return uri; }
            @Override public String header(String name) { return null; } // no Authorization header
            @Override public byte[] body() { return body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8); }
            @Override public List<X509Certificate> peerCertificates() { return List.of(cert); }
        };
        return h.handle(r).status();
    }
}
