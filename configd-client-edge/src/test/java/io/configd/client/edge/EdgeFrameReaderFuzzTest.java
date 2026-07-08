package io.configd.client.edge;

import io.configd.client.edge.session.EdgeFrameReader;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * The client mirror of the server's wire-security fuzz: an arbitrary byte stream fed to the client's frame
 * reader must <b>only</b> ever yield a decoded frame, a clean end-of-stream, or a mapped
 * {@link EdgeFrameCodec.CodecException} — never hang, never OOM (the length prefix is bounded before any
 * allocation), and never leak a different exception type. A small frame cap keeps a lying length from forcing
 * a large transient allocation, which is exactly the bound the reader enforces.
 */
class EdgeFrameReaderFuzzTest {

    private static final int SMALL_CAP = 4096;

    @Property(tries = 2000)
    void randomBytesOnlyYieldFrameOrEofOrCodecException(@ForAll @Size(max = 4096) byte[] data) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            EdgeFrameReader.readFrame(in, null, SMALL_CAP); // returns a frame or null; both are fine
        } catch (EdgeFrameCodec.CodecException expected) {
            // the sole structural failure mode — clean
        } catch (IOException io) {
            // an in-memory stream never throws a genuine IOException, and a truncation is remapped to
            // FRAME_CORRUPT, so reaching here is a bug.
            throw new AssertionError("unexpected IOException from an in-memory stream", io);
        }
    }

    @Test
    void aWellFormedFrameStillDecodes() throws IOException {
        byte[] wire = EdgeFrameCodec.encode(new EdgeFrame.Heartbeat(7L, 9L));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(wire));
        EdgeFrame frame = EdgeFrameReader.readFrame(in, null, EdgeFrameCodec.MAX_EDGE_FRAME_SIZE);
        EdgeFrame.Heartbeat hb = Assertions.assertInstanceOf(EdgeFrame.Heartbeat.class, frame);
        Assertions.assertEquals(7L, hb.latestSeq());
        Assertions.assertEquals(9L, hb.serverNowMillis());
        Assertions.assertNull(EdgeFrameReader.readFrame(in, null, EdgeFrameCodec.MAX_EDGE_FRAME_SIZE),
                "a clean end-of-stream at a frame boundary returns null");
    }
}
