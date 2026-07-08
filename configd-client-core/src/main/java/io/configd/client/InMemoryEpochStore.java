package io.configd.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The default {@link EpochStore}: a process-local high-water that does not survive a restart. Suitable for an
 * ephemeral client (which re-hydrates from scratch anyway) and for tests. Thread-safe and monotonic — a
 * {@link #save(long)} never lowers the mark.
 */
public final class InMemoryEpochStore implements EpochStore {

    private final AtomicLong highWater = new AtomicLong();

    @Override
    public long load() {
        return highWater.get();
    }

    @Override
    public void save(long epoch) {
        highWater.accumulateAndGet(epoch, Math::max);
    }
}
