package io.configd.common;

import io.configd.common.kms.KeyId;
import io.configd.common.kms.RootKey;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the nonce-uniqueness invariant and keyring semantics of {@link SegmentKeyManager} -
 * the class the redteam will attack hardest, because a reused (key, nonce) breaks AES-GCM.
 */
class SegmentKeyManagerTest {

    private static final int MAGIC_A = 0x5257_414C; // "RWAL"
    private static final int MAGIC_B = 0x5253_4E50; // "RSNP"

    private static RootKey root(int term) {
        byte[] m = new byte[32];
        Arrays.fill(m, (byte) (0x30 + term));
        return new RootKey(m, new KeyId("local", "kid", term));
    }

    private static String nonceKey(AtRestKeys.Seal seal) {
        // A (segmentId, nonce) pair - the thing that must never repeat under a fixed key term.
        return HexFormat.of().formatHex(seal.segmentId()) + "/" + HexFormat.of().formatHex(seal.nonce());
    }

    @Test
    void noncesAreUniqueWithinASegmentStream() {
        SegmentKeyManager km = new SegmentKeyManager(root(1));
        Set<String> seen = ConcurrentHashMap.newKeySet();
        byte[] firstSegment = null;
        for (int i = 0; i < 100_000; i++) {
            AtRestKeys.Seal seal = km.nextSeal(MAGIC_A);
            assertEquals(AtRestKeys.NONCE_LEN, seal.nonce().length);
            assertTrue(seen.add(nonceKey(seal)), "duplicate (segmentId,nonce) at i=" + i);
            if (firstSegment == null) {
                firstSegment = seal.segmentId();
            } else {
                // one boot session means one WAL segment (no rekey below the ceiling)
                assertArrayEquals(firstSegment, seal.segmentId(),
                        "all records of one stream share the session segment");
            }
        }
        assertEquals(100_000, seen.size());
    }

    @Test
    void distinctMagicsGetDistinctSegmentsHenceDistinctDeks() {
        SegmentKeyManager km = new SegmentKeyManager(root(1));
        AtRestKeys.Seal a = km.nextSeal(MAGIC_A);
        AtRestKeys.Seal b = km.nextSeal(MAGIC_B);
        assertFalse(Arrays.equals(a.segmentId(), b.segmentId()),
                "WAL and snapshot streams must be different segments");
        // a different segmentId means a different derived DEK
        assertFalse(Arrays.equals(a.dek().getEncoded(), b.dek().getEncoded()));
    }

    @Test
    void freshManagerDrawsAFreshSegment_soRestartCounterResetIsSafe() {
        // Two managers over the same root (a restart re-deriving the same root) still draw
        // independent random segmentIds, so both starting the counter at 0 is safe.
        SegmentKeyManager first = new SegmentKeyManager(root(1));
        SegmentKeyManager second = new SegmentKeyManager(root(1));
        AtRestKeys.Seal s1 = first.nextSeal(MAGIC_A);
        AtRestKeys.Seal s2 = second.nextSeal(MAGIC_A);
        assertArrayEquals(s1.nonce(), s2.nonce(), "both counters start at 0 (same nonce bytes)...");
        assertFalse(Arrays.equals(s1.segmentId(), s2.segmentId()),
                "...but distinct fresh segmentIds -> distinct DEKs -> no (key,nonce) reuse");
    }

    @Test
    void resolveDekMatchesTheWriteDek() {
        SegmentKeyManager km = new SegmentKeyManager(root(1));
        AtRestKeys.Seal seal = km.nextSeal(MAGIC_A);
        SecretKey readDek = km.resolveDek(seal.keyTerm(), seal.segmentId());
        assertArrayEquals(seal.dek().getEncoded(), readDek.getEncoded(),
                "the reader re-derives the identical per-segment DEK");
    }

    @Test
    void resolveUnknownTermFailsClosed() {
        SegmentKeyManager km = new SegmentKeyManager(root(1));
        byte[] segId = new byte[AtRestKeys.SEGMENT_ID_LEN];
        assertThrows(IntegrityException.class, () -> km.resolveDek(999, segId));
    }

    @Test
    void rotationRetainsOldTermForReadAndUsesNewTermForWrite() {
        SegmentKeyManager km = new SegmentKeyManager(root(1));
        AtRestKeys.Seal oldSeal = km.nextSeal(MAGIC_A);
        assertEquals(1, oldSeal.keyTerm());

        km.rotateTo(root(2));

        // old-term data still decrypts (old root retained)
        SecretKey oldDek = km.resolveDek(oldSeal.keyTerm(), oldSeal.segmentId());
        assertArrayEquals(oldSeal.dek().getEncoded(), oldDek.getEncoded());

        // new writes use the new term
        AtRestKeys.Seal newSeal = km.nextSeal(MAGIC_A);
        assertEquals(2, newSeal.keyTerm());
        assertFalse(Arrays.equals(oldSeal.segmentId(), newSeal.segmentId()),
                "rotation rolls to a fresh segment at the new term");
    }

    @Test
    void concurrentNextSealNeverRepeatsANonce() throws Exception {
        SegmentKeyManager km = new SegmentKeyManager(root(1));
        int threads = 8;
        int perThread = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        boolean[] dup = {false};
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    if (!seen.add(nonceKey(km.nextSeal(MAGIC_A)))) {
                        dup[0] = true;
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers finished");
        assertFalse(dup[0], "a (segmentId,nonce) was issued twice under concurrency");
        assertEquals(threads * perThread, seen.size());
    }
}
