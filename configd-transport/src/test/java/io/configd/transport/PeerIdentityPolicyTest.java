package io.configd.transport;

import io.configd.common.NodeId;
import io.configd.common.config.ConfigSource;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PeerIdentityPolicy} - the cert-identity &harr; NodeId resolver. Pure
 * (no TLS); the on-the-wire enforcement is proven by the transport red-team tests.
 */
class PeerIdentityPolicyTest {

    @Test
    void unenforcedResolvesNull() {
        PeerIdentityPolicy policy = PeerIdentityPolicy.unenforced();
        assertFalse(policy.enforced());
        assertNull(policy.resolve("CN=node-1,O=configd"),
                "an unenforced policy authorizes nothing (callers gate on enforced() first)");
    }

    @Test
    void resolvesCnMarkerToNodeId() {
        PeerIdentityPolicy policy = PeerIdentityPolicy.of("CN",
                Map.of("node-1", NodeId.of(1), "node-2", NodeId.of(2)));
        assertTrue(policy.enforced());
        assertEquals(NodeId.of(1), policy.resolve("CN=node-1,O=configd-test"));
        assertEquals(NodeId.of(2), policy.resolve("CN=node-2,O=configd-test"));
    }

    @Test
    void unauthorizedIdentityResolvesNull() {
        PeerIdentityPolicy policy = PeerIdentityPolicy.of("CN", Map.of("node-1", NodeId.of(1)));
        assertNull(policy.resolve("CN=client-app,O=configd-test"),
                "a cert whose CN is not in the allow-list is not an authorized peer");
        assertNull(policy.resolve("O=configd-test"),
                "a cert with no CN marker is not an authorized peer");
        assertNull(policy.resolve(null));
    }

    @Test
    void markerMatchIsCaseInsensitiveAndDnOrderIndependent() {
        PeerIdentityPolicy policy = PeerIdentityPolicy.of("CN", Map.of("node-7", NodeId.of(7)));
        // RFC 2253 attribute types are case-insensitive; CN may not be the leading RDN.
        assertEquals(NodeId.of(7), policy.resolve("cn=node-7,O=configd"));
        assertEquals(NodeId.of(7), policy.resolve("O=configd,OU=raft,CN=node-7"));
    }

    @Test
    void malformedDnFailsClosed() {
        PeerIdentityPolicy policy = PeerIdentityPolicy.of("CN", Map.of("node-1", NodeId.of(1)));
        assertNull(policy.resolve("this is not a DN"),
                "an unparseable Subject DN must not authorize any node");
    }

