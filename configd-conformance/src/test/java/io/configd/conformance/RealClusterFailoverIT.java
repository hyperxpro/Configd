package io.configd.conformance;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.ServerAddress;
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
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.server.ConfigdServer;
import io.configd.server.ServerConfig;
import io.configd.store.SigningKeyStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An end-to-end proof: the <b>reference driver client</b> (built independently from the RFC) driven against a
 * <b>REAL multi-node {@link ConfigdServer} cluster across a REAL leader failover</b> -- not a mock. It proves
 * both reference-client planes survive a genuine consensus leadership change on a real cluster over real
 * localhost sockets -- the HTTP control plane transparently follows the new leader, and the edge data plane's
 * own node dying forces the client to rotate to a survivor and RESUME its subscription from its cursor with no
 * committed data lost, no edge event gapped, and no duplicate.
 *
 * <h2>The cluster</h2>
 * Three real {@link ConfigdServer} nodes over loopback: each with an HTTP control-plane endpoint (ephemeral
 * {@code --api-port 0}), an edge fan-out endpoint (ephemeral {@code --edge-port 0}), and the Netty Raft
 * consensus transport wired between all three ({@code --peer-addresses}). A single Raft group (the default
 * {@code shardCount=1}) is replicated across the three nodes; one shared cluster signing key lives OUTSIDE
 * every node's data dir. Everything is deadline-polled -- no sleep-as-sync; the per-method {@link Timeout} is
 * pure hang detection on the throttled 2-vCPU box, and the election budget uses a heartbeat/election ratio
 * already proven stable under load, so scheduling jitter cannot manufacture a spurious election.
 *
 * <h2>The proof (deadline-polled every phase)</h2>
 * <ol>
 *   <li><b>Elect + write + converge.</b> Boot the cluster, elect a stable leader; the reference
 *       {@link ConfigdHttpClient} (pointed at ALL THREE endpoints via a {@link NodeEndpoints#ofMap id-to-URI
 *       map}, so its {@code LeaderRouter} can follow {@code X-Leader-Hint}s and rotate) commits a batch and
 *       reads it back. Then WAIT until every node has applied the batch (so the survivor the edge later
 *       reconnects to already holds the subscription's resume cursor -- a deterministic {@code TAIL} resume,
 *       not a re-hydrate).</li>
 *   <li><b>Edge subscribe ON THE NODE TO BE KILLED.</b> The reference {@link ConfigdEdgeClient} is configured
 *       with ALL THREE edge endpoints, <b>leader first</b>, so its full-store subscription's connection lands
 *       on the leader; it hydrates the pre-failover state (via the snapshot, not as change events) and records
 *       its resume cursor. A pre-kill assertion confirms the connection was stable on the leader (zero
 *       reconnects).</li>
 *   <li><b>KILL the leader.</b> {@code shutdown()} the leader -- the crash equivalent (edge + HTTP + Raft
 *       transport all close). This forces BOTH a control-plane re-election AND the edge connection to drop.</li>
 *   <li><b>HTTP leader-follow.</b> A put issued after the kill transparently succeeds on the NEW leader -- the
 *       client rode the real election window: rotated off the connect-refused dead endpoint, followed the REAL
 *       {@code X-Leader-Hint} / rotated on a hintless-503.</li>
 *   <li><b>EDGE FAILOVER-RESUME (the literal proof).</b> The killed edge connection's {@code SERVER_SHUTDOWN}
 *       drives the session's bounded reconnect, which rotates ({@code EdgeSession.nextEndpoint}) to a SURVIVOR
 *       endpoint; the {@link Subscription} automatically re-subscribes at its persisted resume cursor
 *       ({@code onConnected} sends the SUBSCRIBE with {@code resume=cursor}; a plain drop does NOT force a
 *       re-bootstrap). The survivor honours the cursor as a {@code TAIL} resume (it holds the cursor's seq from
 *       the pre-kill convergence), so the post-failover write is delivered <b>exactly once</b> as a change
 *       event -- no gap, no duplicate -- the cursor advances monotonically, continuing the per-shard sequence,
 *       and NO already-hydrated key is re-delivered.</li>
 *   <li><b>No committed data lost.</b> Every pre-failover key is still readable through the reference client.</li>
 * </ol>
 *
 * <p><b>Why the SUBSCRIBE plane, not the 0x02 WATCH plane.</b> Against a real {@code ConfigdServer} booted
 * with authentication OFF, the fan-out driver fails every {@code WATCH_CREATE} CLOSED (no principal model, so
 * {@code watchAuthorizer == null}) while it ADMITS the legacy full-store {@code SUBSCRIBE} -- so the genuine
 * client-node-dies -> rotate-to-survivor -> resume-from-cursor failover is exercised here on the
 * SUBSCRIBE plane. The 0x02 WATCH plane (whose authorization needs a privileged {@code _acl/} bootstrap) is
 * covered by {@code RealServerWatchTest} against a permissive in-process {@code FanOutServer}. The resume
 * mechanics are identical (persisted cursor, re-subscribe on reconnect, {@code TAIL} vs re-bootstrap decided
 * by the server).
 */
