package io.configd.client;

import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.InboundFrameHandler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The reference-client entry point. It holds the shared {@link ConfigdClientConfig} and one shared scheduler
 * (proactive refresh timers, reconnect backoff) and vends plane clients: {@link #edge()} now; {@code http()}
 * is the Gate-4 seam (the control plane). Closing the facade closes every plane client it vended and the
 * shared scheduler.
 *
 * <p><b>Module placement (a deliberate seam).</b> A facade that vends a plane client must sit in a module that
 * can see it. In the client's core/edge/http split, the only plane in Gate 1–3 is the edge, so {@code Configd}
 * lives in {@code configd-client-edge} (package {@code io.configd.client}). Its import path
 * {@code io.configd.client.Configd} is stable; when Gate 4 adds the HTTP plane, the facade gains
 * {@code http()} from a module that depends on both planes — the import path does not change (no JPMS here, so
 * the split package is harmless).
 */
public final class Configd implements AutoCloseable {

    private final ConfigdClientConfig config;
    private final ScheduledExecutorService scheduler;
    private final List<ConfigdEdgeClient> vended = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Configd(ConfigdClientConfig config) {
        this.config = config;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "configd-client");
            t.setDaemon(true);
            return t;
        });
    }

    /** Opens a facade over an already-built configuration. */
    public static Configd open(ConfigdClientConfig config) {
        return new Configd(config);
    }

    /** A fluent builder that yields a {@link Configd} directly from the shared {@link ConfigdClientConfig}. */
    public static Builder builder() {
        return new Builder();
    }

    /** The edge (watch / fan-out) plane client, sharing this facade's scheduler. */
    public ConfigdEdgeClient edge() {
        return edge(null);
    }

    /** The edge plane client with an inbound-frame handler (the later gates' extension point). */
    public ConfigdEdgeClient edge(InboundFrameHandler handler) {
        ensureOpen();
        ConfigdEdgeClient client = ConfigdEdgeClient.open(config, handler, scheduler);
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
        for (ConfigdEdgeClient client : vended) {
            client.close();
        }
        scheduler.shutdownNow();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Configd is closed");
        }
    }

    /** A thin convenience wrapper over {@link ConfigdClientConfig.Builder} that builds a {@link Configd}. */
    public static final class Builder {
        private final ConfigdClientConfig.Builder delegate = ConfigdClientConfig.builder();

        private Builder() {
        }

        public Builder endpoint(String host, int port) {
            delegate.endpoint(host, port);
            return this;
        }

        public Builder endpoint(ServerAddress address) {
            delegate.endpoint(address);
            return this;
        }

        public Builder tls(io.configd.client.tls.ClientTls tls) {
            delegate.tls(tls);
            return this;
        }

        public Builder credentialSource(CredentialSource credentialSource) {
            delegate.credentialSource(credentialSource);
            return this;
        }

        public Builder limits(HostileServerLimits limits) {
            delegate.limits(limits);
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            delegate.retryPolicy(retryPolicy);
            return this;
        }

        public Builder cursorStore(CursorStore cursorStore) {
            delegate.cursorStore(cursorStore);
            return this;
        }

        public Builder allowPlaintext(boolean allowPlaintext) {
            delegate.allowPlaintext(allowPlaintext);
            return this;
        }

        public Configd build() {
            return Configd.open(delegate.build());
        }
    }
}
