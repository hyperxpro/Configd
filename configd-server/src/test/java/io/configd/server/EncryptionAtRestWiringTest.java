package io.configd.server;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
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
    private static final String REQUIRE = "configd.raft.encryption.requireEncrypted";
    private static final int WAL_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SCOPE = 0;               // gid 0 (N=1); same on wrap+read here
    private static final String SECRET = "wiring-secret-value-42";

    /** A signing key outside the data dir (a co-located key would defeat the at-rest integrity
     *  guarantee it backs). */
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
    void encryptionOffProducesTermVersionedHmacEnvelope(@TempDir Path root) throws Exception {
        System.clearProperty(ENABLE);
        IntegrityEnvelope env = ConfigdServer.deriveRaftIntegrityEnvelope(
                keyStore(root), keyFile(root), dataDir(root));
        assertFalse(env.isEncrypting(), "default (encryption OFF) must NOT encrypt");
        assertTrue(env.isKeyed(), "default is the term-versioned keyed HMAC envelope");
        byte[] wrapped = env.wrap(WAL_MAGIC, SCOPE, SECRET.getBytes(StandardCharsets.UTF_8));
        assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, wrapped[6], "OFF writes algId=HMAC");
        // keyTerm is at v3 offset 12; a fresh node mints activeTerm 1.
        int keyTerm = ((wrapped[12] & 0xFF) << 24) | ((wrapped[13] & 0xFF) << 16)
                | ((wrapped[14] & 0xFF) << 8) | (wrapped[15] & 0xFF);
        assertEquals(1, keyTerm, "the HMAC record is stamped with the keyring activeTerm");
        // A second boot from the SAME signing key loads the SAME keyring -> verifies the record.
        IntegrityEnvelope env2 = ConfigdServer.deriveRaftIntegrityEnvelope(
                keyStore(root), keyFile(root), dataDir(root));
        assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8), env2.unwrap(WAL_MAGIC, SCOPE, wrapped),
                "a fresh boot from the same signing key verifies the term-versioned HMAC record");
    }

    @Test
    void keylessPostureIsByteIdentical_noKeyTerm() {
        // The operator relaxed byte-identity ONLY for the auth-on HMAC sub-case; the keyless posture
        // (encryption off AND auth off) MUST stay byte-identical: no keyTerm, payload at offset 12.
        byte[] payload = "keyless-unchanged".getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = IntegrityEnvelope.keyless().wrap(WAL_MAGIC, SCOPE, payload);
        assertEquals(IntegrityEnvelope.ALG_NONE, wrapped[6], "keyless writes algId=NONE");
        // header(8) + scopeId(4) + payload + CRC(4) - NO keyTerm inserted.
        assertEquals(IntegrityEnvelope.HEADER_SIZE + IntegrityEnvelope.SCOPE_ID_SIZE
                        + payload.length + IntegrityEnvelope.CRC_SIZE, wrapped.length,
                "keyless layout is unchanged (no keyTerm)");
        byte[] body = java.util.Arrays.copyOfRange(wrapped, 12, 12 + payload.length);
        assertArrayEquals(payload, body, "keyless payload sits at offset 12, immediately after scopeId");
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
            byte[] wrapped = env.wrap(WAL_MAGIC, SCOPE,SECRET.getBytes(StandardCharsets.UTF_8));
            assertEquals(IntegrityEnvelope.ALG_AES256_GCM, wrapped[6], "ON writes algId=AES256_GCM");
            assertFalse(new String(wrapped, StandardCharsets.ISO_8859_1).contains(SECRET),
                    "no plaintext in the encrypted envelope");

            // A SECOND envelope from the SAME signing key re-derives the same root -> decrypts it
            // (the restart path).
            IntegrityEnvelope env2 = ConfigdServer.deriveRaftIntegrityEnvelope(
                    keyStore(root), keyFile(root), dataDir(root));
            assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8), env2.unwrap(WAL_MAGIC, SCOPE,wrapped),
                    "a fresh boot from the same signing key decrypts the record");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void requireEncryptedRefusesLegacyRecordsAndDefaultAccepts(@TempDir Path root) throws Exception {
        // A legacy algId=1 record written under the OFF (keyed HMAC) posture, same signing key.
        System.clearProperty(ENABLE);
        byte[] legacy = ConfigdServer.deriveRaftIntegrityEnvelope(keyStore(root), keyFile(root), dataDir(root))
                .wrap(WAL_MAGIC, SCOPE,SECRET.getBytes(StandardCharsets.UTF_8));
        assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, legacy[6]);

        System.setProperty(ENABLE, "true");
        try {
            // requireEncrypted OFF (default): the encrypting reader still reads the legacy record (migration).
            assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8),
                    ConfigdServer.deriveRaftIntegrityEnvelope(keyStore(root), keyFile(root), dataDir(root))
                            .unwrap(WAL_MAGIC, SCOPE,legacy),
                    "default (migration) accepts legacy algId=1 records");

            // requireEncrypted ON: the reader REFUSES the legacy algId=1 record (post-migration lock-down).
            System.setProperty(REQUIRE, "true");
            IntegrityEnvelope strict = ConfigdServer.deriveRaftIntegrityEnvelope(
                    keyStore(root), keyFile(root), dataDir(root));
            assertThrows(IntegrityException.class, () -> strict.unwrap(WAL_MAGIC, SCOPE,legacy),
                    "requireEncrypted must refuse a legacy algId=1 record");
            // and it still round-trips its own encrypted writes
            assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8),
                    strict.unwrap(WAL_MAGIC, SCOPE,strict.wrap(WAL_MAGIC, SCOPE,SECRET.getBytes(StandardCharsets.UTF_8))));
        } finally {
            System.clearProperty(ENABLE);
            System.clearProperty(REQUIRE);
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

    @Test
    void bothAuthOnPosturesMintPreallocatedKeyring(@TempDir Path root) throws Exception {
        // The keyring is an AUTH-ON feature (operator ruling): BOTH the term-versioned HMAC (encryption
        // OFF) and the GCM (encryption ON) postures mint the dual-slot keyring, preallocated at the
        // frozen 131080-byte size (8 + 2*65536). (Only the keyless, no-signing-key posture has none.)
        System.clearProperty(ENABLE);
        ConfigdServer.deriveRaftIntegrityEnvelope(keyStore(root), keyFile(root), dataDir(root));
        Path keyring = dataDir(root).resolve("raft-keyring");
        assertTrue(java.nio.file.Files.exists(keyring), "encryption OFF (auth on) mints the keyring");
        assertEquals(131080L, java.nio.file.Files.size(keyring), "frozen preallocated size");

        // ON: a distinct data dir so the two mints don't collide; same frozen keyring geometry.
        System.setProperty(ENABLE, "true");
        try {
            Path onData = root.resolve("data-on");
            ConfigdServer.deriveRaftIntegrityEnvelope(keyStore(root), keyFile(root), onData);
            Path onKeyring = onData.resolve("raft-keyring");
            assertTrue(java.nio.file.Files.exists(onKeyring), "encryption ON mints the keyring");
            assertEquals(131080L, java.nio.file.Files.size(onKeyring), "frozen preallocated size");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void tamperedKeyringRefusesBoot(@TempDir Path root) throws Exception {
        System.setProperty(ENABLE, "true");
        try {
            // Boot #1 mints the keyring.
            ConfigdServer.deriveRaftIntegrityEnvelope(keyStore(root), keyFile(root), dataDir(root));
            Path keyring = dataDir(root).resolve("raft-keyring");
            // Perform the attack: corrupt the live slot's sealed record on disk.
            byte[] image = java.nio.file.Files.readAllBytes(keyring);
            image[8 + 20] ^= 0x40; // slot 0 @ offset 8; flip a byte inside its envelope
            java.nio.file.Files.write(keyring, image);
            // Boot #2 must REFUSE (fail-closed) rather than silently re-mint and orphan the data.
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> ConfigdServer.deriveRaftIntegrityEnvelope(keyStore(root), keyFile(root), dataDir(root)));
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("no slot verifies"),
                    "a tampered keyring must fail closed: " + ex.getMessage());
        } finally {
            System.clearProperty(ENABLE);
        }
    }
}
