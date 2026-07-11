package io.configd.server;

import io.configd.api.HealthService;
import io.configd.common.NodeId;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin read/write HTTP API must bind the configured interface, not the wildcard. If an adapter
 * bound {@code new InetSocketAddress(port)} (all interfaces) regardless of bindAddress, a
 * loopback-default + auth-off deployment would still expose the store on every interface, while an
 * operator's "loopback" check on the config would pass and miss it - one bindAddress must govern the
 * Raft, edge, and API planes alike. A single-interface CI box cannot prove a wildcard-negative, so this
 * asserts the wiring directly: the bound host equals the configured loopback host, and the legacy
 * no-bindAddress constructor still binds the wildcard (byte-identical). Covers both adapters (Netty
 * production and the JDK adapter).
 */
@Timeout(30)
class AdminApiBindAddressTest {

    private static NettyHttpApiServer nettyLoopback() {
        return new NettyHttpApiServer(
                "127.0.0.1", 0, null, new HealthService(), new PrometheusExporter(new MetricsRegistry()),
                new VersionedConfigStore(), null, null, null, null, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), null, null, null, null);
    }

    private static NettyHttpApiServer nettyLegacyWildcard() {
        return new NettyHttpApiServer(
                0, null, new HealthService(), new PrometheusExporter(new MetricsRegistry()),
                new VersionedConfigStore(), null, null, null, null, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), null, null);
    }

    @Test
    void nettyHonorsExplicitLoopbackBind() throws Exception {
        NettyHttpApiServer server = nettyLoopback();
        try {
            server.start();
            assertEquals("127.0.0.1", server.boundHost(),
                    "the API must bind the configured loopback interface, not the wildcard");
        } finally {
            server.stop();
        }
    }

    @Test
    void nettyLegacyConstructorStillBindsWildcard() throws Exception {
        NettyHttpApiServer server = nettyLegacyWildcard();
        try {
            server.start();
            assertTrue(InetAddress.getByName(server.boundHost()).isAnyLocalAddress(),
                    "the no-bindAddress constructor must bind the wildcard (byte-identical): "
                            + server.boundHost());
        } finally {
            server.stop();
        }
    }

    @Test
    void jdkAdapterHonorsExplicitLoopbackBind() throws Exception {
        HttpApiServer server = new HttpApiServer(
                "127.0.0.1", 0, null, new HealthService(), new PrometheusExporter(new MetricsRegistry()),
                new VersionedConfigStore(), null, null, null, null, StrongReadPolicy.defaultPolicy(),
                (scope, key) -> NodeId.of(1), null, null, null, null);
        try {
            server.start();
            assertEquals("127.0.0.1", server.boundHost(),
                    "the JDK adapter must also bind the configured loopback interface");
        } finally {
            server.stop(0);
        }
    }
}
