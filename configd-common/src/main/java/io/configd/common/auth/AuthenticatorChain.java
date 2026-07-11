package io.configd.common.auth;

import io.configd.common.config.ConfigSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Resolves a {@link Credential} against an ORDERED, TYPE-DISPATCHED, FAIL-CLOSED set of
 * {@link Authenticator}s. This is the security core: it never "tries each until one says yes" (that is how
 * a forged or unavailable credential slips through a weaker path). The rules:
 *
 * <ul>
 *   <li>{@link Authenticator#canAttempt} is a cheap type filter; an authenticator that does not handle the
 *       credential's class is skipped.</li>
 *   <li>{@link AuthResult.Authenticated} - first acceptance wins, STOP.</li>
 *   <li>{@link AuthResult.Denied} with {@link DenyReason#INVALID_CREDENTIAL} - the credential is owned by
 *       this authenticator and is bad; STOP (401), NEVER fall through to a weaker authenticator.</li>
 *   <li>{@link AuthResult.Denied} with {@link DenyReason#NOT_THIS_AUTHENTICATOR} / {@link
 *       DenyReason#NO_CREDENTIAL} - not this authenticator's; CONTINUE to the next.</li>
 *   <li>{@link AuthResult.Unavailable} - a configured backend is down; STOP, fail closed (503-class),
 *       NEVER downgrade.</li>
 *   <li><b>Any throwable</b> from {@code canAttempt} OR {@code authenticate} - fail closed
 *       ({@link AuthResult.Unavailable}); a buggy or hostile provider can never fault the chain open.</li>
 *   <li>Chain exhausted with nothing accepting - {@link AuthResult.Denied}{@code (NO_CREDENTIAL)} (401),
 *       default-deny.</li>
 * </ul>
 *
 * <p>Ordering is "specific before catch-all": a catch-all authenticator (one that claims a whole credential
 * type and hard-rejects non-matches, like the static {@code bearer}) MUST come last among that type, so an
 * OIDC authenticator (which yields {@code NOT_THIS_AUTHENTICATOR} for a foreign issuer) can run first. The
 * correct order is {@code mtls,oidc,bearer}.
 */
public final class AuthenticatorChain {

    private static final Logger LOG = Logger.getLogger(AuthenticatorChain.class.getName());

    /**
     * Provider names that consume a {@code BearerToken} and would therefore be starved by an EARLIER
     * catch-all {@code bearer} (which hard-rejects any token it does not recognize). If any of these
     * follows {@code bearer} in the configured order the chain fails CLOSED at boot rather than silently
     * disabling them. A future bearer-type provider (a custom JWT authenticator) should be added here.
     */
    private static final Set<String> BEARER_TYPE_PROVIDERS = Set.of("oidc");

    private final List<Authenticator> authenticators;

    /** @param authenticators the resolution order (highest-priority first); must be non-empty. */
    public AuthenticatorChain(List<Authenticator> authenticators) {
        if (authenticators.isEmpty()) {
            throw new IllegalArgumentException("an authenticator chain must have at least one authenticator");
        }
        this.authenticators = List.copyOf(authenticators);
    }

    /** Resolves {@code credential} against the chain, fail-closed. See the class contract. */
    public AuthResult resolve(Credential credential) {
        Objects.requireNonNull(credential, "credential");
        boolean attempted = false;
        for (Authenticator a : authenticators) {
            try {
                if (!a.canAttempt(credential)) {
                    continue;
                }
                attempted = true;
                AuthResult r = a.authenticate(credential);
                if (r == null) {
                    return new AuthResult.Unavailable("authenticator '" + a.type() + "' returned no result");
                }
                if (r instanceof AuthResult.Authenticated) {
                    return r; // first acceptance wins
                }
                if (r instanceof AuthResult.Unavailable) {
                    return r; // fail closed, stop
                }
                AuthResult.Denied denied = (AuthResult.Denied) r; // sealed: only Denied remains
                if (denied.reason() == DenyReason.INVALID_CREDENTIAL) {
                    return r; // owned + bad: hard stop, never fall through
                }
                // NO_CREDENTIAL / NOT_THIS_AUTHENTICATOR: try the next authenticator
            } catch (Throwable t) {
                return new AuthResult.Unavailable(
                        "authenticator '" + a.type() + "' faulted: " + t.getClass().getSimpleName());
            }
        }
        return new AuthResult.Denied(DenyReason.NO_CREDENTIAL,
                attempted ? "no authenticator accepted the credential" : "no or unsupported credential");
    }

    /** The ordered provider types in this chain (for boot logging / audit). */
    public List<String> providerTypes() {
        return authenticators.stream().map(Authenticator::type).collect(Collectors.toUnmodifiableList());
    }

    /**
     * The ordered provider names the operator configured: {@code configd.auth.providers} (a comma list, the
     * chain) if set, else {@code configd.auth.mode} (a single provider) if set, else empty (authentication
     * is not configured through the SPI - the caller decides the off-posture).
     */
    public static List<String> configuredProviders(ConfigSource cfg) {
        List<String> providers = cfg.getList("configd.auth.providers");
        if (!providers.isEmpty()) {
            return providers;
        }
        return cfg.getString("configd.auth.mode")
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(List::of)
                .orElse(List.of());
    }

    /**
     * Builds the chain from configuration, or {@link Optional#empty()} if no provider is configured (no
     * {@code configd.auth.providers} / {@code configd.auth.mode}). Fail-loud: an unknown provider name, or a
     * name whose optional module is absent from the classpath, is a startup error - never a silent downgrade
     * to a weaker chain.
     */
    public static Optional<AuthenticatorChain> fromConfig(ConfigSource cfg) {
        List<String> names = configuredProviders(cfg);
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(build(names, cfg));
    }

    /**
     * Builds a chain for the given ordered provider {@code names}. Built-in providers ({@code none},
     * {@code bearer}, {@code basic}, {@code mtls}) are wired directly; any other name is discovered via
     * {@link ServiceLoader}&lt;{@link AuthenticatorFactory}&gt;. Fail-loud: an unknown name is a
     * startup error, as is a ServiceLoader factory that duplicates or shadows another provider's type.
     */
    public static AuthenticatorChain build(List<String> names, ConfigSource cfg) {
        if (names.isEmpty()) {
            throw new IllegalStateException("no authentication providers configured (empty chain is not allowed)");
        }
        // 'none' (authentication disabled) is an all-or-nothing posture: mixing it with real authenticators
        // is an ambiguous "optional auth" that is not supported. Reject it loudly rather than guess.
        if (names.contains("none") && names.size() > 1) {
            throw new IllegalStateException("authentication provider 'none' (auth disabled) cannot be combined "
                    + "with other providers - it is all-or-nothing; got: " + names);
        }
        Map<String, AuthenticatorFactory> registry = discoverFactories();

        // The static catch-all 'bearer' hard-rejects (INVALID_CREDENTIAL) any BearerToken it does not
        // recognize, so any OTHER bearer-type provider (oidc) placed after it never runs - every one of its
        // JWTs is rejected before it is reached. That silently and totally disables the later provider, so
        // fail CLOSED at boot. A NON-bearer provider after 'bearer' (e.g. mtls, a different credential type)
        // is not shadowed, so it only draws the ordering warning.
        int bearerIdx = names.indexOf("bearer");
        if (bearerIdx >= 0 && bearerIdx != names.size() - 1) {
            List<String> after = names.subList(bearerIdx + 1, names.size()).stream()
                    .map(String::trim).collect(Collectors.toList());
            List<String> shadowed = after.stream()
                    .filter(BEARER_TYPE_PROVIDERS::contains).collect(Collectors.toList());
            if (!shadowed.isEmpty()) {
                throw new IllegalStateException("authentication provider 'bearer' is a catch-all and MUST be "
                        + "LAST in configd.auth.providers: it hard-rejects every token before the bearer-type "
                        + "provider(s) " + shadowed + " placed after it can run, silently disabling them. "
                        + "Reorder so 'bearer' is last (the correct order is e.g. mtls,oidc,bearer).");
            }
            LOG.log(Level.WARNING, "authentication provider ''bearer'' is a catch-all and should be LAST in "
                    + "configd.auth.providers; providers after it ({0}) may never run", after);
        }

        List<Authenticator> chain = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        for (String rawName : names) {
            String name = rawName.trim();
            if (!seen.add(name)) {
                throw new IllegalStateException("duplicate authentication provider in configd.auth.providers: " + name);
            }
            AuthenticatorFactory factory = registry.get(name);
            if (factory == null) {
                throw new IllegalStateException("unknown authentication provider '" + name + "': not a built-in "
                        + "(none/bearer/basic/mtls) and no module on the classpath provides it. Refusing to start "
                        + "rather than silently downgrade to a weaker chain. Known providers: "
                        + new java.util.TreeSet<>(registry.keySet()));
            }
            Authenticator authenticator = factory.create(cfg);
            chain.add(authenticator);
        }
        LOG.log(Level.INFO, "authentication chain: {0}", names.stream().map(String::trim).collect(Collectors.toList()));
        return new AuthenticatorChain(chain);
    }

    private static Map<String, AuthenticatorFactory> discoverFactories() {
        Map<String, AuthenticatorFactory> registry = new HashMap<>();
        for (AuthenticatorFactory f : List.of(
                new NoAuthAuthenticatorFactory(),
                new BearerTokenAuthenticatorFactory(),
                new BasicAuthenticatorFactory(),
                new MtlsAuthenticatorFactory())) {
            registry.put(f.type(), f);
        }
        Set<String> builtin = Set.copyOf(registry.keySet());
        for (AuthenticatorFactory f : ServiceLoader.load(AuthenticatorFactory.class)) {
            String t = f.type();
            if (builtin.contains(t)) {
                throw new IllegalStateException(
                        "a discovered authenticator factory advertises the built-in type '" + t + "' - refusing to "
                                + "let it shadow the built-in provider");
            }
            if (registry.putIfAbsent(t, f) != null) {
                throw new IllegalStateException(
                        "two discovered authenticator factories advertise the same type '" + t + "'");
            }
        }
        return registry;
    }
}
