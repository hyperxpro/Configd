package io.configd.kms;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Selects the configured {@link KmsProvider} by name — the discovery seam, modelled on
 * {@code NettyTransport.select()}: a config key chooses a provider, the built-in default
 * is always present, and a forced-but-unavailable choice is a <b>startup error, never a
 * silent downgrade</b>.
 *
 * <p>Design-research artifact (KMS-SPI). NOT production code.
 *
 * <p><b>Hybrid discovery.</b> Selection is by explicit name (the codebase convention —
 * cf. {@code configd.netty.transport}); the optional providers are <em>discovered</em>
 * via {@link ServiceLoader} so the core never compile-depends on a cloud SDK. The
 * built-in {@code local} provider is wired directly (it needs the signing key and must
 * always be available with zero dependencies).
 */
public final class KmsProviders {

    /** Selection key, e.g. {@code -Dconfigd.raft.encryption.kms.provider=aws-kms}. */
    public static final String PROVIDER_KEY = "configd.raft.encryption.kms.provider";

    /** The zero-dependency default: HKDF-from-signing-key (encryption research B-minimal). */
    public static final String DEFAULT = "local";

    private KmsProviders() {
    }

    /**
     * Resolves the provider named by {@link #PROVIDER_KEY} (default {@link #DEFAULT}).
     *
     * @param config         the {@code configd.raft.encryption.kms.*} configuration
     * @param signingKeyIkm  supplies the cluster signing-key encoding (IKM) for the
     *                       built-in {@code local} provider only; never given to
     *                       third-party factories
     * @param localKeyId     the {@link KeyId} the {@code local} provider stamps (derived
     *                       from the signing-key id)
     * @return the selected provider
     * @throws IllegalStateException if a non-{@code local} provider is named but no
     *                               matching {@link KmsProviderFactory} is on the
     *                               classpath (fail-loud; never falls back to {@code local})
     */
    public static KmsProvider select(KmsConfig config,
                                     Supplier<byte[]> signingKeyIkm,
                                     KeyId localKeyId) {
        String name = config.get(PROVIDER_KEY, DEFAULT).trim();

        if (DEFAULT.equals(name)) {
            return new LocalDerivedKmsProvider(signingKeyIkm, localKeyId);
        }

        List<String> discovered = new ArrayList<>();
        for (KmsProviderFactory factory : ServiceLoader.load(KmsProviderFactory.class)) {
            discovered.add(factory.type());
            if (factory.type().equals(name)) {
                return factory.create(config);
            }
        }
        throw new IllegalStateException(
                PROVIDER_KEY + '=' + name + " selects a KMS provider that is not on the classpath. "
                        + "Add the configd-kms-" + name + " module, or pick an available provider. "
                        + "Refusing to silently fall back to '" + DEFAULT + "' — a silent downgrade is "
                        + "how a 'data is KMS-protected' claim becomes fiction. "
                        + "Discovered providers: " + discovered + " (plus built-in '" + DEFAULT + "').");
    }

    /** Startup-log line: the selected name + the discovered optional providers. */
    public static String availabilityReport(KmsConfig config) {
        List<String> discovered = new ArrayList<>();
        for (KmsProviderFactory factory : ServiceLoader.load(KmsProviderFactory.class)) {
            discovered.add(factory.type());
        }
        return "kms provider: selected=" + config.get(PROVIDER_KEY, DEFAULT)
                + ", built-in=[" + DEFAULT + "], discovered=" + discovered;
    }
}
