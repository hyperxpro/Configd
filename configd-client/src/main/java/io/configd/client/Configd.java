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

    private final ConfigdClientConfig config;
    private final NodeEndpoints httpNodes;
    private final boolean replayGuard;
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

    public static Configd open(ConfigdClientConfig config) {
        return new Configd(config, null, false, config.credentialSource().orElse(null),
                config.tls().orElse(null), config.retryPolicy(), config.allowPlaintext());
    }

    public static Builder builder() {
        return new Builder();
    }

    public ConfigdEdgeClient edge() {
        return edge(null);
    }

    public ConfigdEdgeClient edge(InboundFrameHandler handler) {
        ensureOpen();
        if (config == null) {
            throw new IllegalStateException("no edge endpoints configured: set Configd.builder().endpoint(...)");
        }
        ConfigdEdgeClient client = ConfigdEdgeClient.open(config, handler, scheduler);
        vended.add(client);
        return client;
    }

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
            }
        }
        scheduler.shutdownNow();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Configd is closed");
        }
    }

    public static final class Builder {
        private final ConfigdClientConfig.Builder delegate = ConfigdClientConfig.builder();
        private int edgeEndpoints;
        private NodeEndpoints httpNodes;
        private boolean replayGuard;
        private CredentialSource credentialSource;
        private io.configd.client.tls.ClientTls tls;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private boolean allowPlaintext;

        private Builder() {
        }

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

        public Builder httpNodes(NodeEndpoints httpNodes) {
            this.httpNodes = httpNodes;
            return this;
        }

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
            ConfigdClientConfig edgeConfig = edgeEndpoints > 0 ? delegate.build() : null;
            return new Configd(edgeConfig, httpNodes, replayGuard, credentialSource, tls, retryPolicy, allowPlaintext);
        }
    }
}
