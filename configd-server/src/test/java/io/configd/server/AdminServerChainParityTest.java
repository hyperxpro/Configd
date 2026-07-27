package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
import io.configd.common.NodeId;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.BasicAuthPasswords;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(30)
class AdminServerChainParityTest {

    private record CfgStub(Map<String, String> m) implements io.configd.common.config.ConfigSource {
        @Override public Optional<String> getString(String key) { return Optional.ofNullable(m.get(key)); }
        @Override public Set<String> keysWithPrefix(String p) {
            return m.keySet().stream().filter(k -> k.startsWith(p)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private interface Server { int port(); void stop(); }

    private static AuthenticatorChain basicChain() {
        String alice = BasicAuthPasswords.hash("wonderland".toCharArray());
        String bob = BasicAuthPasswords.hash("builder".toCharArray());
        return AuthenticatorChain.build(List.of("basic"),
                new CfgStub(Map.of("configd.auth.basic.users", "alice:" + alice + ",bob:" + bob)));
    }

    private static AclService acl() {
        AclService acl = new AclService();
        acl.grant("app.", "alice", EnumSet.of(AclService.Permission.READ));
        acl.grant("app.", "bob", EnumSet.of(AclService.Permission.READ, AclService.Permission.WRITE));
        return acl;
    }

    private static VersionedConfigStore store() {
        VersionedConfigStore s = new VersionedConfigStore();
        s.put("app.feature", "on".getBytes(StandardCharsets.UTF_8), 1);
        return s;
    }

    private static ConfigWriteService committing() {
        return new ConfigWriteService(
                (scope, keys, command) -> new ConfigWriteService.ProposeCommitResult.Committed(1L), null, null);
    }

    private static Server jdk(AuthenticatorChain chain) throws Exception {
        HttpApiServer s = new HttpApiServer(0, null, new HealthService(),
                new PrometheusExporter(new MetricsRegistry()), store(), committing(), null, null, acl(),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), null, null, null, chain);
        s.start();
        return new Server() {
            @Override public int port() { return s.port(); }
            @Override public void stop() { s.stop(0); }
        };
    }

    private static Server netty(AuthenticatorChain chain) throws Exception {
        NettyHttpApiServer s = new NettyHttpApiServer(0, null, new HealthService(),
                new PrometheusExporter(new MetricsRegistry()), store(), committing(), null, null, acl(),
                StrongReadPolicy.defaultPolicy(), (scope, key) -> NodeId.of(1), null, null, null, chain);
        s.start();
        return new Server() {
            @Override public int port() { return s.port(); }
            @Override public void stop() { s.stop(); }
        };
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static int status(HttpClient http, int port, String method, String path, String auth, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void jdkAndNettyEnforceTheChainIdentically() throws Exception {
        Server jdk = jdk(basicChain());
        Server netty = netty(basicChain());
        HttpClient http = HttpClient.newHttpClient();
        try {
            // {method, path, auth-header, body, expected-status}
            Object[][] cases = {
                    {"GET", "/v1/config/app.feature", null, null, 401},                       // no credential
                    {"GET", "/v1/config/app.feature", basic("alice", "WRONG"), null, 401},     // bad password
                    {"GET", "/v1/config/app.feature", basic("alice", "wonderland"), null, 200},// reader OK
                    {"PUT", "/v1/config/app.x", basic("alice", "wonderland"), "v", 403},        // reader lacks WRITE
                    {"PUT", "/v1/config/app.x", basic("bob", "builder"), "v", 200},             // writer OK
            };
            for (Object[] c : cases) {
                String method = (String) c[0], path = (String) c[1], auth = (String) c[2], body = (String) c[3];
                int expected = (int) c[4];
                int jdkStatus = status(http, jdk.port(), method, path, auth, body);
                int nettyStatus = status(http, netty.port(), method, path, auth, body);
                assertEquals(jdkStatus, nettyStatus,
                        "JDK and Netty must agree for " + method + " " + path + " auth=" + (auth == null ? "none" : "set"));
                assertEquals(expected, jdkStatus,
                        "unexpected status for " + method + " " + path + " auth=" + (auth == null ? "none" : "set"));
            }
        } finally {
            jdk.stop();
            netty.stop();
        }
    }
}
