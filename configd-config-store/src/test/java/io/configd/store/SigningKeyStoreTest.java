package io.configd.store;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SigningKeyStore} durability + format. The generation path is a temp+fsync+atomic-rename
 * (crash-safe: a torn key file can never appear), restricted to 0600 before it is visible, and the
 * load path validates magic+version strictly.
 */
class SigningKeyStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void loadOrCreateGeneratesWellFormedKeyThatReloads() throws Exception {
        Path keyFile = tempDir.resolve("signing-key.bin");

        SigningKeyStore created = SigningKeyStore.loadOrCreate(keyFile);
        assertTrue(Files.exists(keyFile), "generation must produce the key file");

        // The atomic rename consumes the temp file - no torn .tmp is left behind.
        assertFalse(Files.exists(tempDir.resolve("signing-key.bin.tmp")),
                "the temp file must be consumed by the atomic rename");

        // Reload: a second loadOrCreate must LOAD the same key, never regenerate/overwrite it.
        SigningKeyStore reloaded = SigningKeyStore.loadOrCreate(keyFile);
        assertEquals(created.keyId(), reloaded.keyId(), "the key id must be stable across reloads");
        assertArrayEquals(created.keyPair().getPublic().getEncoded(),
                reloaded.keyPair().getPublic().getEncoded(),
                "the public key must round-trip byte-for-byte");
        assertArrayEquals(created.keyPair().getPrivate().getEncoded(),
                reloaded.keyPair().getPrivate().getEncoded(),
                "the private key must round-trip byte-for-byte");
    }

    @Test
    void generatedKeyFileIsOwnerOnly0600() throws Exception {
        Path keyFile = tempDir.resolve("signing-key.bin");
        SigningKeyStore.loadOrCreate(keyFile);
        assertOwnerOnly(keyFile);
    }

    @Test
    void writeForTestProducesOwnerOnly0600AndReloads() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID keyId = UUID.randomUUID();
        Path keyFile = tempDir.resolve("fixture-key.bin");

        SigningKeyStore.writeForTest(keyFile, kp, keyId);
        assertOwnerOnly(keyFile);

        SigningKeyStore loaded = SigningKeyStore.load(keyFile);
        assertEquals(keyId, loaded.keyId());
        assertArrayEquals(kp.getPublic().getEncoded(), loaded.keyPair().getPublic().getEncoded());
    }

    @Test
    void loadRejectsBadMagic() throws Exception {
        Path keyFile = tempDir.resolve("bad-magic.bin");
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.putInt(0xDEADBEEF); // wrong magic
        buf.putShort((short) 1);
        Files.write(keyFile, buf.array());
        assertThrows(IOException.class, () -> SigningKeyStore.load(keyFile),
                "a wrong magic must be refused");
    }

    @Test
    void loadRejectsUnsupportedVersion() throws Exception {
        Path keyFile = tempDir.resolve("bad-version.bin");
        ByteBuffer buf = ByteBuffer.allocate(6);
        buf.putInt(0xC0DF_51C5); // correct magic
        buf.putShort((short) 2); // unsupported version
        Files.write(keyFile, buf.array());
        assertThrows(IOException.class, () -> SigningKeyStore.load(keyFile),
                "an unsupported version must be refused");
    }

    /** Asserts POSIX 0600 (owner read/write only); skips on a filesystem without POSIX perms. */
    private static void assertOwnerOnly(Path path) throws IOException {
        Set<java.nio.file.attribute.PosixFilePermission> perms;
        try {
            perms = Files.getPosixFilePermissions(path);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("filesystem does not support POSIX permissions");
            return;
        }
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms,
                "the key file must be owner-only (0600)");
    }
}
