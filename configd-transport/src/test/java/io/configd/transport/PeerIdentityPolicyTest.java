package io.configd.transport;

import io.configd.common.NodeId;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PeerIdentityPolicy} - the WH-08/09 cert-identity &harr; NodeId resolver. Pure
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
}
