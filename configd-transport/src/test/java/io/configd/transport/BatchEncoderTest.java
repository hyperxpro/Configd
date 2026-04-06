package io.configd.transport;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BatchEncoderTest {

    @Test
    void offerAndFlush() {
        BatchEncoder encoder = new BatchEncoder(10, 200_000);
        NodeId peer = NodeId.of(1);

        encoder.offer(peer, "msg1", 0);
        encoder.offer(peer, "msg2", 0);

        assertEquals(2, encoder.pendingCount(peer));
        List<Object> flushed = encoder.flush(peer);
        assertEquals(List.of("msg1", "msg2"), flushed);
        assertEquals(0, encoder.pendingCount(peer));
    }

    @Test
    void autoFlushOnMaxSize() {
        BatchEncoder encoder = new BatchEncoder(3, 200_000);
        NodeId peer = NodeId.of(1);

        assertFalse(encoder.offer(peer, "a", 0));
        assertFalse(encoder.offer(peer, "b", 0));
        assertTrue(encoder.offer(peer, "c", 0)); // hits max
    }

    @Test
    void readyPeersByTime() {
        BatchEncoder encoder = new BatchEncoder(100, 200_000);
        NodeId peer = NodeId.of(1);

        encoder.offer(peer, "msg", 1000);
        assertTrue(encoder.readyPeers(1000).isEmpty()); // not enough time
        assertTrue(encoder.readyPeers(1000 + 200_000).contains(peer)); // time elapsed
    }

    @Test
    void readyPeersBySize() {
        BatchEncoder encoder = new BatchEncoder(2, 200_000);
        NodeId peer = NodeId.of(1);

        encoder.offer(peer, "a", 0);
        assertTrue(encoder.readyPeers(0).isEmpty());
        encoder.offer(peer, "b", 0);
        assertTrue(encoder.readyPeers(0).contains(peer));
    }

    @Test
    void flushAllMultiplePeers() {
        BatchEncoder encoder = new BatchEncoder(10, 200_000);
        NodeId peer1 = NodeId.of(1);
        NodeId peer2 = NodeId.of(2);

        encoder.offer(peer1, "a", 0);
        encoder.offer(peer2, "b", 0);

        Map<NodeId, List<Object>> result = encoder.flushAll();
        assertEquals(1, result.get(peer1).size());
        assertEquals(1, result.get(peer2).size());
        assertEquals(0, encoder.totalPending());
    }

    @Test
    void flushEmptyReturnsEmptyList() {
        BatchEncoder encoder = new BatchEncoder(10, 200_000);
        assertTrue(encoder.flush(NodeId.of(1)).isEmpty());
    }

    @Test
    void reset() {
        BatchEncoder encoder = new BatchEncoder(10, 200_000);
        encoder.offer(NodeId.of(1), "msg", 0);
        encoder.reset();
        assertEquals(0, encoder.totalPending());
    }

    @Test
    void factoryMethods() {
        assertNotNull(BatchEncoder.forRaft());
        assertNotNull(BatchEncoder.forPlumtree());
    }
}
