package io.configd.server;

import io.configd.common.config.ConfigSource;
import io.configd.common.kms.KeyId;
import io.configd.common.kms.KmsBootContext;
import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsProviderFactory;
import io.configd.common.kms.RootKey;
import io.configd.common.kms.WrappedKey;

import java.security.SecureRandom;
import java.util.Map;

/**
 * A ServiceLoader-registered EXTERNAL KMS provider used only to prove the {@code ConfigdServer} external-custody
 * boot path end-to-end without a live backend: it seals the per-node secret reversibly (XOR with a fixed pad -
 * a TEST DOUBLE, not real custody) so a first boot can provision + persist the carrier and a second boot can
 * read + unseal it. Registered under the type {@code test-kms} via
 * {@code META-INF/services/io.configd.common.kms.KmsProviderFactory} on the configd-server TEST classpath; it is
 * never on the production classpath. Selecting {@code configd.raft.encryption.kms.provider=test-kms} routes the
 * boot through {@link KmsSealedRootStore} and {@code SegmentKeyManager}, exactly as {@code vault-transit} would.
 */
public final class InMemoryTestKmsProviderFactory implements KmsProviderFactory {

    static final String TYPE = "test-kms";
    private static final byte[] PAD = "configd-test-kms-seal-pad-000000".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public KmsProvider create(ConfigSource cfg, KmsBootContext ctx) {
        return new InMemoryProvider();
    }

    /** The reversible-seal provider. */
    static final class InMemoryProvider implements KmsProvider {

        private final SecureRandom rng = new SecureRandom();

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public KeyId currentKeyId() {
            return new KeyId(TYPE, "test", 1);
        }

        @Override
        public Provisioned generateRootKey() {
            byte[] secret = new byte[32];
            rng.nextBytes(secret);
            WrappedKey wrapped = new WrappedKey(currentKeyId(), seal(secret), Map.of("scheme", "xor-test-double"));
            RootKey root = new RootKey(secret, currentKeyId());
            java.util.Arrays.fill(secret, (byte) 0);
            return new Provisioned(root, wrapped);
        }

        @Override
        public WrappedKey wrap(RootKey rootKey) {
            byte[] ct = rootKey.withMaterial(InMemoryProvider::seal);
            return new WrappedKey(currentKeyId(), ct, Map.of("scheme", "xor-test-double"));
        }

        @Override
        public RootKey unwrap(WrappedKey wrapped) {
            byte[] secret = seal(wrapped.ciphertext()); // XOR is its own inverse
            try {
                return new RootKey(secret, wrapped.keyId());
            } finally {
                java.util.Arrays.fill(secret, (byte) 0);
            }
        }

        private static byte[] seal(byte[] in) {
            byte[] out = new byte[in.length];
            for (int i = 0; i < in.length; i++) {
                out[i] = (byte) (in[i] ^ PAD[i % PAD.length]);
            }
            return out;
        }
    }
}
