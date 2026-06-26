package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.InboundMessage;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.TcpRaftTransport;
import io.configd.transport.TlsManager;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@link AbstractRaftTransportContract} bound to the JDK {@link TcpRaftTransport} (the incumbent
 * consensus transport). Proves the contract harness faithfully encodes the JDK baseline that the
 * Netty subclasses must match — if any folded leg fails here, the transcription is wrong, not the
 * assertion.
 */
class JdkRaftTransportContractTest extends AbstractRaftTransportContract {

    @Override
    protected RaftTransportEndpoint newEndpoint(NodeId self, InetSocketAddress bind,
                                                Map<NodeId, InetSocketAddress> peers,
                                                TlsManager tls, Consumer<InboundMessage> handler) {
        return new TcpRaftTransport(self, bind, peers, tls, handler);
    }
}
