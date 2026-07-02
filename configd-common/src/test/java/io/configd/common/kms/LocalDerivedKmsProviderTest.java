package io.configd.common.kms;

import io.configd.common.Hkdf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LocalDerivedKmsProvider}: deterministic HKDF-from-signing-key derivation,
 * domain separation from the integrity/audit keys, and that the SPI never fails closed for
 * {@code local} (nothing external to be unavailable).
 */
class LocalDerivedKmsProviderTest {

    private static byte[] signingKey() {
        byte[] k = new byte[64];
        Arrays.fill(k, (byte) 0xA5);
        return k;
    }

    private static byte[] salt() {
        byte[] s = new byte[16];
        Arrays.fill(s, (byte) 0x11);
        return s;
    }

    @Test
    void unwrapIsDeterministicAndNeverFailsClosed() throws Exception {
        LocalDerivedKmsProvider p1 = new LocalDerivedKmsProvider(signingKey(), salt(), "kid", 1);
        LocalDerivedKmsProvider p2 = new LocalDerivedKmsProvider(signingKey(), salt(), "kid", 1);

        KmsProvider.Provisioned prov = p1.generateRootKey();
        assertEquals("local", p1.type());
        assertEquals(32, prov.rootKey().length());
        assertEquals(new KeyId("local", "kid", 1), prov.rootKey().keyId());
        // WrappedKey for local is a non-secret re-derivation descriptor (empty ciphertext)
        assertEquals(0, prov.wrapped().ciphertext().length);

        // unwrap re-derives the SAME root bytes on a fresh provider (restart semantics)
        RootKey again = p2.unwrap(prov.wrapped());
        assertArrayEquals(prov.rootKey().withMaterial(byte[]::clone),
                again.withMaterial(byte[]::clone),
                "unwrap re-derives the identical root from the same signing key");
    }

    @Test
    void rootIsDomainSeparatedFromIntegrityAndAuditKeys() throws Exception {
        byte[] ikm = signingKey();
        byte[] salt = salt();
        RootKey root = new LocalDerivedKmsProvider(ikm, salt, "kid", 1).generateRootKey().rootKey();
        byte[] rootBytes = root.withMaterial(byte[]::clone);

        // The two existing derived keys, computed with their real info strings.
        byte[] integrity = Hkdf.deriveKey(ikm, salt,
                "configd/raft-at-rest-integrity/v2".getBytes(StandardCharsets.UTF_8), 32);
        byte[] audit = Hkdf.deriveKey(ikm, salt,
                "configd/audit-log-integrity/v1".getBytes(StandardCharsets.UTF_8), 32);

        assertFalse(Arrays.equals(rootBytes, integrity), "encryption root must differ from K_integrity");
        assertFalse(Arrays.equals(rootBytes, audit), "encryption root must differ from K_audit");

        // and it equals the expected KEK derivation exactly
        byte[] expected = Hkdf.deriveKey(ikm, salt, LocalDerivedKmsProvider.KEK_INFO, 32);
        assertArrayEquals(expected, rootBytes);
    }

    @Test
    void differentSigningKeyGivesDifferentRoot() throws Exception {
        byte[] a = new LocalDerivedKmsProvider(signingKey(), salt(), "kid", 1)
                .generateRootKey().rootKey().withMaterial(byte[]::clone);
        byte[] other = signingKey();
        other[0] ^= 0xFF;
        byte[] b = new LocalDerivedKmsProvider(other, salt(), "kid", 1)
                .generateRootKey().rootKey().withMaterial(byte[]::clone);
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    void currentKeyIdReportsProviderAndTerm() {
        LocalDerivedKmsProvider p = new LocalDerivedKmsProvider(signingKey(), salt(), "kid", 3);
        KeyId id = p.currentKeyId();
        assertNotNull(id);
        assertEquals("local", id.providerType());
        assertEquals(3, id.version());
        assertTrue(id.toString().contains("#3"));
    }
}
