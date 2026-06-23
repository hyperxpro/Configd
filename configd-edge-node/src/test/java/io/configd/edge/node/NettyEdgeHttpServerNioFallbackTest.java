package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.PrometheusExporter;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CI-fallback proof (M1.6, charter §3.2/§7): re-runs the <b>entire</b>
 * {@link AbstractEdgeReadServerContract} on the Netty server with the transport <b>forced to the
 * pure-Java NIO tier</b> — the always-available floor a CI runner (or any sandbox) that lacks
 * io_uring/epoll falls back to. io_uring is a performance tier, never a correctness dependency; this
 * proves every read-governance + S7 control holds on the fallback transport, not just on the tier
 * this dev box happens to pick. (Forcing the tier in-process avoids depending on surefire forwarding
 * a {@code -D} to the test fork.)
 *
 * <p>An epoll-forced equivalent isn't a separate class: {@link NettyTransportTest} proves epoll
 * resolves where available, and the auto-selected suite ({@link NettyEdgeHttpServerTest}) already
 * exercises the best available tier (epoll or io_uring) on this box.
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
