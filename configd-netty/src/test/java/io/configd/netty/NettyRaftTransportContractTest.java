package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.InboundMessage;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.TlsManager;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@link AbstractRaftTransportContract} bound to the Netty {@link NettyRaftTransport} with the
 * transport tier auto-selected (Epoll -&gt; NIO; io_uring opt-in) at construction. Proves the
 * consensus transport reproduces every behaviour the JDK subclass does on whatever native tier the
 * host offers.
 */
class NettyRaftTransportContractTest extends AbstractRaftTransportContract {

    @Override
    protected RaftTransportEndpoint newEndpoint(NodeId self, InetSocketAddress bind,
                                                Map<NodeId, InetSocketAddress> peers,
                                                TlsManager tls, Consumer<InboundMessage> handler) {
        return new NettyRaftTransport(self, bind, peers, tls, handler);
    }
}
