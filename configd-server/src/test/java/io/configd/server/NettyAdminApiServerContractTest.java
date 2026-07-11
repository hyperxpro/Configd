package io.configd.server;

/**
 * The full admin / control-plane contract on the Netty {@link NettyHttpApiServer} transport, running
 * the <b>identical</b> {@link AbstractAdminApiServerContract} matrix the JDK transport passes
 * ({@link JdkAdminApiServerContractTest}). Because both adapters delegate to the same
 * {@link AdminApiHandler}, every security control - authn/authz/escalation, audit completeness and
 * chain, replay, strong-read fail-close, and the path-normalization evasion vectors - is re-proven on
 * this transport. The transport auto-selects Epoll then NIO (io_uring is opt-in); the forced-NIO floor
 * is proven by {@link NettyAdminApiServerNioFallbackTest}.
 */
class NettyAdminApiServerContractTest extends AbstractAdminApiServerContract {

    @Override
    ServerHandle startServer(ServerSpec spec) throws Exception {
        NettyHttpApiServer server = new NettyHttpApiServer(
                0, spec.sslContext(), spec.healthService(), spec.prometheusExporter(),
                spec.configStore(), spec.writeService(), spec.readService(), spec.authInterceptor(),
                spec.aclService(), spec.strongReadPolicy(), spec.leaderHint(),
                spec.auditLog(), spec.replayGuard());
        server.start();
        return new ServerHandle() {
            @Override public int port() { return server.port(); }

            @Override public void stop() { server.stop(); }
        };
    }
}
