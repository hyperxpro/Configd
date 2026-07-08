package io.configd.authn.oidc;

import io.configd.common.auth.Authenticator;
import io.configd.common.auth.AuthenticatorFactory;
import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@link java.util.ServiceLoader}-discovered factory for the OIDC authenticator. It advertises
 * {@code type() == "oidc"}; naming {@code oidc} in {@code configd.auth.providers} while this module is on the
 * classpath registers the authenticator, and naming it WITHOUT this module is a fail-loud startup error in
 * {@link io.configd.common.auth.AuthenticatorChain} - never a silent downgrade to the static bearer path.
 * The core never compile-depends on this module; nimbus lives here only.
 *
 * <p>One or more issuers are configured under {@code configd.auth.oidc.issuer.<name>.*}; the factory builds a
 * per-issuer validator (which, for a discovery issuer, performs its one-time {@code .well-known} fetch at
 * this point) and returns a single {@link OidcAuthenticator} that dispatches by the token's {@code iss}.
 */
public final class OidcAuthenticatorFactory implements AuthenticatorFactory {

    static final String ISSUER_PREFIX = "configd.auth.oidc.issuer.";

    @Override
    public String type() {
        return "oidc";
    }

    @Override
    public Authenticator create(ConfigSource cfg) {
        Set<String> issuerNames = issuerNames(cfg);
        if (issuerNames.isEmpty()) {
            throw new ConfigException("authentication provider 'oidc' is configured but no issuer is defined; "
                    + "add at least one " + ISSUER_PREFIX + "<name>.uri / .audience");
        }
        Map<String, OidcIssuerValidator> validatorsByIssuer = new LinkedHashMap<>();
        for (String name : issuerNames) {
            OidcIssuerValidator validator = OidcIssuerConfig.parse(cfg, name).buildValidator();
            OidcIssuerValidator previous = validatorsByIssuer.putIfAbsent(validator.issuerUri(), validator);
            if (previous != null) {
                throw new ConfigException("two OIDC issuer blocks pin the same issuer URI '"
                        + validator.issuerUri() + "' - each issuer must be unique");
            }
        }
        return new OidcAuthenticator(validatorsByIssuer);
    }

    /** The distinct issuer block names under {@link #ISSUER_PREFIX} (the segment before the next dot). */
    static Set<String> issuerNames(ConfigSource cfg) {
        Set<String> names = new TreeSet<>();
        for (String key : cfg.keysWithPrefix(ISSUER_PREFIX)) {
            String remainder = key.substring(ISSUER_PREFIX.length());
            int dot = remainder.indexOf('.');
            if (dot > 0) {
                names.add(remainder.substring(0, dot));
            }
        }
        return names;
    }
}
