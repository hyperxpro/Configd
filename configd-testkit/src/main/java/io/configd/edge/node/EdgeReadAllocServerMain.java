package io.configd.edge.node;

import com.sun.management.ThreadMXBean;
import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * <pre>
 *   java --enable-preview -cp benchmarks.jar io.configd.edge.node.EdgeReadAllocServerMain \
 *        &lt;jdk|netty&gt; &lt;httpPort&gt; &lt;controlPort&gt; &lt;keyCount&gt; &lt;valueBytes&gt;
 * </pre>
 */
public final class EdgeReadAllocServerMain {

    private EdgeReadAllocServerMain() {
    }

    /** A fixed clock: time never advances, so the edge core stays CURRENT (reads served 200). */
    private static final class FixedClock implements Clock {
        @Override public long currentTimeMillis() { return 1_000_000L; }
        @Override public long nanoTime() { return 1_000_000L * 1_000_000L; }
    }

    public static void main(String[] args) throws Exception {
        String which = args[0];
        int httpPort = Integer.parseInt(args[1]);
        int controlPort = Integer.parseInt(args[2]);
        int keyCount = Integer.parseInt(args[3]);
        int valueBytes = Integer.parseInt(args[4]);

        ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threadBean.isThreadAllocatedMemorySupported()) {
            System.out.println("RESULT error=thread-allocated-memory-unsupported");
            return;
        }
        threadBean.setThreadAllocatedMemoryEnabled(true);

        Clock clock = new FixedClock();
        MetricsRegistry registry = new MetricsRegistry();
        InvariantMonitor monitor = new InvariantMonitor(registry, false);
        EdgeNodeMetrics metrics = new EdgeNodeMetrics(registry);
        EdgeClientCore core = new EdgeClientCore(clock, monitor, metrics.implausibleCounter(),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        metrics.bind(core);

        byte[] value = new byte[valueBytes];
        for (int i = 0; i < valueBytes; i++) {
            value[i] = (byte) i;
        }
        for (int i = 0; i < keyCount; i++) {
            long seq = i + 1;
            ConfigDelta delta = new ConfigDelta(seq - 1, seq,
                    List.of(new ConfigMutation.Put("config/svc-" + i, value)));
            core.onFrame(new EdgeFrame.Notify(List.of(
                    new CommitNotification(seq, 1_000_000L, delta))));
        }

        Runnable stopper;
        int boundPort;
        boolean epoll = false;
        String tier = "-";
        if ("netty".equals(which)) {
            // The head-to-head prototype: a minimal read-only shell, not the production pipeline.
            NettyEdgeReadServer server =
                    new NettyEdgeReadServer(httpPort, core, StrongReadKeyClass.DEFAULT, metrics);
            server.start();
            boundPort = server.port();
            epoll = server.usingEpoll();
            tier = epoll ? "epoll" : "nio";
            stopper = server::stop;
        } else if ("netty-prod".equals(which)) {
            NettyEdgeHttpServer server = new NettyEdgeHttpServer(httpPort, core,
                    StrongReadKeyClass.DEFAULT, new PrometheusExporter(registry), metrics);
            server.start();
            boundPort = server.port();
            tier = server.transportTier();
            epoll = "epoll".equals(tier);
            stopper = server::stop;
        } else {
            EdgeHttpServer server = new EdgeHttpServer(httpPort, core, StrongReadKeyClass.DEFAULT,
                    new PrometheusExporter(registry), metrics);
            server.start();
            boundPort = server.port();
            tier = "jdk";
            stopper = () -> server.stop(0);
        }
        System.out.println("READY which=" + which + " httpPort=" + boundPort
                + " epoll=" + epoll + " tier=" + tier);
        System.out.flush();

        try (ServerSocket control = new ServerSocket(controlPort);
             Socket conn = control.accept();
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(conn.getOutputStream(), true,
                     StandardCharsets.UTF_8)) {
            long windowStart = 0;
            long requestCount = 0;
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                switch (parts[0]) {
                    case "START" -> {
                        requestCount = Long.parseLong(parts[1]);
                        System.gc();
                        Thread.sleep(200);
                        windowStart = threadBean.getTotalThreadAllocatedBytes();
                        out.println("OK");
                    }
                    case "STOP" -> {
                        long windowEnd = threadBean.getTotalThreadAllocatedBytes();
                        long delta = windowEnd - windowStart;
                        double perReq = (double) delta / requestCount;
                        System.out.printf("RESULT which=%s tier=%s epoll=%s requests=%d "
                                        + "serverAllocBytes=%d serverBytesPerRequest=%.1f%n",
                                which, tier, epoll, requestCount, delta, perReq);
                        System.out.flush();
                        out.println("RESULT " + perReq);
                    }
                    case "IDLE" -> {
                        long ms = Long.parseLong(parts[1]);
                        System.gc();
                        Thread.sleep(100);
                        long s0 = threadBean.getTotalThreadAllocatedBytes();
                        Thread.sleep(ms);
                        long s1 = threadBean.getTotalThreadAllocatedBytes();
                        System.out.printf("IDLE which=%s windowMs=%d backgroundAllocBytes=%d%n",
                                which, ms, (s1 - s0));
                        System.out.flush();
                        out.println("OK");
                    }
                    case "QUIT" -> {
                        out.println("BYE");
                        stopper.run();
                        return;
                    }
                    default -> out.println("ERR unknown:" + parts[0]);
                }
            }
        }
        stopper.run();
    }
}
