package io.configd.server;

/**
 * The full admin / control-plane S7 contract on the Netty {@link NettyHttpApiServer} transport
 * (ADR-0043 M2), running the <b>identical</b> {@link AbstractAdminApiServerContract} matrix the JDK
 * transport passes ({@link JdkAdminApiServerContractTest}). Because both adapters delegate to the same
 * {@link AdminApiHandler}, every S7 control — authn/authz/escalation, audit completeness + chain,
 * replay, strong-read fail-close, and the five C6 path-normalization evasion vectors — is re-proven on
 * the migrated pipeline. The transport auto-selects io_uring→Epoll→NIO; the forced-NIO floor is proven
 * by {@link NettyAdminApiServerNioFallbackTest}.
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
