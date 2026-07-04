package io.configd.raft;

import io.configd.common.Hkdf;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.SegmentKeyManager;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ATTACK 3 - keyTerm-wired key selection is fail-closed. A real GCM segment written under keyring term
 * T is taken and its authenticated {@code keyTerm} field is rewritten (with the CRC repaired, so this
 * defeats the corruption check and tests the AUTHENTICATION, not merely the CRC):
 * <ul>
 *   <li>rolled to another PRESENT term ⇒ the DEK selected differs AND the GCM AAD (which binds keyTerm)
 *       no longer matches ⇒ the tag fails ⇒ REFUSE;</li>
 *   <li>rolled to a term ABSENT from the keyring ⇒ {@code resolveDek} fails closed (unknown term).</li>
 * </ul>
 * Built over a real two-term keyring so this is the end-to-end selection path, not a codec unit.
 */
class KeyringKeyTermSelectionTest {

    private static final int WAL_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SCOPE = 3;
    private static final String REF = "kid";
    private static final byte[] NODE = "nodeA".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();

    private static SecretKey key(String info, byte fill, String alg) {
        byte[] sk = new byte[64];
        java.util.Arrays.fill(sk, fill);
        byte[] salt = new byte[16];
        java.util.Arrays.fill(salt, (byte) 0x5A);
        return new SecretKeySpec(Hkdf.deriveKey(sk, salt, info.getBytes(StandardCharsets.UTF_8), 32), alg);
    }

    /** A two-term keyring (mint + one rotation) and an encrypting envelope over both terms, active=2. */
    private static IntegrityEnvelope twoTermEnv(CrashModelAnchorIO.Disk disk) {
        SecretKey mac = key("configd/keyring-mac/v1", (byte) 0x33, "HmacSHA256");
        SecretKey kek = key("configd/keyring-wrap/v1", (byte) 0x33, "AES");
        NodeKeyring k = NodeKeyring.loadOrCreateOverIO(new CrashModelAnchorIO(disk), mac, kek, NODE, RNG);
        k.rotateTerm(REF); // now activeTerm 2, two retained terms
        List<RootKey> roots = k.unsealRootKeys(REF);
        SegmentKeyManager km = SegmentKeyManager.overTerms(roots, k.activeTerm());
        return IntegrityEnvelope.encrypting(km);
    }

    @Test
    void rolledKeyTermToAnotherPresentTerm_tagFails() {
        IntegrityEnvelope env = twoTermEnv(new CrashModelAnchorIO.Disk());
        byte[] rec = env.wrap(WAL_MAGIC, SCOPE, "term-2 secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, keyTermOf(rec), "written under the active term 2");

        // Roll the authenticated keyTerm 2 -> 1 (a PRESENT term) and repair the CRC so the corruption
        // check passes. The GCM tag (AAD binds keyTerm) then catches the roll.
        byte[] forged = rec.clone();
        setKeyTerm(forged, 1);
        repairCrc(forged);
        assertThrows(IntegrityException.class, () -> env.unwrap(WAL_MAGIC, SCOPE, forged),
                "a rolled keyTerm is refused by the GCM tag (wrong DEK + AAD mismatch)");
    }

    @Test
    void rolledKeyTermToAbsentTerm_failsClosed() {
        IntegrityEnvelope env = twoTermEnv(new CrashModelAnchorIO.Disk());
        byte[] rec = env.wrap(WAL_MAGIC, SCOPE, "term-2 secret".getBytes(StandardCharsets.UTF_8));

        byte[] forged = rec.clone();
        setKeyTerm(forged, 99); // a term the keyring does not retain
        repairCrc(forged);
        assertThrows(IntegrityException.class, () -> env.unwrap(WAL_MAGIC, SCOPE, forged),
                "a keyTerm with no root in the keyring fails closed (resolveDek)");
    }

    @Test
    void untamperedRecord_roundTrips() {
        IntegrityEnvelope env = twoTermEnv(new CrashModelAnchorIO.Disk());
        byte[] plain = "healthy".getBytes(StandardCharsets.UTF_8);
        byte[] rec = env.wrap(WAL_MAGIC, SCOPE, plain);
        assertArrayEquals(plain, env.unwrap(WAL_MAGIC, SCOPE, rec), "a genuine record still decrypts");
    }

    // ---- HMAC-posture variants (encryption OFF, auth ON) ------------------------------------

    /** A two-term keyring HMAC envelope, activeTerm 2. */
    private static IntegrityEnvelope twoTermHmacEnv(CrashModelAnchorIO.Disk disk) {
        SecretKey mac = key("configd/keyring-mac/v1", (byte) 0x44, "HmacSHA256");
        SecretKey kek = key("configd/keyring-wrap/v1", (byte) 0x44, "AES");
        NodeKeyring k = NodeKeyring.loadOrCreateOverIO(new CrashModelAnchorIO(disk), mac, kek, NODE, RNG);
        k.rotateTerm(REF);
        return IntegrityEnvelope.hmac(SegmentKeyManager.overTerms(k.unsealRootKeys(REF), k.activeTerm()));
    }

    @Test
    void hmac_rolledKeyTermToAnotherPresentTerm_macFails() {
        IntegrityEnvelope env = twoTermHmacEnv(new CrashModelAnchorIO.Disk());
        byte[] rec = env.wrap(WAL_MAGIC, SCOPE, "term-2 hmac secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, keyTermOf(rec));
        assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, rec[6]);
        // Roll keyTerm 2 -> 1 and repair the CRC: the reader now selects K_integrity[1] and MACs over a
        // keyTerm=1 input, but the stored MAC was under K_integrity[2] over keyTerm=2 -> mismatch.
        byte[] forged = rec.clone();
        setKeyTerm(forged, 1);
        repairCrc(forged);
        assertThrows(IntegrityException.class, () -> env.unwrap(WAL_MAGIC, SCOPE, forged),
                "a rolled HMAC keyTerm is refused (wrong K_integrity + MAC-input mismatch)");
    }

    @Test
    void hmac_rolledKeyTermToAbsentTerm_failsClosed() {
        IntegrityEnvelope env = twoTermHmacEnv(new CrashModelAnchorIO.Disk());
        byte[] rec = env.wrap(WAL_MAGIC, SCOPE, "term-2 hmac secret".getBytes(StandardCharsets.UTF_8));
        byte[] forged = rec.clone();
        setKeyTerm(forged, 99); // no such term
        repairCrc(forged);
        assertThrows(IntegrityException.class, () -> env.unwrap(WAL_MAGIC, SCOPE, forged),
                "an HMAC keyTerm with no root in the keyring fails closed (macKey)");
    }

    private static void setKeyTerm(byte[] rec, int term) {
        ByteBuffer.wrap(rec, 12, 4).putInt(term); // keyTerm at v3 offset 12 (header 8 + scopeId 4)
    }

    private static void repairCrc(byte[] rec) {
        CRC32C crc = new CRC32C();
        crc.update(rec, 0, rec.length - 4);
        ByteBuffer.wrap(rec, rec.length - 4, 4).putInt((int) crc.getValue());
    }

    private static int keyTermOf(byte[] rec) {
        return ByteBuffer.wrap(rec, 12, 4).getInt();
    }
}
