package io.configd.transport;

import io.configd.common.NodeId;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Binds a peer's authenticated TLS certificate identity to its consensus {@link NodeId}
 * (WH-08/WH-09). The 4-byte {@code senderId} prefix and the in-body {@code leaderId}/{@code candidateId}
 * are attacker-influenceable wire bytes; without this policy a cert-valid-but-Byzantine cluster member
 * can impersonate another member's id. Grounded in etcd ({@code --peer-cert-allowed-cn}),
 * CockroachDB ({@code CN=node}), and ZooKeeper quorum-cert verification, which all cross-check the
 * peer's certificate identity rather than trusting a self-declared wire id.
 *
 * <h2>Model</h2>
 * A peer's certificate carries a per-node identity in a configurable <b>marker</b> RDN of its Subject
 * DN (default {@code CN}). {@link #resolve(String)} extracts that marker value and maps it to the
 * {@link NodeId} the node is authorized to present. A certificate whose marker is absent or not in the
 * configured {@link #allowedNodes} set resolves to {@code null} = not an authorized peer.
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
 * <p>Immutable and thread-safe: {@link #allowedNodes} is an unmodifiable copy taken at construction.
 */
public final class PeerIdentityPolicy {

    /** System property: the Subject-DN RDN type that carries the per-node identity. Default {@code CN}. */
    public static final String MARKER_PROP = "configd.raft.peerIdentity.marker";

    /**
     * System property: the allowed peer identities as {@code identity=nodeId} pairs, comma-separated
     * (e.g. {@code node-1=1,node-2=2,node-3=3}). Empty/unset leaves the policy unenforced (legacy
     * CA-chain-only posture with a one-time warning).
     */
    public static final String ALLOWED_NODES_PROP = "configd.raft.peerIdentity.allowedNodes";

    private static final String DEFAULT_MARKER = "CN";

    private final String nodeIdentityMarker;
    private final Map<String, NodeId> allowedNodes;

    private PeerIdentityPolicy(String nodeIdentityMarker, Map<String, NodeId> allowedNodes) {
        this.nodeIdentityMarker = Objects.requireNonNull(nodeIdentityMarker, "nodeIdentityMarker");
        this.allowedNodes = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(allowedNodes, "allowedNodes")));
    }

    /** The unenforced policy: legacy CA-chain-only admission with a one-time "unconfigured" warning. */
    public static PeerIdentityPolicy unenforced() {
        return new PeerIdentityPolicy(DEFAULT_MARKER, Map.of());
    }

    /**
     * Builds an enforced policy from an explicit identity&rarr;NodeId map (test/programmatic wiring).
     *
     * @param nodeIdentityMarker the Subject-DN RDN type that carries the per-node identity (e.g. {@code CN})
     * @param allowedNodes       the authorized {@code certificate-identity -> NodeId} map; empty = unenforced
     */
    public static PeerIdentityPolicy of(String nodeIdentityMarker, Map<String, NodeId> allowedNodes) {
        return new PeerIdentityPolicy(nodeIdentityMarker, allowedNodes);
    }

    /**
     * Builds the policy from {@value #MARKER_PROP} / {@value #ALLOWED_NODES_PROP}. An unset/blank
     * allowed-nodes property yields {@link #unenforced()}; malformed entries are rejected loudly so a
     * fat-fingered allow-list fails closed at boot rather than silently disabling enforcement.
     *
     * @throws IllegalArgumentException if a pair is not {@code identity=intNodeId} or an id repeats
     */
    public static PeerIdentityPolicy fromSystemProperties() {
        String marker = System.getProperty(MARKER_PROP, DEFAULT_MARKER).trim();
        if (marker.isEmpty()) {
            marker = DEFAULT_MARKER;
        }
        String spec = System.getProperty(ALLOWED_NODES_PROP, "").trim();
        if (spec.isEmpty()) {
            return unenforced();
        }
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
        // misconfiguration, not "unenforced". Only a blank/unset spec (handled above) means unenforced.
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Non-blank " + ALLOWED_NODES_PROP + " '" + spec + "' declares no peer identities");
        }
        return new PeerIdentityPolicy(marker, allowed);
    }

    /** Whether identity binding is enforced (a non-empty allow-list was configured). */
    public boolean enforced() {
        return !allowedNodes.isEmpty();
    }

    /** The Subject-DN RDN type that carries the per-node identity (default {@code CN}). */
    public String nodeIdentityMarker() {
        return nodeIdentityMarker;
    }

    /**
     * Resolves a peer's authorized {@link NodeId} from its certificate Subject DN, or {@code null} if
     * the cert is not an authorized peer (marker absent, unparseable DN, or marker value not in the
     * allow-list). Always {@code null} when {@link #enforced()} is false - callers must gate on
     * {@link #enforced()} first.
     *
     * @param subjectDn the peer certificate Subject principal name (RFC 2253, e.g. {@code CN=node-1,O=configd})
     * @return the authorized NodeId, or {@code null} if the identity is unauthorized/unresolvable
     */
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
