package io.configd.raft;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.raft.KeyringCodec.Keyring;
import io.configd.raft.KeyringCodec.KeyringEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dual-slot mechanics for the real on-disk {@code raft-keyring} ({@link KeyringFile}): highest-seq
 * wins, a torn stale slot (a real on-disk byte corruption) leaves the intact slot winning, both-slots-
 * invalid is REFUSED, and a foreign/corrupt container header is REFUSED. The crash-atomic byte
 * geometry the whole non-destructive-rotation guarantee rests on.
 */
class KeyringFileTest {

    private static final SecureRandom RNG = new SecureRandom();

    private static SecretKey macKey(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static SecretKey kek(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return new SecretKeySpec(k, "AES");
    }

    private static byte[] root(byte fill) {
        byte[] r = new byte[KeyringCodec.KEYRING_ROOT_LEN];
        java.util.Arrays.fill(r, fill);
        return r;
    }

    private static final byte[] NODE = "nodeA".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static Keyring mint(SecretKey kek) {
        return KeyringCodec.bootstrap(kek, NODE, root((byte) 0x01), RNG);
    }

    @Test
    void fileGeometry_isFrozen() {
        assertEquals(65536, KeyringFile.SLOT_STRIDE);
        assertEquals(8, KeyringFile.SLOT0_OFFSET);
        assertEquals(8 + 65536, KeyringFile.SLOT1_OFFSET);
        assertEquals(8 + 2 * 65536, KeyringFile.FILE_SIZE); // 131080
        assertEquals(65536 - 4, KeyringFile.MAX_RECORD_LEN);
    }

    @Test
    void dualSlot_highestSeqWinsAcrossReopens(@TempDir Path dir) {
        IntegrityEnvelope env = IntegrityEnvelope.keyringMac(macKey((byte) 0x11));
        SecretKey kek = kek((byte) 0x12);
        try (KeyringFile f = KeyringFile.openInDirectory(dir, env)) {
            f.bootstrap(mint(kek));                    // slot0 = seq1
            f.write(bump(kek, f.current()));           // slot1 = seq2 (live)
            f.write(bump(kek, f.current()));           // slot0 = seq3 (live)
            assertEquals(3L, f.current().keyringSeq());
        }
        try (KeyringFile f2 = KeyringFile.openInDirectory(dir, env)) {
            assertEquals(3L, f2.current().keyringSeq(), "reopen takes the highest valid keyringSeq");
        }
        assertEquals(KeyringFile.FILE_SIZE, dir.resolve("raft-keyring").toFile().length(),
                "the keyring file is fully preallocated at the frozen size");
    }

    @Test
    void tornStaleSlot_intactSlotWins(@TempDir Path dir) throws Exception {
        IntegrityEnvelope env = IntegrityEnvelope.keyringMac(macKey((byte) 0x21));
        SecretKey kek = kek((byte) 0x22);
        try (KeyringFile f = KeyringFile.openInDirectory(dir, env)) {
            f.bootstrap(mint(kek));            // slot0 = seq1 (stays valid)
            f.write(bump(kek, f.current()));   // slot1 = seq2 (live)
        }
        // Perform the attack: corrupt the seq2 slot's record on disk, modelling a torn rotation write.
        Path file = dir.resolve("raft-keyring");
        byte[] image = Files.readAllBytes(file);
        image[KeyringFile.SLOT1_OFFSET + 20] ^= 0x40; // flip a byte inside slot1's sealed envelope
        Files.write(file, image);
        try (KeyringFile f2 = KeyringFile.openInDirectory(dir, env)) {
            assertTrue(f2.hasValidRecord(), "the intact prior slot survives a torn write");
            assertEquals(1L, f2.current().keyringSeq(), "recovers to the intact seq1 slot - no key lost");
            assertEquals(1, f2.current().entries().size());
        }
    }

    @Test
    void bothSlotsTampered_hasNoValidRecord(@TempDir Path dir) throws Exception {
        IntegrityEnvelope env = IntegrityEnvelope.keyringMac(macKey((byte) 0x31));
        SecretKey kek = kek((byte) 0x32);
        try (KeyringFile f = KeyringFile.openInDirectory(dir, env)) {
            f.bootstrap(mint(kek));
            f.write(bump(kek, f.current())); // both slots now hold a valid record (seq1 + seq2)
        }
        Path file = dir.resolve("raft-keyring");
        byte[] image = Files.readAllBytes(file);
        image[KeyringFile.SLOT0_OFFSET + 20] ^= 0x40;
        image[KeyringFile.SLOT1_OFFSET + 20] ^= 0x40;
        Files.write(file, image);
        try (KeyringFile f2 = KeyringFile.openInDirectory(dir, env)) {
            assertTrue(f2.existedAtOpen());
            assertFalse(f2.hasValidRecord(), "both slots invalid ⇒ present-but-untrustworthy ⇒ caller REFUSEs");
        }
    }

    @Test
    void foreignContainerHeader_refuses(@TempDir Path dir) throws Exception {
        IntegrityEnvelope env = IntegrityEnvelope.keyringMac(macKey((byte) 0x41));
        SecretKey kek = kek((byte) 0x42);
        try (KeyringFile f = KeyringFile.openInDirectory(dir, env)) {
            f.bootstrap(mint(kek));
        }
        Path file = dir.resolve("raft-keyring");
        byte[] image = Files.readAllBytes(file);
        image[0] ^= 0x7F; // corrupt the container magic
        Files.write(file, image);
        // parseExisting throws during construction (before a handle is returned to close).
        assertThrows(IntegrityException.class, () -> KeyringFile.openInDirectory(dir, env));
    }

    @Test
    void write_mustBumpSeqByOne(@TempDir Path dir) {
        IntegrityEnvelope env = IntegrityEnvelope.keyringMac(macKey((byte) 0x51));
        SecretKey kek = kek((byte) 0x52);
        try (KeyringFile f = KeyringFile.openInDirectory(dir, env)) {
            f.bootstrap(mint(kek));
            Keyring notBumped = f.current();
            assertThrows(IllegalStateException.class, () -> f.write(notBumped),
                    "the dual-slot invariant requires a strictly monotonic keyringSeq");
        }
    }

    /** Append a fresh term to bump keyringSeq (the normal rotation shape). */
    private static Keyring bump(SecretKey kek, Keyring current) {
        return KeyringCodec.appendTerm(kek, NODE,
                current, root((byte) (0x40 + current.activeTerm())), RNG);
    }
}
