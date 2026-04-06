package io.configd.transport;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages logical connections to peer nodes with reconnection backoff.
 * <p>
 * Tracks connection state per peer: connected, disconnected, or backing off.
 * When a connection fails, exponential backoff prevents reconnection storms.
 * <p>
 * This class manages logical state only — actual socket/channel management
 * is delegated to the transport implementation (e.g., Netty).
 * <p>
 * Thread safety: designed for single-threaded access from the transport
 * I/O thread. No synchronization is used.
 */
public final class ConnectionManager {

    /**
     * Connection states.
     */
    public enum ConnectionState {
        /** Connection is established and healthy. */
        CONNECTED,
        /** Connection failed; waiting for backoff period before reconnect. */
        BACKING_OFF,
        /** Not connected, eligible for reconnection attempt. */
        DISCONNECTED
    }

    private static final long INITIAL_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final Clock clock;
    private final Map<NodeId, PeerConnection> peers;

    /**
     * Creates a connection manager with the given clock.
     */
    public ConnectionManager(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.peers = new HashMap<>();
    }

    /**
     * Registers a peer for connection tracking.
     *
     * @param peer the peer node to track
     */
    public void addPeer(NodeId peer) {
        Objects.requireNonNull(peer, "peer must not be null");
        peers.putIfAbsent(peer, new PeerConnection());
    }

    /**
     * Removes a peer from connection tracking.
     */
    public void removePeer(NodeId peer) {
        peers.remove(peer);
    }

    /**
     * Marks a peer as successfully connected. Resets backoff state.
     */
    public void markConnected(NodeId peer) {
        PeerConnection conn = peers.get(peer);
        if (conn != null) {
            conn.state = ConnectionState.CONNECTED;
            conn.currentBackoffMs = INITIAL_BACKOFF_MS;
            conn.lastAttemptMs = clock.currentTimeMillis();
            conn.consecutiveFailures = 0;
        }
    }

    /**
     * Marks a peer as disconnected and enters backoff. Each consecutive
     * failure doubles the backoff period up to {@value MAX_BACKOFF_MS}ms.
     */
    public void markDisconnected(NodeId peer) {
        PeerConnection conn = peers.get(peer);
        if (conn != null) {
            conn.state = ConnectionState.BACKING_OFF;
            conn.lastAttemptMs = clock.currentTimeMillis();
            conn.consecutiveFailures++;
            conn.currentBackoffMs = Math.min(
                    (long) (conn.currentBackoffMs * BACKOFF_MULTIPLIER),
                    MAX_BACKOFF_MS);
        }
    }

    /**
     * Returns the current connection state for a peer.
     */
    public ConnectionState state(NodeId peer) {
        PeerConnection conn = peers.get(peer);
        if (conn == null) {
            return ConnectionState.DISCONNECTED;
        }
        if (conn.state == ConnectionState.BACKING_OFF) {
            long elapsed = clock.currentTimeMillis() - conn.lastAttemptMs;
            if (elapsed >= conn.currentBackoffMs) {
                conn.state = ConnectionState.DISCONNECTED;
            }
        }
        return conn.state;
    }

    /**
     * Returns true if the peer is eligible for a reconnection attempt
     * (either CONNECTED or backoff period has elapsed).
     */
    public boolean canSend(NodeId peer) {
        ConnectionState s = state(peer);
        return s == ConnectionState.CONNECTED || s == ConnectionState.DISCONNECTED;
    }

    /**
     * Returns the number of consecutive failures for a peer.
     */
    public int consecutiveFailures(NodeId peer) {
        PeerConnection conn = peers.get(peer);
        return (conn != null) ? conn.consecutiveFailures : 0;
    }

    /**
     * Returns all tracked peers.
     */
    public Set<NodeId> peers() {
        return Set.copyOf(peers.keySet());
    }

    /**
     * Resets all peers to DISCONNECTED state with initial backoff.
     */
    public void resetAll() {
        for (PeerConnection conn : peers.values()) {
            conn.state = ConnectionState.DISCONNECTED;
            conn.currentBackoffMs = INITIAL_BACKOFF_MS;
            conn.consecutiveFailures = 0;
        }
    }

    private static final class PeerConnection {
        ConnectionState state = ConnectionState.DISCONNECTED;
        long lastAttemptMs;
        long currentBackoffMs = INITIAL_BACKOFF_MS;
        int consecutiveFailures;
    }
}
