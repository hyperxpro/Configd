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
import java.security.PublicKey;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composes FOUR features over ONE live socket against a REAL encrypted {@link ConfigdServer}: node-local
 * <b>encryption at rest</b>, the server-side <b>prefix filter</b> (wire {@code 0x03}), <b>signed-position
 * verification</b> (the reference client's {@code verifyWith(clusterKey)}), and the edge fan-out plane over
 * a plaintext loopback socket. This is the proof that the reference client cryptographically verifies a
 * genuine {@code ConfigdServer}'s signed deltas while that server keeps the same values AES-GCM-scrambled
 * on disk.
 *
 * <p><b>How the fan-out signing public key is obtained.</b> The cluster signs its fan-out deltas with the
 * keypair loaded from {@code --signing-key-file} ({@code ConfigdServer} builds {@code new
 * ConfigSigner(SigningKeyStore.loadOrCreate(keyFile).keyPair())}). The test pre-creates that key file and
 * captures its PUBLIC key, which is exactly what the reference client passes to {@code verifyWith}: no
 * out-of-band export needed.
 *
 * <p><b>Why the SUBSCRIBE plane.</b> Authentication is OFF, so the fan-out driver fails every
 * {@code WATCH_CREATE} closed (no principal model) while admitting the legacy prefix {@code SUBSCRIBE};
 * the filtered {@code 0x03} SUBSCRIBE is served, so the composition is exercised there. Single-node
 * ({@code --peers ""}) keeps this a light compose check; synchronous group commit makes a just-committed
 * write durably on disk for the canary walk. Everything is deadline-polled; the per-method {@link Timeout}
 * is hang detection only.
 */
@Timeout(180)
class EncryptedFilteredSignedEdgeSweepIT {

    private static final long ELECT_MS = 30_000;
    private static final long HYDRATE_MS = 30_000;
    private static final long DELIVER_MS = 30_000;
    private static final long POLL_MS = 50;

    private static final String PREFIX = "app/";
    private static final String OUT_PREFIX = "sys/";

    // A long, highly distinctive OUT-OF-PREFIX value: it is both the at-rest canary (its absence on disk
    // proves encryption) and the filter canary (its absence from the 0x03 change stream proves the
    // server-side prefix filter). Committed + readable via HTTP, yet delivered to neither.
    private static final String CANARY_KEY = OUT_PREFIX + "secret";
    private static final String CANARY_VALUE =
            "PLAINTEXT-CANARY-3d71e9f0-a2b8-4c15-configd-groupd-filtered-signed-DO-NOT-PERSIST-IN-CLEARTEXT";

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
        // Encryption ON for the whole sweep; synchronous fsync per commit so a just-committed value is
        // durably on disk when the canary walk runs (server-side prefix filtering is on by default).
        System.setProperty("configd.raft.encryption.enabled", "true");
        System.setProperty("configd.groupCommit.enabled", "false");
        System.setProperty("configd.raft.netty.workerThreads", "1");
    }

    @AfterEach
    void tearDown() {
        for (ConfigdServer s : running) {
            try {
                s.shutdown();
            } catch (RuntimeException ignored) {
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
    void encryptedServerFiltersAndSignsTheEdgeSweepAndStaysPlaintextOffDisk(@TempDir Path root)
            throws Exception {
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        PublicKey clusterKey = SigningKeyStore.loadOrCreate(signingKey).keyPair().getPublic();

        Path dataDir = root.resolve("node-enc");
        ConfigdServer server = bootSingleNode(dataDir, signingKey);
        int edgePort = server.fanOutServer().localPort();

        try (ConfigdHttpClient http = httpClient(server)) {

            try (ConfigdEdgeClient edge = ConfigdEdgeClient.open(verifyingEdgeConfig(edgePort, clusterKey))) {
                Subscription sub = edge.subscribePrefixes(List.of(PREFIX),
                        SubscribeOptions.defaults().withAcceptFiltered(true));
                List<ConfigChange> changes = new CopyOnWriteArrayList<>();
                sub.subscribe(recordingSubscriber(changes));
                // Empty store => TAIL from cursor 0 (no hydration snapshot); the tail below chains cleanly.
                sub.awaitHydrated(Duration.ofMillis(HYDRATE_MS));

                http.blocking().put(PREFIX + "name", "configd".getBytes(UTF_8), WriteOptions.defaults());
                http.blocking().put(PREFIX + "region", "us-east".getBytes(UTF_8), WriteOptions.defaults());
                assertTrue(await(DELIVER_MS, () -> viewHas(sub, PREFIX + "name", "configd")
                                && viewHas(sub, PREFIX + "region", "us-east")),
                        "the 0x03 filtered tail must deliver both in-prefix keys to the verified view");
                // Delivered as CHANGE EVENTS (signed deltas the verifier accepted), not via a snapshot: a
                // failed signature would have torn the connection down before either arrived.
                assertTrue(await(DELIVER_MS, () -> count(changes, PREFIX + "name") == 1
                                && count(changes, PREFIX + "region") == 1),
                        "each in-prefix commit must arrive as exactly one verified change event over 0x03");
                assertEquals0(countPrefix(changes, OUT_PREFIX), changes,
                        "no out-of-prefix key may be delivered before any is even committed");

                // Commit the out-of-prefix canary, then an in-prefix sentinel; when the sentinel is visible the client
                // has processed past the canary's position, so the canary's absence is decisive.
                WriteOutcome canaryPut = http.blocking()
                        .put(CANARY_KEY, CANARY_VALUE.getBytes(UTF_8), WriteOptions.defaults());
                assertTrue(canaryPut.seq() > 0, "the out-of-prefix canary must commit (seq=" + canaryPut.seq() + ")");
                http.blocking().put(PREFIX + "tier", "gold".getBytes(UTF_8), WriteOptions.defaults());
                assertTrue(await(DELIVER_MS, () -> viewHas(sub, PREFIX + "tier", "gold")),
                        "the in-prefix sentinel committed after the canary must reach the filtered view");
                // The decisive live-tail-filter proof. `tier` was committed after the interleaved out-of-prefix
                // canary, so the server dropped the canary's whole signed delta and opened a forward jump in the
                // delivered chain (its covered-S cursor advances past the skipped position -- a deliberate
                // consequence of the filter, per ADR-0045 carve-out 2, not a bug). `tier` must therefore arrive
                // as exactly one live change event, forward-applied over that jump, not re-hydrated by a
                // re-bootstrap. A client that misread the forward jump as a chain gap would re-subscribe at 0
                // and re-hydrate `tier` through the prefix snapshot, which emits no change event: this exact
                // count is what fails if that re-bootstrap regression ever returns. It is what turns the leg
                // from "tier eventually visible" (a snapshot re-hydrate also satisfies that) into "tier arrived
                // live over the filtered tail".
                assertTrue(await(DELIVER_MS, () -> count(changes, PREFIX + "tier") == 1),
                        "tier must arrive as exactly one live change event over the filtered tail (forward-applied "
                                + "over the dropped out-of-prefix delta) — a re-bootstrap re-hydration would deliver "
                                + "it via the snapshot with no change event, failing this");
                // The change-event stream is NOT client-filtered (only the view is), so an out-of-prefix key
                // in it would mean the server sent it. Its absence == the server dropped the whole signed
                // delta before the wire: the 0x03 server-side prefix filter is genuinely engaged.
                assertEquals0(countPrefix(changes, OUT_PREFIX), changes,
                        "the 0x03 server-side prefix filter must drop the out-of-prefix delta — never delivered");
                assertFalse(sub.view().get(CANARY_KEY).isPresent(),
                        "the filtered view must never contain the out-of-prefix canary");
                System.out.println("[GD-FILTER-0x03] verified in-prefix tail delivered live over the forward jump "
                        + "(tier as one change event, not a re-bootstrap re-hydrate); out-of-prefix delta dropped "
                        + "server-side (never a change event, never in the view)");
            }

            // The out-of-prefix canary IS committed and readable via HTTP -- it was FILTERED from the edge,
            // not un-written. This makes the at-rest walk a true differential (readable via API, absent as
            // plaintext on disk).
            GetResult canaryRead = http.blocking().get(CANARY_KEY, GetOptions.defaults());
            assertTrue(canaryRead.found(), "the out-of-prefix canary must be readable through the HTTP client");
            assertArrayEquals(CANARY_VALUE.getBytes(UTF_8), canaryRead.valueOrThrow(),
                    "the HTTP client reads the committed out-of-prefix value verbatim");

            try (ConfigdEdgeClient legacy = ConfigdEdgeClient.open(plaintextEdgeConfig(edgePort))) {
                Subscription full = legacy.subscribeFullStore(SubscribeOptions.defaults()); // 0x01, whole store
                List<ConfigChange> legacyChanges = new CopyOnWriteArrayList<>();
                full.subscribe(recordingSubscriber(legacyChanges));
                full.awaitHydrated(Duration.ofMillis(HYDRATE_MS));
                assertTrue(await(HYDRATE_MS, () -> viewHas(full, PREFIX + "name", "configd")
                                && viewHas(full, CANARY_KEY, CANARY_VALUE)),
                        "a 0x01 full-store client must hydrate the WHOLE store from the 0x03-capable server — "
                                + "including the out-of-prefix key the filtered client never saw (back-compat)");
                System.out.println("[GD-BACKCOMPAT-0x01] the 0x03-capable encrypted server served a 0x01 "
                        + "full-store client the whole store, incl. the out-of-prefix key");
            }
        }

        String hit = firstFileContaining(dataDir, CANARY_VALUE.getBytes(UTF_8));
        assertTrue(hit == null,
                "encryption at rest is ON: the committed canary must not appear in cleartext on disk, found it in "
                        + hit);
        System.out.println("[GD-ENCRYPT-ON] the committed canary is AES-GCM-scrambled at rest (absent as "
                + "cleartext under " + dataDir + ") — encryption, 0x03 filtering, and signed verify compose");
    }


    private ConfigdServer bootSingleNode(Path dataDir, Path signingKey) {
        // Signing key kept outside the data dir: the boot guard refuses a key co-located with the encrypted
        // artifacts it protects. Already created and captured by the caller.
        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "0",
                "--data-dir", dataDir.toString(),
                "--peers", "",       // empty => single-node self-elects (majority 1); no Raft transport
                "--api-port", "0",   // ephemeral HTTP control plane
                "--edge-port", "0",  // ephemeral edge fan-out plane
                "--signing-key-file", signingKey.toString(),
        });
        ConfigdServer server = ConfigdServer.start(config);
        running.add(server);

        assertTrue(await(ELECT_MS, () -> {
            var node = server.driver().getGroup(0);
            return node != null && node.monitorView().role() == RaftRole.LEADER;
        }), "the single encrypted node must self-elect a leader (cold start → serving)");
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

    /** The 0x03 filtered client that cryptographically verifies every signed position against {@code key}. */
    private static ConfigdClientConfig verifyingEdgeConfig(int port, PublicKey key) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .verifyWith(key)
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();
    }

    /** The 0x01 back-compat client (full-store hydrate; the snapshot is unsigned, trusted on the socket). */
    private static ConfigdClientConfig plaintextEdgeConfig(int port) {
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


    private static boolean viewHas(Subscription sub, String key, String value) {
        return sub.view().get(key).map(v -> value.equals(new String(v, UTF_8))).orElse(false);
    }

    private static int count(List<ConfigChange> changes, String key) {
        int n = 0;
        for (ConfigChange c : changes) {
            if (key.equals(c.key())) {
                n++;
            }
        }
        return n;
    }

    private static int countPrefix(List<ConfigChange> changes, String prefix) {
        int n = 0;
        for (ConfigChange c : changes) {
            if (c.key().startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /** assertEquals(0, actual) with the offending change list in the message (diagnostics on failure). */
    private static void assertEquals0(int actual, List<ConfigChange> changes, String message) {
        assertTrue(actual == 0, () -> message + " (saw " + actual + " in " + keys(changes) + ")");
    }

    private static List<String> keys(List<ConfigChange> changes) {
        List<String> ks = new ArrayList<>(changes.size());
        for (ConfigChange c : changes) {
            ks.add(c.key());
        }
        return ks;
    }

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

    private static boolean await(long budgetMs, BooleanSupplier cond) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return cond.getAsBoolean();
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }
}
