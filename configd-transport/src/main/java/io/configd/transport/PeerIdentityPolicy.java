package io.configd.transport;

import io.configd.common.NodeId;
import io.configd.common.config.ConfigSource;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Binds a peer's authenticated TLS certificate identity to its consensus {@link NodeId}.
 * The 4-byte {@code senderId} prefix and the in-body {@code leaderId}/{@code candidateId}
 * are attacker-influenceable wire bytes; without this policy a cert-valid-but-Byzantine cluster member
 * can impersonate another member's id. Grounded in etcd ({@code --peer-cert-allowed-cn}),
 * CockroachDB ({@code CN=node}), and ZooKeeper quorum-cert verification, which all cross-check the
 * peer's certificate identity rather than trusting a self-declared wire id.
 *
 * <h2>Model</h2>
 * A peer's certificate carries a per-node identity in either a configurable <b>marker RDN</b> of its
 * Subject DN (default {@code CN}) or a <b>SAN URI</b> (e.g. a SPIFFE id). {@link #resolve(String)}
 * (RDN mode) / {@link #resolveFromSanUris(X509Certificate)} (SAN-URI mode) extract that value and map it
 * to the {@link NodeId} the node is authorized to present. A certificate whose marker is absent or not in
 * the configured {@link #allowedNodes} set resolves to {@code null} = not an authorized peer. The
 * per-node allow-list is stronger than a single reserved marker: it pins <b>which</b> node, not merely
 * "a node".
 *
 * <h2>Enforce-when-configured, warn-when-not (etcd semantics)</h2>
 * When {@link #allowedNodes} is non-empty the policy is {@linkplain #enforced() enforced}: a
 * connection whose cert identity is unauthorized is rejected, and every frame's {@code senderId} /
 * in-body id must equal the connection's resolved {@link NodeId}. When it is empty (the default, and
 * the existing shared-cert test fleet) the policy is <b>unenforced</b>: the transport keeps its prior
 * CA-chain-only behavior (a valid, trusted client cert is admitted) but emits a loud one-time warning
 * that peer-identity verification is unconfigured. This builds the capability now without changing the
 * bytes of, or breaking, an existing single-shared-cert deployment.
 *
 * <h2>Fail-closed under authentication</h2>
 * The unenforced/warn posture is safe only for an explicitly auth-disabled deployment. When
 * authentication is enabled and the Raft interior uses TLS, an empty allow-list is a boot error
 * ({@link #requireEnforcedUnderAuth(boolean, boolean)}): a CA-valid client certificate could otherwise
 * forge a peer's {@code senderId} and join consensus.
 *
 * <p>Immutable and thread-safe: {@link #allowedNodes} is an unmodifiable copy taken at construction.
 */
public final class PeerIdentityPolicy {

    public enum MarkerMode {
        RDN,
        SAN_URI
    }

    public static final String MARKER_PROP = "configd.raft.peerIdentity.marker";
    public static final String MARKER_TYPE_PROP = "configd.raft.peerIdentity.markerType";
    public static final String ALLOWED_NODES_PROP = "configd.raft.peerIdentity.allowedNodes";

    /**
     * Config property (read by the server's TLS wiring, not by this policy): the path to a <b>separate</b>
     * PKCS12 trust store for the Raft interior (etcd {@code --peer-trusted-ca-file} / ZooKeeper
     * {@code ssl.quorum.trustStore}). When set, a client certificate that does not chain to this peer CA
     * cannot complete the peer handshake - structurally stronger than a marker match on a shared CA. When
     * unset the shared client/edge trust store is used.
     */
    public static final String TRUST_STORE_PROP = "configd.raft.peerIdentity.trustStore";

    public static final String TRUST_STORE_PASSWORD_PROP = "configd.raft.peerIdentity.trustStorePassword";

    private static final String DEFAULT_MARKER = "CN";
    private static final String MARKER_TYPE_RDN = "rdn";
    private static final String MARKER_TYPE_SAN_URI = "san-uri";
    private static final int SAN_URI_TYPE = 6;

    private final MarkerMode markerMode;
    private final String nodeIdentityMarker;
    private final Map<String, NodeId> allowedNodes;

    private PeerIdentityPolicy(MarkerMode markerMode, String nodeIdentityMarker, Map<String, NodeId> allowedNodes) {
        this.markerMode = Objects.requireNonNull(markerMode, "markerMode");
        this.nodeIdentityMarker = Objects.requireNonNull(nodeIdentityMarker, "nodeIdentityMarker");
        this.allowedNodes = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(allowedNodes, "allowedNodes")));
    }

    public static PeerIdentityPolicy unenforced() {
        return new PeerIdentityPolicy(MarkerMode.RDN, DEFAULT_MARKER, Map.of());
    }

    public static PeerIdentityPolicy of(String nodeIdentityMarker, Map<String, NodeId> allowedNodes) {
        return new PeerIdentityPolicy(MarkerMode.RDN, nodeIdentityMarker, allowedNodes);
    }

    public static PeerIdentityPolicy ofSanUri(Map<String, NodeId> allowedNodes) {
        return new PeerIdentityPolicy(MarkerMode.SAN_URI, DEFAULT_MARKER, allowedNodes);
    }

    public static PeerIdentityPolicy fromConfig(ConfigSource cfg) {
        Objects.requireNonNull(cfg, "cfg");
        MarkerMode mode = resolveMarkerMode(cfg);
        String marker = cfg.getString(MARKER_PROP)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(DEFAULT_MARKER);
        String spec = cfg.getString(ALLOWED_NODES_PROP).map(String::trim).orElse("");
        if (spec.isEmpty()) {
            return new PeerIdentityPolicy(mode, marker, Map.of());
        }
        Map<String, NodeId> allowed = parseAllowList(spec);
        return new PeerIdentityPolicy(mode, marker, allowed);
    }

    public static PeerIdentityPolicy fromSystemProperties() {
        return fromConfig(ConfigSource.system());
    }

    private static MarkerMode resolveMarkerMode(ConfigSource cfg) {
        String type = cfg.getString(MARKER_TYPE_PROP)
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .orElse(MARKER_TYPE_RDN);
        return switch (type) {
            case MARKER_TYPE_RDN -> MarkerMode.RDN;
            case MARKER_TYPE_SAN_URI -> MarkerMode.SAN_URI;
            default -> throw new IllegalArgumentException(
                    "Unknown " + MARKER_TYPE_PROP + " '" + type + "'; expected '"
                            + MARKER_TYPE_RDN + "' or '" + MARKER_TYPE_SAN_URI + "'");
        };
    }

    /**
     * Parses a non-blank {@code identity=nodeId,...} spec into an ordered allow-list. Uses
     * {@code lastIndexOf('=')} so a SAN-URI identity (which contains no '=' but may contain '/', ':') maps
     * cleanly. Fails closed on a malformed pair, a duplicate identity, or a spec that declares no entries.
     */
    private static Map<String, NodeId> parseAllowList(String spec) {
        Map<String, NodeId> allowed = new LinkedHashMap<>();
        for (String pair : spec.split(",")) {
            String entry = pair.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.lastIndexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Malformed " + ALLOWED_NODES_PROP + " entry '" + entry
                                + "'; expected identity=nodeId");
            }
            String identity = entry.substring(0, eq).trim();
            String idText = entry.substring(eq + 1).trim();
            int nodeId;
            try {
                nodeId = Integer.parseInt(idText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Malformed node id in " + ALLOWED_NODES_PROP + " entry '" + entry + "'", e);
            }
            if (allowed.put(identity, NodeId.of(nodeId)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate peer identity in " + ALLOWED_NODES_PROP + ": '" + identity + "'");
            }
        }
        // Fail closed: a NON-blank spec that yields zero entries (e.g. separator-only "," / ",,") is a
        // misconfiguration, not "unenforced". Only a blank/unset spec (handled by the caller) is unenforced.
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Non-blank " + ALLOWED_NODES_PROP + " '" + spec + "' declares no peer identities");
        }
        return allowed;
    }

    /**
     * Fail-closed default: an authenticated cluster with TLS on the Raft interior MUST enumerate
     * its peers. When {@code authEnabled} and {@code tlsEnabled} but this policy is not {@link #enforced()},
     * refuse to boot - a CA-valid client certificate could otherwise forge a peer's {@code senderId} and
     * join consensus (the exact residual the unenforced warning names). An auth-disabled or plaintext-interior
     * deployment keeps the legacy loud-warning open gate: this returns without throwing.
     *
     * @param authEnabled whether authentication is enabled (the {@code configd.auth.*} chain, or a legacy token)
     * @param tlsEnabled  whether the Raft interior transport uses TLS
     * @throws IllegalStateException when auth+TLS are on but no peer allow-list is configured
     */
    public void requireEnforcedUnderAuth(boolean authEnabled, boolean tlsEnabled) {
        if (authEnabled && tlsEnabled && !enforced()) {
            throw new IllegalStateException(
                    "Authentication is enabled and the Raft interior uses TLS, but no peer allow-list is "
                            + "configured (" + ALLOWED_NODES_PROP + " is empty). An authenticated cluster MUST "
                            + "enumerate its peers: otherwise any client certificate your CA trusts could forge "
                            + "a peer's senderId and join consensus. Set " + ALLOWED_NODES_PROP
                            + " (e.g. node-1=1,node-2=2,node-3=3), or disable authentication for a dev cluster.");
        }
    }

    public boolean enforced() {
        return !allowedNodes.isEmpty();
    }

    public MarkerMode markerMode() {
        return markerMode;
    }

    public boolean usesSanUriMarker() {
        return markerMode == MarkerMode.SAN_URI;
    }

    public String nodeIdentityMarker() {
        return nodeIdentityMarker;
    }

    public NodeId resolve(String subjectDn) {
        if (!enforced() || subjectDn == null) {
            return null;
        }
        String marker = extractMarker(subjectDn);
        if (marker == null) {
            return null;
        }
        return allowedNodes.get(marker);
    }

    public NodeId resolveFromSanUris(X509Certificate cert) {
        if (!enforced() || cert == null) {
            return null;
        }
        Collection<List<?>> sans;
        try {
            sans = cert.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return null; // fail closed: an unparseable SAN extension is not an authorized identity
        }
        if (sans == null) {
            return null;
        }
        for (List<?> san : sans) {
            if (san.size() >= 2 && san.get(0) instanceof Integer type && type == SAN_URI_TYPE
                    && san.get(1) instanceof String uri) {
                NodeId id = allowedNodes.get(uri);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the marker RDN value from an RFC 2253 Subject DN. Uses {@link LdapName} so quoting and
     * escaping are handled correctly (a hand-rolled {@code split(",")} would mis-parse
     * {@code CN=a\,b}). Returns {@code null} when the DN is unparseable or has no marker RDN.
     */
    private String extractMarker(String subjectDn) {
        LdapName dn;
        try {
            dn = new LdapName(subjectDn);
        } catch (InvalidNameException e) {
            return null; // fail closed: an unparseable DN is not an authorized identity
        }
        // Iterate most-significant RDN first so a leaf CN is preferred over an (unusual) parent CN.
        for (int i = dn.getRdns().size() - 1; i >= 0; i--) {
            Rdn rdn = dn.getRdns().get(i);
            if (rdn.getType().equalsIgnoreCase(nodeIdentityMarker)) {
                Object value = rdn.getValue();
                return value != null ? value.toString() : null;
            }
        }
        return null;
    }
}
