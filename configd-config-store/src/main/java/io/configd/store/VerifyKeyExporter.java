package io.configd.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Exports public key from SigningKeyStore (Configd-private format) as X.509/SPKI DER.
 * Operator runs against leader's signing-key.bin, ships result to edges as --verify-key.
 * Fails (exit 1) on missing signing key file (refuses fresh generation).
 */
public final class VerifyKeyExporter {

    private VerifyKeyExporter() {
    }

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
