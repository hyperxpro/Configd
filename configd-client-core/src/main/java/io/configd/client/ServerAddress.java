package io.configd.client;

import java.util.Objects;

/**
 * Resolvable edge endpoint: host name (not pre-resolved) and port so TLS can verify SAN. No wire discovery.
 */
public record ServerAddress(String host, int port) {

    public ServerAddress {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in [1, 65535]: " + port);
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
