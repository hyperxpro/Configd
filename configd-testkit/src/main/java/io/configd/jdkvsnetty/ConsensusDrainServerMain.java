package io.configd.jdkvsnetty;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * End-to-end consensus-send head-to-head (the "does Netty's off-heap {@code ByteBuf} avoid a
 * write-path copy that matters?" question) - the <b>receiver</b>. Runs in a SEPARATE process from
 * {@link ConsensusSendE2EMain} so only the sender's allocation is measured. It just drains and
 * discards raw TCP bytes as fast as it can (the wire bytes are identical from both sender stacks,
 * so a byte sink suffices - no framing needed). Accepts connections sequentially: the sender opens
 * one connection for its JDK phase, another for its Netty phase.
 *
 * <pre>java --enable-preview -cp benchmarks.jar io.configd.jdkvsnetty.ConsensusDrainServerMain &lt;port&gt;</pre>
 */
public final class ConsensusDrainServerMain {

    private ConsensusDrainServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            System.out.println("DRAIN_READY port=" + ss.getLocalPort());
            System.out.flush();
            byte[] sink = new byte[1 << 16];
            while (true) {
                try (Socket s = ss.accept()) {
                    s.setTcpNoDelay(true);
                    InputStream in = s.getInputStream();
                    while (in.read(sink) >= 0) {
                        // discard - we only need to keep the sender's socket from back-pressuring
                    }
                } catch (IOException e) {
                    // peer closed; loop and accept the next phase's connection
                }
            }
        }
    }
}
