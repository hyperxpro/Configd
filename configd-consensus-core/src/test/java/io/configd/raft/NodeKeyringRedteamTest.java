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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second, adversarial pass over {@link NodeKeyring} that goes beyond {@link NodeKeyringTest}: it
 * attacks the paths the builder's tests only exercise once - a MULTI-term signing-key rewrap
 * (rewrapUnderNewKek's whole loop), a whole-file KEYRING ROLLBACK (the documented R-a residual, here
 * proven to be honestly bounded - it can lose the new term but can NEVER silently accept new-term
 * data under an old root), and a CROSS-NODE root replay at the facade (the per-entry node-AAD binding
 * as a defense-in-depth layer BENEATH the outer K_keyringMac, which does not depend on the node id).
 */
class NodeKeyringRedteamTest {

    private static final int WAL_MAGIC = 0x5257_414C; // "RWAL"
    private static final int SCOPE = 3;               // a per-shard gid, same on wrap + read here
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

    private static IntegrityEnvelope encOver(NodeKeyring keyring) {
        return IntegrityEnvelope.encrypting(
                SegmentKeyManager.overTerms(keyring.unsealRootKeys(REF), keyring.activeTerm()));
    }

    private static int keyTermOf(byte[] enveloped) {
        return ((enveloped[12] & 0xFF) << 24) | ((enveloped[13] & 0xFF) << 16)
                | ((enveloped[14] & 0xFF) << 8) | (enveloped[15] & 0xFF);
    }

    // ---------------------------------------------------------------------------------------------
    // MULTI-TERM signing-key rewrap: three independent random roots, then a signing-key rotation.
    // rewrapUnderNewKek must rewrap EVERY entry; all three terms must still decrypt under the new key.
    // ---------------------------------------------------------------------------------------------