@Timeout(240) // hang detection on the throttled 2-vCPU box; every phase bounds itself with an explicit deadline
class RealClusterFailoverIT {

    private static final int NODES = 3;
    private static final int GROUP = 0; // the default single Raft group (shardCount=1), replicated across all 3

    // 2-vCPU election budget: heartbeat 100ms << election 1500-3000ms (ratio ~15-30), a range already proven
    // stable, so jitter cannot manufacture a spurious election or shed.
    private static final long STABILIZE_MS = 60_000; // all-three converge on one leader from a cold boot
    private static final long CONVERGE_MS = 30_000;  // all three nodes apply the pre-failover batch
    private static final long FAILOVER_MS = 60_000;  // survivors re-elect after the leader is killed
    private static final long HYDRATE_MS = 30_000;   // the edge subscription hydrates the pre-failover state
    private static final long DELIVER_MS = 45_000;   // the reconnect + resume + post-failover write reach the edge
    private static final long POLL_MS = 50;
    private static final int STABLE_OBSERVATIONS = 10; // ~0.5s of a steady single-leader reading = "settled"

    private static final int KEY_COUNT = 5;

    private static final String[] PROPS = {
            "configd.raft.encryption.enabled",
            "configd.raft.electionTimeoutMinMs",
            "configd.raft.electionTimeoutMaxMs",
            "configd.raft.heartbeatIntervalMs",
            "configd.raft.netty.workerThreads",
    };
    private final Map<String, String> saved = new HashMap<>();
    private final List<ConfigdServer> running = new ArrayList<>();

