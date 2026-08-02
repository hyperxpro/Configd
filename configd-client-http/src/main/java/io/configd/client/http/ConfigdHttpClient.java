package io.configd.client.http;

import io.configd.client.CredentialSource;
import io.configd.client.PathGrammar;
import io.configd.client.ProtocolViolationException;
import io.configd.client.RetryPolicy;
import io.configd.client.tls.ClientTls;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP control-plane client: get/put/delete, transferLeadership (ADMIN-gated).
 * Routes through LeaderRouter (leader-follow + backoff-retry + indeterminate-write handling).
 * Async (CompletableFuture); blocking facade for reference driver. 404 = empty GetResult, never exception.
 */
public final class ConfigdHttpClient implements AutoCloseable {

    private static final Pattern COMMITTED = Pattern.compile("^Committed: seq=(\\d+)\\s*$");

    private final LeaderRouter router;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    private ConfigdHttpClient(LeaderRouter router, ExecutorService executor, boolean ownsExecutor) {
        this.router = router;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public CompletableFuture<GetResult> get(String key, GetOptions options) {
        return CompletableFuture.supplyAsync(() -> doGet(key, options), executor);
    }

    public CompletableFuture<WriteOutcome> put(String key, byte[] value, WriteOptions options) {
        byte[] copy = value.clone();
        return CompletableFuture.supplyAsync(() -> doWrite("PUT", key, copy, options), executor);
    }

    public CompletableFuture<WriteOutcome> delete(String key, WriteOptions options) {
        return CompletableFuture.supplyAsync(() -> doWrite("DELETE", key, null, options), executor);
    }

    /**
     * Requests leadership transfer (ADMIN-gated). Completes when transfer is initiated (200, asynchronous;
     * confirm via follow-up leader read), or fails (409/400/503 as UnavailableException, 403 without ADMIN).
     */
    public CompletableFuture<Void> transferLeadership(int groupId, int targetNodeId) {
        return CompletableFuture.supplyAsync(() -> {
            String path = "/v1/admin/groups/" + groupId + "/transfer-leadership?target=" + targetNodeId;
            router.execute(new LeaderRouter.Request("POST", path, null, true, false));
            return null;
        }, executor);
    }

    public Blocking blocking() {
        return new Blocking();
    }

    public final class Blocking {
        public GetResult get(String key, GetOptions options) {
            return doGet(key, options);
        }

        public WriteOutcome put(String key, byte[] value, WriteOptions options) {
            return doWrite("PUT", key, value.clone(), options);
        }

        public WriteOutcome delete(String key, WriteOptions options) {
            return doWrite("DELETE", key, null, options);
        }

        public void transferLeadership(int groupId, int targetNodeId) {
            String path = "/v1/admin/groups/" + groupId + "/transfer-leadership?target=" + targetNodeId;
            router.execute(new LeaderRouter.Request("POST", path, null, true, false));
        }
    }

    private GetResult doGet(String key, GetOptions options) {
        PathGrammar.validateCanonical(key);
        String path = "/v1/config/" + encodeKeyPath(key) + readQuery(options);
        HttpResponse<byte[]> resp = router.execute(new LeaderRouter.Request("GET", path, null, false, false));
        if (resp.statusCode() == 404) {
            return GetResult.absent(options.consistency());
        }
        long version = resp.headers().firstValue("X-Config-Version")
                .map(ConfigdHttpClient::parseVersionHeader).orElse(-1L);
        boolean strongRead = resp.headers().firstValue("X-Strong-Read").map("true"::equals).orElse(false);
        return GetResult.present(resp.body(), version, strongRead, options.consistency());
    }

    private WriteOutcome doWrite(String method, String key, byte[] body, WriteOptions options) {
        PathGrammar.validateCanonical(key);
        String path = "/v1/config/" + encodeKeyPath(key) + scopeQuery(options.scope());
        HttpResponse<byte[]> resp = router.execute(new LeaderRouter.Request(method, path, body, true, true));
        String text = new String(resp.body(), StandardCharsets.UTF_8);
        Matcher m = COMMITTED.matcher(text.trim());
        if (!m.matches()) {
            throw new ProtocolViolationException(
                    "write returned 200 but the body was not 'Committed: seq=<N>' (§04 D4-2)");
        }
        try {
            return new WriteOutcome(Long.parseLong(m.group(1)));
        } catch (NumberFormatException overflow) {
            throw new ProtocolViolationException(
                    "write returned 200 but the committed seq '" + m.group(1) + "' is not a valid long (§04 D4-2)");
        }
    }

    private static long parseVersionHeader(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ProtocolViolationException(
                    "server returned a non-numeric X-Config-Version: '" + raw + "' (§04)");
        }
    }

