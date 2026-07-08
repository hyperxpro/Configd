package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

/**
 * Builds a named {@link Authenticator} from configuration - the {@link java.util.ServiceLoader} discovery
 * discriminator for OPTIONAL authenticator modules (OIDC, LDAP, cloud-IAM). An optional module ships a
 * {@code META-INF/services/io.configd.common.auth.AuthenticatorFactory} entry; naming its {@link #type()}
 * in the provider chain then registers it without the core compile-depending on the module - the same
 * discovery idiom the KMS provider SPI uses.
 *
 * <p>The in-core defaults ({@code none}, {@code bearer}, {@code basic}, {@code mtls}) are registered
 * DIRECTLY by {@link AuthenticatorChain} (always available, zero dependencies) and are NOT ServiceLoader
 * entries - exactly as the KMS {@code local} provider is built in.
 */
public interface AuthenticatorFactory {

    /** The provider name this factory builds - matched against the configured provider chain. */
    String type();

    /**
     * Builds the authenticator from {@code cfg} (its per-provider keys live under
     * {@code configd.auth.<type>.*}). A malformed or missing required setting must fail closed - throw so
     * the boot refuses to start rather than silently building a weaker authenticator.
     */
    Authenticator create(ConfigSource cfg);
}