    @Test
    void multiTermRewrap_everyTermStillDecryptsUnderNewSigningKey() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] skA = signingKey((byte) 0x0A);
        byte[] skB = signingKey((byte) 0x0B);
        byte[] p1 = "term-1-data".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "term-2-data".getBytes(StandardCharsets.UTF_8);
        byte[] p3 = "term-3-data".getBytes(StandardCharsets.UTF_8);
        byte[] r1;
        byte[] r2;
        byte[] r3;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skA), kek(skA), nodeId("nodeA"), new SecureRandom())) {
            r1 = encOver(k).wrap(WAL_MAGIC, SCOPE, p1);
            assertEquals(1, keyTermOf(r1));

            k.rotateTerm(REF);                       // -> term 2 (fresh independent random root)
            r2 = encOver(k).wrap(WAL_MAGIC, SCOPE, p2);
            assertEquals(2, keyTermOf(r2));

            k.rotateTerm(REF);                       // -> term 3
            r3 = encOver(k).wrap(WAL_MAGIC, SCOPE, p3);
            assertEquals(3, keyTermOf(r3));
            assertEquals(3, k.termCount());

            // Signing-key rotation: rewrap ALL THREE roots under skB (roots unchanged), before swap.
            k.rewrapForNewSigningKey(mac(skB), kek(skB), nodeId("nodeA"));
        }
        // Reboot under the NEW signing key B: the rewrapped slot wins, roots are unchanged, so every
        // term's data still decrypts. Proves the whole rewrap loop, not just a single entry.
        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(skB), kek(skB), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(3, k2.termCount(), "all three terms survive the signing-key rewrap");
            assertEquals(3, k2.activeTerm());
            IntegrityEnvelope env = encOver(k2);
            assertArrayEquals(p1, env.unwrap(WAL_MAGIC, SCOPE, r1), "term-1 data survives rewrap");
            assertArrayEquals(p2, env.unwrap(WAL_MAGIC, SCOPE, r2), "term-2 data survives rewrap");
            assertArrayEquals(p3, env.unwrap(WAL_MAGIC, SCOPE, r3), "term-3 data survives rewrap");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // WHOLE-FILE KEYRING ROLLBACK (the R-a residual, honestly bounded). Roll the keyring back to a
    // prior VALID image that predates a term rotation. The keyring layer accepts the older image (it
    // is genuinely valid - the documented residual an external witness/node-anchor closes) BUT the
    // data written under the newer, now-absent term FAILS CLOSED: a rollback can never make new-term
    // ciphertext decrypt under an old root.
    // ---------------------------------------------------------------------------------------------

    @Test
    void keyringRollback_losesNewTerm_butNewTermDataFailsClosed_neverForged() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x5C);
        byte[] p1 = "written-under-term-1".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "written-under-term-2".getBytes(StandardCharsets.UTF_8);
        byte[] r1;
        byte[] r2;
        byte[] rollbackTarget;
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            r1 = encOver(k).wrap(WAL_MAGIC, SCOPE, p1);
            assertEquals(1, keyTermOf(r1));
            // Snapshot the durable term-1-only keyring image: the attacker's rollback target.
            rollbackTarget = disk.image.clone();

            k.rotateTerm(REF);                       // -> term 2 durably added
            r2 = encOver(k).wrap(WAL_MAGIC, SCOPE, p2);
            assertEquals(2, keyTermOf(r2));
        }
        // Perform the rollback: overwrite the whole keyring file with the older, still-valid image.
        disk.image = rollbackTarget;

        try (NodeKeyring k2 = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            // The keyring layer accepts the older valid image - this IS the documented R-a residual
            // (a rollback to a prior valid keyring), bounded: it loses term 2, detectable only by the
            // external node-anchor/witness, not by the keyring alone.
            assertEquals(1, k2.activeTerm(), "rollback reverts to the older valid keyring (R-a residual)");
            assertEquals(1, k2.termCount(), "the term-2 entry is gone after the rollback");
            IntegrityEnvelope env = encOver(k2);
            // Term-1 data still decrypts (term 1 retained) ...
            assertArrayEquals(p1, env.unwrap(WAL_MAGIC, SCOPE, r1));
            // ... but term-2 data FAILS CLOSED: no silent decrypt under an old/wrong root.
            IntegrityException ex = assertThrows(IntegrityException.class,
                    () -> env.unwrap(WAL_MAGIC, SCOPE, r2),
                    "term-2 ciphertext must never decrypt after the term-2 root was rolled away");
            assertTrue(ex.getMessage().contains("unknown at-rest encryption key term 2"), ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CROSS-NODE root replay at the facade. The outer K_keyringMac is derived from the signing key
    // ONLY (not the node id), so the SAME signing key opens the file under a different node id - the
    // outer MAC does NOT stop this. The per-entry wrap AAD (which binds nodeKeyId) does: unsealing
    // under nodeB REFUSES. Defense in depth beneath the outer MAC.
    // ---------------------------------------------------------------------------------------------

    @Test
    void sameSigningKey_differentNode_outerMacPassesButRootUnsealRefuses() {
        CrashModelAnchorIO.Disk disk = new CrashModelAnchorIO.Disk();
        byte[] sk = signingKey((byte) 0x6E);
        try (NodeKeyring k = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeA"), new SecureRandom())) {
            assertEquals(1, k.activeTerm());
        }
        // Open the SAME keyring file with the SAME signing key but a DIFFERENT node id. loadOrCreate
        // succeeds because the outer MAC (K_keyringMac) does not depend on the node id ...
        try (NodeKeyring kB = NodeKeyring.loadOrCreateOverIO(
                new CrashModelAnchorIO(disk), mac(sk), kek(sk), nodeId("nodeB"), new SecureRandom())) {
            // ... but the root cannot be unsealed under the wrong node's AAD.
            IntegrityException ex = assertThrows(IntegrityException.class, () -> kB.unsealRootKeys(REF),
                    "a root wrapped for nodeA must not unseal under nodeB (per-entry AAD node binding)");
            assertTrue(ex.getMessage().contains("term/node") || ex.getMessage().contains("tag failure"),
                    ex.getMessage());
        }
    }
}
