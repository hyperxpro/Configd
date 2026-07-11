import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * E2E-compose secrets helper -- runs in java source-file mode at setup time only
 * (never inside a container, never a runtime dependency):
 *
 * <pre>
 *   java [-cp configd-server-shaded.jar] SecretsTool.java &lt;subcommand&gt; args...
 * </pre>
 *
 * Subcommands:
 * <ul>
 *   <li>{@code repack <in.p12> <out.p12> <password>} -- re-writes a keytool-built PKCS12
 *       (keytool refuses store passwords shorter than 6 chars) into an empty-password
 *       PKCS12, which is what the production CLI TLS path expects: {@code TlsConfig.mtls}
 *       hard-codes an empty store password. Key entries are re-protected with the empty
 *       password too ({@code TlsManager} uses one password for store and key).</li>
 *   <li>{@code truststore <out.p12> <alias=cert.pem>...} -- builds an empty-password
 *       PKCS12 trust store from PEM certificates (avoids keytool's password minimum
 *       entirely on the trust side).</li>
 *   <li>{@code signing-key <signing-key.bin> <verify-key.der>} -- mints (or loads) the
 *       cluster's shared Ed25519 signing key via the production
 *       {@code SigningKeyStore.loadOrCreate} and exports the verify key via the
 *       production {@code VerifyKeyExporter} (requires the server shaded jar on the
 *       classpath). Generating it once at setup and mounting the same file into all
 *       three CP nodes is required: each node signs its own fan-out stream at apply
 *       time, so a per-node key would break edge verification at the first leader
 *       failover.</li>
 * </ul>
 */
public class SecretsTool {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
        }
        switch (args[0]) {
            case "repack" -> repack(Path.of(args[1]), Path.of(args[2]), args[3].toCharArray());
            case "truststore" -> truststore(args);
            case "signing-key" -> signingKey(Path.of(args[1]), Path.of(args[2]));
            default -> usage();
        }
    }

    private static void repack(Path in, Path out, char[] pass) throws Exception {
        char[] empty = new char[0];
        KeyStore src = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(in)) {
            src.load(is, pass);
        }
        KeyStore dst = KeyStore.getInstance("PKCS12");
        dst.load(null, null);
        for (var aliases = src.aliases(); aliases.hasMoreElements();) {
            String alias = aliases.nextElement();
            if (src.isKeyEntry(alias)) {
                dst.setKeyEntry(alias, src.getKey(alias, pass), empty,
                        src.getCertificateChain(alias));
            } else {
                dst.setCertificateEntry(alias, src.getCertificate(alias));
            }
        }
        store(dst, out);
        verifyLoadsEmpty(out);
        System.out.println("repacked " + in + " -> " + out + " (empty store password)");
    }

    private static void truststore(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            String alias = args[i].substring(0, eq);
            Path pem = Path.of(args[i].substring(eq + 1));
            try (InputStream is = Files.newInputStream(pem)) {
                Certificate cert = cf.generateCertificate(is);
                ts.setCertificateEntry(alias, cert);
            }
        }
        store(ts, out);
        verifyLoadsEmpty(out);
        System.out.println("trust store " + out + " (" + (args.length - 2) + " certs)");
    }

    private static void signingKey(Path keyFile, Path verifyKeyOut) throws Exception {
        // Production classes from the server shaded jar -- reflective so this file also
        // compiles standalone (source-file mode compiles before the classpath is probed
        // for these optional classes).
        Class<?> store = Class.forName("io.configd.store.SigningKeyStore");
        store.getMethod("loadOrCreate", Path.class).invoke(null, keyFile);
        Class<?> exporter = Class.forName("io.configd.store.VerifyKeyExporter");
        int n = (int) exporter.getMethod("export", Path.class, Path.class)
                .invoke(null, keyFile, verifyKeyOut);
        System.out.println("signing key at " + keyFile + "; verify key " + verifyKeyOut
                + " (" + n + " bytes, X.509/SPKI DER)");
    }

    private static void store(KeyStore ks, Path out) throws Exception {
        try (OutputStream os = Files.newOutputStream(out)) {
            ks.store(os, new char[0]);
        }
    }

    /** The whole point: prove the artifact loads exactly the way TlsManager will load it. */
    private static void verifyLoadsEmpty(Path p) throws Exception {
        KeyStore check = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(p)) {
            check.load(is, new char[0]);
        }
    }

    private static void usage() {
        System.err.println("Usage: SecretsTool.java repack <in.p12> <out.p12> <password>");
        System.err.println("     | SecretsTool.java truststore <out.p12> <alias=cert.pem>...");
        System.err.println("     | SecretsTool.java signing-key <signing-key.bin> <verify-key.der>");
        System.exit(2);
    }
}