    @Test
    void fromSystemPropertiesParsesAllowList() {
        String prevAllowed = System.getProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP);
        String prevMarker = System.getProperty(PeerIdentityPolicy.MARKER_PROP);
        try {
            System.setProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP, "node-1=1, node-2=2 ,node-3=3");
            PeerIdentityPolicy policy = PeerIdentityPolicy.fromSystemProperties();
            assertTrue(policy.enforced());
            assertEquals(NodeId.of(1), policy.resolve("CN=node-1"));
            assertEquals(NodeId.of(3), policy.resolve("CN=node-3"));
        } finally {
            restore(PeerIdentityPolicy.ALLOWED_NODES_PROP, prevAllowed);
            restore(PeerIdentityPolicy.MARKER_PROP, prevMarker);
        }
    }

    @Test
    void fromSystemPropertiesUnsetIsUnenforced() {
        String prev = System.getProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP);
        try {
            System.clearProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP);
            assertFalse(PeerIdentityPolicy.fromSystemProperties().enforced());
        } finally {
            restore(PeerIdentityPolicy.ALLOWED_NODES_PROP, prev);
        }
    }

    @Test
    void fromSystemPropertiesRejectsSeparatorOnlySpecFailClosed() {
        String prev = System.getProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP);
        try {
            // A non-blank but separator-only spec yields zero entries; it must NOT silently become
            // unenforced (fail-closed contract), it must throw.
            System.setProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP, ",,");
            assertThrows(IllegalArgumentException.class, PeerIdentityPolicy::fromSystemProperties,
                    "a non-blank allow-list that declares no nodes is a misconfig, not 'unenforced'");
        } finally {
            restore(PeerIdentityPolicy.ALLOWED_NODES_PROP, prev);
        }
    }

    @Test
    void fromSystemPropertiesRejectsMalformedEntryFailClosed() {
        String prev = System.getProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP);
        try {
            System.setProperty(PeerIdentityPolicy.ALLOWED_NODES_PROP, "node-1=notAnInt");
            assertThrows(IllegalArgumentException.class, PeerIdentityPolicy::fromSystemProperties,
                    "a fat-fingered allow-list must fail closed at boot, not silently disable enforcement");
        } finally {
            restore(PeerIdentityPolicy.ALLOWED_NODES_PROP, prev);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    // ---- fromConfig(ConfigSource): the config path (parity with fromSystemProperties) ----

    @Test
    void fromConfigParsesAllowListAndMarker() {
        PeerIdentityPolicy policy = PeerIdentityPolicy.fromConfig(config(Map.of(
                PeerIdentityPolicy.ALLOWED_NODES_PROP, "node-1=1, node-2=2 ,node-3=3",
                PeerIdentityPolicy.MARKER_PROP, "CN")));
        assertTrue(policy.enforced());
        assertEquals(PeerIdentityPolicy.MarkerMode.RDN, policy.markerMode());
        assertEquals(NodeId.of(1), policy.resolve("CN=node-1"));
        assertEquals(NodeId.of(3), policy.resolve("CN=node-3"));
    }

    @Test
    void fromConfigUnsetAllowListIsUnenforced() {
        assertFalse(PeerIdentityPolicy.fromConfig(config(Map.of())).enforced(),
                "an unset allow-list keeps the legacy unenforced (CA-chain-only + warning) posture");
    }

    @Test
    void fromConfigSeparatorOnlySpecFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> PeerIdentityPolicy.fromConfig(config(Map.of(PeerIdentityPolicy.ALLOWED_NODES_PROP, ",,"))),
                "a non-blank allow-list that declares no nodes is a misconfig, not 'unenforced'");
    }

    @Test
    void fromConfigMalformedEntryFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> PeerIdentityPolicy.fromConfig(config(
                        Map.of(PeerIdentityPolicy.ALLOWED_NODES_PROP, "node-1=notAnInt"))),
                "a fat-fingered allow-list must fail closed at boot");
    }

    @Test
    void fromConfigUnknownMarkerTypeFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> PeerIdentityPolicy.fromConfig(config(Map.of(
                        PeerIdentityPolicy.ALLOWED_NODES_PROP, "node-1=1",
                        PeerIdentityPolicy.MARKER_TYPE_PROP, "bogus"))),
                "an unknown markerType must fail closed, never silently fall back to RDN");
    }

    @Test
    void fromConfigSanUriModeSelectsSanMarker() {
        PeerIdentityPolicy policy = PeerIdentityPolicy.fromConfig(config(Map.of(
                PeerIdentityPolicy.MARKER_TYPE_PROP, "san-uri",
                PeerIdentityPolicy.ALLOWED_NODES_PROP, "spiffe://configd/node-1=1,spiffe://configd/node-2=2")));
        assertTrue(policy.enforced());
        assertTrue(policy.usesSanUriMarker());
        assertEquals(PeerIdentityPolicy.MarkerMode.SAN_URI, policy.markerMode());
        // In SAN-URI mode the DN path is not the marker source; resolution is by SAN URI (proven
        // end-to-end over a real handshake in the transport binding tests).
        assertNull(policy.resolveFromSanUris((X509Certificate) null),
                "a null cert is never an authorized peer (fail closed)");
    }

    // ---- requireEnforcedUnderAuth: the fail-closed default ----

    @Test
    void bootGateThrowsWhenAuthAndTlsButNoAllowList() {
        // (auth enabled + TLS on + empty allow-list) is the exact hole the node-join gate closes: a
        // CA-valid client cert could forge a peer's senderId. Refuse to boot.
        assertThrows(IllegalStateException.class,
                () -> PeerIdentityPolicy.unenforced().requireEnforcedUnderAuth(true, true),
                "an authenticated TLS cluster with no peer allow-list must refuse to start");
    }

    @Test
    void bootGateAllowsAuthDisabledEmptyAllowList() {
        // The auth-DISABLED loud-warning escape stays byte-identical (dev/test/shared-cert fleets).
        PeerIdentityPolicy.unenforced().requireEnforcedUnderAuth(false, true);
    }

    @Test
    void bootGateAllowsPlaintextInteriorEmptyAllowList() {
        // Plaintext interior (no TLS) never trips the gate - enforced()+plaintext already fails start().
        PeerIdentityPolicy.unenforced().requireEnforcedUnderAuth(true, false);
    }

    @Test
    void bootGateAllowsAuthAndTlsWithAllowList() {
        // The intended production posture: auth + TLS + an enumerated allow-list boots cleanly.
        PeerIdentityPolicy enforced = PeerIdentityPolicy.of("CN", Map.of("node-1", NodeId.of(1)));
        enforced.requireEnforcedUnderAuth(true, true);
    }

    @Test
    void sanUriResolveFailsClosedWhenUnenforced() {
        assertNull(PeerIdentityPolicy.unenforced().resolveFromSanUris((X509Certificate) null),
                "an unenforced policy authorizes nothing, even by SAN URI");
    }

    /** A minimal in-memory {@link ConfigSource} over a fixed key/value map (no layering, no env). */
    private static ConfigSource config(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values);
        return new ConfigSource() {
            @Override
            public Optional<String> getString(String key) {
                return Optional.ofNullable(copy.get(key));
            }

            @Override
            public Set<String> keysWithPrefix(String prefix) {
                return Set.of();
            }
        };
    }
}
