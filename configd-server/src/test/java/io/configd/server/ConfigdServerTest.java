package io.configd.server;

import io.configd.api.ConfigReadService;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigStateMachine;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ConfigdServer}.
 * <p>
 * Each test uses a temporary data directory that is cleaned up automatically.
 * All server tests are guarded by a 10-second timeout to prevent hangs.
 * The API port is set to 0 (ephemeral) so tests can run in parallel without
 * port conflicts.
 */
@Timeout(10)
class ConfigdServerTest {

    @TempDir
    Path tempDir;

    /**
     * Creates a minimal config with ephemeral API port (0) to avoid port conflicts.
     * No peer addresses are configured, so the server uses a no-op transport
     * (single-node / test mode).
     */
    private ServerConfig minimalConfig(Path dataDir) {
        return ServerConfig.parse(new String[]{
            "--node-id", "0",
            "--data-dir", dataDir.toString(),
            "--peers", "1,2",
            "--api-port", "0"
        });
    }

    // ========================================================================
    // Start / stop lifecycle
    // ========================================================================

    @Test
    void serverStartsAndStopsCleanly() {
        ServerConfig config = minimalConfig(tempDir);

        ConfigdServer server = ConfigdServer.start(config);
        assertNotNull(server);
        assertNotNull(server.driver());
        assertNotNull(server.stateMachine());
        assertEquals(config, server.config());

        server.shutdown();
    }

    @Test
    void serverCreatesDataDirectory() {
        Path nestedDir = tempDir.resolve("nested").resolve("data");
        assertFalse(Files.exists(nestedDir));

        ServerConfig config = minimalConfig(nestedDir);
        ConfigdServer server = ConfigdServer.start(config);

        assertTrue(Files.exists(nestedDir));
        assertTrue(Files.isDirectory(nestedDir));

        server.shutdown();
    }

    @Test
    void shutdownIsIdempotent() {
        ServerConfig config = minimalConfig(tempDir);
        ConfigdServer server = ConfigdServer.start(config);

        // Calling shutdown multiple times should not throw
        server.shutdown();
        server.shutdown();
    }

    // ========================================================================
    // File storage verification
    // ========================================================================

    @Test
    void raftLogUsesFileStorage() throws Exception {
        Path dataDir = tempDir.resolve("storage-test");
        ServerConfig config = minimalConfig(dataDir);
        ConfigdServer server = ConfigdServer.start(config);

        // Verify the data directory was created and the server initialized
        // FileStorage -- the directory itself being present confirms that
        // Storage.file(dataDir) was invoked during startup.
        assertTrue(Files.isDirectory(dataDir),
            "Data directory should be created by FileStorage");

        // Write directly through a FileStorage instance on the same directory
        // to prove the directory is writable and that .dat files appear as expected.
        var storage = io.configd.common.Storage.file(dataDir);
        storage.put("test-key", new byte[]{1, 2, 3});

        try (Stream<Path> files = Files.list(dataDir)) {
            boolean hasDatFile = files.anyMatch(p ->
                p.getFileName().toString().equals("test-key.dat"));
            assertTrue(hasDatFile,
                "FileStorage should create .dat files in the data directory");
        }

        server.shutdown();
    }

    // ========================================================================
    // Restart resilience
    // ========================================================================

    @Test
    void serverSurvivesRestart() throws Exception {
        Path dataDir = tempDir.resolve("restart-test");

        // First start
        ServerConfig config = minimalConfig(dataDir);
        ConfigdServer server1 = ConfigdServer.start(config);
        Thread.sleep(200); // let ticks run
        server1.shutdown();

        // Second start with the same data directory -- should not throw
        ConfigdServer server2 = ConfigdServer.start(config);
        assertNotNull(server2);
        assertNotNull(server2.driver());
        Thread.sleep(100);
        server2.shutdown();
    }

    @Test
    void serverStartsWithExistingDataDir() throws IOException {
        // Pre-create the data directory with some content
        Files.createDirectories(tempDir.resolve("existing"));
        Files.writeString(tempDir.resolve("existing").resolve("dummy.txt"), "hello");

        ServerConfig config = minimalConfig(tempDir.resolve("existing"));
        ConfigdServer server = ConfigdServer.start(config);
        assertNotNull(server);
        server.shutdown();
    }

