package io.configd.raft;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link NodeAnchorRecord} 92-byte payload codec and the {@code shardAnchorDigest}
 * helper (the shard-liveness fingerprint). The dual-slot file mechanics are in
 * {@link NodeAnchorFileTest}; the boot cross-check is in the server module's {@code NodeAnchorBootTest}.
 */
class NodeAnchorRecordTest {

    private static byte[] hash(int fill) {
        byte[] h = new byte[NodeAnchorRecord.HASH_LEN];
        java.util.Arrays.fill(h, (byte) fill);
        return h;
    }

    @Test
    void payloadIs92Bytes() {
        assertEquals(92, NodeAnchorRecord.PAYLOAD_LEN, "frozen node-anchor payload = 92 B (60 + 32 digest)");
    }

    @Test
    void encodeDecodeRoundTrip() {
        NodeAnchorRecord r = new NodeAnchorRecord(7L, 3L, 4, 128L, hash(0xAB), hash(0xCD));
        byte[] payload = r.encodePayload();
        assertEquals(NodeAnchorRecord.PAYLOAD_LEN, payload.length);

        NodeAnchorRecord back = NodeAnchorRecord.decode(payload);
        assertEquals(7L, back.nodeAnchorSeq());
        assertEquals(3L, back.topologyEpoch());
        assertEquals(4, back.shardCount());
        assertEquals(128L, back.auditRecordCount());
        assertArrayEquals(hash(0xAB), back.auditHeadHash());
        assertArrayEquals(hash(0xCD), back.shardAnchorDigest());
    }

    @Test
    void decodeWrongLengthThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> NodeAnchorRecord.decode(new byte[91]));
        assertThrows(IllegalArgumentException.class,
                () -> NodeAnchorRecord.decode(new byte[93]));
    }

    @Test
    void constructorRejectsBadHashLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeAnchorRecord(1L, 1L, 1, 0L, new byte[31], hash(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new NodeAnchorRecord(1L, 1L, 1, 0L, hash(0), new byte[33]));
    }

    @Test
    void constructorRejectsShardCountBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new NodeAnchorRecord(1L, 1L, 0, 0L, hash(0), hash(0)));
    }

    @Test
    void freshHasSeqOneGenesisAuditAndBoundDigest() {
        byte[] digest = hash(0x11);
        NodeAnchorRecord fresh = NodeAnchorRecord.fresh(5L, 3, digest);
        assertEquals(1L, fresh.nodeAnchorSeq());
        assertEquals(5L, fresh.topologyEpoch());
        assertEquals(3, fresh.shardCount());
        assertEquals(0L, fresh.auditRecordCount());
        assertArrayEquals(NodeAnchorRecord.ZERO_HASH, fresh.auditHeadHash());
        assertArrayEquals(digest, fresh.shardAnchorDigest());
    }

    @Test
    void withSeqAndWithAuditAndDigestAreCopies() {
        NodeAnchorRecord base = NodeAnchorRecord.fresh(1L, 2, hash(1));
        NodeAnchorRecord seq5 = base.withSeq(5L);
        assertEquals(5L, seq5.nodeAnchorSeq());
        assertEquals(base.topologyEpoch(), seq5.topologyEpoch());

        NodeAnchorRecord adv = base.withAuditAndDigest(42L, hash(0x22), hash(0x33));
        assertEquals(base.nodeAnchorSeq(), adv.nodeAnchorSeq(), "audit/digest update keeps the seq");
        assertEquals(base.topologyEpoch(), adv.topologyEpoch(), "audit/digest update keeps topology");
        assertEquals(base.shardCount(), adv.shardCount());
        assertEquals(42L, adv.auditRecordCount());
        assertArrayEquals(hash(0x22), adv.auditHeadHash());
        assertArrayEquals(hash(0x33), adv.shardAnchorDigest());
    }

    @Test
    void recordIsImmutableAgainstArrayMutation() {
        byte[] audit = hash(0x40);
        byte[] digest = hash(0x50);
        NodeAnchorRecord r = new NodeAnchorRecord(1L, 1L, 1, 0L, audit, digest);
        audit[0] ^= 0xFF;
        digest[0] ^= 0xFF;
        assertEquals(0x40, r.auditHeadHash()[0] & 0xFF, "auditHeadHash must be defensively cloned");
        assertEquals(0x50, r.shardAnchorDigest()[0] & 0xFF, "shardAnchorDigest must be defensively cloned");
    }

    @Test
    void shardDigestIsDeterministicAndOrderInvariant() {
        Map<Integer, Long> ascending = new TreeMap<>();
        ascending.put(0, 100L);
        ascending.put(1, 200L);
        ascending.put(2, 300L);

        Map<Integer, Long> shuffled = new LinkedHashMap<>();
        shuffled.put(2, 300L);
        shuffled.put(0, 100L);
        shuffled.put(1, 200L);

        byte[] a = NodeAnchorRecord.computeShardAnchorDigest(ascending);
        byte[] b = NodeAnchorRecord.computeShardAnchorDigest(shuffled);
        assertEquals(NodeAnchorRecord.HASH_LEN, a.length);
        assertArrayEquals(a, b, "the digest must be independent of the caller's map iteration order");
    }

    @Test
    void shardDigestChangesWhenAShardResetsToZero() {
        Map<Integer, Long> live = new TreeMap<>();
        live.put(0, 100L);
        live.put(1, 200L);
        byte[] before = NodeAnchorRecord.computeShardAnchorDigest(live);

        Map<Integer, Long> wiped = new TreeMap<>();
        wiped.put(0, 100L);
        wiped.put(1, 0L);
        byte[] after = NodeAnchorRecord.computeShardAnchorDigest(wiped);

        assertFalse(java.util.Arrays.equals(before, after),
                "a shard reset to index 0 must change the shard-anchor digest (R-f detection)");
    }

    @Test
    void shardDigestForwardAdvanceAlsoChangesButIsNotBackward() {
        Map<Integer, Long> t0 = new TreeMap<>();
        t0.put(0, 100L);
        byte[] d0 = NodeAnchorRecord.computeShardAnchorDigest(t0);

        Map<Integer, Long> t1 = new TreeMap<>();
        t1.put(0, 150L);
        byte[] d1 = NodeAnchorRecord.computeShardAnchorDigest(t1);

        assertFalse(java.util.Arrays.equals(d0, d1),
                "a forward advance also changes the digest (so equality alone cannot distinguish it "
                        + "from a wipe - the boot check uses the FRESH-shard signal to discriminate)");
        assertTrue(t1.get(0) > t0.get(0), "advance is forward, not a reset");
    }
}
