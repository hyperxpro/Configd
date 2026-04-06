package io.configd.transport;

import io.configd.common.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionManagerTest {

    private final AtomicLong fakeTimeMs = new AtomicLong(0);
    private final AtomicLong fakeTimeNanos = new AtomicLong(0);
    private ConnectionManager manager;

    @BeforeEach
    void setUp() {
        io.configd.common.Clock fakeClock = new io.configd.common.Clock() {
            @Override public long currentTimeMillis() { return fakeTimeMs.get(); }
            @Override public long nanoTime() { return fakeTimeNanos.get(); }
        };
        manager = new ConnectionManager(fakeClock);
    }

    @Test
    void newPeerStartsDisconnected() {
        NodeId peer = NodeId.of(1);
        manager.addPeer(peer);
        assertEquals(ConnectionManager.ConnectionState.DISCONNECTED, manager.state(peer));
        assertTrue(manager.canSend(peer));
    }

    @Test
    void markConnectedTransitionsState() {
        NodeId peer = NodeId.of(1);
        manager.addPeer(peer);
        manager.markConnected(peer);
        assertEquals(ConnectionManager.ConnectionState.CONNECTED, manager.state(peer));
        assertTrue(manager.canSend(peer));
    }

    @Test
    void markDisconnectedEntersBackoff() {
        NodeId peer = NodeId.of(1);
        manager.addPeer(peer);
        manager.markConnected(peer);
        manager.markDisconnected(peer);

        assertEquals(ConnectionManager.ConnectionState.BACKING_OFF, manager.state(peer));
        assertFalse(manager.canSend(peer));
    }

    @Test
    void backoffExpiresAfterTimeout() {
        NodeId peer = NodeId.of(1);
        manager.addPeer(peer);
        manager.markConnected(peer);
        manager.markDisconnected(peer);

        // Advance time past backoff
        fakeTimeMs.addAndGet(250);
        assertEquals(ConnectionManager.ConnectionState.DISCONNECTED, manager.state(peer));
        assertTrue(manager.canSend(peer));
    }

    @Test
    void consecutiveFailuresIncrement() {
        NodeId peer = NodeId.of(1);
        manager.addPeer(peer);
        manager.markDisconnected(peer);
        assertEquals(1, manager.consecutiveFailures(peer));

        fakeTimeMs.addAndGet(500);
        manager.markDisconnected(peer);
        assertEquals(2, manager.consecutiveFailures(peer));
    }

    @Test
    void markConnectedResetsFailures() {
        NodeId peer = NodeId.of(1);
        manager.addPeer(peer);
        manager.markDisconnected(peer);
        manager.markDisconnected(peer);
        manager.markConnected(peer);
        assertEquals(0, manager.consecutiveFailures(peer));
    }

    @Test
    void removePeer() {
        NodeId peer = NodeId.of(1);
        manager.addPeer(peer);
        manager.removePeer(peer);
        assertFalse(manager.peers().contains(peer));
    }

    @Test
    void unknownPeerReturnsDisconnected() {
        assertEquals(ConnectionManager.ConnectionState.DISCONNECTED,
                manager.state(NodeId.of(99)));
    }
}
