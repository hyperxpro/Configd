package io.configd.conformance;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.edge.ConfigChange;
import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.SubscribeOptions;
import io.configd.client.edge.Subscription;
import io.configd.client.http.ConfigdHttpClient;
import io.configd.client.http.GetOptions;
import io.configd.client.http.GetResult;
import io.configd.client.http.NodeEndpoints;
import io.configd.client.http.WriteOptions;
import io.configd.client.http.WriteOutcome;
import io.configd.raft.RaftRole;
import io.configd.server.ConfigdServer;
import io.configd.server.ServerConfig;
import io.configd.store.SigningKeyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §4 encryption-compose proof: the Group-C <b>reference driver client</b> round-trips transparently against
 * a REAL {@link ConfigdServer} booted with <b>node-local encryption at rest ENABLED</b> — proving that
 * encryption is an at-rest-only property invisible to the client (the wire and the client API are byte-identical
 * to the plaintext deployment; the client carries no encryption knob).
 *
 * <h2>The two halves (a genuine at-rest differential)</h2>
 * <ul>
 *   <li><b>Encryption ON</b> ({@link #encryptedNodeRoundTripsTheClientAndKeepsValuesOffDiskInCleartext}): a
 *       single encrypted node ({@code -Dconfigd.raft.encryption.enabled=true}, the default HKDF-from-signing-key
 *       KMS — no external KMS). The reference {@link ConfigdHttpClient} does put→get and the reference
 *       {@link ConfigdEdgeClient} hydrates + tails a from-now change — all round-trip correctly. Then a walk of
 *       the node's data dir proves a distinctive canary value the client just committed does NOT appear on disk
 *       in cleartext: it is committed + readable through the client, yet AES-GCM-scrambled at rest.</li>
 *   <li><b>Plaintext control</b> ({@link #plaintextControlLeavesTheCanaryOnDiskProvingTheWalkIsSensitive}): the
 *       SAME node with encryption OFF commits the SAME canary, and the SAME walk FINDS it on disk. This control
 *       proves the ON-absence above is caused by encryption — not by a blind spot in the walk or a value that
 *       never reached durable storage.</li>
 * </ul>
 *
 * <p>Single-node ({@code --peers ""} ⇒ majority 1 ⇒ self-elects; no peer addresses ⇒ no Raft transport, so the
 * armed peer-quorum witness gate never engages) keeps this a light compose check next to the heavier 3-node
 * failover E2E. Group commit is synchronous ({@code -Dconfigd.groupCommit.enabled=false}) so a committed write
 * is durably on disk the instant the client sees its {@code seq}, making the on-disk canary walk meaningful.
 * Everything is deadline-polled; the per-method {@link Timeout} is hang detection only. Authentication is OFF,
 * so the edge round-trip uses the full-store {@code SUBSCRIBE} feed (the surface an auth-OFF cluster serves).
 */
@Timeout(120)
class RealClusterEncryptionIT {

    private static final long ELECT_MS = 30_000;
    private static final long HYDRATE_MS = 30_000;
    private static final long DELIVER_MS = 30_000;
    private static final long POLL_MS = 50;

    // A long, highly distinctive value: if these exact bytes appear anywhere under the data dir the store kept
    // cleartext at rest; their ABSENCE under encryption (paired with the client reading them back) is the proof.
    private static final String CANARY_KEY = "cfg/secret";
    private static final String CANARY_VALUE =
            "PLAINTEXT-CANARY-8f2a91c4-b7e3-4d6a-configd-groupc-at-rest-DO-NOT-PERSIST-IN-CLEARTEXT";

    private static final String[] PROPS = {
            "configd.raft.encryption.enabled",
            "configd.groupCommit.enabled",
            "configd.raft.netty.workerThreads",
    };
    private final Map<String, String> saved = new HashMap<>();
    private final List<ConfigdServer> running = new ArrayList<>();

    @BeforeEach
    void setPosture() {
        for (String p : PROPS) {
            saved.put(p, System.getProperty(p));
        }
        // Synchronous fsync per commit so a just-committed write is durably on disk for the canary walk.
        System.setProperty("configd.groupCommit.enabled", "false");
        System.setProperty("configd.raft.netty.workerThreads", "1");
        // Each test sets `configd.raft.encryption.enabled` itself (ON for the proof, OFF for the control).
    }

    @AfterEach
    void tearDown() {
        for (ConfigdServer s : running) {
            try {
                s.shutdown();
            } catch (RuntimeException ignored) {
                // best-effort teardown
            }
        }
        running.clear();
        for (String p : PROPS) {
            String v = saved.get(p);
            if (v == null) {
                System.clearProperty(p);
            } else {
                System.setProperty(p, v);
            }
        }
    }

    @Test
    void encryptedNodeRoundTripsTheClientAndKeepsValuesOffDiskInCleartext(@TempDir Path root) throws Exception {
        System.setProperty("configd.raft.encryption.enabled", "true");
        Path dataDir = root.resolve("node-enc");
        ConfigdServer server = bootSingleNode(root, dataDir);

        try (ConfigdHttpClient http = httpClient(server)) {
            // put → get: the reference client round-trips the canary against the encrypted node.
            WriteOutcome put = http.blocking().put(CANARY_KEY, CANARY_VALUE.getBytes(UTF_8), WriteOptions.defaults());
            assertTrue(put.seq() > 0, "the encrypted node must commit the write (seq=" + put.seq() + ")");
            GetResult read = http.blocking().get(CANARY_KEY, GetOptions.defaults());
            assertTrue(read.found(), "the client must read the committed key back from the encrypted node");
            assertArrayEquals(CANARY_VALUE.getBytes(UTF_8), read.valueOrThrow(),
                    "the client must read back the exact value — encryption is transparent on the wire/API");

            // The edge plane also round-trips under encryption: hydrate carries the canary, and a fresh commit
            // is tailed as a from-now change — the fan-out feed is plaintext on the wire (at-rest only).
            try (ConfigdEdgeClient edge = ConfigdEdgeClient.open(edgeConfig(server.fanOutServer().localPort()))) {
                Subscription sub = edge.subscribeFullStore(SubscribeOptions.defaults());
                List<ConfigChange> changes = new CopyOnWriteArrayList<>();
                sub.subscribe(recordingSubscriber(changes));
                sub.awaitHydrated(Duration.ofMillis(HYDRATE_MS));
                assertTrue(await(HYDRATE_MS, () -> sub.view().get(CANARY_KEY)
                                .map(v -> CANARY_VALUE.equals(new String(v, UTF_8))).orElse(false)),
                        "the encrypted node's edge hydrate must carry the committed canary (wire is plaintext)");

                String liveKey = "cfg/live";
                String liveValue = "live-value-2f9c";
                http.blocking().put(liveKey, liveValue.getBytes(UTF_8), WriteOptions.defaults());
                assertTrue(await(DELIVER_MS, () -> sub.view().get(liveKey)
                                .map(v -> liveValue.equals(new String(v, UTF_8))).orElse(false)),
                        "a fresh commit must be tailed to the edge subscription under encryption (from-now feed)");
                assertTrue(changes.stream().anyMatch(c -> liveKey.equals(c.key())),
                        "the fresh commit must arrive as a change event on the encrypted node's edge feed");
            }
        }

        // AT-REST PROOF: the committed canary must NOT appear anywhere under the data dir in cleartext.
        // (It IS committed + readable via the client above, so this is a true differential: readable through the
        //  API yet absent as plaintext on disk ⇒ encrypted at rest, not merely unflushed.)
        String hit = firstFileContaining(dataDir, CANARY_VALUE.getBytes(UTF_8));
        assertTrue(hit == null,
                "encryption at rest is ON: the committed value must not appear in cleartext on disk, but found it in "
                        + hit);
        System.out.println("[GC-ENCRYPT-ON] client put→get + edge hydrate/tail round-tripped; the committed "
                + "canary is AES-GCM-scrambled at rest (absent as cleartext under " + dataDir + ")");
    }

    @Test
    void plaintextControlLeavesTheCanaryOnDiskProvingTheWalkIsSensitive(@TempDir Path root) throws Exception {
        System.setProperty("configd.raft.encryption.enabled", "false");
        Path dataDir = root.resolve("node-plain");
        ConfigdServer server = bootSingleNode(root, dataDir);

        try (ConfigdHttpClient http = httpClient(server)) {
            WriteOutcome put = http.blocking().put(CANARY_KEY, CANARY_VALUE.getBytes(UTF_8), WriteOptions.defaults());
            assertTrue(put.seq() > 0, "the plaintext node must commit the write (seq=" + put.seq() + ")");
            GetResult read = http.blocking().get(CANARY_KEY, GetOptions.defaults());
            assertArrayEquals(CANARY_VALUE.getBytes(UTF_8), read.valueOrThrow(),
                    "the client reads the value back on the plaintext node too");
        }

        // CONTROL: with encryption OFF the SAME committed value MUST be findable on disk in cleartext. This
        // proves the ON-side absence is caused by encryption, not by a value that never reached durable storage
        // or a blind spot in the walk.
        String hit = firstFileContaining(dataDir, CANARY_VALUE.getBytes(UTF_8));
        assertTrue(hit != null,
                "control (encryption OFF): the committed value must appear on disk in cleartext so the walk is "
                        + "proven sensitive; scanned " + dataDir + " and found none");
        System.out.println("[GC-ENCRYPT-OFF] control: the committed canary IS present in cleartext at rest ("
                + hit + ") — the on-disk walk is sensitive, so the ON-side absence is genuinely encryption");
    }

    // =======================================================================
    // single-node boot + reference clients
    // =======================================================================

    private ConfigdServer bootSingleNode(Path root, Path dataDir) throws Exception {
        // Signing key OUTSIDE the data dir (the D-1 co-location guard REFUSES a key co-located with the
        // encrypted artifacts it protects), pre-created so the boot never mints it.
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        SigningKeyStore.loadOrCreate(signingKey);

        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "0",
                "--data-dir", dataDir.toString(),
                "--peers", "",       // empty ⇒ single-node self-elects (majority 1); no Raft transport
                "--api-port", "0",   // ephemeral HTTP control plane
                "--edge-port", "0",  // ephemeral edge fan-out plane
                "--signing-key-file", signingKey.toString(),
        });
        ConfigdServer server = ConfigdServer.start(config);
        running.add(server);

        // The single node must self-elect before it can serve writes.
        assertTrue(await(ELECT_MS, () -> {
            var node = server.driver().getGroup(0);
            return node != null && node.monitorView().role() == RaftRole.LEADER;
        }), "the single node must self-elect a leader (cold start → serving)");
        return server;
    }

    private static ConfigdHttpClient httpClient(ConfigdServer server) {
        return ConfigdHttpClient.builder()
                .endpoints(NodeEndpoints.of(URI.create("http://127.0.0.1:" + server.apiPort())))
                .allowPlaintext(true) // auth OFF, plaintext loopback: no credential needed (open gate)
                .retryPolicy(new RetryPolicy(Duration.ofMillis(50), Duration.ofMillis(500), 20))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static ConfigdClientConfig edgeConfig(int port) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();
    }

    private static Flow.Subscriber<ConfigChange> recordingSubscriber(List<ConfigChange> sink) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ConfigChange item) {
                sink.add(item);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        };
    }

    // =======================================================================
    // on-disk cleartext walk
    // =======================================================================

    /** Returns the path of the first regular file under {@code dir} whose bytes contain {@code needle}, or null. */
    private static String firstFileContaining(Path dir, byte[] needle) throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(dir)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        for (Path p : files) {
            if (indexOf(Files.readAllBytes(p), needle) >= 0) {
                return p.toString();
            }
        }
        return null;
    }

    /** Naive byte-array search (files are tiny in a test); returns the first index of {@code needle} or -1. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean await(long budgetMs, BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return cond.getAsBoolean();
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }
}
