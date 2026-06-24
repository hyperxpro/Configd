package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.PrometheusExporter;

/**
 * The edge read-serving contract on the Netty {@link NettyEdgeHttpServer} transport (M1, ADR-0043).
 * Runs the <b>identical</b> {@link AbstractEdgeReadServerContract} matrix the JDK transport passes
 * ({@link EdgeHttpServerTest}) — re-proving every read-governance + S7 control (cursor/staleness,
 * strong-read fail-close, not-subscribed/cursor-behind refusal, {@code /metrics} Bearer gate, method
 * validation, INV-M1 seam) on the migrated pipeline. The transport auto-selects io_uring→Epoll→NIO;
 * CI forces the fallback tiers (the configd-netty {@code NettyTransportTest} + the gate).
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
