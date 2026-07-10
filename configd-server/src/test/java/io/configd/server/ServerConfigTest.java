package io.configd.server;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ServerConfig} argument parsing and default values.
 */
class ServerConfigTest {

    @TempDir
    Path tempDir;

    // ========================================================================
    // Parsing with all required arguments
    // ========================================================================

    @Nested
    class AllRequiredArgs {

        @Test
        void parsesAllRequiredArguments() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(NodeId.of(1), config.nodeId());
            assertEquals(tempDir, config.dataDir());
            assertEquals(Set.of(NodeId.of(2), NodeId.of(3)), config.peers());
        }

        @Test
        void parsesAllArgumentsIncludingOptional() {
            String[] args = {
                "--node-id", "5",
                "--data-dir", tempDir.toString(),
                "--peers", "1,2,3",
                "--bind-address", "127.0.0.1",
                "--bind-port", "7070",
                "--api-port", "9999",
                "--auth-token", "secret-token-123"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(NodeId.of(5), config.nodeId());
            assertEquals(tempDir, config.dataDir());
            assertEquals(Set.of(NodeId.of(1), NodeId.of(2), NodeId.of(3)), config.peers());
            assertEquals("127.0.0.1", config.bindAddress());
            assertEquals(7070, config.bindPort());
            assertEquals(9999, config.apiPort());
            assertEquals("secret-token-123", config.authToken());
        }

        @Test
        void parsesSinglePeer() {
            String[] args = {
                "--node-id", "0",
                "--data-dir", tempDir.toString(),
                "--peers", "1"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(Set.of(NodeId.of(1)), config.peers());
        }

        @Test
        void parsesEmptyPeers() {
            String[] args = {
                "--node-id", "0",
                "--data-dir", tempDir.toString(),
                "--peers", ""
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(Set.of(), config.peers());
        }
    }

    // ========================================================================
    // Missing required arguments
    // ========================================================================

    @Nested
    class MissingRequiredArgs {

        @Test
        void throwsWhenNodeIdMissing() {
            String[] args = {
                "--data-dir", tempDir.toString(),
                "--peers", "1,2"
            };

            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.parse(args)
            );
            assertTrue(ex.getMessage().contains("--node-id"));
        }

        @Test
        void throwsWhenDataDirMissing() {
            String[] args = {
                "--node-id", "1",
                "--peers", "2,3"
            };

            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.parse(args)
            );
            assertTrue(ex.getMessage().contains("--data-dir"));
        }

        @Test
        void throwsWhenPeersMissing() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString()
            };

            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.parse(args)
            );
            assertTrue(ex.getMessage().contains("--peers"));
        }

        @Test
        void throwsWhenNoArguments() {
            String[] args = {};

            assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(args));
        }

        @Test
        void throwsWhenFlagHasNoValue() {
            String[] args = {
                "--node-id"
            };

            IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.parse(args)
            );
            assertTrue(ex.getMessage().contains("--node-id"));
        }
    }

    // ========================================================================
    // Default values
    // ========================================================================

    @Nested
    class DefaultValues {

        @Test
        void defaultBindAddressIsLoopback() {
            // B5: the default bind is loopback, not the wildcard 0.0.0.0. Binding an unauthenticated store
            // to a public interface by default is the Redis/etcd "default-open" footgun; a real cluster
            // sets --bind-address to a routable address on purpose.
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals("127.0.0.1", config.bindAddress());
        }

        @Test
        void defaultBindPortIs9090() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(9090, config.bindPort());
        }

        @Test
        void defaultApiPortIs8080() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(8080, config.apiPort());
        }

        @Test
        void defaultTlsPathsAreNull() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertNull(config.tlsCertPath());
            assertNull(config.tlsKeyPath());
            assertNull(config.tlsTrustStorePath());
        }

        @Test
        void defaultAuthTokenIsNull() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertNull(config.authToken());
        }
    }

    // ========================================================================
    // Optional arguments
    // ========================================================================

    @Nested
    class OptionalArgs {

        @Test
        void parsesBindAddress() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3",
                "--bind-address", "192.168.1.1"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals("192.168.1.1", config.bindAddress());
        }

        @Test
        void parsesBindPort() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3",
                "--bind-port", "7070"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(7070, config.bindPort());
        }

        @Test
        void parsesApiPort() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3",
                "--api-port", "3000"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals(3000, config.apiPort());
        }

        @Test
        void parsesAuthToken() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3",
                "--auth-token", "my-secret"
            };

            ServerConfig config = ServerConfig.parse(args);

            assertEquals("my-secret", config.authToken());
            assertTrue(config.authEnabled());
        }
    }

    // ========================================================================
    // Invalid arguments
    // ========================================================================

    @Nested
    class InvalidArgs {

        @Test
        void throwsOnUnknownArgument() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3",
                "--unknown-flag", "value"
            };

            assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(args));
        }

        @Test
        void throwsOnNonNumericNodeId() {
            String[] args = {
                "--node-id", "abc",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3"
            };

            assertThrows(NumberFormatException.class, () -> ServerConfig.parse(args));
        }

        @Test
        void throwsOnNonNumericBindPort() {
            String[] args = {
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3",
                "--bind-port", "not-a-port"
            };

            assertThrows(NumberFormatException.class, () -> ServerConfig.parse(args));
        }
    }

    // ========================================================================
    // Record equality / immutability
    // ========================================================================

    @Test
    void configIsAValueType() {
        String[] args = {
            "--node-id", "1",
            "--data-dir", tempDir.toString(),
            "--peers", "2,3"
        };

        ServerConfig a = ServerConfig.parse(args);
        ServerConfig b = ServerConfig.parse(args);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ========================================================================
    // ServerConfig.parsePeerAddresses was 0/15 lines. Exercise the
    // "id=host:port,..." parser through the public parse() entry point: valid
    // single/multi entries, surrounding whitespace, the empty case, and each
    // malformed-input rejection branch.
    // ========================================================================

    @Nested
    class PeerAddressParsing {

        private String[] withPeerAddresses(String value) {
            return new String[]{
                "--node-id", "1",
                "--data-dir", tempDir.toString(),
                "--peers", "2,3",
                "--peer-addresses", value
            };
        }

        @Test
        void parsesSinglePeerAddress() {
            ServerConfig config = ServerConfig.parse(withPeerAddresses("2=192.168.1.10:9091"));
            var addrs = config.peerAddresses();
            assertNotNull(addrs);
            assertEquals(1, addrs.size());
            var a = addrs.get(NodeId.of(2));
            assertNotNull(a, "peer 2 must be present");
            assertEquals(9091, a.getPort());
        }

        @Test
        void parsesMultiplePeerAddresses() {
            ServerConfig config = ServerConfig.parse(
                    withPeerAddresses("2=10.0.0.2:9092,3=10.0.0.3:9093"));
            var addrs = config.peerAddresses();
            assertEquals(2, addrs.size());
            assertEquals(9092, addrs.get(NodeId.of(2)).getPort());
            assertEquals(9093, addrs.get(NodeId.of(3)).getPort());
        }

        @Test
        void trimsSurroundingWhitespaceAndSkipsEmptyEntries() {
            ServerConfig config = ServerConfig.parse(
                    withPeerAddresses(" 2 = 10.0.0.2:9092 , , 3 = 10.0.0.3:9093 "));
            var addrs = config.peerAddresses();
            assertEquals(2, addrs.size(), "empty entries between commas are skipped");
            assertEquals(9092, addrs.get(NodeId.of(2)).getPort());
            assertEquals(9093, addrs.get(NodeId.of(3)).getPort());
        }

        @Test
        void blankValueProducesEmptyMap() {
            ServerConfig config = ServerConfig.parse(withPeerAddresses("   "));
            assertTrue(config.peerAddresses().isEmpty(),
                    "a blank --peer-addresses value yields an empty map, not null");
        }

        @Test
        void rejectsEntryMissingEquals() {
            assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.parse(withPeerAddresses("2:10.0.0.2:9092")),
                    "an entry without '=' (id=host:port) must be rejected");
        }

        @Test
        void rejectsAddressMissingPort() {
            assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.parse(withPeerAddresses("2=10.0.0.2")),
                    "an address without a ':port' must be rejected");
        }

        @Test
        void rejectsNonNumericNodeId() {
            assertThrows(NumberFormatException.class,
                    () -> ServerConfig.parse(withPeerAddresses("two=10.0.0.2:9092")),
                    "a non-numeric node id must fail to parse");
        }

        @Test
        void rejectsNonNumericPort() {
            assertThrows(NumberFormatException.class,
                    () -> ServerConfig.parse(withPeerAddresses("2=10.0.0.2:notaport")),
                    "a non-numeric port must fail to parse");
        }
    }

    // ========================================================================
    // --edge-port
    // ========================================================================

    @Nested
    class EdgePort {

        @Test
        void edgePortAbsentByDefaultDisablesTheEndpoint() {
            ServerConfig c = ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.toString(), "--peers", "1,2"});
            assertNull(c.edgePort(), "edge port must default to null (endpoint disabled)");
            assertFalse(c.edgeEnabled());
        }

        @Test
        void edgePortIsParsedWhenPresent() {
            ServerConfig c = ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.toString(), "--peers", "1,2",
                    "--edge-port", "9443"});
            assertEquals(9443, c.edgePort());
            assertTrue(c.edgeEnabled());
        }

        @Test
        void edgePortRequiresAValue() {
            assertThrows(IllegalArgumentException.class, () -> ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.toString(), "--peers", "1,2",
                    "--edge-port"}));
        }

        @Test
        void nonNumericEdgePortFails() {
            assertThrows(NumberFormatException.class, () -> ServerConfig.parse(new String[]{
                    "--node-id", "0", "--data-dir", tempDir.toString(), "--peers", "1,2",
                    "--edge-port", "nope"}));
        }
    }
}