    /** The read query: scope (omitted when GLOBAL) plus the exact {@code consistency=linearizable} literal. */
    private static String readQuery(GetOptions options) {
        StringBuilder q = new StringBuilder();
        if (options.scope() != Scope.GLOBAL) {
            q.append("scope=").append(options.scope().name());
        }
        if (options.consistency() == Consistency.LINEARIZABLE) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append("consistency=linearizable"); // exactly this literal, nowhere else -- the loose-substring trap
        }
        return q.length() == 0 ? "" : "?" + q;
    }

    private static String scopeQuery(Scope scope) {
        return scope == Scope.GLOBAL ? "" : "?scope=" + scope.name();
    }

    /**
     * Percent-encodes a config key for the URI path, preserving {@code '/'} (a key's slashes are literal path
     * segments -- the server takes the whole remainder after {@code /v1/config/} as the key, URL-decoded).
     * Unreserved characters (RFC 3986) pass through; everything else is UTF-8 %XX-encoded.
     */
    private static String encodeKeyPath(String key) {
        StringBuilder out = new StringBuilder(key.length() + 8);
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    /** Builds a {@link ConfigdHttpClient} (endpoints required; TLS required for {@code https} unless plaintext). */
    public static final class Builder {
        private NodeEndpoints endpoints;
        private CredentialSource credentialSource;
        private ClientTls tls;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private boolean replayGuard;
        private boolean allowPlaintext;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private ExecutorService executor;

        private Builder() {
        }

        public Builder endpoints(NodeEndpoints endpoints) {
            this.endpoints = endpoints;
            return this;
        }

        public Builder credentialSource(CredentialSource credentialSource) {
            this.credentialSource = credentialSource;
            return this;
        }

        public Builder tls(ClientTls tls) {
            this.tls = tls;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy);
            return this;
        }

        /** Enable the optional replay guard: stamp each mutation with a fresh timestamp and nonce. */
        public Builder replayGuard(boolean enabled) {
            this.replayGuard = enabled;
            return this;
        }

        public Builder allowPlaintext(boolean allowPlaintext) {
            this.allowPlaintext = allowPlaintext;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout);
            return this;
        }

        /** Use a caller-supplied executor for the async surface (not owned -- not shut down on close). */
        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public ConfigdHttpClient build() {
            Objects.requireNonNull(endpoints, "endpoints are required");
            boolean anyHttps = endpoints.entries().stream().anyMatch(u -> "https".equalsIgnoreCase(u.getScheme()));
            boolean anyPlaintext = endpoints.entries().stream().anyMatch(u -> "http".equalsIgnoreCase(u.getScheme()));
            if (anyHttps && tls == null) {
                throw new IllegalStateException("an https endpoint requires tls(ClientTls) (server verification)");
            }
            if (anyPlaintext && !allowPlaintext) {
                throw new IllegalStateException("plaintext http endpoints require allowPlaintext(true) (test-only)");
            }
            HttpClient.Builder hb = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1) // the server is com.sun.net.httpserver (HTTP/1.1)
                    .connectTimeout(connectTimeout);
            if (tls != null) {
                hb.sslContext(tls.sslContext());
                hb.sslParameters(tls.httpsParameters());
            }
            boolean ownsExecutor = executor == null;
            ExecutorService exec = ownsExecutor
                    ? Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "configd-http-client");
                        t.setDaemon(true);
                        return t;
                    })
                    : executor;
            LeaderRouter router = new LeaderRouter(hb.build(), endpoints, credentialSource, retryPolicy,
                    replayGuard ? new ReplayGuardSigner() : null, requestTimeout);
            return new ConfigdHttpClient(router, exec, ownsExecutor);
        }
    }

    public static ConfigdHttpClient open(URI endpoint, CredentialSource credentialSource, ClientTls tls) {
        return builder().endpoints(NodeEndpoints.of(endpoint)).credentialSource(credentialSource).tls(tls).build();
    }
}
