package io.configd.netty;

import io.configd.common.NodeId;
import io.configd.transport.InboundMessage;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.TlsManager;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** NIO-fallback contract proof: forces tier to pure-Java NIO, the always-available floor. */
class NettyRaftTransportNioContractTest extends AbstractRaftTransportContract {

    @Override
    protected RaftTransportEndpoint newEndpoint(NodeId self, InetSocketAddress bind,
                                                Map<NodeId, InetSocketAddress> peers,
                                                TlsManager tls, Consumer<InboundMessage> handler) {
        // select() reads the forced tier in the NettyRaftTransport constructor; restore the property
        // immediately so it cannot leak into other tests (the chosen tier is now fixed on this
        // instance). NettyTransport is in this same package, so PROP is referenced directly.
        String saved = System.getProperty(NettyTransport.PROP);
        System.setProperty(NettyTransport.PROP, "nio");
        NettyRaftTransport transport;
        try {
            transport = new NettyRaftTransport(self, bind, peers, tls, handler);
        } finally {
            if (saved == null) {
                System.clearProperty(NettyTransport.PROP);
            } else {
                System.setProperty(NettyTransport.PROP, saved);
            }
        }
        assertEquals("nio", transport.transportTier(), "fallback contract must run on the NIO tier");
        return transport;
    }
}
