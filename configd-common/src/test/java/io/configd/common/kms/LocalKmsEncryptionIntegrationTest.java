package io.configd.common.kms;

import io.configd.common.IntegrityEnvelope;
import io.configd.common.SegmentKeyManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end exercise of the KMS-provider SPI contract through the full at-rest envelope
 * path with {@link LocalDerivedKmsProvider}. Proves the contract so a future
 * {@code AwsKmsProvider}/{@code VaultProvider} implements an already-exercised SPI:
 * provision -> encrypt -> persist -> RESTART (re-unseal from the same on-disk state) ->
 * decrypt -> bytes identical; plus keyring rotation with old-term retention.
 */
class LocalKmsEncryptionIntegrationTest {

    private static final int WAL_MAGIC = 0x5257_414C;  // "RWAL"
    private static final int SNAP_MAGIC = 0x5253_4E50; // "RSNP"
    private static final int SCOPE = 3;                // a per-shard scope (gid); same on wrap+read here

    /** The cluster signing-key encoding, held outside the data directory in production. */
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

    @Test
    void provisionPersistRestartDecrypt_bytesIdentical() throws Exception {
        byte[] sk = signingKey((byte) 0xC3);
        List<byte[]> plaintexts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            plaintexts.add(("wal-command-" + i + "=secret-value-" + i).getBytes(StandardCharsets.UTF_8));
        }

        // boot #1: provision the root, persist its WrappedKey, encrypt and "persist" records
        WrappedKey persistedWrapped;
        List<byte[]> onDisk = new ArrayList<>();
        try (KmsProvider provider = new LocalDerivedKmsProvider(sk, salt(), "kid", 1)) {
            provider.healthCheck();
            KmsProvider.Provisioned prov = provider.generateRootKey();
            persistedWrapped = prov.wrapped();
            SegmentKeyManager km = new SegmentKeyManager(prov.rootKey());
            IntegrityEnvelope env = IntegrityEnvelope.encrypting(km);
            for (byte[] pt : plaintexts) {
                onDisk.add(env.wrap(WAL_MAGIC, SCOPE,pt));
            }
        }

        for (byte[] record : onDisk) {
            String latin1 = new String(record, StandardCharsets.ISO_8859_1);
            assertFalse(latin1.contains("secret-value-"), "on-disk record leaked plaintext");
        }

        // boot #2 (RESTART): re-unseal the root from the SAME signing key and persisted WrappedKey
        try (KmsProvider provider2 = new LocalDerivedKmsProvider(sk, salt(), "kid", 1)) {
            SegmentKeyManager km2 = SegmentKeyManager.unsealFrom(provider2, persistedWrapped);
            IntegrityEnvelope env2 = IntegrityEnvelope.encrypting(km2);
            for (int i = 0; i < onDisk.size(); i++) {
                assertArrayEquals(plaintexts.get(i), env2.unwrap(WAL_MAGIC, SCOPE,onDisk.get(i)),
                        "record " + i + " must decrypt to identical bytes after restart");
            }
        }
    }

    @Test
    void freshDekPerSegment_walAndSnapshotUseDifferentKeys() throws Exception {
        byte[] sk = signingKey((byte) 0x11);
        try (KmsProvider provider = new LocalDerivedKmsProvider(sk, salt(), "kid", 1)) {
            SegmentKeyManager km = SegmentKeyManager.unsealFrom(provider, provider.generateRootKey().wrapped());
            IntegrityEnvelope env = IntegrityEnvelope.encrypting(km);
            byte[] wal = env.wrap(WAL_MAGIC, SCOPE,"w".getBytes(StandardCharsets.UTF_8));
            byte[] snap = env.wrap(SNAP_MAGIC, SCOPE,"s".getBytes(StandardCharsets.UTF_8));
            // v3: segmentId is at [16, 32) (header 8 + scopeId 4 + keyTerm 4).
            byte[] walSeg = Arrays.copyOfRange(wal, 16, 32);
            byte[] snapSeg = Arrays.copyOfRange(snap, 16, 32);
            assertFalse(Arrays.equals(walSeg, snapSeg),
                    "WAL and snapshot are distinct segments -> distinct DEKs");
            assertArrayEquals("w".getBytes(StandardCharsets.UTF_8), env.unwrap(WAL_MAGIC, SCOPE,wal));
            assertArrayEquals("s".getBytes(StandardCharsets.UTF_8), env.unwrap(SNAP_MAGIC, SCOPE,snap));
        }
    }

    @Test
    void rotation_oldTermDataStillDecryptsAfterBumpToNewTerm() throws Exception {
        byte[] sk1 = signingKey((byte) 0x01);
        byte[] sk2 = signingKey((byte) 0x02); // a rotated signing key -> a new root at term 2

        SegmentKeyManager km;
        byte[] oldTermRecord;
        try (KmsProvider p1 = new LocalDerivedKmsProvider(sk1, salt(), "kid", 1)) {
            km = SegmentKeyManager.unsealFrom(p1, p1.generateRootKey().wrapped());
        }
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(km);

        byte[] oldPlain = "written-under-term-1".getBytes(StandardCharsets.UTF_8);
        oldTermRecord = env.wrap(WAL_MAGIC, SCOPE,oldPlain);
        assertEquals(1, keyTermOf(oldTermRecord));

        // rotate: install a new root at term 2, RETAIN term 1
        try (KmsProvider p2 = new LocalDerivedKmsProvider(sk2, salt(), "kid", 2)) {
            km.rotateTo(p2.unwrap(p2.generateRootKey().wrapped()));
        }

        byte[] newPlain = "written-under-term-2".getBytes(StandardCharsets.UTF_8);
        byte[] newTermRecord = env.wrap(WAL_MAGIC, SCOPE,newPlain);
        assertEquals(2, keyTermOf(newTermRecord));

        // BOTH decrypt: old term retained, new term current
        assertArrayEquals(oldPlain, env.unwrap(WAL_MAGIC, SCOPE,oldTermRecord),
                "term-1 data must still decrypt after rotation to term 2");
        assertArrayEquals(newPlain, env.unwrap(WAL_MAGIC, SCOPE,newTermRecord));
    }

    @Test
    void unsealFrom_isTheOneBootCall_andRootSurvivesProviderClose() throws Exception {
        byte[] sk = signingKey((byte) 0x77);
        SegmentKeyManager km;
        WrappedKey wrapped;
        try (KmsProvider provider = new LocalDerivedKmsProvider(sk, salt(), "kid", 1)) {
            wrapped = provider.generateRootKey().wrapped();
            km = SegmentKeyManager.unsealFrom(provider, wrapped);
        } // provider closed here (R2): the cached root in `km` must remain usable
        IntegrityEnvelope env = IntegrityEnvelope.encrypting(km);
        byte[] pt = "post-close".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(pt, env.unwrap(WAL_MAGIC, SCOPE,env.wrap(WAL_MAGIC, SCOPE,pt)),
                "the node runs on the cached root after the provider is dropped");
    }

    /** keyTerm is the 4 bytes after the 8-byte header + 4-byte scopeId (v3 offset 12). */
    private static int keyTermOf(byte[] enveloped) {
        return ((enveloped[12] & 0xFF) << 24) | ((enveloped[13] & 0xFF) << 16)
                | ((enveloped[14] & 0xFF) << 8) | (enveloped[15] & 0xFF);
    }
}
