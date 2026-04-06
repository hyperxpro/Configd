package io.configd.common;

/**
 * Hybrid Logical Clock (HLC) timestamp.
 * Combines wall-clock time with a logical counter to provide
 * a causally-consistent total ordering without TrueTime hardware.
 *
 * Based on: "Logical Physical Clocks" (Kulkarni et al., 2014).
 */
public final class HybridTimestamp implements Comparable<HybridTimestamp> {

    private final long wallTime;
    private final int logical;

    public static final HybridTimestamp ZERO = new HybridTimestamp(0, 0);

    public HybridTimestamp(long wallTime, int logical) {
        this.wallTime = wallTime;
        this.logical = logical;
    }

    public long wallTime() { return wallTime; }
    public int logical() { return logical; }

    /**
     * Pack into 64 bits. Top 48: wall time. Bottom 16: logical.
     */
    public long packed() {
        return (wallTime << 16) | (logical & 0xFFFF);
    }

    public static HybridTimestamp fromPacked(long packed) {
        return new HybridTimestamp(packed >>> 16, (int) (packed & 0xFFFF));
    }

    @Override
    public int compareTo(HybridTimestamp other) {
        int cmp = Long.compare(this.wallTime, other.wallTime);
        return cmp != 0 ? cmp : Integer.compare(this.logical, other.logical);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof HybridTimestamp t
                && wallTime == t.wallTime && logical == t.logical);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(wallTime) * 31 + logical;
    }

    @Override
    public String toString() {
        return wallTime + ":" + logical;
    }
}
