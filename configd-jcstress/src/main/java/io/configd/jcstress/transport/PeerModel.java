package io.configd.jcstress.transport;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A faithful, socket-free model of {@code TcpRaftTransport.PeerConnection}'s
 * concurrent shared state and control flow, for jcstress race-testing of the
 * transport threading change.
 *
 * <p><b>Why a model and not the real class?</b> {@code PeerConnection} drives real
 * {@link java.net.Socket}s and a {@code ScheduledExecutorService}; running it
 * under jcstress would inject non-deterministic OS I/O into timing-sensitive race
 * windows and could not exercise a specific interleaving thousands of times. The
 * race lives entirely in the <em>field algebra</em>: the same field TYPES
 * ({@code ArrayBlockingQueue(1024)}, two {@link AtomicBoolean}s, two volatile
 * reference fields, an {@link AtomicLong}), the same publish ORDER, and the same
 * CAS / identity-guard logic copied verbatim from the source. This model
 * reproduces every documented hazard while staying deterministic. The static
 * guard {@code NoBlockingConnectOnConsensusPathTest} + the live blackhole drill
 * cover the I/O-bound behaviour separately.
 *
 * <p>Each method below mirrors a specific {@code PeerConnection} method. Socket
 * publication is modelled by publishing a non-null {@link StreamRef} into the
 * volatile {@code out}/{@code socket} fields; "starting the writer" is modelled by
 * incrementing {@link #writersStarted} for the published stream, so a test can
 * assert at most one writer per stream and never a null stream. The
 * {@code connectExecutor.schedule} hand-off is modelled by {@link #scheduledConnects}
 * (a connect that WOULD run), so a test can assert exactly-one-pending semantics.
 */
public final class PeerModel {

    /** Small capacity so eviction is reachable in race (mirrors {@code OUTBOUND_QUEUE_CAPACITY}). */
    private final int capacity;

    public record StreamRef(int id) {
    }

    final BlockingQueue<byte[]> queue;
    final AtomicBoolean connectInFlight = new AtomicBoolean(false);
    final AtomicBoolean closed = new AtomicBoolean(false);
    volatile StreamRef socket;   // mirrors volatile Socket
    volatile StreamRef out;      // mirrors volatile DataOutputStream

    public final AtomicLong framesDropped = new AtomicLong();
    public final AtomicInteger writersStarted = new AtomicInteger();
    public final AtomicBoolean writerSawNullStream = new AtomicBoolean(false); // publish/visibility bug
    public final AtomicInteger scheduledConnects = new AtomicInteger();
    /**
     * Connects currently PENDING (scheduled-but-not-yet-run): incremented when a
     * connect is handed to the connector, decremented when the connector picks it
     * up to run. The {@code connectInFlight} CAS guarantees this never exceeds 1;
     * a value &gt; 1 is a double-schedule, a value of 0 with frames still queued is
     * a lost reschedule. Lets the (2) test detect BOTH failure shapes.
     */
    public final AtomicInteger pendingConnects = new AtomicInteger();
    private final AtomicInteger streamIds = new AtomicInteger();
    // Separate from closed: close() stops serving, running keeps it true for test lifetime.
    private final AtomicBoolean running = new AtomicBoolean(true);

    public PeerModel(int capacity) {
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public int queueSize() {
        return queue.size();
    }

    public StreamRef out() {
        return out;
    }

    public void enqueueOrDrop(byte[] wire) {
        if (closed.get()) {
            framesDropped.incrementAndGet();
            return;
        }
        if (!queue.offer(wire)) {
            queue.poll();                          // drop OLDEST
            framesDropped.incrementAndGet();
            if (!queue.offer(wire)) {
                framesDropped.incrementAndGet();
            }
        }
        if (out == null) {                         // volatile read
            scheduleConnect();
        }
    }

    public void scheduleConnect() {
        if (closed.get() || !running.get()) {
            return;
        }
        if (!connectInFlight.compareAndSet(false, true)) {
            return; // already in flight/scheduled
        }
        // Source then calls connectExecutor.schedule(this::connectAndStartWriter).
        scheduledConnects.incrementAndGet();
        pendingConnects.incrementAndGet(); // a connect is now pending until the connector runs it
    }

    public void connectAndStartWriterSuccess() {
        pendingConnects.decrementAndGet(); // the connector picked up the scheduled connect
        boolean connected = false;
        try {
            if (closed.get() || !running.get()) {
                return;
            }
            StreamRef s = new StreamRef(streamIds.incrementAndGet());
            StreamRef o = s; // one stream per socket
            // Publish in source order BEFORE starting writer (socket before out).
            this.socket = s;
            this.out = o;
            // "submit writer" - model it inline: the writer reads the published o.
            startWriter(o);
            connected = true;
        } finally {
            connectInFlight.set(false);
            if (!connected && !closed.get() && running.get() && !queue.isEmpty()) {
                scheduleConnect();
            }
        }
    }

    public void connectAndStartWriterFailure() {
        pendingConnects.decrementAndGet(); // the connector picked up the scheduled connect
        boolean connected = false;
        try {
            if (closed.get() || !running.get()) {
                return;
            }
        } finally {
            connectInFlight.set(false);
            if (!connected && !closed.get() && running.get() && !queue.isEmpty()) {
                scheduleConnect();
            }
        }
    }

    private void startWriter(StreamRef o) {
        if (o == null) {
            writerSawNullStream.set(true); // a publish/visibility bug would land here
            return;
        }
        writersStarted.incrementAndGet();
    }

    public void teardown(StreamRef s) {
        boolean wasLive = (this.socket == s);     // identity guard
        if (wasLive) {
            this.out = null;
            this.socket = null;
        }
        if (wasLive && !closed.get()) {
            if (!queue.isEmpty() && running.get()) {
                scheduleConnect();
            }
        }
    }

    public void close() {
        closed.set(true);
        out = null;
        socket = null;
        queue.clear();
    }

    /** Publishes a fresh stream the way a connect that passed the closed gate would. */
    public StreamRef publishFreshStreamPastGate() {
        if (closed.get() || !running.get()) {
            return null;
        }
        StreamRef s = new StreamRef(streamIds.incrementAndGet());
        this.socket = s;
        this.out = s;
        startWriter(s);
        return s;
    }
}
