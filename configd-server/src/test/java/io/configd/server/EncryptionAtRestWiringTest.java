package io.configd.server;

import io.configd.common.IntegrityEnvelope;
import io.configd.store.SigningKeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the secure-by-config wiring: the {@code configd.raft.encryption.enabled} flag toggles
 * {@link ConfigdServer#deriveRaftIntegrityEnvelope} between the byte-identical keyed HMAC envelope
 * (OFF, the default) and an AES-256-GCM encrypting envelope (ON), and an unknown KMS provider fails
 * closed. Mirrors D1FailClosedTest's direct-call approach (no full server boot needed).
 */
class EncryptionAtRestWiringTest {

    private static final String ENABLE = "configd.raft.encryption.enabled";
    private static final String PROVIDER = "configd.raft.encryption.kms.provider";
    private static final int WAL_MAGIC = 0x5257_414C; // "RWAL"
    private static final String SECRET = "wiring-secret-value-42";

    /** A signing key OUTSIDE the data dir, so the D-1 co-location guard passes. */
    private static SigningKeyStore keyStore(Path root) throws Exception {
        return SigningKeyStore.loadOrCreate(root.resolve("secrets").resolve("signing-key.bin"));
    }

    private static Path keyFile(Path root) {
        return root.resolve("secrets").resolve("signing-key.bin");
    }

    private static Path dataDir(Path root) {
        return root.resolve("data");
    }

    @Test
    void encryptionOffProducesByteIdenticalKeyedHmacEnvelope(@TempDir Path root) throws Exception {
        System.clearProperty(ENABLE);
        IntegrityEnvelope env = ConfigdServer.deriveRaftIntegrityEnvelope(
                keyStore(root), keyFile(root), dataDir(root));
        assertFalse(env.isEncrypting(), "default must NOT encrypt");
        assertTrue(env.isKeyed(), "default is the keyed HMAC envelope");
        byte[] wrapped = env.wrap(WAL_MAGIC, SECRET.getBytes(StandardCharsets.UTF_8));
        assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, wrapped[6], "OFF writes algId=HMAC (unchanged)");
    }

    @Test
    void encryptionOnProducesEncryptingEnvelopeWithNoPlaintextAndRoundTrips(@TempDir Path root)
            throws Exception {
        System.setProperty(ENABLE, "true");
        try {
            SigningKeyStore ks = keyStore(root);
            IntegrityEnvelope env = ConfigdServer.deriveRaftIntegrityEnvelope(
                    ks, keyFile(root), dataDir(root));
            assertTrue(env.isEncrypting(), "flag ON must encrypt");
            byte[] wrapped = env.wrap(WAL_MAGIC, SECRET.getBytes(StandardCharsets.UTF_8));
            assertEquals(IntegrityEnvelope.ALG_AES256_GCM, wrapped[6], "ON writes algId=AES256_GCM");
            assertFalse(new String(wrapped, StandardCharsets.ISO_8859_1).contains(SECRET),
                    "no plaintext in the encrypted envelope");

            // A SECOND envelope from the SAME signing key re-derives the same root -> decrypts it
            // (the restart path).
            IntegrityEnvelope env2 = ConfigdServer.deriveRaftIntegrityEnvelope(
                    keyStore(root), keyFile(root), dataDir(root));
            assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8), env2.unwrap(WAL_MAGIC, wrapped),
                    "a fresh boot from the same signing key decrypts the record");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void unknownKmsProviderFailsClosed(@TempDir Path root) throws Exception {
        System.setProperty(ENABLE, "true");
        System.setProperty(PROVIDER, "aws-kms"); // module not on the classpath
        try {
            SigningKeyStore ks = keyStore(root);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ConfigdServer.deriveRaftIntegrityEnvelope(ks, keyFile(root), dataDir(root)));
            assertTrue(ex.getMessage().contains("aws-kms")
                            && ex.getMessage().toLowerCase().contains("silent"),
                    "must refuse to silently downgrade: " + ex.getMessage());
        } finally {
            System.clearProperty(ENABLE);
            System.clearProperty(PROVIDER);
        }
    }
}
