package io.configd.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent red-team pass over {@link SigningKeyStore}'s strict magic/version load and its durable
 * write.
 *
 * <p>Covers the reserved-illegal version 0, and confirms that TORN / TRUNCATED key files are
 * refused, never partially loaded into a usable-looking key: a sub-header stub, a header-only
 * stub, a mid-DER truncation, and a zero-length-DER file must all fail the load. It also confirms
 * a torn scratch {@code .tmp} left by a crashed generation is never adopted as the key.
 */
class SigningKeyStoreRedteamTest {

    @TempDir
    Path tempDir;

    private static byte[] goodKeyFileBytes(Path scratch) throws Exception {
        // Produce a real, well-formed v1 key file via the test writer, then read its bytes so the
        // truncation/tamper cases below start from an authentic layout.
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SigningKeyStore.writeForTest(scratch, kp, UUID.randomUUID());
        return Files.readAllBytes(scratch);
    }

    @Test
    void versionZeroRejected() throws Exception {
        Path f = tempDir.resolve("verzero.bin");
        byte[] good = goodKeyFileBytes(f);
        // version is the u16 at offset 4; set it to the reserved-illegal 0.
        good[4] = 0;
        good[5] = 0;
        Files.write(f, good);
        IOException ex = assertThrows(IOException.class, () -> SigningKeyStore.load(f));
        assertTrue(ex.getMessage().toLowerCase().contains("version"),
                "version 0 must be refused as unsupported, got: " + ex.getMessage());
    }

    @Test
    void subHeaderStubRefused() throws Exception {
        // A file too short to even hold the 4-byte magic must refuse, never return a key.
        Path f = tempDir.resolve("stub.bin");
        Files.write(f, new byte[]{0x01, 0x02, 0x03});
        assertThrows(RuntimeException.class, () -> SigningKeyStore.load(f),
                "a sub-header key file must fail the load");
    }

    @Test
    void headerOnlyTruncationRefused() throws Exception {
        // magic + version present but nothing after (keyId read underflows). Must refuse.
        Path f = tempDir.resolve("hdronly.bin");
        byte[] good = goodKeyFileBytes(f);
        Files.write(f, java.util.Arrays.copyOf(good, 6)); // magic(4) + version(2) only
        assertThrows(RuntimeException.class, () -> SigningKeyStore.load(f),
                "a header-only key file must fail the load, not yield a partial key");
    }

    @Test
    void midDerTruncationRefused() throws Exception {
        // A file cut off in the middle of the private-key DER: the declared privLen exceeds the
        // bytes present, so the DER read underflows. Must refuse.
        Path f = tempDir.resolve("midder.bin");
        byte[] good = goodKeyFileBytes(f);
        // Header is magic(4)+version(2)+keyId(16)+privLen(4) = 26 bytes; cut a few bytes into the DER.
        Files.write(f, java.util.Arrays.copyOf(good, 30));
        assertThrows(RuntimeException.class, () -> SigningKeyStore.load(f),
                "a mid-DER truncated key file must fail the load");
    }

    @Test
    void zeroLengthDerRefused() throws Exception {
        // A structurally-complete file whose DER blobs are empty (privLen=pubLen=0). The length
        // checks pass, but an empty PKCS#8/X.509 key spec cannot yield a key: it must fail closed
        // with a crypto error, never a usable key.
        Path f = tempDir.resolve("zerolen.bin");
        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 16 + 4 + 4);
        buf.putInt(0xC0DF_51C5); // magic
        buf.putShort((short) 1); // version = 1
        buf.putLong(0L);         // keyId hi
        buf.putLong(0L);         // keyId lo
        buf.putInt(0);           // privLen = 0
        buf.putInt(0);           // pubLen  = 0
        Files.write(f, buf.array());
        assertThrows(GeneralSecurityException.class, () -> SigningKeyStore.load(f),
                "an empty-DER key file must fail closed with a crypto error");
    }

    @Test
    void tornScratchTmpIsNeverAdoptedAsTheKey() throws Exception {
        // Simulate a crash that left the durable-write scratch file behind: a torn ".tmp" sibling
        // exists but the real key path does not. loadOrCreate must regenerate a fresh, valid key at
        // the real path (the torn scratch is transient, never read as server state) and that key must
        // reload cleanly.
        Path key = tempDir.resolve("node-signing.bin");
        Path tmp = key.resolveSibling(key.getFileName().toString() + ".tmp");
        Files.write(tmp, new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}); // torn scratch
        assertFalse(Files.exists(key), "precondition: the real key path is absent (crash before rename)");

        SigningKeyStore created = SigningKeyStore.loadOrCreate(key);
        assertTrue(Files.exists(key), "loadOrCreate must produce a durable key at the real path");
        // The generated key reloads deterministically and is not the torn scratch's bytes.
        SigningKeyStore reloaded = SigningKeyStore.load(key);
        assertTrue(reloaded.keyId().equals(created.keyId()),
                "the freshly generated key must reload with the same keyId (not the torn scratch)");
    }
}
