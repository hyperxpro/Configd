package io.configd.server;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.kms.KeyId;
import io.configd.common.kms.WrappedKey;
import io.configd.store.SigningKeyStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmsExternalProviderBootTest {

    private static final String ENABLE = "configd.raft.encryption.enabled";
    private static final String PROVIDER = "configd.raft.encryption.kms.provider";
    private static final int WAL_MAGIC = 0x5257_414C;
    private static final int SCOPE = 0;
    private static final byte[] SECRET = "external-custody-boot-record".getBytes(StandardCharsets.UTF_8);

    @Test
    void externalProviderProvisionsThenSecondBootUnsealsAndDecrypts(@TempDir Path root) throws Exception {
        Path keyFile = root.resolve("secrets").resolve("signing-key.bin");
        Path dataDir = root.resolve("data");
        System.setProperty(ENABLE, "true");
        System.setProperty(PROVIDER, "test-kms");
        try {
            IntegrityEnvelope env1 = ConfigdServer.deriveRaftIntegrityEnvelope(
                    SigningKeyStore.loadOrCreate(keyFile), keyFile, dataDir);
            assertTrue(env1.isEncrypting(), "external provider + encryption ON must encrypt");
            Path sealedRoot = dataDir.resolve("raft-kms-root");
            assertTrue(Files.exists(sealedRoot), "first boot must persist the KMS sealed-root carrier");
            byte[] record = env1.wrap(WAL_MAGIC, SCOPE, SECRET);

            // Boot #2: the sealed-root file exists -> the provider unseals the SAME secret, keyring loads,
            // and the record from boot #1 decrypts. Proves the persist -> read -> unwrap -> derive -> mint path.
            IntegrityEnvelope env2 = ConfigdServer.deriveRaftIntegrityEnvelope(
                    SigningKeyStore.loadOrCreate(keyFile), keyFile, dataDir);
            assertArrayEquals(SECRET, env2.unwrap(WAL_MAGIC, SCOPE, record),
                    "second boot must unseal via the external provider and decrypt the first boot's record");
        } finally {
            System.clearProperty(ENABLE);
            System.clearProperty(PROVIDER);
        }
    }

    @Test
    void localPostureWritesNoSealedRootFile(@TempDir Path root) throws Exception {
        // The byte-identical local posture must NOT create the external-only sealed-root artifact.
        Path keyFile = root.resolve("secrets").resolve("signing-key.bin");
        Path dataDir = root.resolve("data");
        System.setProperty(ENABLE, "true"); // provider defaults to 'local'
        try {
            ConfigdServer.deriveRaftIntegrityEnvelope(SigningKeyStore.loadOrCreate(keyFile), keyFile, dataDir);
            assertFalse(Files.exists(dataDir.resolve("raft-kms-root")),
                    "local custody derives from the signing key - no sealed-root file appears");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void sealedRootStoreRoundTripsAndFailsClosedOnCorruption(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("raft-kms-root");
        WrappedKey wrapped = new WrappedKey(new KeyId("vault-transit", "transit/configd-root-kek", 1),
                "vault:v1:abc123DEF".getBytes(StandardCharsets.UTF_8),
                Map.of("aad", "node-9", "vaultMount", "transit"));
        KmsSealedRootStore.write(file, wrapped);
        assertTrue(KmsSealedRootStore.exists(file));
        assertEquals(wrapped, KmsSealedRootStore.read(file), "sealed-root carrier round-trips byte-for-byte");

        byte[] good = Files.readAllBytes(file);

        byte[] badMagic = good.clone();
        badMagic[0] ^= 0x7F;
        Files.write(file, badMagic);
        assertThrows(RuntimeException.class, () -> KmsSealedRootStore.read(file), "corrupt magic must refuse");

        Files.write(file, Arrays.copyOf(good, 6));
        assertThrows(RuntimeException.class, () -> KmsSealedRootStore.read(file), "truncation must refuse");

        Files.write(file, Arrays.copyOf(good, good.length + 3));
        assertThrows(RuntimeException.class, () -> KmsSealedRootStore.read(file), "trailing bytes must refuse");
    }
}
