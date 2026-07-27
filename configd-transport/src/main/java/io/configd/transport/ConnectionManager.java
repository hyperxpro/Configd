package io.configd.transport;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages logical connection state only; actual socket/channel management is delegated to
 * the transport implementation (e.g., Netty).
 * <p>
 * Single-threaded access from the transport I/O thread. No synchronization is used.
 */
public final class ConnectionManager {

    public enum ConnectionState {
        CONNECTED,
        BACKING_OFF,
        DISCONNECTED
    }

    private static final long INITIAL_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final Clock clock;
    private final Map<NodeId, PeerConnection> peers;

    public ConnectionManager(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.peers = new HashMap<>();
    }

    public void addPeer(NodeId peer) {
        Objects.requireNonNull(peer, "peer must not be null");
        peers.putIfAbsent(peer, new PeerConnection());
    }

    public void removePeer(NodeId peer) {
        peers.remove(peer);
    }

    public void markConnected(NodeId peer) {
        PeerConnection conn = peers.get(peer);
        if (conn != null) {
            conn.state = ConnectionState.CONNECTED;
            conn.currentBackoffMs = INITIAL_BACKOFF_MS;
            conn.lastAttemptMs = clock.currentTimeMillis();
            conn.consecutiveFailures = 0;
        }
    }

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

    public boolean canSend(NodeId peer) {
        ConnectionState s = state(peer);
        return s == ConnectionState.CONNECTED || s == ConnectionState.DISCONNECTED;
    }

    public int consecutiveFailures(NodeId peer) {
        PeerConnection conn = peers.get(peer);
        return (conn != null) ? conn.consecutiveFailures : 0;
    }

    /** Lets a connector schedule a reconnect that respects the backoff without polling. */
    public long backoffRemainingMs(NodeId peer) {
        PeerConnection conn = peers.get(peer);
        if (conn == null || conn.state != ConnectionState.BACKING_OFF) {
            return 0;
        }
        long elapsed = clock.currentTimeMillis() - conn.lastAttemptMs;
        long remaining = conn.currentBackoffMs - elapsed;
        return Math.max(0, remaining);
    }

    public Set<NodeId> peers() {
        return Set.copyOf(peers.keySet());
    }

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
