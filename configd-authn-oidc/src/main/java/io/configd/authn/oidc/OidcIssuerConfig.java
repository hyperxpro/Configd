package io.configd.authn.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;

import io.configd.common.config.ConfigException;
import io.configd.common.config.ConfigSource;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The parsed, validated configuration for one OIDC issuer, read from {@code configd.auth.oidc.issuer.<name>.*}
 * (all keys lowercase-dotted so they are environment-reachable). Parsing is fail-closed: a missing required
 * key, a non-{@code https} URL, or a symmetric/{@code none} algorithm in the allowlist throws a
 * {@link ConfigException} so the boot refuses to start rather than build a weaker validator.
 *
 * <p>Keys (under the per-issuer prefix):
 * <pre>
 *   uri                = https issuer identifier, pinned; the token iss must exactly match   (REQUIRED)
 *   audience           = the Configd API identifier the token aud must contain               (REQUIRED)
 *   jwksUri            = https JWKS endpoint; if omitted, resolved by discovery from uri
 *   discovery          = fetch {uri}/.well-known/openid-configuration once at boot (default: jwksUri absent)
 *   algs               = allowed signature algorithms (default RS256,ES256); none/HS* refused
 *   clockSkewSeconds   = exp/nbf leeway (default 60)
 *   requireTypeAtJwt   = require typ=at+jwt (default true; relax for IdPs that stamp typ:JWT)
 *   claimsPath         = dotted claim path to roles/groups/scope (absent => default deny)
 *   rolePrefix         = prefix for pass-through role names (default empty)
 *   roleMap.&lt;ext&gt;       = external claim value -&gt; Configd role (allowlist when any present)
 *   jwks.ttlSeconds / refreshAheadSeconds / rateLimitSeconds / outageToleranceSeconds
 *   jwks.refreshTimeoutMs / connectTimeoutMs / readTimeoutMs / sizeLimitBytes
 * </pre>
 */
final class OidcIssuerConfig {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(OidcIssuerConfig.class.getName());

    private final String issuerUri;
    private final String audience;
    private final URL jwksUri;            // may be null until resolved by discovery
    private final boolean discovery;
    private final Set<JWSAlgorithm> algorithms;
    private final int clockSkewSeconds;
    private final boolean requireTypeAtJwt;
    private final ClaimsRoleMapper roleMapper;
    private final JwksSettings jwks;

    private OidcIssuerConfig(String issuerUri, String audience, URL jwksUri, boolean discovery,
                             Set<JWSAlgorithm> algorithms, int clockSkewSeconds, boolean requireTypeAtJwt,
                             ClaimsRoleMapper roleMapper, JwksSettings jwks) {
        this.issuerUri = issuerUri;
        this.audience = audience;
        this.jwksUri = jwksUri;
        this.discovery = discovery;
        this.algorithms = algorithms;
        this.clockSkewSeconds = clockSkewSeconds;
        this.requireTypeAtJwt = requireTypeAtJwt;
        this.roleMapper = roleMapper;
        this.jwks = jwks;
    }

    String issuerUri() {
        return issuerUri;
    }

    /** Fetch/timeout/size bounds for the JWKS retrieval (SSRF/DoS bounds, RFC 8725 §3.10). */
    record JwksSettings(long ttlMillis, long refreshTimeoutMillis, long refreshAheadMillis,
                        long rateLimitMillis, long outageToleranceMillis, int connectTimeoutMs,
                        int readTimeoutMs, int sizeLimitBytes) {
    }

