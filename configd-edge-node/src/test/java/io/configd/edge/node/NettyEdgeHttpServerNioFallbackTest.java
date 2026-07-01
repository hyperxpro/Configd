package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.netty.NettyTransport;
import io.configd.observability.PrometheusExporter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Re-runs the entire {@link AbstractEdgeReadServerContract} on the Netty server with the transport
 * <b>forced to the pure-Java NIO tier</b> — the always-available floor that CI runners or sandboxes
 * without io_uring/epoll fall back to. io_uring is a performance tier, never a correctness
 * dependency; this proves every read-governance and security control holds on the fallback transport,
 * not just on whatever tier this box happens to select. (The tier is forced in-process to avoid
 * depending on surefire forwarding a {@code -D} to the test fork.)
 *
 * <p>An epoll-forced equivalent is not a separate class: {@code NettyTransportTest} proves epoll
 * resolves where available, and {@link NettyEdgeHttpServerTest} exercises the auto-selected best tier.
 */
class NettyEdgeHttpServerNioFallbackTest extends AbstractEdgeReadServerContract {

    @Override
    ServerHandle start(int port, EdgeClientCore core, StrongReadKeyClass strongReadKeyClass,
                       PrometheusExporter exporter, EdgeNodeMetrics metrics) throws Exception {
        String saved = System.getProperty(NettyTransport.PROP);
        System.setProperty(NettyTransport.PROP, "nio");
        NettyEdgeHttpServer server;
        try {
            // select() reads the forced tier in the constructor; restore the property immediately so
            // it cannot leak into other tests (the chosen tier is now fixed on this server instance).
            server = new NettyEdgeHttpServer(port, core, strongReadKeyClass, exporter, metrics);
        } finally {
            if (saved == null) {
                System.clearProperty(NettyTransport.PROP);
            } else {
                System.setProperty(NettyTransport.PROP, saved);
            }
        }
        assertEquals("nio", server.transportTier(), "fallback test must run on the NIO tier");
        server.start();
        return new ServerHandle() {
            @Override public int port() { return server.port(); }

            @Override public void stop() { server.stop(); }
        };
    }
}
