package io.configd.edge.node;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI parsing/validation matrix for {@link EdgeNodeConfig}. Flag names are contractual;
 * changing them breaks deployed scripts and operator runsheets.
 */
class EdgeNodeConfigTest {

    private static String[] minimal(String... extra) {
        String[] base = {
                "--edge-id", "CN=edge-1,O=configd",
                "--fanout-endpoints", "10.0.0.1:7000",
                "--data-dir", "/tmp/edge"};
        String[] out = new String[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    @Test
    void minimalArgsParseWithDefaults() {
        EdgeNodeConfig cfg = EdgeNodeConfig.parse(minimal());
        assertEquals("CN=edge-1,O=configd", cfg.edgeId());
        assertEquals(List.of(InetSocketAddress.createUnresolved("10.0.0.1", 7000)),
                cfg.fanOutEndpoints());
        assertEquals(EdgeNodeConfig.DEFAULT_API_PORT, cfg.apiPort());
        assertEquals(Path.of("/tmp/edge"), cfg.dataDir());
        assertNull(cfg.verifyKeyPath());
        assertTrue(cfg.subscribePrefixes().isEmpty(), "no prefixes = full store");
        assertFalse(cfg.tlsEnabled());
        assertEquals(EdgeNodeConfig.DEFAULT_RECONNECT_BACKOFF_MS, cfg.reconnectBackoffMs());
        assertEquals(EdgeNodeConfig.DEFAULT_HEARTBEAT_SILENCE_FACTOR, cfg.heartbeatSilenceFactor());
    }

    @Test
    void allFlagsParse() {
        EdgeNodeConfig cfg = EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e1",
                "--fanout-endpoints", "h1:7000,h2:7001",
                "--api-port", "0",
                "--data-dir", "/tmp/d",
                "--verify-key", "/tmp/vk.der",
                "--subscribe-prefix", "svc/",
                "--subscribe-prefix", "app/",
                "--tls-cert", "/tmp/c.pem",
                "--tls-key", "/tmp/k.p12",
                "--tls-trust-store", "/tmp/t.p12",
                "--reconnect-backoff-ms", "250",
                "--heartbeat-silence-factor", "4"});
        assertEquals(2, cfg.fanOutEndpoints().size());
        assertEquals("h2", cfg.fanOutEndpoints().get(1).getHostString());
        assertEquals(7001, cfg.fanOutEndpoints().get(1).getPort());
        assertEquals(0, cfg.apiPort());
        assertEquals(Path.of("/tmp/vk.der"), cfg.verifyKeyPath());
        assertEquals(List.of("svc/", "app/"), cfg.subscribePrefixes(), "repeatable, ordered");
        assertTrue(cfg.tlsEnabled());
        assertEquals(250, cfg.reconnectBackoffMs());
        assertEquals(4, cfg.heartbeatSilenceFactor());
    }

    @Test
    void requiredFlagsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--fanout-endpoints", "h:1", "--data-dir", "/tmp"}), "edge-id required");
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--data-dir", "/tmp"}), "endpoints required");
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "h:1"}), "data-dir required");
    }

    @Test
    void partialTlsTripleIsRejectedFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeNodeConfig.parse(minimal("--tls-cert", "/tmp/c.pem")));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeNodeConfig.parse(minimal(
                        "--tls-cert", "/tmp/c.pem", "--tls-key", "/tmp/k.p12")));
    }

    @Test
    void endpointParsingRejectsGarbage() {
        assertThrows(UnsupportedOperationException.class,
                () -> EdgeNodeConfig.parse(minimal()).fanOutEndpoints().add(null),
                "endpoints list is immutable");
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "no-port", "--data-dir", "/tmp"}));
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "h:notnum", "--data-dir", "/tmp"}));
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "h:99999", "--data-dir", "/tmp"}));
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", ",", "--data-dir", "/tmp"}));
    }

    @Test
    void policyThresholdBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeNodeConfig.parse(minimal("--reconnect-backoff-ms", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeNodeConfig.parse(minimal("--heartbeat-silence-factor", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeNodeConfig.parse(minimal("--api-port", "70000")));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeNodeConfig.parse(minimal("--subscribe-prefix", " ")));
    }

    @Test
    void unknownArgumentAndDanglingValueAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeNodeConfig.parse(minimal("--bogus", "x")));
        // EVERY flag must fail with a clean IllegalArgumentException (never a raw
        // ArrayIndexOutOfBounds) when its value is missing — the operator-facing
        // CLI contract.
        for (String flag : new String[]{
                "--edge-id", "--fanout-endpoints", "--api-port", "--data-dir",
                "--verify-key", "--subscribe-prefix", "--tls-cert", "--tls-key",
                "--tls-trust-store", "--reconnect-backoff-ms", "--heartbeat-silence-factor"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> EdgeNodeConfig.parse(minimal(flag)),
                    "dangling " + flag + " must be a clean usage error");
        }
    }

    @Test
    void endpointHostAndPortBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", ":7000", "--data-dir", "/tmp"}),
                "empty host");
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "h:", "--data-dir", "/tmp"}),
                "trailing colon / empty port");
        assertThrows(IllegalArgumentException.class, () -> EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "h:0", "--data-dir", "/tmp"}),
                "port 0 is not a connectable endpoint");
        assertEquals(1, EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "h:1", "--data-dir", "/tmp"})
                .fanOutEndpoints().get(0).getPort());
        assertEquals(65535, EdgeNodeConfig.parse(new String[]{
                "--edge-id", "e", "--fanout-endpoints", "h:65535", "--data-dir", "/tmp"})
                .fanOutEndpoints().get(0).getPort());
    }
}