    @BeforeEach
    void setPosture() {
        for (String p : PROPS) {
            saved.put(p, System.getProperty(p));
        }
        System.setProperty("configd.raft.encryption.enabled", "false"); // isolate failover from at-rest crypto
        System.setProperty("configd.raft.electionTimeoutMinMs", "1500");
        System.setProperty("configd.raft.electionTimeoutMaxMs", "3000");
        System.setProperty("configd.raft.heartbeatIntervalMs", "100");
        System.setProperty("configd.raft.netty.workerThreads", "1"); // less event-loop contention on 2 vCPU
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
    void referenceClientFollowsARealLeaderFailoverWithNoDataLossAndEdgeResume(@TempDir Path root) throws Exception {
        ConfigdServer[] servers = bootCluster(root);
        NodeEndpoints endpoints = httpEndpoints(servers);

        int leader0 = awaitStableLeaderExcluding(servers, -1, STABILIZE_MS);
        assertTrue(leader0 >= 0, "the 3-node cluster must elect a single stable leader on the real Netty wire");

        try (ConfigdHttpClient http = httpClient(endpoints)) {
            for (int k = 0; k < KEY_COUNT; k++) {
                WriteOutcome put = http.blocking().put(key(k), value(k).getBytes(UTF_8), WriteOptions.defaults());
                assertTrue(put.seq() > 0, "pre-failover write " + key(k) + " must commit (seq=" + put.seq() + ")");
            }
            // Every node must have APPLIED the batch before we read it back and before we subscribe. A default
            // GET is a stale read that may land on a follower, so reading cfg/k4 the instant its commit returns
            // races that follower's apply; and the survivor the edge later reconnects to must already hold the
            // subscription's resume cursor so the server TAIL-resumes it (recover-from-cursor) rather than
            // re-hydrating -- the deterministic exactly-once path. Converge once, then both are safe.
            assertTrue(awaitConvergence(servers, GROUP, CONVERGE_MS),
                    "all three nodes must apply the pre-failover batch before the read-back + the edge subscribe: "
                            + appliedSnapshot(servers));
            // A default GET is an eventually-consistent local read (served off whatever node round-robin lands
            // on, not routed to the leader) and a 404 is terminal -- not retried -- and the store's read-serving
            // state can briefly lag the raft-applied index that awaitConvergence observes. So poll each key to a
            // deadline rather than a single get (the same read-after-write handling as the post-failover POST_KEY).
            for (int k = 0; k < KEY_COUNT; k++) {
                final int kk = k;
                assertTrue(await(DELIVER_MS, () -> {
                    GetResult read = http.blocking().get(key(kk), GetOptions.defaults());
                    return read.found() && value(kk).equals(new String(read.valueOrThrow(), UTF_8));
                }), "pre-failover key " + key(k) + " must be readable with its committed value");
            }

            // The reference edge client subscribes with all three endpoints, leader first, so its connection
            // lands on the node we will kill -- forcing a genuine cross-endpoint failover.
            try (ConfigdEdgeClient edge =
                         ConfigdEdgeClient.open(edgeConfig(edgeEndpointsLeaderFirst(servers, leader0)))) {
                Subscription sub = edge.subscribeFullStore(SubscribeOptions.defaults());
                List<ConfigChange> changes = new CopyOnWriteArrayList<>();
                sub.subscribe(recordingSubscriber(changes));
                sub.awaitHydrated(Duration.ofMillis(HYDRATE_MS));

                // Hydrated on the LEADER: the pre-failover keys arrived via the hydrate SNAPSHOT (a bulk state
                // load), NOT as change events -- so the change stream is empty until a post-hydrate delta.
                for (int k = 0; k < KEY_COUNT; k++) {
                    final int kk = k;
                    assertTrue(await(HYDRATE_MS, () -> sub.view().get(key(kk))
                                    .map(v -> value(kk).equals(new String(v, UTF_8))).orElse(false)),
                            "the leader's edge hydrate must carry pre-failover key " + key(k));
                }
                assertEquals(0, countPrefix(changes, "cfg/k"),
                        "pre-failover keys hydrate via the snapshot, not as change events");
                assertEquals(0, edge.reconnectCount(),
                        "the subscription connected to the leader and stayed stable (no pre-kill reconnect)");
                long cursorBefore = sub.cursor();

                // KILL the leader (crash equivalent: shutdown closes edge + HTTP + Raft transport). This drops
                // the edge connection AND forces a control-plane re-election.
                servers[leader0].shutdown();
                running.remove(servers[leader0]);
                System.out.println("[GC-FAILOVER] killed leader node " + leader0
                        + " (edge subscription's own node); cursor at kill=" + cursorBefore);

                // HTTP LEADER-FOLLOW: a put issued AFTER the kill transparently succeeds on the NEW leader --
                // the client rode the election window, following the real X-Leader-Hint.
                WriteOutcome postPut = http.blocking()
                        .put(POST_KEY, POST_VALUE.getBytes(UTF_8), WriteOptions.defaults());
                assertTrue(postPut.seq() > 0,
                        "a post-failover write must transparently commit on the NEW leader via the client's "
                                + "leader-follow (seq=" + postPut.seq() + ")");
                int leader1 = awaitStableLeaderExcluding(servers, leader0, FAILOVER_MS);
                assertTrue(leader1 >= 0, "the two survivors must elect a new stable leader after the kill");
                assertNotEquals(leader0, leader1, "the new leader must be a survivor, not the killed node");
                System.out.println("[GC-FAILOVER] HTTP leader-followed: post write committed at seq "
                        + postPut.seq() + "; new leader is node " + leader1);

                // EDGE FAILOVER-RESUME: the killed edge connection reconnects (cross-endpoint) to a survivor
                // and resumes FROM ITS CURSOR -- the post-failover write is delivered EXACTLY ONCE (no gap, no
                // duplicate), the cursor advances, and nothing already hydrated is re-delivered.
                assertTrue(await(DELIVER_MS, () -> edge.reconnectCount() >= 1),
                        "the edge subscription's node was killed — it must reconnect (rotate) to a survivor");
                assertTrue(await(DELIVER_MS, () -> sub.view().get(POST_KEY)
                                .map(v -> POST_VALUE.equals(new String(v, UTF_8))).orElse(false)),
                        "the resumed subscription must deliver the post-failover write on the survivor");
                assertTrue(await(DELIVER_MS, () -> count(changes, POST_KEY) >= 1),
                        "the post-failover write must be delivered to the resumed subscription as a change event");
                assertEquals(1, count(changes, POST_KEY),
                        "the post-failover write must be delivered EXACTLY ONCE across the cross-endpoint "
                                + "failover-resume — no duplicate");
                assertEquals(0, countPrefix(changes, "cfg/k"),
                        "the resume must NOT re-deliver an already-hydrated key — it TAIL-resumed from the cursor, "
                                + "it did not re-bootstrap");
                assertTrue(sub.cursor() > cursorBefore,
                        "the subscription cursor must advance monotonically across the failover-resume (before="
                                + cursorBefore + ", after=" + sub.cursor() + ") — no gap, no rewind");
                System.out.println("[GC-FAILOVER] edge failover-resume: reconnected (" + edge.reconnectCount()
                        + " reconnect) to a survivor, resumed from cursor " + cursorBefore + " -> " + sub.cursor()
                        + ", post write delivered exactly once");
            }

            for (int k = 0; k < KEY_COUNT; k++) {
                GetResult read = http.blocking().get(key(k), GetOptions.defaults());
                assertTrue(read.found(), "post-failover: pre-failover key " + key(k) + " must survive (no data loss)");
                assertArrayEquals(value(k).getBytes(UTF_8), read.valueOrThrow(),
                        "post-failover: key " + key(k) + " must retain its committed value");
            }
            // The post-failover HTTP read-back is also eventually-consistent: a default GET round-robins onto a
            // survivor that may be a few ms behind on APPLY, and a 404 is terminal (not retried) -- the same
            // read-after-write class the pre-failover convergence guards. Poll to a deadline, not a single get.
            assertTrue(await(DELIVER_MS, () -> {
                GetResult post = http.blocking().get(POST_KEY, GetOptions.defaults());
                return post.found() && POST_VALUE.equals(new String(post.valueOrThrow(), UTF_8));
            }), "the post-failover write must be readable through the client after the failover");
        }
        System.out.println("[GC-FAILOVER] PASS: reference client followed a real leader failover — HTTP "
                + "leader-follow + edge cross-endpoint resume-from-cursor (exactly once) + " + KEY_COUNT
                + " pre-failover keys intact");
    }

    private ConfigdServer[] bootCluster(Path root) throws Exception {
        // One shared cluster signing key, kept outside every node's data dir and pre-created up front, so the
        // concurrent boots never race to mint it.
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        SigningKeyStore.loadOrCreate(signingKey);

        int[] bindPorts = reserveDistinctPorts(NODES);
        ConfigdServer[] servers = new ConfigdServer[NODES];
        for (int i = 0; i < NODES; i++) {
            servers[i] = ConfigdServer.start(nodeConfig(i, bindPorts, root.resolve("node-" + i), signingKey));
            running.add(servers[i]);
        }
        return servers;
    }

    private ServerConfig nodeConfig(int nodeId, int[] bindPorts, Path dataDir, Path signingKey) {
        StringBuilder peers = new StringBuilder();
        StringBuilder peerAddrs = new StringBuilder();
        for (int j = 0; j < NODES; j++) {
            if (j == nodeId) {
                continue;
            }
            if (peers.length() > 0) {
                peers.append(',');
                peerAddrs.append(',');
            }
            peers.append(j);
            peerAddrs.append(j).append("=127.0.0.1:").append(bindPorts[j]);
        }
        return ServerConfig.parse(new String[]{
                "--node-id", Integer.toString(nodeId),
                "--data-dir", dataDir.toString(),
                "--peers", peers.toString(),
                "--peer-addresses", peerAddrs.toString(),
                "--bind-port", Integer.toString(bindPorts[nodeId]),
                "--api-port", "0",   // ephemeral HTTP control plane
                "--edge-port", "0",  // ephemeral edge fan-out plane
                "--signing-key-file", signingKey.toString(),
        });
    }

    /** A full {@code NodeId to HTTP base URI} map for all three nodes, so the client's LeaderRouter can follow
     *  {@code X-Leader-Hint}s (a bare NodeId is resolved ONLY through this operator map -- anti-SSRF). */
    private static NodeEndpoints httpEndpoints(ConfigdServer[] servers) {
        Map<Integer, URI> byId = new LinkedHashMap<>();
        for (int i = 0; i < servers.length; i++) {
            byId.put(i, URI.create("http://127.0.0.1:" + servers[i].apiPort()));
        }
        return NodeEndpoints.ofMap(byId);
    }

    /** The edge endpoints ordered LEADER FIRST: {@code EdgeSession.nextEndpoint} is round-robin, so the initial
     *  subscription connects to the leader (index 0) and a post-kill reconnect rotates to a survivor. */
    private static List<ServerAddress> edgeEndpointsLeaderFirst(ConfigdServer[] servers, int leader) {
        List<ServerAddress> addrs = new ArrayList<>(servers.length);
        addrs.add(new ServerAddress("127.0.0.1", servers[leader].fanOutServer().localPort())); // index 0 = leader
        for (int i = 0; i < servers.length; i++) {
            if (i != leader) {
                addrs.add(new ServerAddress("127.0.0.1", servers[i].fanOutServer().localPort()));
            }
        }
        return addrs;
    }

    /** The reference HTTP client with a generous retry budget so a post-kill write rides the whole election. */
    private static ConfigdHttpClient httpClient(NodeEndpoints endpoints) {
        return ConfigdHttpClient.builder()
                .endpoints(endpoints)
                .allowPlaintext(true) // auth OFF, plaintext loopback: no credential needed (open gate)
                .retryPolicy(new RetryPolicy(Duration.ofMillis(50), Duration.ofSeconds(1), 100))
                .requestTimeout(Duration.ofSeconds(2)) // a dead endpoint refuses fast; bound a genuine hang
                .build();
    }

    /** The reference edge client with ALL cluster edge endpoints; on a connection drop it rotates to the next
     *  and the Subscription re-subscribes at its persisted resume cursor (an in-memory cursor across a running
     *  session; a durable FileCursorStore under {@code dataDir} is only needed for cross-RESTART resume). */
    private static ConfigdClientConfig edgeConfig(List<ServerAddress> endpoints) {
        return ConfigdClientConfig.builder()
                .endpoints(endpoints)
                .allowPlaintext(true)
                .trustUnverified() // plaintext SUBSCRIBE trusts the snapshot (auth OFF admits the full-store feed)
                .retryPolicy(new RetryPolicy(Duration.ofMillis(50), Duration.ofMillis(500), 15))
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

    // Leadership and replication observation, deadline-polled: monitorView() is the safe way to read Raft
    // state from off the owner thread, which is what these polling helpers rely on.

    /** The sole node reporting LEADER for {@code GROUP} (excluding {@code excluded}), or -1 (none / split). */
    private static int singleLeader(ConfigdServer[] servers, int excluded) {
        int leader = -1;
        for (int i = 0; i < servers.length; i++) {
            if (i == excluded) {
                continue;
            }
            RaftNode node = servers[i].driver().getGroup(GROUP);
            if (node != null && node.monitorView().role() == RaftRole.LEADER) {
                if (leader >= 0) {
                    return -1; // two leaders observed (transient) -- not settled
                }
                leader = i;
            }
        }
        return leader;
    }

    private static int awaitStableLeaderExcluding(ConfigdServer[] servers, int excluded, long budgetMs)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        int candidate = -1;
        int stable = 0;
        while (System.nanoTime() < end) {
            int leader = singleLeader(servers, excluded);
            if (leader >= 0 && leader == candidate) {
                if (++stable >= STABLE_OBSERVATIONS) {
                    return leader;
                }
            } else {
                candidate = leader;
                stable = (leader >= 0) ? 1 : 0;
            }
            Thread.sleep(POLL_MS);
        }
        return -1;
    }

    private static long appliedIndex(ConfigdServer server, int gid) {
        RaftNode node = server.driver().getGroup(gid);
        return node == null ? -1 : node.monitorView().lastApplied();
    }

    /** Polls until all nodes report the SAME positive applied index for {@code gid} (the batch replicated). */
    private static boolean awaitConvergence(ConfigdServer[] servers, int gid, long budgetMs)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < end) {
            long a0 = appliedIndex(servers[0], gid);
            boolean converged = a0 > 0;
            for (int i = 1; i < servers.length && converged; i++) {
                if (appliedIndex(servers[i], gid) != a0) {
                    converged = false;
                }
            }
            if (converged) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return false;
    }

    private static String appliedSnapshot(ConfigdServer[] servers) {
        StringBuilder sb = new StringBuilder("applied[");
        for (int i = 0; i < servers.length; i++) {
            sb.append(i).append('=').append(appliedIndex(servers[i], GROUP)).append(' ');
        }
        return sb.append(']').toString();
    }

    private static final String POST_KEY = "cfg/post";
    private static final String POST_VALUE = "after-failover";

    private static String key(int k) {
        return "cfg/k" + k;
    }

    private static String value(int k) {
        return "v" + k;
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

    private static int[] reserveDistinctPorts(int n) throws Exception {
        ServerSocket[] socks = new ServerSocket[n];
        int[] ports = new int[n];
        try {
            for (int i = 0; i < n; i++) {
                socks[i] = new ServerSocket(0);
                ports[i] = socks[i].getLocalPort();
            }
        } finally {
            for (ServerSocket s : socks) {
                if (s != null) {
                    s.close();
                }
            }
        }
        return ports;
    }
}