    /** Parses the configuration block for one issuer {@code name}. */
    static OidcIssuerConfig parse(ConfigSource cfg, String name) {
        String prefix = "configd.auth.oidc.issuer." + name + ".";
        String issuerUri = requireHttpsUrl("uri", cfg.getRequiredString(prefix + "uri")).toString();
        String audience = cfg.getRequiredString(prefix + "audience");

        URL jwksUri = cfg.getString(prefix + "jwksUri")
                .filter(s -> !s.isBlank())
                .map(s -> requireHttpsUrl("jwksUri", s))
                .orElse(null);
        boolean discovery = cfg.getBoolean(prefix + "discovery", jwksUri == null);
        if (jwksUri == null && !discovery) {
            throw new ConfigException("OIDC issuer '" + name + "': set either " + prefix + "jwksUri or "
                    + prefix + "discovery=true (need a JWKS source)");
        }

        Set<JWSAlgorithm> algorithms = parseAlgorithms(cfg.getList(prefix + "algs"), name);
        int clockSkewSeconds = cfg.getInt(prefix + "clockSkewSeconds", 60);
        if (clockSkewSeconds < 0) {
            throw new ConfigException("OIDC issuer '" + name + "': clockSkewSeconds must be >= 0");
        }
        boolean requireTypeAtJwt = cfg.getBoolean(prefix + "requireTypeAtJwt", true);

        String claimsPath = cfg.getString(prefix + "claimsPath").filter(s -> !s.isBlank()).orElse(null);
        String rolePrefix = cfg.getString(prefix + "rolePrefix").orElse("");
        Map<String, String> roleMap = parseRoleMap(cfg, prefix + "roleMap.");
        ClaimsRoleMapper roleMapper = new ClaimsRoleMapper(claimsPath, roleMap, rolePrefix);
        // Privilege foot-gun warning: an EMPTY roleMap is pass-through (the external claim values become
        // Configd role names verbatim). When the mapped claim is the CLIENT-INFLUENCEABLE OAuth scope
        // (scope/scp - the client requests it and, for many IdPs, gets it), pass-through lets a caller
        // name its own roles. Recommend the roleMap allowlist for any untrusted-claim IdP.
        if (roleMap.isEmpty() && claimsPath != null) {
            String leaf = claimsPath.substring(claimsPath.lastIndexOf('.') + 1);
            if (leaf.equals("scope") || leaf.equals("scp")) {
                LOG.warning("OIDC issuer '" + name + "': claimsPath='" + claimsPath + "' maps the "
                        + "client-influenceable OAuth scope to role names with an EMPTY roleMap (pass-through) "
                        + "- a caller can name its own roles. Configure a roleMap.<scope>=<role> allowlist for "
                        + "this issuer, or map a non-client-controlled claim (groups / realm_access.roles).");
            }
        }

        JwksSettings jwks = new JwksSettings(
                seconds(cfg, prefix + "jwks.ttlSeconds", 600),
                millis(cfg, prefix + "jwks.refreshTimeoutMs", 15_000),
                seconds(cfg, prefix + "jwks.refreshAheadSeconds", 60),
                seconds(cfg, prefix + "jwks.rateLimitSeconds", 30),
                seconds(cfg, prefix + "jwks.outageToleranceSeconds", 3_600),
                (int) millis(cfg, prefix + "jwks.connectTimeoutMs", 2_000),
                (int) millis(cfg, prefix + "jwks.readTimeoutMs", 3_000),
                (int) cfg.getLong(prefix + "jwks.sizeLimitBytes", 65_536));

        return new OidcIssuerConfig(issuerUri, audience, jwksUri, discovery, algorithms, clockSkewSeconds,
                requireTypeAtJwt, roleMapper, jwks);
    }

    /** Builds the live per-issuer validator: resolves the JWKS URL (discovery if needed) and wires nimbus. */
    OidcIssuerValidator buildValidator() {
        URL resolvedJwksUri = jwksUri;
        if (resolvedJwksUri == null) {
            resolvedJwksUri = OidcDiscovery.resolveJwksUri(
                    issuerUri, jwks.connectTimeoutMs(), jwks.readTimeoutMs(), jwks.sizeLimitBytes());
        } else if (discovery) {
            // jwksUri explicitly configured but discovery also on: still verify the issuer advertises the
            // same jwks_uri is out of scope; the explicit value wins and boot does not depend on the IdP.
            resolvedJwksUri = jwksUri;
        }
        JWKSource<SecurityContext> jwkSource = buildJwkSource(resolvedJwksUri, jwks);
        return new OidcIssuerValidator(issuerUri, audience, algorithms, requireTypeAtJwt, clockSkewSeconds,
                jwkSource, roleMapper);
    }

