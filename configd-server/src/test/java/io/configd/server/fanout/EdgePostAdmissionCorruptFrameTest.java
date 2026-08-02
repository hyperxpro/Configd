package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A POST-admission decode error must close with the frame's REAL {@link ErrorCode} on BOTH transports.
 * The Netty {@code FanOutConnection.exceptionCaught} used to test {@code instanceof CodecException} on the
 * raw cause, which misses the {@code DecoderException} in which Netty wraps a decoder throw, so it closed
 * such a frame with the catch-all {@code SERVER_SHUTDOWN} instead of e.g. {@code FRAME_CORRUPT}. The JDK
 * reader catches the {@code CodecException} directly and already reported the real code; this proves both
 * transports agree (the fix unwraps the cause chain). No correct client depends on the old catch-all close
 * code.
 */
@Timeout(60)
class EdgePostAdmissionCorruptFrameTest {

    private FanOutEndpoint server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void postSubscribeCorruptFrameClosesFrameCorrupt(boolean netty) throws Exception {
        int port = startPlaintextServer(netty);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore("edge-corrupt", 0L);
            readUntil(edge, EdgeFrame.SubscribeOk.class);

            // A well-formed length prefix + version + type, but a flipped CRC trailer -> the decoder
            // throws CodecException(FRAME_CORRUPT). On Netty that arrives at exceptionCaught wrapped in a
            // DecoderException; the fix unwraps it so the close carries FRAME_CORRUPT, not SERVER_SHUTDOWN.
            byte[] frame = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(5L));
            frame[frame.length - 1] ^= (byte) 0xFF;
            edge.sendRaw(frame);

            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.FRAME_CORRUPT, close.code(),
                    "a post-admission corrupt frame must close with the real code on both transports");
        }
    }

    @Test
    void jdkPostSubscribeCorruptFrameClosesFrameCorrupt() throws Exception {
        postSubscribeCorruptFrameClosesFrameCorrupt(false);
    }

    @Test
    void nettyPostSubscribeCorruptFrameClosesFrameCorrupt() throws Exception {
        postSubscribeCorruptFrameClosesFrameCorrupt(true);
    }


    private int startPlaintextServer(boolean netty) throws IOException {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        CommitNotificationSource source = buffer;
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        FanOutConfig config = FanOutConfig.defaults();
        Clock clock = Clock.system();
        server = netty
                ? new NettyFanOutServer(bind, null, source, replay, config,
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        metrics, clock)
                : new FanOutServer(bind, null, source, replay, config,
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        metrics, clock);
        server.start();
        return server.localPort();
    }

    private static EdgeFrame readUntil(EdgeProtocolClient edge, Class<? extends EdgeFrame> type)
            throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed while waiting for " + type.getSimpleName());
            }
            if (type.isInstance(f)) {
                return f;
            }
        }
        fail("did not receive a " + type.getSimpleName() + " within the deadline");
        return null;
    }
}
