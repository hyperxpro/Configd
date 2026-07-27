package io.configd.kms.vault;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class FakeVaultTransit implements AutoCloseable {

    private final HttpServer server;
    private final String mount;
    private final String token = "fake.client.token";
    private volatile int version = 1;

    FakeVaultTransit(String mount) throws IOException {
        this.mount = mount;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Bumps the fake KEK version (rotate); prior ciphertext still decrypts (version-agnostic container). */
    void rotate() {
        version++;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/v1/sys/health") && method.equals("GET")) {
                send(ex, 200, "{\"initialized\":true,\"sealed\":false}");
                return;
            }
            if (path.equals("/v1/auth/approle/login") && method.equals("POST")) {
                Object body = Json.parse(readBody(ex));
                if (Json.string(body, "role_id") == null || Json.string(body, "secret_id") == null) {
                    send(ex, 400, "{\"errors\":[\"missing role_id/secret_id\"]}");
                    return;
                }
                send(ex, 200, "{\"auth\":{\"client_token\":\"" + token + "\",\"lease_duration\":600}}");
                return;
            }
            // all transit calls require the token
            if (!token.equals(ex.getRequestHeaders().getFirst("X-Vault-Token"))) {
                send(ex, 403, "{\"errors\":[\"permission denied\"]}");
                return;
            }
            if (path.contains("/" + mount + "/encrypt/")) {
                Object body = Json.parse(readBody(ex));
                String pt = Json.string(body, "plaintext");
                String aad = Json.string(body, "associated_data");
                String container = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(((nn(aad)) + "." + nn(pt)).getBytes(StandardCharsets.UTF_8));
                send(ex, 200, "{\"data\":{\"ciphertext\":\"vault:v" + version + ":" + container + "\"}}");
                return;
            }
            if (path.contains("/" + mount + "/decrypt/") || path.contains("/" + mount + "/rewrap/")) {
                Object body = Json.parse(readBody(ex));
                String ct = Json.string(body, "ciphertext");
                String aad = nn(Json.string(body, "associated_data"));
                String container = ct.substring(ct.indexOf(':', "vault:v".length()) + 1);
                String decoded = new String(Base64.getUrlDecoder().decode(container), StandardCharsets.UTF_8);
                int dot = decoded.indexOf('.');
                String sealedAad = decoded.substring(0, dot);
                String pt = decoded.substring(dot + 1);
                if (!sealedAad.equals(aad)) {
                    send(ex, 400, "{\"errors\":[\"ciphertext could not be decrypted: aad mismatch\"]}");
                    return;
                }
                if (path.contains("/rewrap/")) {
                    String re = Base64.getUrlEncoder().withoutPadding()
                            .encodeToString((sealedAad + "." + pt).getBytes(StandardCharsets.UTF_8));
                    send(ex, 200, "{\"data\":{\"ciphertext\":\"vault:v" + version + ":" + re + "\"}}");
                } else {
                    send(ex, 200, "{\"data\":{\"plaintext\":\"" + pt + "\"}}");
                }
                return;
            }
            if (path.endsWith("/rotate")) {
                rotate();
                send(ex, 200, "{}");
                return;
            }
            send(ex, 404, "{\"errors\":[\"no route\"]}");
        } catch (RuntimeException e) {
            send(ex, 500, "{\"errors\":[\"" + e.getClass().getSimpleName() + "\"]}");
        }
    }

    /** The last path segment (the transit key name), for endpoint matching. */
    private static String keyOf(String path) {
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1);
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
