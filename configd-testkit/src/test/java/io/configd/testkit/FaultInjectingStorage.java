package io.configd.testkit;

import io.configd.common.Storage;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An injectable {@link Storage} decorator for the OPERATIONAL storage
 * faults that throw / delay / truncate in place: write-failure, ENOSPC, fsync-failure,
 * short-read, latency. It composes over any delegate {@code Storage}.
 * <p>
 * Scope and fidelity (see {@code docs/session-4/storage-fault-layer-design.md}): this
 * injector exercises how CALLERS react to a storage op that fails or lies in place. It
 * does NOT model the crash/power-loss durability window - torn-write, fsync-lie, and
 * crash-at-point belong to {@code CrashStorage} (consensus-core test), which alone
 * faithfully models the directory-fsync-pending window (the fsync-ack gap). A
 * {@code FaultInjectingStorage} over {@code CrashStorage} composes the two: operational
 * faults plus a faithful crash model.
 * <p>
 * Faults are armed up-front and fire deterministically (count-down or threshold), so a
 * cell places a fault at an exact operation. The injected exception type is
 * {@link UncheckedIOException} - the {@code Storage} contract is unchecked, so a real
 * device error surfaces unchecked; a caller that only catches narrower types is a finding.
 * Not thread-safe beyond the single Raft path; counters are atomic for test reads.
 */
public final class FaultInjectingStorage implements Storage {

    private final Storage delegate;

    // Armed faults (counts/thresholds; -1 / 0 == disarmed).
    private int failNextWrites;
    private int failNextSyncs;
    private long enospcLimitBytes = -1;
    private String shortReadLog;

    // Observability for the oracle assertions.
    private final AtomicLong bytesAppended = new AtomicLong();
    private final AtomicLong writeFaultsFired = new AtomicLong();
    private final AtomicLong syncFaultsFired = new AtomicLong();

    public FaultInjectingStorage(Storage delegate) {
        this.delegate = delegate;
    }

    // ---- Arming (fluent) ----

    /** The next {@code n} {@code put}/{@code appendToLog} calls throw (transient IO error). */
    public FaultInjectingStorage failNextWrites(int n) {
        this.failNextWrites = n;
        return this;
    }

    /** The next {@code n} {@code sync()} calls throw (directory fsync failure). */
    public FaultInjectingStorage failNextSyncs(int n) {
        this.failNextSyncs = n;
        return this;
    }

    /** Once cumulative {@code appendToLog} bytes exceed {@code limit}, appends throw ENOSPC. */
    public FaultInjectingStorage enospcAfterBytes(long limit) {
        this.enospcLimitBytes = limit;
        return this;
    }

    /** {@code readLog(logName)} returns its frames minus the last one (a short/torn read). */
    public FaultInjectingStorage shortReadLog(String logName) {
        this.shortReadLog = logName;
        return this;
    }

    public long writeFaultsFired() { return writeFaultsFired.get(); }
    public long syncFaultsFired() { return syncFaultsFired.get(); }
    public long bytesAppended() { return bytesAppended.get(); }

    // ---- Storage ----

    @Override
    public void put(String key, byte[] value) {
        if (failNextWrites > 0) {
            failNextWrites--;
            writeFaultsFired.incrementAndGet();
            throw new UncheckedIOException(new java.io.IOException(
                    "injected write failure on put(" + key + ")"));
        }
        delegate.put(key, value);
    }

    @Override
    public void appendToLog(String logName, byte[] data) {
        if (failNextWrites > 0) {
            failNextWrites--;
            writeFaultsFired.incrementAndGet();
            throw new UncheckedIOException(new java.io.IOException(
                    "injected write failure on appendToLog(" + logName + ")"));
        }
        long total = bytesAppended.addAndGet(data.length);
        if (enospcLimitBytes >= 0 && total > enospcLimitBytes) {
            writeFaultsFired.incrementAndGet();
            throw new UncheckedIOException(new java.io.IOException(
                    "injected ENOSPC: " + total + " > limit " + enospcLimitBytes));
        }
        delegate.appendToLog(logName, data);
    }

    @Override
    public void sync() {
        if (failNextSyncs > 0) {
            failNextSyncs--;
            syncFaultsFired.incrementAndGet();
            throw new UncheckedIOException(new java.io.IOException("injected fsync failure"));
        }
        delegate.sync();
    }

    @Override
    public byte[] get(String key) {
        return delegate.get(key);
    }

    @Override
    public List<byte[]> readLog(String logName) {
        List<byte[]> frames = delegate.readLog(logName);
        if (logName.equals(shortReadLog) && !frames.isEmpty()) {
            return List.copyOf(frames.subList(0, frames.size() - 1)); // drop the last frame
        }
        return frames;
    }

    @Override
    public void truncateLog(String logName) {
        delegate.truncateLog(logName);
    }

    @Override
    public void renameLog(String fromLogName, String toLogName) {
        delegate.renameLog(fromLogName, toLogName);
    }
}
