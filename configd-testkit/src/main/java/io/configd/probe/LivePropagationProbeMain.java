package io.configd.probe;

import io.configd.common.NodeId;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.server.ConfigdServer;
import io.configd.server.ServerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live-mode driver for the {@link PropagationProbe} (Session 3 Phase V2 — charter §3 V2):
 * a CLI that measures real, wall-clock propagation latency on this box, with honest
 * caveats. It is the live counterpart to the simulator probe; Session 5 uses it for the
 * real p99 &lt; 500 ms target — here it must merely work and produce honest numbers.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li><b>{@code --mode boundary}</b> (implemented): starts a single in-process
 *       single-node {@link ConfigdServer}, drives {@code --writes} HTTP PUTs over
 *       loopback (parsing {@code Committed: seq=S}), and tails
 *       {@link ConfigdServer#commitNotificationSource()} with the ADR-0034 consumer loop
 *       (read since cursor; replay on GAP). It records publish ts =
 *       {@link CommitNotification#commitTimestampMillis()} (the leader-assigned commit
 *       timestamp, contract §2 / ADR-0035 §2) and visible ts =
 *       {@link System#currentTimeMillis()} at consumption. This measures
 *       <b>commit→boundary-visibility</b> wall time — the only propagation that exists
 *       today (RR-001).</li>
 *   <li><b>{@code --mode edge}</b> (stub): exits with code {@value #EXIT_EDGE_NOT_BUILT}
 *       because the C2/C6 edge processes do not yet exist.</li>
 * </ul>
 *
 * <h2>How to run</h2>
 * The testkit {@code benchmarks.jar} keeps {@code org.openjdk.jmh.Main} as its manifest
 * main class (unchanged), so this probe is run by naming the class explicitly on a
 * classpath that includes the shaded jar:
 * <pre>{@code
 *   java --enable-preview \
 *     -cp configd-testkit/target/benchmarks.jar \
 *     io.configd.probe.LivePropagationProbeMain --mode boundary --writes 200
 * }</pre>
 *
 * <p><b>Wall-clock honesty.</b> The output header records the 2-vCPU throttled hardware
 * caveat and the boundary-only scope verbatim. No sleeps are used as synchronization —
 * every wait polls a condition against a deadline.
 *
 * @see PropagationProbe
 */
public final class LivePropagationProbeMain {

    /** Exit code for {@code --mode edge}: the edge data plane is not yet built. */
    public static final int EXIT_EDGE_NOT_BUILT = 2;

    /** The single boundary observer id (one in-process consumer of the boundary). */
    private static final int BOUNDARY_OBSERVER_ID = 0;

    /** {@code Committed: seq=<S>} — the ADR-0033 200 body the consumer parses. */
    private static final Pattern COMMITTED_SEQ = Pattern.compile("Committed: seq=(\\d+)");

    private LivePropagationProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Options opts = Options.parse(args);
        switch (opts.mode) {
            case "boundary" -> System.exit(runBoundary(opts));
            case "edge" -> {
                System.err.println(
                        "edge mode requires the C2/C6 edge processes — not yet built");
                System.exit(EXIT_EDGE_NOT_BUILT);
            }
            default -> {
                System.err.println("unknown --mode: " + opts.mode
                        + " (expected 'boundary' or 'edge')");
                System.exit(64); // EX_USAGE
            }
        }
    }

    // -----------------------------------------------------------------------
    // boundary mode
    // -----------------------------------------------------------------------

    private static int runBoundary(Options opts) throws Exception {
        printHeader(opts);

        Path dataDir = Files.createTempDirectory("configd-probe-");
        ServerConfig config = singleNodeConfig(opts, dataDir);
        ConfigdServer server = ConfigdServer.start(config);

        PropagationProbe probe = new PropagationProbe();
        AtomicBoolean draining = new AtomicBoolean(false);
        // Tail the ADR-0034 boundary in-process on its own thread. The consumer loop
        // records publish ts = commit timestamp and visible ts = now at consumption.
        ConsumerLoop consumer = new ConsumerLoop(
                server.commitNotificationSource(), server.replaySource(), probe, draining);
        Thread consumerThread = new Thread(consumer, "probe-boundary-consumer");
        consumerThread.setDaemon(true);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        String base = "http://127.0.0.1:" + opts.apiPort;

        try {
            awaitLeader(http, base, opts);
            consumerThread.start();

            long lastSeq = driveWrites(http, base, opts);

            // Wait — by polling, never a fixed sleep — until the consumer has observed
            // every committed seq (cursor reached lastSeq) or the deadline expires.
            awaitConsumed(probe, lastSeq, opts);
        } finally {
            // Signal the consumer to finish its current pass and stop, then shut down.
            draining.set(true);
            consumer.stop();
            consumerThread.join(Duration.ofSeconds(5).toMillis());
            server.shutdown();
            deleteRecursive(dataDir);
        }

        System.out.println();
        System.out.println(probe.report());
        System.out.flush();
        return 0;
    }

    /**
     * Drives {@code opts.writes} HTTP PUTs over loopback, each to a distinct key, and
     * returns the highest committed seq observed. Retries an individual write across
     * transient leader churn (the smoke gate's robustness pattern), polling a deadline.
     */
    private static long driveWrites(HttpClient http, String base, Options opts)
            throws Exception {
        long maxSeq = -1;
        for (int i = 0; i < opts.writes; i++) {
            String key = opts.keyPrefix + i;
            String body = "probe-value-" + i;
            long seq = putCommitted(http, base, key, body, opts);
            maxSeq = Math.max(maxSeq, seq);
        }
        System.out.println("drove " + opts.writes + " committed writes; highest seq=" + maxSeq);
        return maxSeq;
    }

    /**
     * PUTs a single key and returns the committed seq, retrying across transient 503/504
     * (leader churn / in-flight) until {@code opts.writeDeadline} elapses. No sleep is
     * used as synchronization — the loop re-issues immediately and bails on a deadline.
     */
    private static long putCommitted(HttpClient http, String base, String key, String body,
            Options opts) throws Exception {
        long deadline = System.nanoTime() + opts.writeDeadline.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/v1/config/" + key))
                        .timeout(opts.writeDeadline)
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    Matcher m = COMMITTED_SEQ.matcher(resp.body());
                    if (m.find()) {
                        return Long.parseLong(m.group(1));
                    }
                    throw new IllegalStateException(
                            "200 without parseable seq: " + resp.body());
                }
                // 503/504/429 — transient (leader churn / in-flight / backpressure). Retry.
            } catch (IOException | InterruptedException e) {
                last = (e instanceof InterruptedException) ? new Exception(e) : (Exception) e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IllegalStateException(
                "write to '" + key + "' not committed within " + opts.writeDeadline, last);
    }

    /**
     * Polls {@code /health/ready} until the single node has elected itself leader (200),
     * bounded by {@code opts.writeDeadline}. No fixed sleep — re-polls on a deadline.
     */
    private static void awaitLeader(HttpClient http, String base, Options opts) throws Exception {
        long deadline = System.nanoTime() + opts.writeDeadline.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/health/ready"))
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // server still binding — re-poll
            }
        }
        throw new IllegalStateException("single node did not become ready within "
                + opts.writeDeadline);
    }

    /**
     * Polls the probe until the boundary observer has recorded every committed seq
     * (matched count reaches {@code expectedCount}) or {@code opts.drainDeadline} elapses.
     * The consumer thread does the work; this only observes its progress.
     */
    private static void awaitConsumed(PropagationProbe probe, long lastSeq, Options opts) {
        if (lastSeq < 0) {
            return; // nothing committed (no writes) — nothing to await
        }
        long deadline = System.nanoTime() + opts.drainDeadline.toNanos();
        while (System.nanoTime() < deadline) {
            if (probe.count(BOUNDARY_OBSERVER_ID) >= opts.writes) {
                return;
            }
            Thread.onSpinWait();
        }
        // Deadline hit — the report below shows the partial count honestly.
    }

    /**
     * The ADR-0034 boundary consumer loop, run on its own thread. Holds a cursor; each
     * pass calls {@code readSince(cursor)}; on {@code Ok} it records each notification
     * (publish ts = commit timestamp, visible ts = now) and advances the cursor; on
     * {@code Gap} it replays a snapshot from the {@link ReplaySource} (the consumer
     * advances its cursor to the replay seq — a snapshot carries no per-seq commit
     * timestamps to sample, so replayed seqs are not double-counted as latency samples).
     */
    private static final class ConsumerLoop implements Runnable {
        private final CommitNotificationSource source;
        private final ReplaySource replaySource;
        private final PropagationProbe probe;
        private final AtomicBoolean draining;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private long cursor;

        ConsumerLoop(CommitNotificationSource source, ReplaySource replaySource,
                PropagationProbe probe, AtomicBoolean draining) {
            this.source = source;
            this.replaySource = replaySource;
            this.probe = probe;
            this.draining = draining;
        }

        void stop() {
            running.set(false);
        }

        @Override
        public void run() {
            while (running.get()) {
                boolean progressed = drainOnce();
                // Once draining is requested, do one final empty pass then exit so no
                // committed seq is left unconsumed.
                if (draining.get() && !progressed) {
                    drainOnce();
                    return;
                }
                if (!progressed) {
                    Thread.onSpinWait();
                }
            }
        }

        /** One readSince pass; returns true if any notification was consumed/recovered. */
        private boolean drainOnce() {
            CommitNotificationSource.Result result = source.readSince(cursor);
            return switch (result) {
                case CommitNotificationSource.Result.Ok ok -> {
                    List<CommitNotification> ns = ok.notifications();
                    for (CommitNotification n : ns) {
                        long now = System.currentTimeMillis();
                        probe.recordPublished(n.seq(), n.commitTimestampMillis());
                        probe.recordVisible(BOUNDARY_OBSERVER_ID, n.seq(), now);
                        cursor = n.seq();
                    }
                    yield !ns.isEmpty();
                }
                case CommitNotificationSource.Result.Gap gap -> {
                    // Recover via the authoritative replay seam (ADR-0034 handoff step 2):
                    // apply the snapshot wholesale and resume tailing from its seq floor.
                    ReplaySource.Replay replay = replaySource.replayFromSnapshot();
                    cursor = replay.seq();
                    yield true;
                }
            };
        }
    }

    // -----------------------------------------------------------------------
    // config + output helpers
    // -----------------------------------------------------------------------

    /** Single-node config: empty peers (self-elect), no peer addresses (no-op transport), TLS off. */
    private static ServerConfig singleNodeConfig(Options opts, Path dataDir) {
        return new ServerConfig(
                NodeId.of(opts.nodeId),
                dataDir,
                Set.of(),                 // empty peers → single-node, self-elects
                "127.0.0.1",
                opts.bindPort,
                opts.apiPort,
                null, null, null,          // TLS off
                null,                      // no auth token
                Map.<NodeId, InetSocketAddress>of(), // no peer addresses → no-op transport
                null,                      // signing key kept under data dir
                Set.of("secure/"),         // default strong-read prefix
                null);                     // no C1 edge endpoint in the boundary probe
    }

    private static void printHeader(Options opts) {
        System.out.println("=== Configd propagation probe — LIVE BOUNDARY MODE ===");
        System.out.println("BOUNDARY-ONLY MODE: no edge data plane exists yet (RR-001); this "
                + "measures commit->boundary visibility, not edge staleness. Edge mode lands "
                + "with C6.");
        System.out.println("HARDWARE CAVEAT: 2-vCPU throttled box — wall-clock numbers are "
                + "honest but not a performance target (Session 5 owns p99 < 500 ms).");
        System.out.println("staleness sample = visible_ts(System.currentTimeMillis at consume) "
                + "- publish_ts(CommitNotification.commitTimestampMillis, ADR-0035 §2 / contract §2 INV-S1)");
        System.out.println("writes=" + opts.writes + " key-prefix=" + opts.keyPrefix
                + " api-port=" + opts.apiPort + " node-id=" + opts.nodeId);
        System.out.println();
    }

    private static void deleteRecursive(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort temp cleanup
                        }
                    });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    // -----------------------------------------------------------------------
    // CLI options
    // -----------------------------------------------------------------------

    /** Parsed CLI options with the charter-specified defaults. */
    private static final class Options {
        String mode = "boundary";
        int writes = 200;
        String keyPrefix = "probe/";
        int apiPort = 18080;
        int bindPort = 19090;
        int nodeId = 1;
        Duration writeDeadline = Duration.ofSeconds(10);
        Duration drainDeadline = Duration.ofSeconds(30);

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--mode" -> o.mode = requireNext(args, ++i, "--mode");
                    case "--writes" -> o.writes = Integer.parseInt(requireNext(args, ++i, "--writes"));
                    case "--key-prefix" -> o.keyPrefix = requireNext(args, ++i, "--key-prefix");
                    case "--api-port" -> o.apiPort = Integer.parseInt(requireNext(args, ++i, "--api-port"));
                    case "--bind-port" -> o.bindPort = Integer.parseInt(requireNext(args, ++i, "--bind-port"));
                    case "--node-id" -> o.nodeId = Integer.parseInt(requireNext(args, ++i, "--node-id"));
                    case "--write-deadline-ms" ->
                            o.writeDeadline = Duration.ofMillis(Long.parseLong(requireNext(args, ++i, "--write-deadline-ms")));
                    case "--drain-deadline-ms" ->
                            o.drainDeadline = Duration.ofMillis(Long.parseLong(requireNext(args, ++i, "--drain-deadline-ms")));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (o.writes < 0) {
                throw new IllegalArgumentException("--writes must be >= 0: " + o.writes);
            }
            return o;
        }

        private static String requireNext(String[] args, int i, String flag) {
            if (i >= args.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[i];
        }
    }
}
