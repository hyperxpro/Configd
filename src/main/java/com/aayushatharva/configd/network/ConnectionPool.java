package com.aayushatharva.configd.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection pool for outgoing connections to peer nodes.
 *
 * Maintains one {@link NetworkClient} per peer address. Connections are
 * lazily established and automatically reconnected on failure.
 */
public class ConnectionPool implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);

    private final ConcurrentHashMap<String, NetworkClient> pool = new ConcurrentHashMap<>();

    /**
     * Get or create a connection to the given address (host:port).
     */
    public NetworkClient getConnection(String host, int port) {
        String key = host + ":" + port;
        return pool.computeIfAbsent(key, k -> {
            var client = new NetworkClient(host, port);
            try {
                client.connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while connecting to " + key, e);
            }
            return client;
        });
    }

    /**
     * Get a connection if it exists and is active, otherwise reconnect.
     */
    public NetworkClient getOrReconnect(String host, int port) {
        String key = host + ":" + port;
        var existing = pool.get(key);
        if (existing != null && existing.isConnected()) {
            return existing;
        }
        // Remove stale connection and create a new one
        if (existing != null) {
            pool.remove(key);
            existing.close();
        }
        return getConnection(host, port);
    }

    public void removeConnection(String host, int port) {
        String key = host + ":" + port;
        var client = pool.remove(key);
        if (client != null) {
            client.close();
        }
    }

    public int activeConnections() {
        return (int) pool.values().stream().filter(NetworkClient::isConnected).count();
    }

    @Override
    public void close() {
        pool.values().forEach(NetworkClient::close);
        pool.clear();
        log.info("Connection pool closed");
    }
}
