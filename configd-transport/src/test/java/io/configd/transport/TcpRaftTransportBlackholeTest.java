package io.configd.transport;

import io.configd.common.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RR-002 (P0) regression tests: a send to a black-holed peer must NEVER park the
 * calling thread on connect / TLS handshake.
 * <p>
 * In production the caller is the single {@code configd-tick} thread, which owns
 * all RaftNode state (R-01). Before the fix, {@link TcpRaftTransport#send} reached
 * {@code createClientSocket} on the caller's thread and blocked on a timeout-less
 * {@code new Socket(addr, port)} / {@code startHandshake()} for the full OS SYN
 * timeout (~127 s) when the peer black-holed inbound SYNs — freezing the node.
 * <p>
 * These tests use {@value #BLACKHOLE_HOST} (a non-routable host whose SYNs are
 * silently dropped) so the OS connect parks exactly as in the production failure
 * mode, with no sudo/iptables required.
 * <p>
 * NOTE: this class deliberately does NOT carry {@link TcpRaftTransportTest}'s
 * class-level {@code @Timeout(10)} budget. Pre-fix, the worker thread we observe
 * stays parked for ~127 s; we bound our OBSERVATION of it to {@link #OBSERVE_MS}
 * (well under that) and fail the assertion rather than waiting out the SYN timeout.
 */
@Timeout(60)
class TcpRaftTransportBlackholeTest {

    /**
     * A non-routable destination on 10.255.255.0/24. SYNs sent here are dropped
     * (no RST), so {@code connect()} parks for the full OS SYN timeout — the same
     * behaviour an iptables {@code -j DROP} produces against a real peer.
     */
    private static final String BLACKHOLE_HOST = "10.255.255.1";
    private static final int BLACKHOLE_PORT = 9999;

    /** How long the production tick thread may be released within (the contract). */
    private static final long CALLER_RELEASE_BUDGET_MS = 2_000;

    /**
     * Bounded observation window for the worker thread. Must comfortably exceed
     * the release budget yet stay far below the ~127 s OS SYN timeout so a pre-fix
     * hang is observed (and failed) quickly instead of waiting it out.
     */
    private static final long OBSERVE_MS = 10_000;

    private final List<TcpRaftTransport> transports = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (TcpRaftTransport t : transports) {
            t.close();
        }
    }

    /**
     * DISCRIMINATING TEST (RR-002): a {@code send} to a black-holed peer must
     * release the calling thread within {@link #CALLER_RELEASE_BUDGET_MS}.
     * <p>
     * Pre-fix: the worker parks in {@code connect()} / {@code startHandshake()};
     * the {@code returned} latch never fires within the observation window, and
     * the test fails after capturing a stack snippet proving the park location.
     * <p>
     * Post-fix: connection establishment is off the caller's thread and the
     * caller returns immediately (enqueue-or-drop), so the latch fires fast.
     */
    @Test
    void callingThreadReleasedWhenPeerBlackholed() throws Exception {
        NodeId self = NodeId.of(1);
        NodeId blackholed = NodeId.of(2);

        TcpRaftTransport transport = newTransport(
                self,
                Map.of(blackholed, new InetSocketAddress(BLACKHOLE_HOST, BLACKHOLE_PORT)));
        transport.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.APPEND_ENTRIES, 1, 5L, "probe".getBytes());

        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                // Stands in for the configd-tick thread calling transport.send().
                transport.send(blackholed, frame);
            } catch (Throwable t) {
                unexpected.set(t);
            } finally {
                returned.countDown();
            }
        }, "rr002-caller");
        worker.setDaemon(true);
        worker.start();

        boolean released = returned.await(CALLER_RELEASE_BUDGET_MS, TimeUnit.MILLISECONDS);
        if (!released) {
            // Bounded observation: confirm the worker is parked, snapshot its
            // stack as evidence, then fail WITHOUT waiting out the ~127 s SYN
            // timeout. (Post-fix this branch is never taken.)
            String stack = stackSnippet(worker);
            // Give it the rest of the observation window only to enrich the
            // diagnostic — the assertion has already conceptually failed.
            returned.await(OBSERVE_MS - CALLER_RELEASE_BUDGET_MS, TimeUnit.MILLISECONDS);
            fail("RR-002: send() to a black-holed peer did NOT release the calling "
                    + "thread within " + CALLER_RELEASE_BUDGET_MS + "ms — the tick "
                    + "thread is parked on connect/handshake. Worker stack:\n" + stack);
        }

        if (unexpected.get() != null) {
            throw new AssertionError(
                    "send() to a black-holed peer threw instead of returning quietly; "
                            + "Raft tolerates message loss, so a drop must be silent at this layer",
                    unexpected.get());
        }
    }

    /**
     * A second, slightly different angle: many ticks in a row toward a black-holed
     * peer must not accumulate into a multi-second stall. This catches a fix that
     * bounds the FIRST connect but still blocks subsequent sends behind it.
     */
    @Test
    void repeatedSendsToBlackholedPeerStayBounded() throws Exception {
        NodeId self = NodeId.of(1);
        NodeId blackholed = NodeId.of(2);

        TcpRaftTransport transport = newTransport(
                self,
                Map.of(blackholed, new InetSocketAddress(BLACKHOLE_HOST, BLACKHOLE_PORT)));
        transport.start();

        FrameCodec.Frame frame = new FrameCodec.Frame(
                MessageType.HEARTBEAT, 1, 1L, "hb".getBytes());

        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                transport.send(blackholed, frame);
            }
            done.countDown();
        }, "rr002-repeated-caller");
        worker.setDaemon(true);
        worker.start();

        boolean finished = done.await(CALLER_RELEASE_BUDGET_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            String stack = stackSnippet(worker);
            fail("RR-002: 20 sends to a black-holed peer did NOT complete within "
                    + CALLER_RELEASE_BUDGET_MS + "ms — establishment is still on the "
                    + "caller's thread. Worker stack:\n" + stack);
        }
        assertTrue(finished);
    }

    private TcpRaftTransport newTransport(NodeId self, Map<NodeId, InetSocketAddress> peers) {
        TcpRaftTransport transport = new TcpRaftTransport(
                self, new InetSocketAddress("127.0.0.1", 0), peers, null, msg -> {});
        transports.add(transport);
        return transport;
    }

    /** Renders the connect/handshake frames of a thread's current stack as evidence. */
    private static String stackSnippet(Thread t) {
        StringBuilder sb = new StringBuilder();
        sb.append("  state=").append(t.getState()).append('\n');
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("    at ").append(e).append('\n');
        }
        return sb.toString();
    }
}