    /**
     * The rotation-aware JWKS source: a positive TTL cache, refresh-ahead so no request pays the fetch,
     * rate-limited forced refetch (bounds attacker-driven unknown-kid fetches - the DoS defence), retry, and
     * outage tolerance (serve-stale-if-warm through a transient IdP blip). A token-supplied {@code jku}/
     * {@code x5u} is never consulted: keys come only from this operator-pinned URL.
     */
    static JWKSource<SecurityContext> buildJwkSource(URL jwksUri, JwksSettings s) {
        DefaultResourceRetriever retriever =
                new DefaultResourceRetriever(s.connectTimeoutMs(), s.readTimeoutMs(), s.sizeLimitBytes());
        retriever.setDisconnectsAfterUse(true);
        JWKSourceBuilder<SecurityContext> builder = JWKSourceBuilder.<SecurityContext>create(jwksUri, retriever)
                .cache(s.ttlMillis(), s.refreshTimeoutMillis())
                .rateLimited(s.rateLimitMillis())
                .outageTolerant(s.outageToleranceMillis())
                .retrying(true);
        // Refresh-ahead keeps a request from ever paying the fetch, but nimbus requires
        // refreshAhead + refreshTimeout < ttl. Enable it only when it fits (the common production case with a
        // multi-minute TTL); a deployment with a very short TTL simply refreshes on demand instead of throwing.
        if (s.refreshAheadMillis() + s.refreshTimeoutMillis() < s.ttlMillis()) {
            builder.refreshAheadCache(s.refreshAheadMillis(), true);
        } else {
            builder.refreshAheadCache(false);
        }
        return builder.build();
    }

    private static Set<JWSAlgorithm> parseAlgorithms(List<String> configured, String issuerName) {
        List<String> names = configured.isEmpty() ? List.of("RS256", "ES256") : configured;
        Set<JWSAlgorithm> algorithms = new LinkedHashSet<>();
        for (String name : names) {
            String upper = name.trim().toUpperCase(Locale.ROOT);
            if (upper.equals("NONE") || upper.startsWith("HS")) {
                throw new ConfigException("OIDC issuer '" + issuerName + "': algorithm '" + name
                        + "' is not permitted on a resource server (no `none`, no symmetric HS*); "
                        + "use an asymmetric algorithm such as RS256 or ES256");
            }
            algorithms.add(JWSAlgorithm.parse(upper));
        }
        if (algorithms.isEmpty()) {
            throw new ConfigException("OIDC issuer '" + issuerName + "': the algorithm allowlist is empty");
        }
        return algorithms;
    }

    private static Map<String, String> parseRoleMap(ConfigSource cfg, String mapPrefix) {
        Map<String, String> roleMap = new LinkedHashMap<>();
        for (String key : cfg.keysWithPrefix(mapPrefix)) {
            String external = key.substring(mapPrefix.length());
            cfg.getString(key).filter(v -> !v.isBlank()).ifPresent(role -> roleMap.put(external, role.trim()));
        }
        return roleMap;
    }

    private static long seconds(ConfigSource cfg, String key, long defaultSeconds) {
        long value = cfg.getLong(key, defaultSeconds);
        if (value <= 0) {
            throw new ConfigException("configuration key '" + key + "' must be a positive number of seconds");
        }
        return value * 1_000L;
    }

    private static long millis(ConfigSource cfg, String key, long defaultMillis) {
        long value = cfg.getLong(key, defaultMillis);
        if (value <= 0) {
            throw new ConfigException("configuration key '" + key + "' must be positive");
        }
        return value;
    }

    /** Parses {@code value} as a URL and asserts the {@code https} scheme (RFC 8414/9068). */
    static URL requireHttpsUrl(String what, String value) {
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new ConfigException(what + " is not a valid URL: '" + value + "'", e);
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            throw new ConfigException(what + " must use the https scheme: '" + value + "'");
        }
        try {
            return uri.toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new ConfigException(what + " is not a valid URL: '" + value + "'", e);
        }
    }
}
