package io.configd.server;

import io.configd.common.Hkdf;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.SegmentKeyManager;
import io.configd.common.kms.RootKey;
import io.configd.raft.NodeKeyring;
import io.configd.store.SigningKeyStore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmsBootByteIdentityTest {

    private static final String ENABLE = "configd.raft.encryption.enabled";
    private static final int WAL_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SCOPE = 0;               // gid 0 (N=1)
    private static final byte[] PLAINTEXT =
            "gate7-byte-identity-record-✓".getBytes(StandardCharsets.UTF_8);

    // The frozen keyring-key derivation info strings, hard-coded here independently; they must mirror
    // ConfigdServer.KEYRING_MAC_INFO / KEYRING_WRAP_INFO.
    private static final byte[] MAC_INFO = "configd/keyring-mac/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRAP_INFO = "configd/keyring-wrap/v1".getBytes(StandardCharsets.UTF_8);

    // Minted once by the frozen formula (independent of ConfigdServer), then asserted decryptable by the boot.
    @TempDir
    static Path shared;
    private static Path keyFile;
    private static Path dataDir;
    private static byte[] frozenRecord;

    @BeforeAll
    static void mintFrozenFormulaKeyringAndRecord() throws Exception {
        keyFile = shared.resolve("secrets").resolve("signing-key.bin"); // deliberately outside dataDir
        dataDir = shared.resolve("data");
        SigningKeyStore ks = SigningKeyStore.loadOrCreate(keyFile); // generates a fresh Ed25519 key (SecureRandom)
        frozenRecord = mintFrozenKeyringAndEncrypt(ks, dataDir, PLAINTEXT);
    }

    @Test
    void postChangeLocalBootDecryptsFrozenFormulaRecord() throws Exception {
        System.setProperty(ENABLE, "true");
        try {
            IntegrityEnvelope env = ConfigdServer.deriveRaftIntegrityEnvelope(
                    SigningKeyStore.loadOrCreate(keyFile), keyFile, dataDir);
            assertTrue(env.isEncrypting(), "encryption ON must produce an encrypting envelope");
            assertArrayEquals(PLAINTEXT, env.unwrap(WAL_MAGIC, SCOPE, frozenRecord),
                    "the post-change local boot must byte-for-byte decrypt a record whose keyring was minted by "
                            + "the frozen pre-change derivation");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    @Test
    void differentialFreshMintAlsoDecryptsUnderLocalBoot(@TempDir Path root) throws Exception {
        // An independent instance: fresh signing key + fresh frozen-formula keyring in its own dir, decrypted
        // by the local boot - proves the match is not an artefact of the @BeforeAll fixture.
        Path kf = root.resolve("secrets").resolve("signing-key.bin");
        Path dd = root.resolve("data");
        SigningKeyStore ks = SigningKeyStore.loadOrCreate(kf);
        byte[] record = mintFrozenKeyringAndEncrypt(ks, dd, PLAINTEXT);

        System.setProperty(ENABLE, "true");
        try {
            IntegrityEnvelope env = ConfigdServer.deriveRaftIntegrityEnvelope(
                    SigningKeyStore.loadOrCreate(kf), kf, dd);
            assertArrayEquals(PLAINTEXT, env.unwrap(WAL_MAGIC, SCOPE, record),
                    "a fresh frozen-formula keyring must also decrypt under the local boot");
        } finally {
            System.clearProperty(ENABLE);
        }
    }

    // Mirrors the shipped boot's local keyring construction, independently of ConfigdServer.
    private static byte[] mintFrozenKeyringAndEncrypt(SigningKeyStore ks, Path dataDir, byte[] plaintext)
            throws Exception {
        Files.createDirectories(dataDir);
        byte[] ikm = ks.keyPair().getPrivate().getEncoded();
        UUID keyId = ks.keyId();
        byte[] salt = ByteBuffer.allocate(16)
                .putLong(keyId.getMostSignificantBits())
                .putLong(keyId.getLeastSignificantBits())
                .array();
        SecretKey keyringMac = new SecretKeySpec(Hkdf.deriveKey(ikm, salt, MAC_INFO, 32), "HmacSHA256");
        SecretKey kek = new SecretKeySpec(Hkdf.deriveKey(ikm, salt, WRAP_INFO, 32), "AES");
        byte[] nodeKeyId = keyId.toString().getBytes(StandardCharsets.UTF_8);
        java.util.Arrays.fill(ikm, (byte) 0);

        try (NodeKeyring keyring = NodeKeyring.loadOrCreate(dataDir, keyringMac, kek, nodeKeyId)) {
            List<RootKey> roots = keyring.unsealRootKeys(keyId.toString());
            SegmentKeyManager keyManager = SegmentKeyManager.overTerms(roots, keyring.activeTerm(), nodeKeyId);
            IntegrityEnvelope env = IntegrityEnvelope.encrypting(keyManager, false);
            return env.wrap(WAL_MAGIC, SCOPE, plaintext);
        }
    }
}
