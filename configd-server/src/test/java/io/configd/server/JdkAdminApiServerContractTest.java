package io.configd.server;

/**
 * The full admin / control-plane contract on the JDK {@link HttpApiServer} transport - the
 * incumbent transport, re-proven through the shared {@link AbstractAdminApiServerContract} after the
 * decision logic was extracted into {@link AdminApiHandler}. This is the baseline the
 * two Netty subclasses must match byte-for-byte; together the three classes are the
 * equivalence-by-construction proof for the transport swap.
 */
class JdkAdminApiServerContractTest extends AbstractAdminApiServerContract {

    @Override
    ServerHandle startServer(ServerSpec spec) throws Exception {
        HttpApiServer server = new HttpApiServer(
                0, spec.sslContext(), spec.healthService(), spec.prometheusExporter(),
                spec.configStore(), spec.writeService(), spec.readService(), spec.authInterceptor(),
                spec.aclService(), spec.strongReadPolicy(), spec.leaderHint(),
                spec.auditLog(), spec.replayGuard());
        server.start();
        return new ServerHandle() {
            @Override public int port() { return server.port(); }

            @Override public void stop() { server.stop(0); }
        };
    }
}
