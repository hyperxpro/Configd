package io.configd.client;

import io.configd.client.tls.ClientTls;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The immutable, plane-agnostic configuration shared by every reference-client plane: the edge endpoints, the
 * TLS setup, the credential source, the signed-chain verification mode, and the hostile-server bounds / retry /
 * resume-persistence policies. Built via {@link #builder()}; validated at build so a misconfiguration fails
 * fast rather than at first connect.
 *
 * <p><b>TLS is required in production.</b> A configuration with no {@link ClientTls} is rejected
 * unless the caller explicitly opts into {@code allowPlaintext} — a test-only escape hatch, never a silent
 * downgrade. The credential source is optional: present ⇒ a token/basic edge; absent with a client certificate
 * ⇒ mTLS; absent without ⇒ no-auth.
 *
 * <p><b>Signed-chain verification is a binary the operator chooses explicitly</b> (a subscribe requires it):
 * either {@link Builder#verifyWith(PublicKey)} (VERIFY — the production default: every delta's Ed25519
 * signature, chain continuity, and epoch monotonicity are checked, fail-closed) or
 * {@link Builder#trustUnverified()} (an explicit opt-out for a genuinely unsigned deployment). There is no
 * implicit middle state.
 *
 * <p><b>Resume persistence is coupled</b>: {@link Builder#dataDir(Path)} makes the client <b>persistent</b> —
 * both the resume cursor and the replay-protection epoch high-water are written there durably and
 * crash-atomically ({@link FileCursorStore} / {@link FileEpochStore}); without it the client is ephemeral
 * (in-memory, re-hydrates fresh on restart).
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

    public EpochStore epochStore() {
        return epochStore;
    }

    /** The leader public key when in VERIFY mode; empty in TRUST-UNVERIFIED mode or when unset. */
    public Optional<PublicKey> leaderVerifyKey() {
        return Optional.ofNullable(leaderVerifyKey);
    }

    /** True iff the operator explicitly opted out of signed-chain verification (TRUST-UNVERIFIED). */
    public boolean trustUnverified() {
        return trustUnverified;
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

        /** Overrides the resume cursor store (defaults to durable under {@link #dataDir} or in-memory). */
        public Builder cursorStore(CursorStore cursorStore) {
            this.cursorStore = Objects.requireNonNull(cursorStore, "cursorStore");
            return this;
        }

        /** Overrides the epoch high-water store (defaults to durable under {@link #dataDir} or in-memory). */
        public Builder epochStore(EpochStore epochStore) {
            this.epochStore = Objects.requireNonNull(epochStore, "epochStore");
            return this;
        }

        /**
         * Makes the client persistent: the resume cursor and the epoch high-water are written durably and
         * crash-atomically under {@code dataDir}. Omit for an ephemeral (in-memory) client.
         */
        public Builder dataDir(Path dataDir) {
            this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
            return this;
        }

        /**
         * VERIFY mode: every streamed delta is verified against {@code leaderPublicKey} (Ed25519), with chain
         * continuity and epoch-replay protection, fail-closed. The production default.
         */
        public Builder verifyWith(PublicKey leaderPublicKey) {
            this.leaderVerifyKey = Objects.requireNonNull(leaderPublicKey, "leaderPublicKey");
            return this;
        }

        /**
         * TRUST-UNVERIFIED mode: an explicit opt-out of signed-chain verification, for a genuinely unsigned
         * deployment. Deltas are applied without a cryptographic check. Never combine with
         * {@link #verifyWith(PublicKey)}.
         */
        public Builder trustUnverified() {
            this.trustUnverified = true;
            return this;
        }

        /** Opt into a test-only plaintext transport (no TLS). Never use in production. */
        public Builder allowPlaintext(boolean allowPlaintext) {
            this.allowPlaintext = allowPlaintext;
            return this;
        }

        public ConfigdClientConfig build() {
            return new ConfigdClientConfig(this);
        }
    }
}