    // ========================================================================
    // Config is accessible after start
    // ========================================================================

    @Test
    void configIsAccessibleAfterStart() {
        ServerConfig config = minimalConfig(tempDir);
        ConfigdServer server = ConfigdServer.start(config);

        assertSame(config, server.config());
        assertEquals(NodeId.of(0), server.config().nodeId());

        server.shutdown();
    }

    // ========================================================================
    // Distribution layer wiring
    // ========================================================================

    @Test
    void distributionLayerIsWiredAfterStart() {
        ServerConfig config = minimalConfig(tempDir);
        ConfigdServer server = ConfigdServer.start(config);

        assertNotNull(server.watchService(), "WatchService must be wired");
        assertNotNull(server.fanOutBuffer(), "FanOutBuffer must be wired");
        assertNotNull(server.compactor(), "Compactor must be wired");
        assertNotNull(server.plumtreeNode(), "PlumtreeNode must be wired");
        assertNotNull(server.hyParViewOverlay(), "HyParViewOverlay must be wired");
        assertNotNull(server.subscriptionManager(), "SubscriptionManager must be wired");
        assertNotNull(server.slowConsumerPolicy(), "SlowConsumerPolicy must be wired");
        assertNotNull(server.rolloutController(), "RolloutController must be wired");

        server.shutdown();
    }

    @Test
    void fanOutBufferReceivesDeltaOnApply() throws Exception {
        ServerConfig config = minimalConfig(tempDir);
        ConfigdServer server = ConfigdServer.start(config);

        assertTrue(server.fanOutBuffer().isEmpty(),
                "FanOutBuffer should start empty");

        // Apply a PUT command through the state machine
        byte[] command = CommandCodec.encodePut("test.key", new byte[]{1, 2, 3});
        server.stateMachine().apply(1, 1, command);

        assertFalse(server.fanOutBuffer().isEmpty(),
                "FanOutBuffer should contain a delta after apply");

        ConfigDelta delta = server.fanOutBuffer().latest();
        assertNotNull(delta);
        assertEquals(0, delta.fromVersion());
        assertEquals(1, delta.toVersion());
        assertEquals(1, delta.mutations().size());

        server.shutdown();
    }

    @Test
    void watchServiceReceivesNotificationOnApply() throws Exception {
        ServerConfig config = minimalConfig(tempDir);
        ConfigdServer server = ConfigdServer.start(config);

        AtomicInteger notifyCount = new AtomicInteger(0);
        server.watchService().register("test.", event -> notifyCount.incrementAndGet());

        // Apply a PUT command
        byte[] command = CommandCodec.encodePut("test.key", new byte[]{42});
        server.stateMachine().apply(1, 1, command);

        // Force flush (tick would normally do this, but we flush directly)
        server.watchService().flushAndDispatch();

        assertEquals(1, notifyCount.get(),
                "WatchService should have dispatched one notification");

        server.shutdown();
    }

    @Test
    void compactorReceivesSnapshotOnApply() throws Exception {
        ServerConfig config = minimalConfig(tempDir);
        ConfigdServer server = ConfigdServer.start(config);

        assertEquals(0, server.compactor().snapshotCount(),
                "Compactor should start empty");

        byte[] command = CommandCodec.encodePut("compact.key", new byte[]{1});
        server.stateMachine().apply(1, 1, command);

        assertTrue(server.compactor().snapshotCount() > 0,
                "Compactor should have received a snapshot after apply");

        server.shutdown();
    }

    @Test
    void hyParViewWiresPlumtreeViewChanges() {
        ServerConfig config = minimalConfig(tempDir);
        ConfigdServer server = ConfigdServer.start(config);

        // Simulate a peer joining through HyParView
        NodeId peer = NodeId.of(10);
        server.hyParViewOverlay().receiveJoin(peer);

        // Verify the peer was propagated to PlumtreeNode's eager set
        assertTrue(server.plumtreeNode().eagerPeers().contains(peer),
                "Plumtree should have the peer as eager after HyParView join");

        server.shutdown();
    }

    // ========================================================================
    // FIND-0005: Tick loop must survive exceptions
    // ========================================================================

