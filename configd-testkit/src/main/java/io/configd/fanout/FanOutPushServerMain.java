package io.configd.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.observability.MetricsRegistry;
import io.configd.server.fanout.NettyFanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fan-out head-to-head - the <b>server side</b>. Boots the production
 * {@link NettyFanOutServer} over a synthetic {@link FanOutBuffer} source and publishes
 * notifications on command so the fan-out push path can be measured under load. The
 * transport tier is forced by {@code -Dconfigd.netty.transport=io_uring|epoll}; a
 * {@code tier=} that doesn't match the forced value is the silent-fallback trap, and is
 * asserted against by the driver.
 *
 * <p>Each published {@link CommitNotification} is stamped with
 * {@code System.currentTimeMillis()} (not {@code nanoTime}, which has no cross-process
 * meaning) so the out-of-JVM subscriber can compute a one-way delivery latency.
 *
 * <h2>Control protocol (line-based, one client)</h2>
 * <ul>
 *   <li>{@code GO <count> <valueBytes> <ratePerSec(0=max)>} - publish {@code count}
 *       notifications, paced to {@code ratePerSec} or as fast as possible; replies
 *       {@code PUBLISHED <count> fromSeq=<s>}.</li>
 *   <li>{@code QUIT} - close the server and exit.</li>
 * </ul>
 */
public final class FanOutPushServerMain {

    private FanOutPushServerMain() {
    }

    /** Real wall/mono clock (heartbeat cadence + staleness need time to advance, unlike edge-read). */
    private static final class SystemClock implements Clock {
        @Override public long currentTimeMillis() { return System.currentTimeMillis(); }
        @Override public long nanoTime() { return System.nanoTime(); }
    }

    public static void main(String[] args) throws Exception {
        int edgePort = Integer.parseInt(args[0]);
        int controlPort = Integer.parseInt(args[1]);

        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        // Large ring so the publisher never laps a prompt subscriber (no eviction -> no GAP/snapshot).
        FanOutBuffer buffer = new FanOutBuffer(1 << 20);
        AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>(ConfigSnapshot.EMPTY);

        // Benchmark isolation: the slow-consumer governor's demotion->snapshot->reconnect path (a
                // session-policy layer above the transport) would inject run-to-run noise identically
                // regardless of transport, adding nothing to an io_uring-vs-epoll comparison. We relax
                // the transport-queue and ack-lag thresholds so a keeping-up subscriber isn't demoted
                // under strace overhead. Override with -Dconfigd.fanout.* to exercise the production
                // policy.
        int transportQueueFrames = Integer.getInteger("configd.fanout.transportQueueFrames", 65_536);
        FanOutConfig config = new FanOutConfig(
                Integer.getInteger("configd.fanout.queueFrames", 65_536),  // session offered-not-acked bound
                80,                                                        // queueWarnPct (unused at this size)
                64,                                                        // batchMaxNotifications (production default)
                262_144,                                                   // batchMaxBytes (256 KiB, production default)
                Long.getLong("configd.fanout.ackLagDemoteSeqs", 1_000_000_000L),
                250L, 5L, 1_048_576);

        NettyFanOutServer server = new NettyFanOutServer(
                new InetSocketAddress("127.0.0.1", edgePort),
                null,                                   // plaintext (the head-to-head isolates transport)
                buffer,
                new SnapshotReplaySource(snapshot::get),
                config,
                transportQueueFrames,
                metrics,
                new SystemClock());
        server.start();
        System.out.println("READY edgePort=" + server.localPort() + " tier=" + server.transportTier());
        System.out.flush();

        long seq = 1;
        try (ServerSocket control = new ServerSocket(controlPort);
             Socket conn = control.accept();
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(conn.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                switch (p[0]) {
                    case "GO" -> {
                        long count = Long.parseLong(p[1]);
                        int valueBytes = Integer.parseInt(p[2]);
                        long rate = p.length > 3 ? Long.parseLong(p[3]) : 0L;
                        byte[] value = new byte[valueBytes];
                        for (int i = 0; i < valueBytes; i++) {
                            value[i] = (byte) i;
                        }
                        long intervalNanos = rate > 0 ? (1_000_000_000L / rate) : 0L;
                        long fromSeq = seq;
                        long t0 = System.nanoTime();
                        for (long i = 0; i < count; i++) {
                            ConfigDelta delta = new ConfigDelta(seq - 1, seq,
                                    List.of(new ConfigMutation.Put("svc/" + (i % 256), value)));
                            buffer.publish(new CommitNotification(seq, System.currentTimeMillis(), delta));
                            seq++;
                            if (intervalNanos > 0) {
                                long next = t0 + (i + 1) * intervalNanos;
                                while (System.nanoTime() < next) {
                                    Thread.onSpinWait();
                                }
                            }
                        }
                        out.println("PUBLISHED " + count + " fromSeq=" + fromSeq);
                    }
                    case "QUIT" -> {
                        out.println("BYE");
                        server.close();
                        return;
                    }
                    default -> out.println("ERR unknown:" + p[0]);
                }
            }
        }
        server.close();
    }
}
