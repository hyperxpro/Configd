package io.configd.server;

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
