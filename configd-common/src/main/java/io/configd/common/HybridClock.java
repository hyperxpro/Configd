package io.configd.common;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Hybrid Logical Clock implementation.
 * <p>
 * Maintains the invariant: {@code HLC >= physical clock} at all times.
 * <p>
 * <b>Allocation-free hot path.</b> The clock state is packed into a single
 * primitive {@code long}: the top 48 bits hold the physical milliseconds
 * (sufficient until year 10889) and the bottom 16 bits hold the logical
 * counter. {@link #now()}, {@link #receive(long)} and {@link #current()}
 * read / write this packed state via a {@link VarHandle} with CAS, so they
 * allocate zero objects.
 * <p>
 * The fieldwise {@link HybridTimestamp} form remains available for callers
 * that need the structured view (e.g. WAL / RPC encoding), but it is created
 * only at the boundary, not on the hot path.
 * <p>
 * <b>Encoding:</b>
 * <pre>
 *   packed = (physicalMs &lt;&lt; 16) | (logical &amp; 0xFFFF)
 * </pre>
 * This keeps natural ordering: a lexicographic comparison of the packed
 * longs matches the {@link HybridTimestamp#compareTo(HybridTimestamp)}
 * ordering.
 */
public final class HybridClock {

    private static final int LOGICAL_BITS = 16;
    private static final long LOGICAL_MASK = (1L << LOGICAL_BITS) - 1L;
    private static final long PHYSICAL_MASK = ~LOGICAL_MASK;

    private static final VarHandle STATE;
    static {
        try {
            STATE = MethodHandles.lookup()
                    .findVarHandle(HybridClock.class, "state", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Clock physicalClock;
    @SuppressWarnings("unused") // read/written via VarHandle
    private volatile long state;

    public HybridClock(Clock physicalClock) {
        this.physicalClock = physicalClock;
        this.state = encode(physicalClock.currentTimeMillis(), 0);
    }

    // ── Encoding helpers ────────────────────────────────────────────────

    /** Pack (physicalMs, logical) into a single long. */
    public static long encode(long physicalMs, int logical) {
        return (physicalMs << LOGICAL_BITS) | (logical & LOGICAL_MASK);
    }

    /** Extract the physical millisecond component from a packed timestamp. */
    public static long physicalOf(long packed) {
        return (packed & PHYSICAL_MASK) >>> LOGICAL_BITS;
    }

    /** Extract the logical counter component from a packed timestamp. */
    public static int logicalOf(long packed) {
        return (int) (packed & LOGICAL_MASK);
    }

    /** Structured view of a packed timestamp. Allocates; use at boundaries only. */
    public static HybridTimestamp toTimestamp(long packed) {
        return new HybridTimestamp(physicalOf(packed), logicalOf(packed));
    }

    /** Packs a structured timestamp into a long. */
    public static long fromTimestamp(HybridTimestamp ts) {
        return encode(ts.wallTime(), ts.logical());
    }

    // ── Hot path: returns packed longs, zero allocation ─────────────────

    /**
     * Generate a new timestamp for a local event and return it packed.
     * Guarantees: result strictly greater than all previously returned
     * timestamps from this clock instance.
     */
    public long now() {
        long pt = physicalClock.currentTimeMillis();
        for (;;) {
            long cur = (long) STATE.getVolatile(this);
            long curPhys = physicalOf(cur);
            long nextPhys;
            int nextLogical;
            if (pt > curPhys) {
                nextPhys = pt;
                nextLogical = 0;
            } else {
                nextPhys = curPhys;
                nextLogical = logicalOf(cur) + 1;
            }
            long next = encode(nextPhys, nextLogical);
            if (STATE.compareAndSet(this, cur, next)) {
                return next;
            }
        }
    }

    /**
     * Update clock upon receiving a message with the given packed timestamp.
     * Ensures causal ordering: result &gt; both local clock and remote timestamp.
     */
    public long receive(long remote) {
        long remotePhys = physicalOf(remote);
        int remoteLogical = logicalOf(remote);
        long pt = physicalClock.currentTimeMillis();
        for (;;) {
            long cur = (long) STATE.getVolatile(this);
            long curPhys = physicalOf(cur);
            int curLogical = logicalOf(cur);
            long nextPhys;
            int nextLogical;
            if (pt > curPhys && pt > remotePhys) {
                nextPhys = pt;
                nextLogical = 0;
            } else if (remotePhys > curPhys) {
                nextPhys = remotePhys;
                nextLogical = remoteLogical + 1;
            } else if (curPhys == remotePhys) {
                nextPhys = curPhys;
                nextLogical = Math.max(curLogical, remoteLogical) + 1;
            } else {
                nextPhys = curPhys;
                nextLogical = curLogical + 1;
            }
            long next = encode(nextPhys, nextLogical);
            if (STATE.compareAndSet(this, cur, next)) {
                return next;
            }
        }
    }

    /** Current packed HLC value without advancing. */
    public long current() {
        return (long) STATE.getVolatile(this);
    }

    // ── Structured-form conveniences (allocate; not on hot path) ────────

    /**
     * Structured form of {@link #now()}. Prefer the primitive variant on
     * hot paths; this form is for WAL records / RPC boundaries where the
     * structured value already needs to exist.
     */
    public HybridTimestamp nowStructured() {
        return toTimestamp(now());
    }

    /** Structured form of {@link #receive(long)}. */
    public HybridTimestamp receive(HybridTimestamp received) {
        return toTimestamp(receive(fromTimestamp(received)));
    }

    /** Structured form of {@link #current()}. */
    public HybridTimestamp currentStructured() {
        return toTimestamp(current());
    }
}
