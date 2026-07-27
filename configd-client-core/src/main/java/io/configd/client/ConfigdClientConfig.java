package io.configd.client;

import io.configd.client.tls.ClientTls;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration shared by both client planes. Validated at build. TLS required in production.
 * Verification mode is binary: verifyWith(key) for VERIFY (production default), or trustUnverified() for opt-out.
 * Persistence is coupled to dataDir: present ⇒ durable, absent ⇒ ephemeral (in-memory).
 */
public final class ConfigdClientConfig {

    private final List<ServerAddress> endpoints;
    private final ClientTls tls; // nullable only when allowPlaintext
    private final CredentialSource credentialSource; // nullable = mTLS / no-auth
    private final HostileServerLimits limits;
    private final RetryPolicy retryPolicy;
    private final CursorStore cursorStore;
    private final EpochStore epochStore;
    private final boolean allowPlaintext;
    private final PublicKey leaderVerifyKey; // nullable; present ⇒ VERIFY mode
    private final boolean trustUnverified;   // explicit opt-out ⇒ TRUST-UNVERIFIED mode

    private ConfigdClientConfig(Builder b) {
        if (b.endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one edge endpoint is required");
        }
        if (b.tls == null && !b.allowPlaintext) {
            throw new IllegalArgumentException(
                    "TLS is required in production (§06 F9-1); to run a test-only plaintext client, call "
                            + "allowPlaintext(true) explicitly");
        }
        if (b.leaderVerifyKey != null && b.trustUnverified) {
            throw new IllegalArgumentException(
                    "choose exactly one verification mode: verifyWith(leaderKey) OR trustUnverified()");
        }
        this.endpoints = List.copyOf(b.endpoints);
        this.tls = b.tls;
        this.credentialSource = b.credentialSource;
        this.limits = b.limits;
        this.retryPolicy = b.retryPolicy;
        this.allowPlaintext = b.allowPlaintext;
        this.leaderVerifyKey = b.leaderVerifyKey;
        this.trustUnverified = b.trustUnverified;
        // Resume persistence: an explicit store wins; else a data-dir makes it durable; else in-memory.
        this.cursorStore = b.cursorStore != null ? b.cursorStore
                : (b.dataDir != null ? new FileCursorStore(b.dataDir) : new InMemoryCursorStore());
        this.epochStore = b.epochStore != null ? b.epochStore
                : (b.dataDir != null ? new FileEpochStore(b.dataDir) : new InMemoryEpochStore());
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ServerAddress> endpoints() {
        return endpoints;
    }

    public Optional<ClientTls> tls() {
        return Optional.ofNullable(tls);
    }

    public Optional<CredentialSource> credentialSource() {
        return Optional.ofNullable(credentialSource);
    }

    public HostileServerLimits limits() {
        return limits;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public CursorStore cursorStore() {
        return cursorStore;
    }

    public EpochStore epochStore() {
        return epochStore;
    }

    public Optional<PublicKey> leaderVerifyKey() {
        return Optional.ofNullable(leaderVerifyKey);
    }

    public boolean trustUnverified() {
        return trustUnverified;
    }

    public boolean allowPlaintext() {
        return allowPlaintext;
    }

    public static final class Builder {
        private final java.util.ArrayList<ServerAddress> endpoints = new java.util.ArrayList<>();
        private ClientTls tls;
        private CredentialSource credentialSource;
        private HostileServerLimits limits = HostileServerLimits.defaults();
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private CursorStore cursorStore;
        private EpochStore epochStore;
        private Path dataDir;
        private boolean allowPlaintext;
        private PublicKey leaderVerifyKey;
        private boolean trustUnverified;

        private Builder() {
        }

        public Builder endpoint(String host, int port) {
            this.endpoints.add(new ServerAddress(host, port));
            return this;
        }

        public Builder endpoint(ServerAddress address) {
            this.endpoints.add(Objects.requireNonNull(address, "address"));
            return this;
        }

        public Builder endpoints(List<ServerAddress> addresses) {
            this.endpoints.addAll(Objects.requireNonNull(addresses, "addresses"));
            return this;
        }

        public Builder tls(ClientTls tls) {
            this.tls = tls;
            return this;
        }

        public Builder credentialSource(CredentialSource credentialSource) {
            this.credentialSource = credentialSource;
            return this;
        }

        public Builder limits(HostileServerLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        public Builder cursorStore(CursorStore cursorStore) {
            this.cursorStore = Objects.requireNonNull(cursorStore, "cursorStore");
            return this;
        }

        public Builder epochStore(EpochStore epochStore) {
            this.epochStore = Objects.requireNonNull(epochStore, "epochStore");
            return this;
        }

        public Builder dataDir(Path dataDir) {
            this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
            return this;
        }

        public Builder verifyWith(PublicKey leaderPublicKey) {
            this.leaderVerifyKey = Objects.requireNonNull(leaderPublicKey, "leaderPublicKey");
            return this;
        }

        public Builder trustUnverified() {
            this.trustUnverified = true;
            return this;
        }

        public Builder allowPlaintext(boolean allowPlaintext) {
            this.allowPlaintext = allowPlaintext;
            return this;
        }

        public ConfigdClientConfig build() {
            return new ConfigdClientConfig(this);
        }
    }
}
