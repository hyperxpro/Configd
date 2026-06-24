package io.configd.server;

import io.configd.netty.NettyTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CI-fallback proof (ADR-0043 M2, charter §3.2/§7): re-runs the <b>entire</b>
 * {@link AbstractAdminApiServerContract} on the Netty admin server with the transport <b>forced to the
 * pure-Java NIO tier</b> — the always-available floor a CI runner (or any sandbox) that lacks
 * io_uring/epoll falls back to. io_uring is a performance tier, never a correctness dependency; this
 * proves every S7 control (incl. the five C6 path-normalization evasion vectors) holds on the fallback
 * transport, not just on the tier this box happens to pick. Forcing the tier in-process avoids
 * depending on surefire forwarding a {@code -D} to the test fork.
 *
 * <p>An epoll-forced equivalent isn't a separate class: the configd-netty {@code NettyTransportTest}
 * proves epoll resolves where available, and the auto-selected suite
 * ({@link NettyAdminApiServerContractTest}) already exercises the best available tier on this box.
 */
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
