package io.configd.replication;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pool of N single-thread scheduled executors. Each Raft group binds to exactly one owner via
 * the static mapping {@code ownerExecutor(groupId) = pool[ floorMod(groupId, N) ]}, fixed for
 * the life of the process (no resharding). Because every owner-only entry point of a group's
 * {@code RaftNode} runs on that group's owner thread, the per-group single-writer invariant holds
 * and {@code RaftNode} stays unsynchronised - exactly what the {@code assertOwnerThread()} net
 * asserts.
 *
 * <ul>
 *   <li><b>At N=1</b> there is a single owner thread, behaviourally equivalent to the legacy
 *       single {@code configd-tick} thread.</li>
 *   <li><b>At N&gt;1</b> different groups progress on different threads (the throughput unlock);
 *       the same group never does (the safety preservation).</li>
 * </ul>
 *
 * Threads are daemon and named {@code configd-raft-owner-<i>} so a stuck owner is identifiable
 * in a thread dump.
 */
public final class OwnerExecutorPool {

    private final ScheduledExecutorService[] owners;
    private final int size;

    public OwnerExecutorPool(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("owner pool size must be >= 1, was " + size);
        }
        this.size = size;
        this.owners = new ScheduledExecutorService[size];
        for (int i = 0; i < size; i++) {
            final int idx = i;
            this.owners[i] = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "configd-raft-owner-" + idx);
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** The static owner index for a group: {@code floorMod(groupId, N)} (handles negative ids). */
    public int ownerIndexOf(int groupId) {
        return Math.floorMod(groupId, size);
    }

    public ScheduledExecutorService ownerExecutor(int groupId) {
        return owners[ownerIndexOf(groupId)];
    }

    public ScheduledExecutorService ownerByIndex(int ownerIndex) {
        return owners[ownerIndex];
    }

    public int size() {
        return size;
    }

    public void shutdown() {
        for (ScheduledExecutorService o : owners) {
            o.shutdown();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        boolean allDone = true;
        for (ScheduledExecutorService o : owners) {
            allDone &= o.awaitTermination(timeout, unit);
        }
        return allDone;
    }
}
