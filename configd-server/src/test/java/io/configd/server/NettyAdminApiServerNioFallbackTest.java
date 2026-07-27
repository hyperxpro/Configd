package io.configd.server;

import io.configd.netty.NettyTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyAdminApiServerNioFallbackTest extends AbstractAdminApiServerContract {

    @Override
    ServerHandle startServer(ServerSpec spec) throws Exception {
        String saved = System.getProperty(NettyTransport.PROP);
        System.setProperty(NettyTransport.PROP, "nio");
        NettyHttpApiServer server;
        try {
            // select() reads the forced tier in the constructor; restore the property immediately so
            // it cannot leak into other tests (the chosen tier is now fixed on this server instance).
            server = new NettyHttpApiServer(
                    0, spec.sslContext(), spec.healthService(), spec.prometheusExporter(),
                    spec.configStore(), spec.writeService(), spec.readService(), spec.authInterceptor(),
                    spec.aclService(), spec.strongReadPolicy(), spec.leaderHint(),
                    spec.auditLog(), spec.replayGuard());
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
