package io.configd.authn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Builds the {@link AuthenticatorChain} from {@code configd.authn.providers} — the selection seam, modelled on
 * {@code NettyTransport.select()} / {@code KmsProviders.select()}: an ordered name list chooses the chain, the
 * {@code mtls}/{@code bearer} built-ins are always present, and a named-but-absent provider is a
 * <b>startup error, never a silent downgrade</b> (RA-7).
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 *
 * <p><b>Hybrid discovery.</b> The names come from config (the codebase convention); optional providers are
 * <em>discovered</em> via {@link ServiceLoader} so the core never compile-depends on a provider SDK. The list
 * order IS the resolution order (authenticator-spi.md §5.1).
 */
public final class Authenticators {

    /** Selection key + resolution order, e.g. {@code -Dconfigd.authn.providers=mtls,oidc,bearer}
     *  (specific before the catch-all static {@code bearer} — see authenticator-spi.md §5.1). */
    public static final String PROVIDERS_KEY = "configd.authn.providers";

    /** The built-in default chain: the built N = 2 (built-reality.md §1). */
    public static final String DEFAULT = "mtls,bearer";

    private Authenticators() {
    }

    /**
     * Resolves the ordered chain named by {@link #PROVIDERS_KEY} (default {@link #DEFAULT}).
     *
     * @throws IllegalStateException if a name is neither a built-in ({@code mtls}/{@code bearer}) nor a
     *                               discovered {@link AuthenticatorFactory} (fail-loud; never silently skipped)
     */
    public static AuthenticatorChain chain(AuthnConfig config) {
        String list = config.get(PROVIDERS_KEY, DEFAULT).trim();

        // Discover optional factories once (mtls/bearer are built in and never ServiceLoader entries).
        Map<String, AuthenticatorFactory> discovered = new LinkedHashMap<>();
        for (AuthenticatorFactory f : ServiceLoader.load(AuthenticatorFactory.class)) {
            AuthenticatorFactory prior = discovered.putIfAbsent(f.type(), f);
            if (prior != null) {
                // Fail-loud: two modules advertise the same type() (e.g. a duplicate/stale jar). Refuse to
                // silently shadow one — which one wins would be classpath-order luck (RA-7).
                throw new IllegalStateException("two AuthenticatorFactory modules both advertise type '"
                        + f.type() + "' (" + prior.getClass().getName() + " and " + f.getClass().getName()
                        + "). Remove the duplicate; refusing to silently shadow one.");
            }
        }

        List<Authenticator> built = new ArrayList<>();
        for (String raw : list.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            switch (name) {
                case "mtls" -> built.add(new MtlsAuthenticator());                 // built-in, zero dep
                case "bearer" -> built.add(BearerTokenAuthenticator.fromConfig(config)); // built-in, zero dep
                default -> {
                    AuthenticatorFactory f = discovered.get(name);
                    if (f == null) {
                        throw new IllegalStateException(failLoud(list, name, discovered));
                    }
                    built.add(f.create(config));
                }
            }
        }
        if (built.isEmpty()) {
            throw new IllegalStateException(PROVIDERS_KEY + "='" + list
                    + "' resolved to an empty chain (no authenticators). Configure at least one.");
        }
        return new AuthenticatorChain(built);
    }

    /** Startup-log line: the selected order + the discovered optional providers. */
    public static String availabilityReport(AuthnConfig config) {
        List<String> discovered = new ArrayList<>();
        for (AuthenticatorFactory f : ServiceLoader.load(AuthenticatorFactory.class)) {
            discovered.add(f.type());
        }
        return "authn chain: selected=[" + config.get(PROVIDERS_KEY, DEFAULT) + "], built-in=[mtls, bearer], "
                + "discovered=" + discovered;
    }

    private static String failLoud(String list, String name, Map<String, AuthenticatorFactory> discovered) {
        return PROVIDERS_KEY + "='" + list + "' names authenticator '" + name + "' that is not on the classpath. "
                + "Add the configd-authn-" + name + " module, or remove it from the list. "
                + "Refusing to silently skip it — a silent downgrade is how an 'authentication required' claim "
                + "becomes fiction. Built-in: [mtls, bearer]; discovered: " + discovered.keySet() + ".";
    }
}
