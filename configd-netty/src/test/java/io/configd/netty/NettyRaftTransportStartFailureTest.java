package io.configd.netty;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.configd.common.NodeId;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A Netty transport whose {@code start()} fails to bind must clean up the NON-DAEMON event-loop
 * threads it just created, so a failed start leaks nothing. Were it not, a component that throws
 * mid-boot would leave live non-daemon event loops behind - the class of leak that turns a
 * fail-closed boot into a hang (the loops keep the JVM alive while nothing serves). This drives
 * many failed starts against an already-occupied port and proves the live non-daemon thread count
 * returns to baseline; a per-start leak of boss + worker threads would accumulate without bound.
 */
class NettyRaftTransportStartFailureTest {

    @Test
    @Timeout(60)
    void repeatedFailedStartDoesNotLeakEventLoopThreads() throws Exception {
        try (ServerSocket hog = new ServerSocket()) {
            hog.setReuseAddress(true);
            hog.bind(new InetSocketAddress("127.0.0.1", 0)); // actively listening -> a second bind gets EADDRINUSE
            InetSocketAddress occupied = new InetSocketAddress("127.0.0.1", hog.getLocalPort());

            long baseline = nonDaemonThreadCount();
            int attempts = 20;
            for (int i = 0; i < attempts; i++) {
                NettyRaftTransport t = new NettyRaftTransport(NodeId.of(0), occupied, Map.of(), null, m -> { });
                assertThrows(Exception.class, t::start);
            }

            awaitThreadsSettle(baseline, attempts);
        }
    }

    /**
     * Waits until the non-daemon thread count returns near baseline (each failed start's
     * {@code shutdownGracefully(0, 2s)} has drained), or fails if the loops leaked. A broken cleanup leaks
     * boss(1) + worker(&ge;2) non-daemon threads per attempt (&ge; 3 &times; attempts); a correct one settles
     * back to ~baseline, so the threshold sits far below even one attempt's worth of leak.
     */
    private static void awaitThreadsSettle(long baseline, int attempts) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        long delta;
        do {
            Thread.sleep(200);
            delta = nonDaemonThreadCount() - baseline;
            if (delta <= 8) {
                return;
            }
        } while (System.nanoTime() < deadlineNanos);
        throw new AssertionError("a failed NettyRaftTransport.start() leaked event-loop threads: " + delta
                + " extra non-daemon threads after " + attempts + " failed starts (a per-start boss+worker leak "
                + "would be ~" + (3L * attempts) + "); the mid-start cleanup regressed");
    }

    private static long nonDaemonThreadCount() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> !t.isDaemon()).count();
    }
}
