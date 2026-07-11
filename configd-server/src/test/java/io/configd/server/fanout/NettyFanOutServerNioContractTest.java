package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.netty.NettyTransport;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;
import io.configd.transport.TlsManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@link AbstractFanOutServerContract} bound to {@code NettyFanOutServer} with the transport tier
 * forced to NIO: the entire behaviour contract must hold even on the portable NIO transport, not only
 * the native io_uring/epoll tiers.
 *
 * <p>{@code NettyTransport.select()} reads {@link NettyTransport#PROP} in the {@code NettyFanOutServer}
 * constructor, so the property must be set before any {@link #newServer} call. It is set both in a
 * static initializer (runs at class-load, before JUnit's per-container {@code @BeforeAll}) and in
 * {@code @BeforeAll} (belt-and-suspenders against ordering), and cleared in {@code @AfterAll}. The
 * prior value is preserved and restored so a parallel/forked run cannot leak the override.
 */
class NettyFanOutServerNioContractTest extends AbstractFanOutServerContract {

    private static String priorTransport;

    static {
        // Class-load time: force NIO before any constructor in this class can run select().
        priorTransport = System.setProperty(NettyTransport.PROP, "nio");
    }

    @BeforeAll
    static void forceNioTransport() {
        // Idempotent re-assertion in case the static value was disturbed; capture the prior value only
        // if the static block did not already (null = was unset, which the restore handles).
        String existing = System.setProperty(NettyTransport.PROP, "nio");
        if (priorTransport == null && existing != null && !"nio".equals(existing)) {
            priorTransport = existing;
        }
    }

    @AfterAll
    static void restoreTransport() {
        if (priorTransport == null) {
            System.clearProperty(NettyTransport.PROP);
        } else {
            System.setProperty(NettyTransport.PROP, priorTransport);
        }
    }

    @Override
    protected FanOutEndpoint newServer(InetSocketAddress bind, TlsManager tls,
                                       CommitNotificationSource source, ReplaySource replay,
                                       FanOutConfig config, int queueFrames, int maxSessions,
                                       SlowConsumerGovernor governor,
                                       RegistryFanOutSessionMetrics metrics, Clock clock) {
        return new NettyFanOutServer(bind, tls, source, replay, config, queueFrames, maxSessions,
                governor, metrics, clock);
    }

    /** Sanity: with the property forced, the constructed Netty endpoint actually selected NIO. */
    @Test
    void transportTierIsNio() {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        FanOutEndpoint endpoint = newServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, new FanOutBuffer(16),
                new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY),
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                FanOutServer.DEFAULT_MAX_SESSIONS,
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics),
                metrics, Clock.system());
        try {
            assertEquals("nio", ((NettyFanOutServer) endpoint).transportTier(),
                    "the forced-NIO subclass must select the NIO transport tier");
        } finally {
            endpoint.close();
        }
    }
}
