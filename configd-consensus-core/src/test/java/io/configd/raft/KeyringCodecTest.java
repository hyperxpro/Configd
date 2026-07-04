package io.configd.raft;

import io.configd.common.Hkdf;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.raft.KeyringCodec.Keyring;
import io.configd.raft.KeyringCodec.KeyringEntry;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec-level fail-closed + wrap-AAD tests for {@link KeyringCodec} - the wire discipline behind the
 * keyring's tamper/replay defenses. Every negative PERFORMS the byte edit and asserts the refusal.
 */
class KeyringCodecTest {

    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] NODE_A = "nodeA".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NODE_B = "nodeB".getBytes(StandardCharsets.UTF_8);

    private static SecretKey kek(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return new SecretKeySpec(k, "AES");
    }

    private static SecretKey macKey(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return new SecretKeySpec(k, "HmacSHA256");
    }

    private static byte[] root(byte fill) {
        byte[] r = new byte[KeyringCodec.KEYRING_ROOT_LEN];
        java.util.Arrays.fill(r, fill);
        return r;
    }

    // ---- round trip -------------------------------------------------------------------------

    @Test
    void wrapUnwrapRoot_roundTrips_andBodyRoundTrips() {
        SecretKey kek = kek((byte) 0x10);
        byte[] r = root((byte) 0xAB);
        KeyringEntry e = KeyringCodec.wrapRoot(kek, NODE_A, 1, r, RNG);
        assertArrayEquals(r, KeyringCodec.unwrapRoot(kek, NODE_A, KeyringCodec.KEYRING_FORMAT_VERSION, e));

        Keyring k = new Keyring(1, 7L, 1, List.of(e));
        Keyring decoded = KeyringCodec.decodeBody(KeyringCodec.encodeBody(k));
        assertEquals(7L, decoded.keyringSeq());
        assertEquals(1, decoded.activeTerm());
        assertEquals(1, decoded.entries().size());
    }

    // ---- ATTACK 4: a wrapped root cannot be replayed into another term / node -----------------

    @Test
    void wrappedRoot_replayedIntoDifferentTerm_failsAad() {
        SecretKey kek = kek((byte) 0x20);
        KeyringEntry e1 = KeyringCodec.wrapRoot(kek, NODE_A, 1, root((byte) 0x01), RNG);
        // Same ciphertext + nonce, but the entry now CLAIMS term 2: the AAD (which binds the term) no
        // longer matches, so the GCM tag fails.
        KeyringEntry replayed = new KeyringEntry(2, KeyringCodec.WRAP_ALG_LOCAL_GCM, e1.nonce(), e1.wrappedRoot());
        assertThrows(IntegrityException.class,
                () -> KeyringCodec.unwrapRoot(kek, NODE_A, KeyringCodec.KEYRING_FORMAT_VERSION, replayed));
    }

    @Test
    void wrappedRoot_replayedIntoDifferentNode_failsAad() {
        SecretKey kek = kek((byte) 0x21);
        KeyringEntry e1 = KeyringCodec.wrapRoot(kek, NODE_A, 1, root((byte) 0x02), RNG);
        // Unwrap under a DIFFERENT node id: AAD mismatch -> tag fails.
        assertThrows(IntegrityException.class,
                () -> KeyringCodec.unwrapRoot(kek, NODE_B, KeyringCodec.KEYRING_FORMAT_VERSION, e1));
    }

    @Test
    void wrappedRoot_wrongKek_failsClosed() {
        KeyringEntry e1 = KeyringCodec.wrapRoot(kek((byte) 0x30), NODE_A, 1, root((byte) 0x03), RNG);
        // A different KEK (a signing-key mismatch) cannot unwrap it.
        assertThrows(IntegrityException.class,
                () -> KeyringCodec.unwrapRoot(kek((byte) 0x31), NODE_A, KeyringCodec.KEYRING_FORMAT_VERSION, e1));
    }

    // ---- ATTACK 5: unknown wrapAlgId / term 0 / bad version fail closed -----------------------

    @Test
    void unknownWrapAlgId_failsClosed() {
        // KeyringEntry itself does not validate wrapAlgId; decodeBody must reject an unknown one.
        KeyringEntry bad = new KeyringEntry(1, (byte) 99, new byte[12], root((byte) 0x04));
        byte[] body = KeyringCodec.encodeBody(new Keyring(1, 1L, 1, List.of(bad)));
        IntegrityException ex = assertThrows(IntegrityException.class, () -> KeyringCodec.decodeBody(body));
        assertTrue(ex.getMessage().contains("wrapAlgId"), ex.getMessage());
    }

    @Test
    void term0_inBody_failsClosed() {
        // Hand-craft a body carrying an entry with term 0 (the KeyringEntry ctor forbids it, so the
        // only way it reaches a reader is a forged body - which decodeBody must reject).
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 4 + 4 + 4 + 1 + 1 + 0 + 4 + 0);
        buf.putShort((short) 1);   // keyringFormatVersion
        buf.putLong(1L);           // keyringSeq
        buf.putInt(1);             // activeTerm
        buf.putInt(1);             // entryCount
        buf.putInt(0);             // entry term = 0 (illegal)
        buf.put(KeyringCodec.WRAP_ALG_LOCAL_GCM);
        buf.put((byte) 0);         // nonceLen 0
        buf.putInt(0);             // wrappedLen 0
        IntegrityException ex = assertThrows(IntegrityException.class,
                () -> KeyringCodec.decodeBody(buf.array()));
        assertTrue(ex.getMessage().contains("term 0"), ex.getMessage());
    }

    @Test
    void unknownKeyringFormatVersion_failsClosed() {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 4 + 4);
        buf.putShort((short) 2);   // unsupported keyringFormatVersion
        buf.putLong(1L);
        buf.putInt(1);
        buf.putInt(0);
        assertThrows(IntegrityException.class, () -> KeyringCodec.decodeBody(buf.array()));

        ByteBuffer zero = ByteBuffer.allocate(2 + 8 + 4 + 4);
        zero.putShort((short) 0);  // version 0 illegal
        zero.putLong(1L);
        zero.putInt(1);
        zero.putInt(0);
        assertThrows(IntegrityException.class, () -> KeyringCodec.decodeBody(zero.array()));
    }

    @Test
    void activeTermWithNoMatchingEntry_failsClosed() {
        // activeTerm points at a term the entry set does not contain.
        KeyringEntry e = KeyringCodec.wrapRoot(kek((byte) 0x40), NODE_A, 1, root((byte) 0x05), RNG);
        byte[] body = KeyringCodec.encodeBody(new Keyring(1, 1L, 5 /* no such term */, List.of(e)));
        assertThrows(IntegrityException.class, () -> KeyringCodec.decodeBody(body));
    }

    // ---- matrix 16: outer MAC over the WHOLE body defeats strip/swap/add/truncate -------------

    @Test
    void outerMac_bodyTamper_failsClosed() {
        IntegrityEnvelope outer = IntegrityEnvelope.keyringMac(macKey((byte) 0x55));
        KeyringEntry e = KeyringCodec.wrapRoot(kek((byte) 0x50), NODE_A, 1, root((byte) 0x06), RNG);
        byte[] sealed = KeyringCodec.seal(outer, new Keyring(1, 1L, 1, List.of(e)));
        // Flip a byte inside the sealed body (past the 8-byte header + 4-byte scopeId).
        byte[] tampered = sealed.clone();
        tampered[20] ^= 0x08;
        assertThrows(IntegrityException.class, () -> KeyringCodec.openSealed(outer, tampered));
    }

    @Test
    void outerMac_entryCountForged_failsClosed() {
        IntegrityEnvelope outer = IntegrityEnvelope.keyringMac(macKey((byte) 0x56));
        KeyringEntry e1 = KeyringCodec.wrapRoot(kek((byte) 0x51), NODE_A, 1, root((byte) 0x07), RNG);
        KeyringEntry e2 = KeyringCodec.wrapRoot(kek((byte) 0x51), NODE_A, 2, root((byte) 0x08), RNG);
        byte[] sealed = KeyringCodec.seal(outer, new Keyring(1, 2L, 2, List.of(e1, e2)));
        // The entryCount lives at body offset 14 (2+8+4); the body starts at envelope offset 16
        // (header 8 + scopeId 4 + keyTerm 4), so entryCount's low byte is at absolute offset 16+14+3 = 33.
        byte[] forged = sealed.clone();
        forged[16 + 14 + 3] = 0x01; // claim only 1 entry (an entry-strip)
        assertThrows(IntegrityException.class, () -> KeyringCodec.openSealed(outer, forged),
                "the outer MAC covers activeTerm + entryCount + every entry - a strip fails loud");
    }

    // ---- ATTACK 5: slot overflow REFUSES loudly ---------------------------------------------

    @Test
    void slotOverflow_bootstrapRefusesLoudly() {
        IntegrityEnvelope outer = IntegrityEnvelope.keyringMac(macKey((byte) 0x60));
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        KeyringFile file = KeyringFile.openOverIO(new CrashModelAnchorIO(disk), outer);
        // A single entry whose wrapped blob overflows the frozen 64 KiB slot: the sealed keyring
        // exceeds MAX_RECORD_LEN, so the writer must REFUSE loudly (operator escalation), never
        // silently drop a term.
        byte[] hugeBlob = new byte[KeyringFile.SLOT_STRIDE + 1024];
        KeyringEntry huge = new KeyringEntry(1, KeyringCodec.WRAP_ALG_LOCAL_GCM, new byte[12], hugeBlob);
        Keyring oversized = new Keyring(1, 1L, 1, List.of(huge));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> file.bootstrap(oversized));
        assertTrue(ex.getMessage().contains("exceeds the frozen") && ex.getMessage().contains("slot capacity"),
                ex.getMessage());
    }

    /** Sanity: a 32-B root wrapped locally uses a 12-B nonce and a 48-B GCM blob (32 ct + 16 tag). */
    @Test
    void localWrappedRoot_hasExpectedShape() {
        KeyringEntry e = KeyringCodec.wrapRoot(kek((byte) 0x70), NODE_A, 1, root((byte) 0x09), RNG);
        assertEquals(12, e.nonce().length);
        assertEquals(48, e.wrappedRoot().length);
        assertEquals(KeyringCodec.WRAP_ALG_LOCAL_GCM, e.wrapAlgId());
    }

    /** Domain check: derive a KEK the same way ConfigdServer does, and confirm wrap/unwrap works. */
    @Test
    void hkdfDerivedKek_wrapsAndUnwraps() {
        byte[] sk = new byte[64];
        java.util.Arrays.fill(sk, (byte) 0x7C);
        byte[] salt = new byte[16];
        java.util.Arrays.fill(salt, (byte) 0x5A);
        SecretKey kek = new SecretKeySpec(
                Hkdf.deriveKey(sk, salt, "configd/keyring-wrap/v1".getBytes(StandardCharsets.UTF_8), 32), "AES");
        byte[] r = root((byte) 0x0C);
        KeyringEntry e = KeyringCodec.wrapRoot(kek, NODE_A, 1, r, RNG);
        assertArrayEquals(r, KeyringCodec.unwrapRoot(kek, NODE_A, KeyringCodec.KEYRING_FORMAT_VERSION, e));
    }
}
