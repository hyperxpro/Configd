package io.configd.client;

import java.util.Objects;

/**
 * A resolvable edge endpoint — a host name and port. The host is kept as a <b>name</b> (not pre-resolved) so
 * the TLS layer can verify the server certificate's SAN against it ({@code HTTPS} endpoint identification,
 * §06 F9-4). There is no wire discovery: endpoints are operator-provided.
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
