package io.configd.authn;

/**
 * {@link java.util.ServiceLoader} SPI by which an <em>optional, out-of-tree</em> module (e.g.
 * {@code configd-authn-oidc}) advertises its {@link Authenticator} to the core without the core
 * compile-depending on it (authenticator-spi.md §8). The core lists each factory's {@link #type()} and
 * instantiates the ones named in {@code configd.authn.providers}.
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 *
 * <p>This is the discovery substrate; selection is by explicit name (mirroring {@code NettyTransport.select()}
 * / {@code KmsProviders.select()}). A {@code configd-authn-oidc} jar ships
 * {@code META-INF/services/io.configd.authn.AuthenticatorFactory} naming its factory; the jar's mere presence
 * on the classpath registers {@code oidc}, and the core stays JWT-SDK-free. The {@code mtls} and {@code bearer}
 * built-ins are wired directly (always available, zero deps) and are NOT {@code ServiceLoader} entries.
 */
public interface AuthenticatorFactory {

    /** Stable discriminator, e.g. {@code "oidc"}. Matches {@link Authenticator#type()}. */
    String type();

    /** Instantiates the authenticator from configuration (no credential is passed in). */
    Authenticator create(AuthnConfig config);
}
