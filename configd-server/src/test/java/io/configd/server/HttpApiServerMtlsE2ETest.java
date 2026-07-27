package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.NodeId;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end proof that {@code configd.auth.mode=mtls} works over the HTTPS admin plane: the JDK
 * {@link HttpApiServer} requests a client certificate, and a client presenting a verified cert is
 * authenticated as its cert-DN principal (and authorized by that principal's ACL), while a client with NO
 * certificate is rejected 401. Certificates are real keytool-minted certs; both sides trust each other via
 * a shared trust store (the same mechanism {@code TlsManager} builds in production).
 */
@Timeout(60)
class HttpApiServerMtlsE2ETest {

    private static final char[] PASS = "changeit".toCharArray();

    @TempDir
    Path dir;

    private record CfgStub(Map<String, String> m) implements io.configd.common.config.ConfigSource {
        @Override public Optional<String> getString(String key) { return Optional.ofNullable(m.get(key)); }
        @Override public Set<String> keysWithPrefix(String p) {
            return m.keySet().stream().filter(k -> k.startsWith(p)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private interface AdminServer { int port(); void stop(); }

    @Test
    void jdkHttpMtlsModeAuthenticatesAClientCertAndRejectsNone() throws Exception {
        runMtlsScenario(false);
    }

    @Test
    void nettyHttpMtlsModeAuthenticatesAClientCertAndRejectsNone() throws Exception {
        runMtlsScenario(true);
    }

    private void runMtlsScenario(boolean netty) throws Exception {
        Path serverKs = dir.resolve("server.p12");
        Path clientKs = dir.resolve("client.p12");
        Path trust = dir.resolve("trust.p12");
        genKeyPair(serverKs, "server", "CN=localhost", "san=ip:127.0.0.1");
        genKeyPair(clientKs, "client", "CN=admin-client,O=configd", null);
        buildTrustStore(trust, Map.of("server", serverKs, "client", clientKs));

        String clientDn = loadCert(clientKs, "client").getSubjectX500Principal().getName();

        AuthenticatorChain chain = AuthenticatorChain.build(List.of("mtls"), new CfgStub(Map.of()));
        AclService acl = new AclService();
        acl.grant("app.", clientDn, EnumSet.of(AclService.Permission.READ));

        VersionedConfigStore store = new VersionedConfigStore();
        store.put("app.feature", "on".getBytes(StandardCharsets.UTF_8), 1);

        AdminServer server = boot(netty, sslContext(serverKs, trust), store, acl, chain);
        try {
            URI uri = URI.create("https://127.0.0.1:" + server.port() + "/v1/config/app.feature");

            HttpClient withCert = HttpClient.newBuilder().sslContext(sslContext(clientKs, trust)).build();
            HttpResponse<String> ok = withCert.send(HttpRequest.newBuilder().uri(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(),
                    (netty ? "netty" : "jdk") + ": a verified client cert must authenticate on the HTTPS admin plane");

            HttpClient noCert = HttpClient.newBuilder().sslContext(trustOnlyContext(trust)).build();
            HttpResponse<String> denied = noCert.send(HttpRequest.newBuilder().uri(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode(),
                    (netty ? "netty" : "jdk") + ": no client certificate must be 401 under mode=mtls");
        } finally {
            server.stop();
        }
    }

    private static AdminServer boot(boolean netty, SSLContext ctx, VersionedConfigStore store, AclService acl,
                                    AuthenticatorChain chain) throws Exception {
        ConfigWriteService write = new ConfigWriteService(
                (s, k, c) -> new ConfigWriteService.ProposeCommitResult.Committed(1L), null, null);
        PrometheusExporter exporter = new PrometheusExporter(new MetricsRegistry());
        if (netty) {
            NettyHttpApiServer s = new NettyHttpApiServer(0, ctx, new HealthService(), exporter, store, write,
                    null, null, acl, StrongReadPolicy.defaultPolicy(), (sc, k) -> NodeId.of(1), null, null, null, chain);
            s.start();
            return new AdminServer() {
                @Override public int port() { return s.port(); }
                @Override public void stop() { s.stop(); }
            };
        }
        HttpApiServer s = new HttpApiServer(0, ctx, new HealthService(), exporter, store, write,
                null, null, acl, StrongReadPolicy.defaultPolicy(), (sc, k) -> NodeId.of(1), null, null, null, chain);
        s.start();
        return new AdminServer() {
            @Override public int port() { return s.port(); }
            @Override public void stop() { s.stop(0); }
        };
    }


    private static void genKeyPair(Path keystore, String alias, String dn, String ext) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(List.of("keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", dn, "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit", "-validity", "365"));
        if (ext != null) {
            cmd.add("-ext");
            cmd.add(ext);
        }
        run(cmd);
    }

    private static void buildTrustStore(Path trust, Map<String, Path> keystores) throws Exception {
        for (Map.Entry<String, Path> e : keystores.entrySet()) {
            Path crt = trust.resolveSibling(e.getKey() + ".crt");
            run(List.of("keytool", "-exportcert", "-alias", e.getKey(), "-keystore", e.getValue().toString(),
                    "-storepass", "changeit", "-file", crt.toString()));
            run(List.of("keytool", "-importcert", "-noprompt", "-alias", e.getKey(), "-file", crt.toString(),
                    "-keystore", trust.toString(), "-storetype", "PKCS12", "-storepass", "changeit"));
        }
    }

    private static void run(java.util.List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + cmd + "\n" + out);
        }
    }

    private static X509Certificate loadCert(Path keystore, String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASS);
        }
        return (X509Certificate) ks.getCertificate(alias);
    }

    private static SSLContext sslContext(Path keystore, Path trust) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASS);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), trustManagers(trust), null);
        return ctx;
    }

    private static SSLContext trustOnlyContext(Path trust) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, trustManagers(trust), null);
        return ctx;
    }

    private static javax.net.ssl.TrustManager[] trustManagers(Path trust) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trust)) {
            ts.load(in, PASS);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf.getTrustManagers();
    }
}
