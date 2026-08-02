package io.configd.client.edge;

import io.configd.client.tls.ClientTls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

final class CertFixtures {

    static final char[] PASSWORD = "changeit".toCharArray();

    final Path serverKeystore;
    final Path badServerKeystore;
    final Path clientKeystore;
    final Path clientTrust;
    final Path serverTrust;

    CertFixtures(Path dir) throws Exception {
        this.serverKeystore = dir.resolve("server.p12");
        this.badServerKeystore = dir.resolve("badserver.p12");
        this.clientKeystore = dir.resolve("client.p12");
        this.clientTrust = dir.resolve("client-trust.p12");
        this.serverTrust = dir.resolve("server-trust.p12");

        genKeyPair(serverKeystore, "server", "CN=server", "dns:localhost,ip:127.0.0.1");
        genKeyPair(badServerKeystore, "badserver", "CN=badserver", "dns:wronghost.example");
        genKeyPair(clientKeystore, "client", "CN=client", null);

        Path serverPem = dir.resolve("server.pem");
        Path badServerPem = dir.resolve("badserver.pem");
        Path clientPem = dir.resolve("client.pem");
        exportCert(serverKeystore, "server", serverPem);
        exportCert(badServerKeystore, "badserver", badServerPem);
        exportCert(clientKeystore, "client", clientPem);

        // The client trusts both server certs (so the "bad" case fails on SAN, not on trust); the server
        // trusts the client cert.
        importCert(clientTrust, "server", serverPem);
        importCert(clientTrust, "badserver", badServerPem);
        importCert(serverTrust, "client", clientPem);
    }

    /** A server {@link SSLContext} (key + trust-client), optionally with the SAN-mismatching "bad" cert. */
    SSLContext serverContext(boolean badServer) throws Exception {
        KeyStore key = load(badServer ? badServerKeystore : serverKeystore);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(key, PASSWORD);
        KeyStore trust = load(serverTrust);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    ClientTls clientMutualTls() throws Exception {
        return ClientTls.mutualTls(clientKeystore, PASSWORD, clientTrust, PASSWORD);
    }

    /** A trust-only client (verifies the server, presents no cert — the token/basic posture). */
    ClientTls clientTrustOnly() throws Exception {
        return ClientTls.trustOnly(clientTrust, PASSWORD);
    }

    private static void genKeyPair(Path keystore, String alias, String dname, String san) throws Exception {
        var cmd = new java.util.ArrayList<>(java.util.List.of(
                "keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", dname, "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", "changeit", "-keypass", "changeit"));
        if (san != null) {
            cmd.add("-ext");
            cmd.add("san=" + san);
        }
        run(cmd.toArray(new String[0]));
    }

    private static void exportCert(Path keystore, String alias, Path pem) throws Exception {
        run("keytool", "-exportcert", "-alias", alias, "-keystore", keystore.toString(),
                "-storepass", "changeit", "-rfc", "-file", pem.toString());
    }

    private static void importCert(Path truststore, String alias, Path pem) throws Exception {
        run("keytool", "-importcert", "-alias", alias, "-file", pem.toString(),
                "-keystore", truststore.toString(), "-storepass", "changeit",
                "-storetype", "PKCS12", "-noprompt");
    }

    private static KeyStore load(Path p12) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            ks.load(in, PASSWORD);
        }
        return ks;
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("keytool failed (" + rc + "): " + command[1] + "\n" + output);
        }
    }
}
