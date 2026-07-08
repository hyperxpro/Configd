package io.configd.client;

import io.configd.client.tls.ClientTls;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The immutable, plane-agnostic configuration shared by every reference-client plane: the edge endpoints, the
 * TLS setup, the credential source, and the hostile-server bounds / retry / cursor-store policies. Built via
 * {@link #builder()}; validated at build so a misconfiguration fails fast rather than at first connect.
 *
 * <p><b>TLS is required in production</b> (§06 F9-1). A configuration with no {@link ClientTls} is rejected
 * unless the caller explicitly opts into {@code allowPlaintext} — a test-only escape hatch, never a silent
 * downgrade. The credential source is optional: present ⇒ a token/basic edge (an {@code AUTH} frame); absent
 * with a client certificate ⇒ mTLS; absent without ⇒ no-auth.
 */
public final class ConfigdClientConfig {

    private final List<ServerAddress> endpoints;
    private final ClientTls tls; // nullable only when allowPlaintext
    private final CredentialSource credentialSource; // nullable = mTLS / no-auth
    private final HostileServerLimits limits;
    private final RetryPolicy retryPolicy;
    private final CursorStore cursorStore;
    private final boolean allowPlaintext;

    private ConfigdClientConfig(Builder b) {
        if (b.endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one edge endpoint is required");
        }
        if (b.tls == null && !b.allowPlaintext) {
            throw new IllegalArgumentException(
                    "TLS is required in production (§06 F9-1); to run a test-only plaintext client, call "
                            + "allowPlaintext(true) explicitly");
        }
        this.endpoints = List.copyOf(b.endpoints);
        this.tls = b.tls;
        this.credentialSource = b.credentialSource;
        this.limits = b.limits;
        this.retryPolicy = b.retryPolicy;
        this.cursorStore = b.cursorStore;
        this.allowPlaintext = b.allowPlaintext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ServerAddress> endpoints() {
        return endpoints;
    }

    /** The TLS setup, or empty for a test-only plaintext client. */
    public Optional<ClientTls> tls() {
        return Optional.ofNullable(tls);
    }

    /** The framed-credential source (bearer/basic), or empty on an mTLS / no-auth edge. */
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

    /** True iff this is a test-only plaintext client (no TLS). */
    public boolean allowPlaintext() {
        return allowPlaintext;
    }

    /** Mutable builder for {@link ConfigdClientConfig}. Not thread-safe; build once. */
    public static final class Builder {
        private final java.util.ArrayList<ServerAddress> endpoints = new java.util.ArrayList<>();
        private ClientTls tls;
        private CredentialSource credentialSource;
        private HostileServerLimits limits = HostileServerLimits.defaults();
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private CursorStore cursorStore = new InMemoryCursorStore();
        private boolean allowPlaintext;

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

        /** Sets the framed-credential source (token/basic). Omit for mTLS or no-auth. */
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

        /** Opt into a test-only plaintext transport (no TLS). Never use in production (§06 F9-1). */
        public Builder allowPlaintext(boolean allowPlaintext) {
            this.allowPlaintext = allowPlaintext;
            return this;
        }

        public ConfigdClientConfig build() {
            return new ConfigdClientConfig(this);
        }
    }
}
