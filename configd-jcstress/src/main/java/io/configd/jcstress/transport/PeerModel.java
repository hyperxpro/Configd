package io.configd.jcstress.transport;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A faithful, socket-free model of {@code TcpRaftTransport.PeerConnection}'s
 * concurrent shared state and control flow, for jcstress race-testing of the
 * RR-002 transport threading change.
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

    /** Mirrors {@code OUTBOUND_QUEUE_CAPACITY}. Small here so eviction is reachable in a race. */
    private final int capacity;

    /** A published output stream identity (stands in for the DataOutputStream {@code out}). */
    public record StreamRef(int id) {
    }

    final BlockingQueue<byte[]> queue;
    final AtomicBoolean connectInFlight = new AtomicBoolean(false);
    final AtomicBoolean closed = new AtomicBoolean(false);
    volatile StreamRef socket;   // mirrors volatile Socket
    volatile StreamRef out;      // mirrors volatile DataOutputStream

    /** Mirrors {@code framesDropped} (transport-wide in source; per-peer here is equivalent for the race). */
    public final AtomicLong framesDropped = new AtomicLong();

    /** Number of distinct writer tasks started; used to assert "never two writers on one stream". */
    public final AtomicInteger writersStarted = new AtomicInteger();
    /** Set if a writer was ever started against a null stream (a publish/visibility bug). */
    public final AtomicBoolean writerSawNullStream = new AtomicBoolean(false);
    /** Number of connects actually handed to the (modelled) connector. */
    public final AtomicInteger scheduledConnects = new AtomicInteger();
    /** A monotonic id generator for published streams. */
    private final AtomicInteger streamIds = new AtomicInteger();
    /** "running" flag — true for the lifetime of a test (close() flips closed, not this). */
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

    // -----------------------------------------------------------------------
    // enqueueOrDrop — verbatim algorithm from PeerConnection.enqueueOrDrop
    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // scheduleConnect — verbatim CAS gate
    // -----------------------------------------------------------------------
    public void scheduleConnect() {
        if (closed.get() || !running.get()) {
            return;
        }
        if (!connectInFlight.compareAndSet(false, true)) {
            return; // already in flight/scheduled
        }
        // Source then calls connectExecutor.schedule(this::connectAndStartWriter).
        scheduledConnects.incrementAndGet();
    }

    /**
     * Models the connector picking up a scheduled connect and running
     * {@code connectAndStartWriter} to a SUCCESSFUL connection: publish the
     * stream (in source order) and start exactly one writer, then run the finally.
     */
    public void connectAndStartWriterSuccess() {
        boolean connected = false;
        try {
            if (closed.get() || !running.get()) {
                return;
            }
            StreamRef s = new StreamRef(streamIds.incrementAndGet());
            StreamRef o = s; // one stream per socket
            // Publish BEFORE starting the writer (source order: this.socket=s; this.out=o;)
            this.socket = s;
            this.out = o;
            // "submit writer" — model it inline: the writer reads the published o.
            startWriter(o);
            connected = true;
        } finally {
            connectInFlight.set(false);
            if (!connected && !closed.get() && running.get() && !queue.isEmpty()) {
                scheduleConnect();
            }
        }
    }

    /**
     * Models {@code connectAndStartWriter} taking the FAILURE path (IOException):
     * no publish, run the finally (reset flag, reschedule if frames remain).
     */
    public void connectAndStartWriterFailure() {
        boolean connected = false;
        try {
            if (closed.get() || !running.get()) {
                return;
            }
            // createClientSocket throws — nothing published.
        } finally {
            connectInFlight.set(false);
            if (!connected && !closed.get() && running.get() && !queue.isEmpty()) {
                scheduleConnect();
            }
        }
    }

    /** Models the writer task start: it reads the published stream {@code o}. */
    private void startWriter(StreamRef o) {
        if (o == null) {
            writerSawNullStream.set(true); // a publish/visibility bug would land here
            return;
        }
        writersStarted.incrementAndGet();
    }

    // -----------------------------------------------------------------------
    // teardown — verbatim identity guard
    // -----------------------------------------------------------------------
    public void teardown(StreamRef s) {
        boolean wasLive = (this.socket == s);     // identity guard
        if (wasLive) {
            this.out = null;
            this.socket = null;
        }
        // closeQuietly(s) — no-op in the model
        if (wasLive && !closed.get()) {
            // markDisconnected — modelled by the reschedule decision only
            if (!queue.isEmpty() && running.get()) {
                scheduleConnect();
            }
        }
    }

    // -----------------------------------------------------------------------
    // close — verbatim
    // -----------------------------------------------------------------------
    public void close() {
        closed.set(true);
        // closeQuietly(out/socket)
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
