package io.configd.client;

import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.InboundFrameHandler;
import io.configd.client.http.ConfigdHttpClient;
import io.configd.client.http.NodeEndpoints;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The reference-client entry point. It holds the shared {@link ConfigdClientConfig} (credential, TLS, retry —
 * common to both planes) and one shared scheduler, and vends plane clients: {@link #edge()} (the streaming /
 * watch plane) and {@link #http()} (the control plane). Closing the facade closes every plane client it vended
 * and the shared scheduler.
 *
 * <p><b>Module placement.</b> A facade that vends a plane client must sit where it can see it. In the client's
 * core / edge / http split, this facade lives in the thin {@code configd-client} aggregator that depends on
 * <b>both</b> plane modules; each plane keeps its own entry point ({@link ConfigdEdgeClient} /
 * {@link ConfigdHttpClient}) so a pure-edge or pure-HTTP user depends only on that plane's module and never
 * drags the other. The import path {@code io.configd.client.Configd} is stable.
 *
 * <p><b>The two planes address different ports.</b> The edge plane connects to the fan-out streaming endpoints
 * ({@link Builder#endpoint}); the HTTP plane addresses the control-plane api-port endpoints
 * ({@link Builder#httpNodes}). They share the credential, TLS, and retry configuration but are configured with
 * distinct endpoints; {@link #http()} requires {@link Builder#httpNodes} to have been set.
 */
public final class Configd implements AutoCloseable {

    private final ConfigdClientConfig config;      // nullable: edge() requires it (edge endpoints configured)
    private final NodeEndpoints httpNodes;         // nullable: http() requires it
    private final boolean replayGuard;
    // Shared HTTP-plane primitives (credential / TLS / retry / plaintext) — usable even with no edge config.
    private final CredentialSource httpCredential;
    private final io.configd.client.tls.ClientTls httpTls;
    private final RetryPolicy httpRetry;
    private final boolean httpAllowPlaintext;
    private final ScheduledExecutorService scheduler;
    private final List<AutoCloseable> vended = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Configd(ConfigdClientConfig config, NodeEndpoints httpNodes, boolean replayGuard,
                    CredentialSource httpCredential, io.configd.client.tls.ClientTls httpTls,
                    RetryPolicy httpRetry, boolean httpAllowPlaintext) {
        this.config = config;
        this.httpNodes = httpNodes;
        this.replayGuard = replayGuard;
        this.httpCredential = httpCredential;
        this.httpTls = httpTls;
        this.httpRetry = httpRetry;
        this.httpAllowPlaintext = httpAllowPlaintext;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "configd-client");
            t.setDaemon(true);
            return t;
        });
    }

    /** Opens a facade over an already-built configuration (edge plane; HTTP requires {@code httpNodes} via the builder). */
    public static Configd open(ConfigdClientConfig config) {
        return new Configd(config, null, false, config.credentialSource().orElse(null),
                config.tls().orElse(null), config.retryPolicy(), config.allowPlaintext());
    }

    /** A fluent builder that yields a {@link Configd} directly. */
    public static Builder builder() {
        return new Builder();
    }

    /** The edge (watch / fan-out) plane client, sharing this facade's scheduler. Requires edge endpoints. */
    public ConfigdEdgeClient edge() {
        return edge(null);
    }

    /** The edge plane client with an inbound-frame handler (the gates' extension point). */
    public ConfigdEdgeClient edge(InboundFrameHandler handler) {
        ensureOpen();
        if (config == null) {
            throw new IllegalStateException("no edge endpoints configured: set Configd.builder().endpoint(...)");
        }
        ConfigdEdgeClient client = ConfigdEdgeClient.open(config, handler, scheduler);
        vended.add(client);
        return client;
    }

    /**
     * The HTTP (control) plane client, sharing this facade's credential / TLS / retry configuration. Requires
     * {@link Builder#httpNodes} to have been configured (the api-port endpoints, distinct from the edge
     * endpoints).
     */
    public ConfigdHttpClient http() {
        ensureOpen();
        if (httpNodes == null) {
            throw new IllegalStateException("no HTTP endpoints configured: set Configd.builder().httpNodes(...)");
        }
        ConfigdHttpClient client = ConfigdHttpClient.builder()
                .endpoints(httpNodes)
                .credentialSource(httpCredential)
                .tls(httpTls)
                .retryPolicy(httpRetry)
                .replayGuard(replayGuard)
                .allowPlaintext(httpAllowPlaintext)
                .build();
        vended.add(client);
        return client;
    }

    /** The shared configuration. */
    public ConfigdClientConfig config() {
        return config;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (AutoCloseable client : vended) {
            try {
                client.close();
            } catch (Exception ignored) {
                // best-effort close of every vended plane client
            }
        }
        scheduler.shutdownNow();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Configd is closed");
        }
    }

    /**
     * A convenience builder over {@link ConfigdClientConfig.Builder} plus the HTTP-plane endpoints. Configure
     * whichever planes you use: {@link #endpoint} for the edge plane, {@link #httpNodes} for the HTTP plane
     * (at least one; a pure-single-plane user may instead use {@code ConfigdEdgeClient}/{@code ConfigdHttpClient}
     * directly). The credential / TLS / retry / plaintext settings are shared by both planes.
     */
    public static final class Builder {
        private final ConfigdClientConfig.Builder delegate = ConfigdClientConfig.builder();
        private int edgeEndpoints;
        private NodeEndpoints httpNodes;
        private boolean replayGuard;
        // Shared primitives mirrored locally so http() works even when no edge endpoints are configured.
        private CredentialSource credentialSource;
        private io.configd.client.tls.ClientTls tls;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private boolean allowPlaintext;

        private Builder() {
        }

        /** An edge (streaming) endpoint. */
        public Builder endpoint(String host, int port) {
            delegate.endpoint(host, port);
            edgeEndpoints++;
            return this;
        }

        public Builder endpoint(ServerAddress address) {
            delegate.endpoint(address);
            edgeEndpoints++;
            return this;
        }

        /** The HTTP-plane (control) api-port endpoints, enabling {@link Configd#http()}. */
        public Builder httpNodes(NodeEndpoints httpNodes) {
            this.httpNodes = httpNodes;
            return this;
        }

        /** Enable the optional replay guard on the HTTP plane's mutations. */
        public Builder replayGuard(boolean enabled) {
            this.replayGuard = enabled;
            return this;
        }

        public Builder tls(io.configd.client.tls.ClientTls tls) {
            this.tls = tls;
            delegate.tls(tls);
            return this;
        }

        public Builder credentialSource(CredentialSource credentialSource) {
            this.credentialSource = credentialSource;
            delegate.credentialSource(credentialSource);
            return this;
        }

        public Builder limits(HostileServerLimits limits) {
            delegate.limits(limits);
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            delegate.retryPolicy(retryPolicy);
            return this;
        }

        public Builder cursorStore(CursorStore cursorStore) {
            delegate.cursorStore(cursorStore);
            return this;
        }

        public Builder epochStore(EpochStore epochStore) {
            delegate.epochStore(epochStore);
            return this;
        }

        public Builder dataDir(java.nio.file.Path dataDir) {
            delegate.dataDir(dataDir);
            return this;
        }

        public Builder verifyWith(java.security.PublicKey leaderPublicKey) {
            delegate.verifyWith(leaderPublicKey);
            return this;
        }

        public Builder trustUnverified() {
            delegate.trustUnverified();
            return this;
        }

        public Builder allowPlaintext(boolean allowPlaintext) {
            this.allowPlaintext = allowPlaintext;
            delegate.allowPlaintext(allowPlaintext);
            return this;
        }

        public Configd build() {
            if (edgeEndpoints == 0 && httpNodes == null) {
                throw new IllegalStateException("configure at least one plane: endpoint(...) or httpNodes(...)");
            }
            // Build the edge config only when edge endpoints are present (ConfigdClientConfig requires them).
            ConfigdClientConfig edgeConfig = edgeEndpoints > 0 ? delegate.build() : null;
            return new Configd(edgeConfig, httpNodes, replayGuard, credentialSource, tls, retryPolicy, allowPlaintext);
        }
    }
}
