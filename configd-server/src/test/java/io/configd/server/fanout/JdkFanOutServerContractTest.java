package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.transport.TlsManager;

import java.net.InetSocketAddress;

/**
 * The {@link AbstractFanOutServerContract} bound to the JDK {@link FanOutServer} (the incumbent
 * transport). Proves the unchanged behaviour baseline that the Netty subclasses must match.
 */
class JdkFanOutServerContractTest extends AbstractFanOutServerContract {

    @Override
    protected FanOutEndpoint newServer(InetSocketAddress bind, TlsManager tls,
                                       CommitNotificationSource source, ReplaySource replay,
                                       FanOutConfig config, int queueFrames, int maxSessions,
                                       SlowConsumerGovernor governor,
                                       RegistryFanOutSessionMetrics metrics, Clock clock) {
        return new FanOutServer(bind, tls, source, replay, config, queueFrames, maxSessions,
                governor, metrics, clock);
    }
}
