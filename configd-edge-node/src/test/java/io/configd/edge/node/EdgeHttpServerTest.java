package io.configd.edge.node;

import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.PrometheusExporter;

/**
 * The edge read-serving contract on the JDK {@link EdgeHttpServer} transport (the equivalence
 * reference). All assertions live in {@link AbstractEdgeReadServerContract}; the Netty transport runs
 * the identical matrix in {@link NettyEdgeHttpServerTest} (ADR-0043 / DR-N2). This subclass only
 * wires the JDK adapter.
 */
class EdgeHttpServerTest extends AbstractEdgeReadServerContract {

    @Override
    ServerHandle start(int port, EdgeClientCore core, StrongReadKeyClass strongReadKeyClass,
                       PrometheusExporter exporter, EdgeNodeMetrics metrics) throws Exception {
        EdgeHttpServer server = new EdgeHttpServer(port, core, strongReadKeyClass, exporter, metrics);
        server.start();
        return new ServerHandle() {
            @Override public int port() { return server.port(); }

            @Override public void stop() { server.stop(0); }
        };
    }
}
