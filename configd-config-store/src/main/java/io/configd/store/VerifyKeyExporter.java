package io.configd.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Exports the Ed25519 PUBLIC verify key from a {@link SigningKeyStore} file
 * ({@code signing-key.bin}) as X.509/SubjectPublicKeyInfo DER — the C2 verify-key
 * distribution path (C2 design §3.6): the control-plane operator runs this against the
 * leader's signing key file and ships the output to edges as {@code --verify-key}.
 * <p>
 * The output is the JDK's {@code PublicKey.getEncoded()} form, interoperable with
 * standard tooling ({@code openssl pkey -pubin -inform DER -text}).
 * <p>
 * Usage (the signing key file format is Configd-private, so keytool/openssl cannot read
 * it directly — this utility is the supported path):
 * <pre>
 *   java -cp configd-server.jar io.configd.store.VerifyKeyExporter \
 *       &lt;signing-key.bin&gt; &lt;verify-key.der&gt;
 * </pre>
 * Fails (exit 1) if the signing key file does not exist — exporting would otherwise
 * silently generate a FRESH key pair ({@link SigningKeyStore#loadOrCreate}), yielding a
 * verify key that matches nothing.
 */
public final class VerifyKeyExporter {

    private VerifyKeyExporter() {
    }

    /**
     * Exports the public key from {@code signingKeyFile} to {@code verifyKeyOut}
     * (X.509/SPKI DER). Refuses a missing signing key file.
     *
     * @param signingKeyFile the {@link SigningKeyStore} file (must exist)
     * @param verifyKeyOut   the DER output path (parent directories created)
     * @return the number of DER bytes written
     * @throws IOException              if the signing key file is missing/unreadable or
     *                                  the output cannot be written
     * @throws GeneralSecurityException if the key material cannot be decoded
     */
    public static int export(Path signingKeyFile, Path verifyKeyOut)
            throws IOException, GeneralSecurityException {
        if (!Files.exists(signingKeyFile)) {
            throw new IOException("signing key file does not exist: " + signingKeyFile
                    + " (refusing to generate a fresh key on export)");
        }
        SigningKeyStore store = SigningKeyStore.load(signingKeyFile);
        byte[] der = store.keyPair().getPublic().getEncoded();
        Path parent = verifyKeyOut.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(verifyKeyOut, der);
        return der.length;
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println(
                    "Usage: VerifyKeyExporter <signing-key.bin> <verify-key-out.der>");
            System.exit(1);
        }
        try {
            int n = export(Path.of(args[0]), Path.of(args[1]));
            System.out.println("Exported Ed25519 verify key (" + n + " bytes, X.509/SPKI DER) to "
                    + args[1]);
        } catch (Exception e) {
            System.err.println("Export failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
