package io.configd.raft;

import io.configd.common.Hkdf;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.IntegrityException;
import io.configd.common.SegmentKeyManager;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-attack tests for the {@link NodeKeyring} facade - every guarantee proven by PERFORMING the
 * attack on real bytes (a crash-model {@link CrashModelAnchorIO} "disk" that reboots), never by
 * reading config. Covers: crash-atomic term rotation and signing-key rewrap-before-swap
 * (rotate-then-crash-mid-write recovers), old-segments-still-decrypt-after-rotate (non-destructive),
 * and the boot fail-closed refusals (both-slots-invalid / keyring-under-a-prior-signing-key).
 */
class NodeKeyringTest {

    private static final int WAL_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SCOPE = 3;               // a per-shard gid; same on wrap+read here
    private static final String REF = "kid-ref";

    private static byte[] signingKey(byte fill) {
        byte[] k = new byte[64];
        Arrays.fill(k, fill);
        return k;
    }

    private static byte[] salt() {
        byte[] s = new byte[16];
        Arrays.fill(s, (byte) 0x5A);
        return s;
    }

    private static SecretKey mac(byte[] sk) {
        return new SecretKeySpec(Hkdf.deriveKey(sk, salt(),
                "configd/keyring-mac/v1".getBytes(StandardCharsets.UTF_8), 32), "HmacSHA256");
    }

    private static SecretKey kek(byte[] sk) {
        return new SecretKeySpec(Hkdf.deriveKey(sk, salt(),
                "configd/keyring-wrap/v1".getBytes(StandardCharsets.UTF_8), 32), "AES");
    }

