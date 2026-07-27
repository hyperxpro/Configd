package io.configd.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Default EpochStore: process-local, does not survive restart. Thread-safe and monotonic: save() never lowers.
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
