package io.configd.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VerifyKeyExporter}. Verifies that the exporter produces an X.509/SPKI
 * DER public key from {@code signing-key.bin} that (a) round-trips through the JDK Ed25519
 * {@link KeyFactory} (how the edge loads {@code --verify-key}) and (b) verifies a signature
 * made with the corresponding private key.
 */
class VerifyKeyExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportedKeyLoadsAndVerifiesALeaderSignature() throws Exception {
        Path signingKey = tempDir.resolve("signing-key.bin");
        SigningKeyStore store = SigningKeyStore.loadOrCreate(signingKey);
        Path out = tempDir.resolve("verify-key.der");

        int n = VerifyKeyExporter.export(signingKey, out);
        byte[] der = Files.readAllBytes(out);
        assertTrue(n > 0);
        assertArrayEquals(store.keyPair().getPublic().getEncoded(), der,
                "the export is the SPKI encoding of the signing key's public half");

        // The edge-side load path: X509EncodedKeySpec + Ed25519 KeyFactory.
        PublicKey loaded = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(der));

        // End-to-end: leader signs, exported key verifies.
        byte[] payload = "delta-payload".getBytes();
        byte[] sig = new ConfigSigner(store.keyPair()).sign(payload);
        assertTrue(new ConfigSigner(loaded).verify(payload, sig));
        assertFalse(new ConfigSigner(loaded).verify("tampered".getBytes(), sig));
    }

    @Test
    void refusesAMissingSigningKeyFile() {
        // Exporting a nonexistent key must FAIL, never silently generate a fresh pair
        // (a typo'd path would otherwise yield a verify key that matches nothing).
        Path missing = tempDir.resolve("nope.bin");
        assertThrows(IOException.class,
                () -> VerifyKeyExporter.export(missing, tempDir.resolve("out.der")));
        assertFalse(Files.exists(missing), "export must not create the signing key");
    }
}
