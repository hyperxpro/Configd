package io.configd.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigSigner}.
 */
class ConfigSignerTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        keyPair = kpg.generateKeyPair();
    }

    private static byte[] data(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void signAndVerifyRoundtrip() throws Exception {
        ConfigSigner signer = new ConfigSigner(keyPair);
        byte[] payload = data("db.host=localhost");

        byte[] signature = signer.sign(payload);
        assertNotNull(signature);
        assertTrue(signature.length > 0);
        assertTrue(signer.verify(payload, signature));
    }

    @Test
    void signAndVerifyEmptyData() throws Exception {
        ConfigSigner signer = new ConfigSigner(keyPair);
        byte[] payload = new byte[0];

        byte[] signature = signer.sign(payload);
        assertTrue(signer.verify(payload, signature));
    }

    @Test
    void signAndVerifyLargeData() throws Exception {
        ConfigSigner signer = new ConfigSigner(keyPair);
        byte[] payload = new byte[1_000_000];
        new SecureRandom().nextBytes(payload);

        byte[] signature = signer.sign(payload);
        assertTrue(signer.verify(payload, signature));
    }

    @Test
    void verifyFailsWithDifferentKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair otherKeyPair = kpg.generateKeyPair();

        ConfigSigner signer = new ConfigSigner(keyPair);
        ConfigSigner otherVerifier = new ConfigSigner(otherKeyPair.getPublic());

        byte[] payload = data("secret.config=42");
        byte[] signature = signer.sign(payload);

        assertFalse(otherVerifier.verify(payload, signature));
    }

    @Test
    void verifyFailsWithTamperedData() throws Exception {
        ConfigSigner signer = new ConfigSigner(keyPair);
        byte[] payload = data("db.host=localhost");
        byte[] signature = signer.sign(payload);

        byte[] tampered = data("db.host=attacker.com");
        assertFalse(signer.verify(tampered, signature));
    }

    @Test
    void verifyFailsWithTamperedSignature() throws Exception {
        ConfigSigner signer = new ConfigSigner(keyPair);
        byte[] payload = data("db.host=localhost");
        byte[] signature = signer.sign(payload);

        byte[] corrupted = signature.clone();
        corrupted[0] ^= 0xFF;
        assertFalse(signer.verify(payload, corrupted));
    }

    @Test
    void verifyOnlyModeCanVerify() throws Exception {
        ConfigSigner leader = new ConfigSigner(keyPair);
        ConfigSigner edge = new ConfigSigner(keyPair.getPublic());

        byte[] payload = data("config.entry=value");
        byte[] signature = leader.sign(payload);

        assertTrue(edge.verify(payload, signature));
    }

    @Test
    void verifyOnlyModeCannotSign() {
        ConfigSigner edge = new ConfigSigner(keyPair.getPublic());

        assertThrows(IllegalStateException.class,
                () -> edge.sign(data("should fail")));
    }

    @Test
    void constructorKeyPairNullThrows() {
        assertThrows(NullPointerException.class,
                () -> new ConfigSigner((KeyPair) null));
    }

    @Test
    void constructorPublicKeyNullThrows() {
        assertThrows(NullPointerException.class,
                () -> new ConfigSigner((PublicKey) null));
    }

    @Test
    void signNullDataThrows() {
        ConfigSigner signer = new ConfigSigner(keyPair);
        assertThrows(NullPointerException.class, () -> signer.sign(null));
    }

    @Test
    void verifyNullDataThrows() {
        ConfigSigner signer = new ConfigSigner(keyPair);
        assertThrows(NullPointerException.class,
                () -> signer.verify(null, new byte[]{1}));
    }

    @Test
    void verifyNullSignatureThrows() {
        ConfigSigner signer = new ConfigSigner(keyPair);
        assertThrows(NullPointerException.class,
                () -> signer.verify(data("test"), null));
    }
}
