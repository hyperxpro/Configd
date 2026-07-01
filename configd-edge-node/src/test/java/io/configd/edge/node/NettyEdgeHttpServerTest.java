package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.PrometheusExporter;

/**
 * The edge read-serving contract on the {@link NettyEdgeHttpServer} transport. Runs the identical
 * {@link AbstractEdgeReadServerContract} matrix the JDK transport passes ({@link EdgeHttpServerTest})
 * — re-proving every read-governance control (cursor/staleness, strong-read fail-close,
 * not-subscribed/cursor-behind refusal, {@code /metrics} Bearer gate, method validation, monotonic
 * read) on the Netty pipeline. The transport auto-selects Epoll then NIO (io_uring is opt-in via
 * system property); CI forces the fallback tier via {@link NettyEdgeHttpServerNioFallbackTest}.
 */
class NettyEdgeHttpServerTest extends AbstractEdgeReadServerContract {

    @Override
    ServerHandle start(int port, EdgeClientCore core, StrongReadKeyClass strongReadKeyClass,
                       PrometheusExporter exporter, EdgeNodeMetrics metrics) throws Exception {
        NettyEdgeHttpServer server = new NettyEdgeHttpServer(port, core, strongReadKeyClass,
                exporter, metrics);
        server.start();
        return new ServerHandle() {
            @Override public int port() { return server.port(); }

            @Override public void stop() { server.stop(); }
        };
    }
}
