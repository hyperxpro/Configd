package io.configd.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;

import org.HdrHistogram.Histogram;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <pre>
 *   java --enable-preview -cp benchmarks.jar io.configd.fanout.FanOutLoadClientMain \
 *        &lt;host&gt; &lt;edgePort&gt; &lt;controlPort&gt; &lt;subscribers&gt; &lt;valueBytes&gt; \
 *        &lt;warmupCount&gt; &lt;measureCount&gt; &lt;ratePerSec(0=max)&gt;
 * </pre>
 */
public final class FanOutLoadClientMain {

    private static final int ACK_EVERY = 256;

    private FanOutLoadClientMain() {
    }

    private static final class Phase {
        final long[] target;
        final CountDownLatch latch;
        final boolean recordLatency;
        Phase(int n, long[] target, boolean recordLatency) {
            this.target = target;
            this.latch = new CountDownLatch(n);
            this.recordLatency = recordLatency;
        }
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int edgePort = Integer.parseInt(args[1]);
        int controlPort = Integer.parseInt(args[2]);
        int subscribers = Integer.parseInt(args[3]);
        int valueBytes = Integer.parseInt(args[4]);
        long warmupCount = Long.parseLong(args[5]);
        long measureCount = Long.parseLong(args[6]);
        long rate = Long.parseLong(args[7]);

        AtomicLong[] received = new AtomicLong[subscribers];
        long[] signaled = new long[subscribers];
        Histogram[] hist = new Histogram[subscribers];
        for (int i = 0; i < subscribers; i++) {
            received[i] = new AtomicLong(0);
            hist[i] = new Histogram(1, 60_000_000_000L, 3);
        }
        final Phase[] currentPhase = new Phase[1];
        CountDownLatch subscribed = new CountDownLatch(subscribers);
        Thread[] threads = new Thread[subscribers];

        for (int s = 0; s < subscribers; s++) {
            final int id = s;
            threads[s] = Thread.ofVirtual().name("sub-" + s).start(() -> {
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(host, edgePort), 5_000);
                    sock.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(sock.getInputStream());
                    OutputStream out = sock.getOutputStream();
                    // SUBSCRIBE full-store, TAIL from 0 (empty store -> no snapshot).
                    out.write(EdgeFrameCodec.encode(
                            new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-" + id)));
                    out.flush();
                    long maxSeq = 0;
                    long sinceAck = 0;
                    boolean okSeen = false;
                    while (true) {
                        EdgeFrame f = readFrame(in);
                        if (f == null) {
                            return;
                        }
                        if (f instanceof EdgeFrame.SubscribeOk) {
                            if (!okSeen) {
                                okSeen = true;
                                subscribed.countDown();
                            }
                            continue;
                        }
                        if (f instanceof EdgeFrame.Notify n) {
                            long nowMs = System.currentTimeMillis();
                            Phase ph = currentPhase[0];
                            for (CommitNotification cn : n.notifications()) {
                                long c = received[id].incrementAndGet();
                                maxSeq = Math.max(maxSeq, cn.seq());
                                if (ph != null && ph.recordLatency) {
                                    long lat = (nowMs - cn.commitTimestampMillis()) * 1000L; // ->micros
                                    hist[id].recordValue(Math.max(1L, lat));
                                }
                                if (ph != null && signaled[id] < ph.target[id] && c >= ph.target[id]) {
                                    signaled[id] = ph.target[id];
                                    ph.latch.countDown();
                                }
                                if (++sinceAck >= ACK_EVERY) {
                                    sinceAck = 0;
                                    out.write(EdgeFrameCodec.encode(new EdgeFrame.CursorAck(maxSeq)));
                                    out.flush();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // EOF/reset at teardown is expected; only note unexpected ones.
                    if (currentPhase[0] != null) {
                        System.err.println("sub-" + id + " error: " + e);
                    }
                }
            });
        }

        if (!subscribed.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("only " + (subscribers - subscribed.getCount())
                    + "/" + subscribers + " subscribers reached SUBSCRIBE_OK");
        }
        System.out.println("CLIENT subscribed=" + subscribers);

        try (Socket ctl = new Socket(host, controlPort);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(ctl.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(ctl.getOutputStream(), true, StandardCharsets.UTF_8)) {

            runPhase(currentPhase, received, subscribers, warmupCount, false, out, in, rate, valueBytes);
            double[] r = runPhase(currentPhase, received, subscribers, measureCount, true, out, in, rate, valueBytes);

            out.println("QUIT");
            in.readLine();

            Histogram merged = new Histogram(1, 60_000_000_000L, 3);
            for (Histogram h : hist) {
                merged.add(h);
            }
            long totalDelivered = (long) (measureCount * (long) subscribers);
            System.out.printf("CLIENT subscribers=%d measureCount=%d totalDelivered=%d seconds=%.3f%n",
                    subscribers, measureCount, totalDelivered, r[1]);
            System.out.printf("CLIENT deliveryThroughputNotifPerSec=%.0f%n", r[0]);
            System.out.printf("CLIENT oneWayLatencyMillis(coarse) p50=%.1f p99=%.1f p999=%.1f max=%.1f%n",
                    merged.getValueAtPercentile(50.0) / 1000.0,
                    merged.getValueAtPercentile(99.0) / 1000.0,
                    merged.getValueAtPercentile(99.9) / 1000.0,
                    merged.getMaxValue() / 1000.0);
        }
        for (Thread t : threads) {
            t.join(2_000);
        }
    }

    /** Runs one phase: set targets, GO, wait for all subscribers to reach target. Returns {throughput, seconds}. */
    private static double[] runPhase(Phase[] slot, AtomicLong[] received, int n, long count,
                                     boolean record, PrintWriter out, BufferedReader in,
                                     long rate, int valueBytes) throws Exception {
        long[] target = new long[n];
        for (int i = 0; i < n; i++) {
            target[i] = received[i].get() + count;
        }
        Phase ph = new Phase(n, target, record);
        slot[0] = ph;
        long t0 = System.nanoTime();
        out.println("GO " + count + " " + valueBytes + " " + rate);
        String published = in.readLine();
        if (published == null || !published.startsWith("PUBLISHED")) {
            throw new IllegalStateException("expected PUBLISHED, got: " + published);
        }
        if (!ph.latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("phase incomplete: " + ph.latch.getCount()
                    + "/" + n + " subscribers did not reach target (slow-consumer demotion?)");
        }
        long t1 = System.nanoTime();
        double seconds = (t1 - t0) / 1e9;
        double throughput = (count * (long) n) / seconds;
        return new double[]{throughput, seconds};
    }

    /** Reads one length-prefixed edge frame; null on EOF. (Mirrors the server's peekLength discipline.) */
    private static EdgeFrame readFrame(DataInputStream in) throws java.io.IOException {
        int length;
        try {
            length = in.readInt();
        } catch (java.io.EOFException eof) {
            return null;
        }
        byte[] header4 = {(byte) (length >>> 24), (byte) (length >>> 16),
                (byte) (length >>> 8), (byte) length};
        int total = EdgeFrameCodec.peekLength(header4);
        byte[] frame = new byte[total];
        System.arraycopy(header4, 0, frame, 0, 4);
        in.readFully(frame, 4, total - 4);
        return EdgeFrameCodec.decode(frame);
    }
}