    private static byte[] nodeId(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Builds an encrypting envelope over EVERY retained keyring term (the boot path). */
    private static IntegrityEnvelope envOf(NodeKeyring keyring) {
        List<RootKey> roots = keyring.unsealRootKeys(REF);
        SegmentKeyManager km = SegmentKeyManager.overTerms(roots, keyring.activeTerm());
        return IntegrityEnvelope.encrypting(km);
    }

    // ---- boot / mint ------------------------------------------------------------------------

    @Test
    void firstBoot_mintsRoot1() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x11);
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(1, k.activeTerm(), "first boot mints activeTerm 1");
            assertEquals(1, k.termCount(), "one retained term");
            assertEquals(1, k.unsealRootKeys(REF).size());
        }
        // The mint is durable: a reboot loads the SAME keyring (not a re-mint).
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(1, k2.activeTerm());
            assertEquals(1, k2.termCount());
        }
    }

    @Test
    void reboot_loadsSameRoot_priorEncryptedDataDecrypts() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x22);
        byte[] plain = "secret-under-term-1".getBytes(StandardCharsets.UTF_8);
        byte[] onDisk;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            onDisk = envOf(k).wrap(WAL_MAGIC, SCOPE, plain);
        }
        assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains("secret-under"),
                "ciphertext leaks no plaintext");
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            assertArrayEquals(plain, envOf(k2).unwrap(WAL_MAGIC, SCOPE, onDisk),
                    "reboot reloads the same random root and decrypts");
        }
    }

    // ---- ATTACK 1: rotate-then-crash mid-write recovers (term rotation) ----------------------

    @Test
    void termRotate_crashBeforeSync_recoversToPreRotationKeyring() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x33);
        byte[] plain = "written-before-the-crashed-rotation".getBytes(StandardCharsets.UTF_8);
        byte[] onDisk;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            onDisk = envOf(k).wrap(WAL_MAGIC, SCOPE, plain); // term-1 record
            // Perform the attack: crash (sync throws) BETWEEN the new-slot write and its fdatasync.
            k.armSyncFailure(1);
            assertThrows(RuntimeException.class, () -> k.rotateTerm(REF),
                    "the injected crash aborts the rotation before the durable barrier");
            assertEquals(1, k.syncFaultsFired());
        }
        // Reboot: the keyring recovers to the intact prior slot - no torn/lost keys, still term 1.
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(1, k2.activeTerm(), "recovered to the pre-rotation keyring");
            assertEquals(1, k2.termCount(), "the half-written new term was not durably added");
            assertArrayEquals(plain, envOf(k2).unwrap(WAL_MAGIC, SCOPE, onDisk),
                    "the term-1 data written before the crash still decrypts");
        }
    }

    // ---- ATTACK 2: old segments still verify after a (successful) rotate ---------------------

    @Test
    void termRotate_oldTermDataStillDecrypts_newWritesUseNewTerm() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x44);
        byte[] oldPlain = "written-under-term-1".getBytes(StandardCharsets.UTF_8);
        byte[] newPlain = "written-under-term-2".getBytes(StandardCharsets.UTF_8);
        byte[] oldRecord;
        byte[] newRecord;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            List<RootKey> roots = k.unsealRootKeys(REF);
            SegmentKeyManager km = SegmentKeyManager.overTerms(roots, k.activeTerm());
            IntegrityEnvelope env = IntegrityEnvelope.encrypting(km);
            oldRecord = env.wrap(WAL_MAGIC, SCOPE, oldPlain);
            assertEquals(1, keyTermOf(oldRecord));

            // Real term rotation: persist a new random root[2], then install it in the live manager.
            RootKey newRoot = k.rotateTerm(REF);
            assertEquals(2, k.activeTerm());
            assertEquals(2, k.termCount());
            km.rotateTo(newRoot);

            newRecord = env.wrap(WAL_MAGIC, SCOPE, newPlain);
            assertEquals(2, keyTermOf(newRecord), "new writes stamp the new term");

            // BOTH decrypt live: old term retained, new term current.
            assertArrayEquals(oldPlain, env.unwrap(WAL_MAGIC, SCOPE, oldRecord));
            assertArrayEquals(newPlain, env.unwrap(WAL_MAGIC, SCOPE, newRecord));
        }
        // Reboot: keyring now carries BOTH terms; both records still decrypt.
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(2, k2.activeTerm());
            assertEquals(2, k2.termCount());
            IntegrityEnvelope env2 = envOf(k2);
            assertArrayEquals(oldPlain, env2.unwrap(WAL_MAGIC, SCOPE, oldRecord),
                    "term-1 data still decrypts after a reboot on the rotated keyring");
            assertArrayEquals(newPlain, env2.unwrap(WAL_MAGIC, SCOPE, newRecord));
        }
    }

    // ---- ATTACK 2 (HMAC posture): old HMAC segments still VERIFY after a term rotate ----------

    @Test
    void hmacTermRotate_oldTermDataStillVerifies_newWritesUseNewTerm() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x4D);
        byte[] oldPlain = "hmac-written-under-term-1".getBytes(StandardCharsets.UTF_8);
        byte[] newPlain = "hmac-written-under-term-2".getBytes(StandardCharsets.UTF_8);
        byte[] oldRecord;
        byte[] newRecord;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            SegmentKeyManager km = SegmentKeyManager.overTerms(k.unsealRootKeys(REF), k.activeTerm());
            IntegrityEnvelope env = IntegrityEnvelope.hmac(km); // encryption OFF, auth ON
            oldRecord = env.wrap(WAL_MAGIC, SCOPE, oldPlain);
            assertEquals(1, keyTermOf(oldRecord), "term-1 HMAC record");
            assertEquals(IntegrityEnvelope.ALG_HMAC_SHA256, oldRecord[6]);

            RootKey newRoot = k.rotateTerm(REF);
            assertEquals(2, k.activeTerm());
            km.rotateTo(newRoot);

            newRecord = env.wrap(WAL_MAGIC, SCOPE, newPlain);
            assertEquals(2, keyTermOf(newRecord), "new HMAC writes stamp the new term");

            // BOTH verify: the term-1 record under K_integrity[1] (retained), the term-2 under [2].
            assertArrayEquals(oldPlain, env.unwrap(WAL_MAGIC, SCOPE, oldRecord),
                    "an HMAC segment written under the old term still verifies after rotation");
            assertArrayEquals(newPlain, env.unwrap(WAL_MAGIC, SCOPE, newRecord));
        }
        // Reboot: the term-versioned HMAC still verifies both records on the rotated keyring.
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            IntegrityEnvelope env2 = IntegrityEnvelope.hmac(
                    SegmentKeyManager.overTerms(k2.unsealRootKeys(REF), k2.activeTerm()));
            assertArrayEquals(oldPlain, env2.unwrap(WAL_MAGIC, SCOPE, oldRecord));
            assertArrayEquals(newPlain, env2.unwrap(WAL_MAGIC, SCOPE, newRecord));
        }
    }

    // ---- ATTACK 1: signing-key rewrap-before-swap crash recovers -----------------------------

    @Test
    void signingKeyRewrap_crashBeforeSync_bootsOnOldKeyStillActive() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] skA = signingKey((byte) 0x01);
        byte[] skB = signingKey((byte) 0x02);
        byte[] plain = "data-encrypted-under-A".getBytes(StandardCharsets.UTF_8);
        byte[] onDisk;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skA), kek(skA), nodeId("nodeA"), new SecureRandom())) {
            onDisk = envOf(k).wrap(WAL_MAGIC, SCOPE, plain);
            // Crash between the rewrap slot write and its fdatasync, BEFORE swapping signing-key.bin.
            k.armSyncFailure(1);
            assertThrows(RuntimeException.class,
                    () -> k.rewrapForNewSigningKey(mac(skB), kek(skB), nodeId("nodeA")));
        }
        // The signing-key file was NOT swapped (crash was before the swap), so A is still active:
        // boot under A finds the intact old slot; all roots + data survive.
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skA), kek(skA), nodeId("nodeA"), new SecureRandom())) {
            assertArrayEquals(plain, envOf(k2).unwrap(WAL_MAGIC, SCOPE, onDisk),
                    "crash-before-swap boots on the old signing key with all data intact");
        }
    }

    // ---- ATTACK 2: signing-key rewrap is non-destructive (old data reads under the new key) ---

    @Test
    void signingKeyRewrap_success_oldDataDecryptsUnderNewKey() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] skA = signingKey((byte) 0x0A);
        byte[] skB = signingKey((byte) 0x0B);
        byte[] plain = "data-that-must-survive-a-signing-key-rotation".getBytes(StandardCharsets.UTF_8);
        byte[] onDisk;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skA), kek(skA), nodeId("nodeA"), new SecureRandom())) {
            onDisk = envOf(k).wrap(WAL_MAGIC, SCOPE, plain);
            // Rewrap-before-swap succeeds (no crash), then the operator swaps signing-key.bin A -> B.
            k.rewrapForNewSigningKey(mac(skB), kek(skB), nodeId("nodeA"));
        }
        // Reboot under the NEW signing key B: the new slot wins, roots are UNCHANGED, so the record
        // encrypted under A's keyring still decrypts. This is the documented-data-destroying rotation
        // made impossible by construction.
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skB), kek(skB), nodeId("nodeA"), new SecureRandom())) {
            assertArrayEquals(plain, envOf(k2).unwrap(WAL_MAGIC, SCOPE, onDisk),
                    "after a signing-key rotation, old data still decrypts (roots unchanged)");
        }
        // And the OLD signing key A also still opens its own (old) slot - the crash-atomic handover
        // leaves BOTH slots valid, each under its own signing key.
        try (NodeKeyring kA = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skA), kek(skA), nodeId("nodeA"), new SecureRandom())) {
            assertArrayEquals(plain, envOf(kA).unwrap(WAL_MAGIC, SCOPE, onDisk));
        }
    }

    // ---- ATTACK 4/5: boot fail-closed refusals ----------------------------------------------

    @Test
    void keyringUnderAPriorSigningKey_refuses_notSilentReMint() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] skA = signingKey((byte) 0x71);
        byte[] skB = signingKey((byte) 0x72); // a DIFFERENT signing key, no rewrap performed
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skA), kek(skA), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(1, k.activeTerm());
        }
        // Booting the SAME keyring under a different signing key (no rewrap) => the only slot fails the
        // outer MAC => REFUSE. A present file must never be silently re-minted (that orphans the data).
        IntegrityException ex = assertThrows(IntegrityException.class, () ->
                NodeKeyring.loadOrCreateOverIO(
                        new CrashModelAnchorIO(disk), mac(skB), kek(skB), nodeId("nodeA"), new SecureRandom()));
        assertTrue(ex.getMessage().contains("no slot verifies"), ex.getMessage());
    }

    @Test
    void bothSlotsTampered_refuses_distinctFromFirstBoot() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x66);
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(1, k.termCount());
        }
        // Tamper: flip a byte inside BOTH slots' record regions (past the container header + recordLen).
        byte[] image = disk.image;
        image[KeyringFile.SLOT0_OFFSET + 20] ^= 0x40;
        image[KeyringFile.SLOT1_OFFSET + 20] ^= 0x40; // slot1 is zero (invalid) anyway; belt-and-braces
        IntegrityException ex = assertThrows(IntegrityException.class, () ->
                NodeKeyring.loadOrCreateOverIO(
                        new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom()));
        assertTrue(ex.getMessage().contains("no slot verifies"), ex.getMessage());
    }

    /** keyTerm is the 4 bytes after the 8-byte header + 4-byte scopeId (v3 GCM offset 12). */
    private static int keyTermOf(byte[] enveloped) {
        return ((enveloped[12] & 0xFF) << 24) | ((enveloped[13] & 0xFF) << 16)
                | ((enveloped[14] & 0xFF) << 8) | (enveloped[15] & 0xFF);
    }
}