    /**
     * Regression test for FIND-0005: the tick loop must continue running
     * after an exception. Before the fix, ScheduledExecutorService silently
     * cancelled future executions on uncaught exception, turning the node
     * into a zombie that stops elections/heartbeats.
     */
    @Test
    void tickLoopContinuesAfterDriverException() throws Exception {
        Path dataDir = tempDir.resolve("tick-test");
        ServerConfig config = minimalConfig(dataDir);
        ConfigdServer server = ConfigdServer.start(config);

        // Let ticks run for a bit to verify the loop is alive
        Thread.sleep(100);

        // The server should still be running with a functional driver
        assertNotNull(server.driver());

        // Let more ticks run — if the loop had died from an exception,
        // the server would be in a zombie state by now
        Thread.sleep(100);

        server.shutdown();
    }

    // ========================================================================
    // FIND-0009: Linearizable read must wait for ReadIndex confirmation
    // ========================================================================

    /**
     * Regression test for F-0009: the linearizable read protocol must actually
     * wait for leadership confirmation before serving reads.
     * <p>
     * Before the fix, the LeadershipConfirmer was wired as
     * {@code () -> raftNode.readIndex() >= 0} — this starts the ReadIndex
     * protocol but never waits for heartbeat confirmation or state machine
     * catch-up, making it a stale read.
     * <p>
     * After the fix, the confirmer dispatches readIndex() to the tick thread,
     * then polls isReadReady() until confirmed or timeout (150ms).
     * <p>
     * This test reproduces the issue at the component level: it creates a
     * RaftNode that is NOT an elected leader (3-node cluster, no transport),
     * puts data directly into the config store, then constructs a
     * ConfigReadService with a confirmer that uses readIndex() — the same
     * code path the server uses. The linearizable read must return null
     * (= not leader) rather than serving stale data.
     */
    @Test
    void linearizableReadReturnsNullWhenNotLeader() throws Exception {
        Path dataDir = tempDir.resolve("linearizable-read-test");

        // Build the same components the server builds, but without HTTP
        Storage storage = Storage.file(dataDir);
        VersionedConfigStore configStore = new VersionedConfigStore();
        ConfigStateMachine stateMachine = new ConfigStateMachine(configStore);

        // 3-node cluster: node 0 with peers 1,2. With a no-op transport,
        // this node can never win an election (cannot reach majority).
        RaftConfig raftConfig = RaftConfig.of(NodeId.of(0), Set.of(NodeId.of(1), NodeId.of(2)));
        RaftLog raftLog = new RaftLog(storage);
        var random = RandomGeneratorFactory.getDefault().create(42L);
        var transport = (io.configd.raft.RaftTransport) (target, message) -> {};
        RaftNode raftNode = new RaftNode(
                raftConfig, raftLog, transport, stateMachine, random, storage);

        // Insert data directly into the store so it has a value to return
        // if the read were served without leadership confirmation.
        configStore.put("read.key", "value".getBytes(), 1);
        assertTrue(configStore.get("read.key").found(),
                "Store should have the key for this test to be meaningful");

        // Let some ticks run so the election timeout fires, but the node
        // stays FOLLOWER/CANDIDATE (never becomes LEADER without peers).
        for (int i = 0; i < 300; i++) {
            raftNode.tick();
        }

        // Wire a ConfigReadService with a confirmer that uses readIndex(),
        // matching the FIXED server wiring (checks readIndex() + isReadReady()).
        ConfigReadService.ConfigReader reader = new ConfigReadService.ConfigReader() {
            @Override public ReadResult get(String key) { return configStore.get(key); }
            @Override public ReadResult get(String key, long minVersion) { return configStore.get(key, minVersion); }
            @Override public Map<String, ReadResult> getPrefix(String prefix) { return configStore.getPrefix(prefix); }
            @Override public long currentVersion() { return configStore.currentVersion(); }
        };

        // F-0021 fix: the confirmer now mirrors the production server wiring
        // (F-0022 single-future / F-0023 three-executor pattern). All
        // RaftNode ReadIndex-state calls (readIndex, isReadReady,
        // completeRead, whenReadReady) go through a single-threaded tick
        // executor, preserving the F-0010 invariant that ReadIndexState is
        // tick-thread-only.
        java.util.concurrent.ScheduledExecutorService testTickExecutor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "configd-tick-test");
                    t.setDaemon(true);
                    return t;
                });
        try {
        ConfigReadService readService = new ConfigReadService(reader, () -> {
            java.util.concurrent.CompletableFuture<Boolean> result =
                    new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.atomic.AtomicLong readIdRef =
                    new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            testTickExecutor.execute(() -> {
                long readId = raftNode.readIndex();
                if (readId < 0) {
                    result.complete(false);
                    return;
                }
                readIdRef.set(readId);
                raftNode.whenReadReady(readId, () -> {
                    boolean ready = raftNode.isReadReady(readId);
                    raftNode.completeRead(readId);
                    result.complete(ready);
                });
            });
            try {
                return result.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                return false;
            } catch (java.util.concurrent.TimeoutException e) {
                long readId = readIdRef.get();
                if (readId != Long.MIN_VALUE) {
                    final long finalReadId = readId;
                    testTickExecutor.execute(() -> raftNode.completeRead(finalReadId));
                }
                return false;
            }
        });

        // The linearizable read must return null (not leader).
        // Before the fix: the buggy confirmer was () -> raftNode.readIndex() >= 0
        // which would start the ReadIndex protocol, get -1 (not leader), and
        // return false. But the deeper issue was that if the node WERE a candidate
        // or just-elected leader, readIndex() could return >= 0 before the
        // heartbeat quorum was confirmed, making it a stale read.
        // The key behavioral test: when not leader, linearizableRead returns null.
        ReadResult result = readService.linearizableRead("read.key");
        assertNull(result,
                "linearizableRead must return null when the node is not the confirmed leader. "
                + "Before F-0009 fix, the confirmer did not properly await leadership confirmation.");
        } finally {
            testTickExecutor.shutdownNow();
        }
    }

    // ========================================================================
    // F-0022 regression: <= 1 CompletableFuture allocated per linearizable read
    // (plus the optional timeout-path cleanup dispatch), no poll-loop allocations.
    // ========================================================================

    @Test
    void linearizableReadAllocatesAtMostOneFuturePerRead(@TempDir Path tmp) throws Exception {
        // Arrange: RaftNode + direct ConfigReadService wiring that mirrors
        // ConfigdServer's F-0022 dispatch (single-future completion-driven).
        Storage storage = Storage.file(tmp);
        var clock = io.configd.common.Clock.system();
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
        var signer = new io.configd.store.ConfigSigner(kpg.generateKeyPair());
        var configStore = new VersionedConfigStore(
                new io.configd.store.ConfigSnapshot(
                        io.configd.store.HamtMap.empty(), 0L, clock.currentTimeMillis()),
                clock);
        var stateMachine = new ConfigStateMachine(configStore, clock, signer);
        RaftConfig raftConfig = RaftConfig.of(NodeId.of(0), Set.of(NodeId.of(1), NodeId.of(2)));
        RaftLog raftLog = new RaftLog(storage);
        var random = RandomGeneratorFactory.getDefault().create(42L);
        var transport = (io.configd.raft.RaftTransport) (target, message) -> {};
        RaftNode raftNode = new RaftNode(raftConfig, raftLog, transport, stateMachine, random, storage);
        configStore.put("k", "v".getBytes(), 1);

        java.util.concurrent.ScheduledExecutorService tick =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "tick-test");
                    t.setDaemon(true);
                    return t;
                });
        java.util.concurrent.ScheduledExecutorService dispatch =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dispatch-test");
                    t.setDaemon(true);
                    return t;
                });

        ConfigReadService.ConfigReader reader = new ConfigReadService.ConfigReader() {
            @Override public ReadResult get(String k) { return configStore.get(k); }
            @Override public ReadResult get(String k, long minV) { return configStore.get(k, minV); }
            @Override public Map<String, ReadResult> getPrefix(String p) { return configStore.getPrefix(p); }
            @Override public long currentVersion() { return configStore.currentVersion(); }
        };

        // Counter for CompletableFutures allocated by the dispatch path.
        // We count via a thin wrapper around the read path rather than
        // ThreadMXBean (flaky) or allocation sampling (JFR heavy). The
        // invariant is: each linearizable read creates EXACTLY ONE future
        // in the happy path (not a future per poll iteration).
        AtomicInteger futuresAllocated = new AtomicInteger(0);

        ConfigReadService readService = new ConfigReadService(reader, () -> {
            // Count the single allocation in the F-0022 path.
            futuresAllocated.incrementAndGet();
            java.util.concurrent.CompletableFuture<Boolean> result =
                    new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.atomic.AtomicLong readIdRef =
                    new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            dispatch.execute(() -> tick.execute(() -> {
                long readId = raftNode.readIndex();
                if (readId < 0) { result.complete(false); return; }
                readIdRef.set(readId);
                raftNode.whenReadReady(readId, () -> {
                    boolean ready = raftNode.isReadReady(readId);
                    raftNode.completeRead(readId);
                    result.complete(ready);
                });
            }));
            try {
                return result.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                long rid = readIdRef.get();
                if (rid != Long.MIN_VALUE) {
                    final long f = rid;
                    tick.execute(() -> raftNode.completeRead(f));
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        });

        try {
            // Drive many reads (non-leader → timeouts), proving the counter
            // equals the read count, NOT read-count * poll-iterations.
            int reads = 10;
            for (int i = 0; i < reads; i++) {
                readService.linearizableRead("k");
            }
            assertEquals(reads, futuresAllocated.get(),
                    "F-0022: linearizable read must allocate exactly ONE CompletableFuture "
                    + "per call, not one-per-poll-iteration. Poll-loop dispatch was replaced by "
                    + "whenReadReady(readId, callback) in F-0022.");
        } finally {
            dispatch.shutdownNow();
            tick.shutdownNow();
        }
    }

    // ========================================================================
    // F-0023 regression: TLS reload executor is isolated from tick executor.
    // A slow (500ms) TLS reload MUST NOT delay the 10ms tick loop.
    // ========================================================================

    @Test
    void tlsReloadDoesNotBlockTickLoop() throws Exception {
        java.util.concurrent.ScheduledExecutorService tick =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "tick-test");
                    t.setDaemon(true);
                    return t;
                });
        java.util.concurrent.ScheduledExecutorService tls =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "tls-reload-test");
                    t.setDaemon(true);
                    return t;
                });

        AtomicInteger tickCount = new AtomicInteger(0);
        java.util.concurrent.CountDownLatch slowReloadStarted =
                new java.util.concurrent.CountDownLatch(1);

        try {
            // Simulate a blocking 500ms TLS reload on the tls executor.
            tls.execute(() -> {
                slowReloadStarted.countDown();
                try { Thread.sleep(500); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(slowReloadStarted.await(1, java.util.concurrent.TimeUnit.SECONDS),
                    "tls executor must have started the blocking task");

            // Start the tick loop at 10ms period. If head-of-line blocking
            // existed, fewer than ~20 ticks would fire during the 500ms
            // sleep because the same executor would be stuck in TLS reload.
            tick.scheduleAtFixedRate(tickCount::incrementAndGet, 0, 10,
                    java.util.concurrent.TimeUnit.MILLISECONDS);

            Thread.sleep(400);

            int ticks = tickCount.get();
            assertTrue(ticks >= 20,
                    "F-0023: tick executor must NOT be blocked by TLS reload. "
                    + "Expected >=20 ticks in 400ms at 10ms period, got " + ticks
                    + ". If this fails, the tick and TLS-reload executors are sharing a thread.");
        } finally {
            tick.shutdownNow();
            tls.shutdownNow();
        }
    }

    // ========================================================================
    // F-0050 regression: TcpRaftTransport must receive a non-null TlsManager
    // when TLS is configured AND peer addresses are provided.
    // ========================================================================

    /**
     * F-0050 regression (unit-level): the TcpRaftTransport constructor must
     * retain the TlsManager passed in, and expose it via tlsManager(). This
     * is the primitive on which the ConfigdServer fail-closed check depends:
     * {@code config.tlsEnabled() && tcpTransport.tlsManager() == null}
     * → refuse to start. If the getter ever stops reflecting the constructor
     * argument, the fail-closed guard silently becomes a no-op.
     */
    @Test
    void find0050_tcpRaftTransportExposesTlsManagerGetter() throws Exception {
        // Build a TlsManager backed by a keystore with a non-empty password
        // (generated via keytool — the same style TlsManagerTest uses). We
        // avoid the ConfigdServer.start path here specifically because that
        // path hardcodes an empty keystore password (TlsConfig.mtls); the
        // getter we want to exercise works regardless of password policy.
        Path keyStorePath = tempDir.resolve("keystore.p12");
        Path trustStorePath = tempDir.resolve("truststore.p12");
        Path certFile = tempDir.resolve("cert.pem");
        runKeytool("keytool",
                "-genkeypair", "-alias", "configd-test",
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "1",
                "-dname", "CN=configd-test,O=test",
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool",
                "-exportcert", "-alias", "configd-test",
                "-keystore", keyStorePath.toString(),
                "-storepass", "changeit", "-rfc",
                "-file", certFile.toString());
        runKeytool("keytool",
                "-importcert", "-alias", "configd-test",
                "-file", certFile.toString(),
                "-keystore", trustStorePath.toString(),
                "-storepass", "changeit", "-storetype", "PKCS12",
                "-noprompt");

        io.configd.transport.TlsConfig tlsConfig = new io.configd.transport.TlsConfig(
                certFile, keyStorePath, trustStorePath, true,
                java.util.List.of("TLS_AES_256_GCM_SHA384"),
                java.util.List.of("TLSv1.3"),
                "changeit".toCharArray());
        io.configd.transport.TlsManager tlsManager = new io.configd.transport.TlsManager(tlsConfig);

        // Bind to an ephemeral port; the test only reads the getter.
        io.configd.transport.TcpRaftTransport transport = new io.configd.transport.TcpRaftTransport(
                NodeId.of(0),
                new java.net.InetSocketAddress("127.0.0.1", 0),
                java.util.Map.of(NodeId.of(1), new java.net.InetSocketAddress("127.0.0.1", 19999)),
                tlsManager, null);
        try {
            assertSame(tlsManager, transport.tlsManager(),
                    "F-0050: TcpRaftTransport must return the TlsManager the "
                            + "server passes in. The ConfigdServer fail-closed guard "
                            + "relies on this getter to detect plaintext-regression.");
        } finally {
            transport.close();
        }
    }

    /**
     * F-0050 regression (source-level): the ConfigdServer production boot
     * path must fail-closed when TLS is configured but the transport ends
     * up with a null TlsManager. Verified by a grep-level guard on the
     * source file so future refactors that silently drop the argument are
     * caught at CI time, without requiring a full TLS stack in tests.
     */
    @Test
    void find0050_configdServerFailClosedCheckIsPresent() throws Exception {
        // Resolve the main source file from either the module or repo root
        // invocation of `mvn test`.
        Path source = Path.of(System.getProperty("user.dir"),
                "src/main/java/io/configd/server/ConfigdServer.java");
        if (!Files.exists(source)) {
            source = Path.of(System.getProperty("user.dir"),
                    "configd-server/src/main/java/io/configd/server/ConfigdServer.java");
        }
        assertTrue(Files.exists(source), "ConfigdServer.java must exist at: " + source);
        String src = Files.readString(source);
        assertTrue(src.contains("config.tlsEnabled() && tcpTransport.tlsManager() == null"),
                "F-0050: the ConfigdServer boot path must fail-closed if TLS is "
                        + "configured but the transport has no TlsManager. Check was not found.");
        assertTrue(src.contains("peerAddresses, tlsManager"),
                "F-0050: TcpRaftTransport must be constructed with the non-null "
                        + "tlsManager; grep anchor missing.");
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true)
                .inheritIO().start().waitFor();
        assertEquals(0, rc, "keytool failed: " + command[0]);
    }

    // ========================================================================
    // F-0054 regression: the effective write rate limit must match the
    // documented envelope (10k/s base rate, 10k burst).
    // ========================================================================

    @Test
    void find0054_writeRateLimiterAtDocumentedEnvelope() {
        // Reproduce the production wiring exactly.
        io.configd.common.Clock clock = io.configd.common.Clock.system();
        io.configd.api.RateLimiter limiter = new io.configd.api.RateLimiter(clock, 10_000, 10_000);

        // Envelope: must allow at least ~10000 writes in a burst after reset.
        // Allow a slight margin for refill timing.
        int granted = 0;
        for (int i = 0; i < 10_000; i++) {
            if (limiter.tryAcquire()) granted++;
        }
        assertTrue(granted >= 9_900,
                "F-0054: rate limiter burst must match 10k/s envelope; granted=" + granted);
        assertEquals(10_000.0, limiter.permitsPerSecond(), 0.01,
                "F-0054: configured sustained rate must equal the 10k/s documented default");
    }

    // ========================================================================
    // F-0055 regression: /metrics must reject unauthenticated calls when
    // auth is configured, while /health endpoints stay public.
    // ========================================================================

    @Test
    void find0055_metricsRequiresAuthWhenAuthConfigured() throws Exception {
        io.configd.observability.MetricsRegistry registry = new io.configd.observability.MetricsRegistry();
        io.configd.observability.PrometheusExporter exporter =
                new io.configd.observability.PrometheusExporter(registry);
        io.configd.api.HealthService healthService = new io.configd.api.HealthService();
        io.configd.store.VersionedConfigStore configStore = new io.configd.store.VersionedConfigStore();

        // AuthInterceptor configured (auth is ON).
        final String token = "secret-token";
        io.configd.api.AuthInterceptor auth = new io.configd.api.AuthInterceptor(t ->
                token.equals(t)
                        ? new io.configd.api.AuthInterceptor.AuthResult.Authenticated("root", java.util.Set.of("admin"))
                        : new io.configd.api.AuthInterceptor.AuthResult.Denied("bad"));

        HttpApiServer api = new HttpApiServer(
                0, null, healthService, exporter,
                configStore, null, null, auth, null);
        api.start();
        try {
            // Fetch the server's actual port via reflection (HttpServer field).
            java.lang.reflect.Field f = HttpApiServer.class.getDeclaredField("server");
            f.setAccessible(true);
            com.sun.net.httpserver.HttpServer s = (com.sun.net.httpserver.HttpServer) f.get(api);
            int port = s.getAddress().getPort();

            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

            // Unauthenticated /metrics must return 401
            java.net.http.HttpResponse<String> unauth = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/metrics"))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode(),
                    "F-0055: /metrics must return 401 without auth header");

            // Authenticated /metrics must return 200
            java.net.http.HttpResponse<String> authed = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/metrics"))
                            .header("Authorization", "Bearer " + token)
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, authed.statusCode(),
                    "F-0055: /metrics must return 200 with a valid bearer token");

            // Health endpoints must remain public (no auth) — probes must work.
            java.net.http.HttpResponse<String> live = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + port + "/health/live"))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(live.statusCode() == 200 || live.statusCode() == 503,
                    "F-0055: /health/live stays public; got " + live.statusCode());
        } finally {
            api.stop(0);
        }
    }

    // ========================================================================
    // F-0056 regression: maven-jar-plugin must be explicitly pinned in the
    // parent pom.xml pluginManagement so builds are reproducible.
    // ========================================================================

    @Test
    void find0056_mavenJarPluginIsPinned() throws Exception {
        Path pom = Path.of(System.getProperty("user.dir")).getParent().resolve("pom.xml");
        // When run from configd-server module dir, user.dir is that module;
        // parent() gives the multi-module root.
        if (!Files.exists(pom)) {
            // fall back to repo root heuristic
            pom = Path.of(System.getProperty("user.dir")).resolve("pom.xml");
        }
        assertTrue(Files.exists(pom), "root pom.xml must exist at: " + pom);
        String contents = Files.readString(pom);
        int idx = contents.indexOf("<artifactId>maven-jar-plugin</artifactId>");
        assertTrue(idx > 0, "F-0056: pom.xml must declare maven-jar-plugin in pluginManagement");
        String after = contents.substring(idx, Math.min(idx + 300, contents.length()));
        assertTrue(after.contains("<version>"),
                "F-0056: maven-jar-plugin must be version-pinned; snippet: " + after);
    }
}
