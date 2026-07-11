package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.transport.TlsManager;

import java.net.InetSocketAddress;

/**
 * The {@link AbstractFanOutServerContract} bound to the Netty {@code NettyFanOutServer} with the
 * transport tier auto-selected (Epoll, falling back to NIO; io_uring is opt-in) at construction. Proves
 * the migrated transport reproduces every behaviour the JDK subclass does on whatever native tier the
 * host offers.
 */
class NettyFanOutServerContractTest extends AbstractFanOutServerContract {

    @Override
    protected FanOutEndpoint newServer(InetSocketAddress bind, TlsManager tls,
                                       CommitNotificationSource source, ReplaySource replay,
                                       FanOutConfig config, int queueFrames, int maxSessions,
                                       SlowConsumerGovernor governor,
                                       RegistryFanOutSessionMetrics metrics, Clock clock) {
        return new NettyFanOutServer(bind, tls, source, replay, config, queueFrames, maxSessions,
                governor, metrics, clock);
    }
}
