package io.configd.common.kms;

import io.configd.common.config.ConfigSource;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Builds a named {@link KmsProvider} from configuration - the {@link ServiceLoader} discovery
 * discriminator for OPTIONAL, EXTERNAL KMS modules (Vault, cloud CMK/HSM). An optional module ships a
 * {@code META-INF/services/io.configd.common.kms.KmsProviderFactory} entry; selecting its {@link #type()}
 * via {@code configd.raft.encryption.kms.provider} then registers it WITHOUT the core compile-depending on
 * the module - the same discovery idiom the authenticator SPI uses
 * ({@link io.configd.common.auth.AuthenticatorFactory}).
 *
 * <h2>Why {@code local} is not a factory</h2>
 * The in-core default {@code local} is NOT discovered here and is NOT a ServiceLoader entry. Its custody
 * secret is the already-loaded cluster signing key, so the boot path derives the keyring-wrapping keys from
 * it inline and byte-identically - the raw signing key never crosses this SPI boundary (secret minimisation).
 * A factory exists only for providers that seal the custody secret under an EXTERNAL key-management backend,
 * which is exactly where the {@code unwrap}-at-boot / fail-closed contract earns its keep. This mirrors how
 * the built-in {@code none/bearer/basic/mtls} authenticators are wired directly, not via ServiceLoader.
 *
 * <h2>Fail-loud discovery</h2>
 * {@link #discover()} refuses to let a discovered factory shadow the built-in {@code local} type or collide
 * with another discovered type - a silent shadow/collision is how a weaker or attacker-supplied custodian
 * could displace the configured one. The boot caller turns an unknown selected name into a startup error
 * (never a silent downgrade to no encryption or a different provider - R3).
 */
public interface KmsProviderFactory {

    /** The provider name this factory builds - matched against {@code configd.raft.encryption.kms.provider}. */
    String type();

    /**
     * Builds the provider from {@code cfg} (its keys live under {@code configd.kms.<type>.*}) and the
     * non-secret boot {@code ctx}. A malformed or missing required setting MUST fail closed - throw so the
     * boot refuses to start rather than silently building a weaker or misconfigured custodian. Construction
     * itself performs no KMS I/O; the first backend call is {@link KmsProvider#healthCheck()} /
     * {@link KmsProvider#unwrap(WrappedKey)} at boot.
     */
    KmsProvider create(ConfigSource cfg, KmsBootContext ctx);

    /**
     * Discovers every optional {@link KmsProviderFactory} on the runtime classpath via {@link ServiceLoader},
     * keyed by {@link #type()}. Fail-loud: a factory advertising the built-in {@code local} type (which would
     * shadow the in-core default), a null/blank type, or a type another discovered factory already claims is a
     * startup error. The returned map never contains {@code local}.
     */
    static Map<String, KmsProviderFactory> discover() {
        return index(ServiceLoader.load(KmsProviderFactory.class));
    }

    /**
     * Indexes discovered factories by {@link #type()} with the fail-loud rules, split out from
     * {@link #discover()} so the shadow/collision guards are unit-testable against hand-built factories
     * (not just whatever {@link ServiceLoader} finds on the classpath).
     */
    static Map<String, KmsProviderFactory> index(Iterable<KmsProviderFactory> factories) {
        Map<String, KmsProviderFactory> registry = new HashMap<>();
        for (KmsProviderFactory f : factories) {
            String t = f.type();
            if (t == null || t.isBlank()) {
                throw new IllegalStateException(
                        "a discovered KMS provider factory advertises a null/blank type: " + f.getClass().getName());
            }
            if ("local".equals(t)) {
                throw new IllegalStateException(
                        "a discovered KMS provider factory advertises the built-in type 'local' - refusing to let "
                                + "it shadow the in-core default: " + f.getClass().getName());
            }
            if (registry.putIfAbsent(t, f) != null) {
                throw new IllegalStateException(
                        "two discovered KMS provider factories advertise the same type '" + t + "'");
            }
        }
        return registry;
    }
}
