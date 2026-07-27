package io.configd.replication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages batched log replication for a single Raft group. Collects
 * proposed entries and sends them in batches to optimize throughput.
 * <p>
 * Design: entries are buffered until either:
 * <ol>
 *   <li>{@code maxBatchSize} entries have accumulated, or</li>
 *   <li>{@code maxBatchDelayNanos} have elapsed since the first buffered entry, or</li>
 *   <li>{@link #flush()} is called explicitly.</li>
 * </ol>
 * <p>
 * This implements the 200us batching window. The pipeline
 * is designed for single-threaded access from the Raft I/O thread.
 * No synchronization is used.
 * <p>
 * Typical usage:
 * <pre>{@code
 * pipeline.offer(command);
 * if (pipeline.shouldFlush(clock.nanoTime())) {
 *     List<byte[]> batch = pipeline.flush();
 *     // propose batch to RaftNode
 * }
 * }</pre>
 */
public final class ReplicationPipeline {

    private final int maxBatchSize;
    private final int maxBatchBytes;
    private final long maxBatchDelayNanos;

    private final ArrayList<byte[]> pending;

    private long pendingBytes;

    /**
     * Nanotime of the first entry added to the current batch.
     * Reset to {@code -1} when the batch is empty.
     */
    private long firstEntryNanos;

    public ReplicationPipeline(int maxBatchSize, int maxBatchBytes, long maxBatchDelayNanos) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive: " + maxBatchSize);
        }
        if (maxBatchBytes <= 0) {
            throw new IllegalArgumentException("maxBatchBytes must be positive: " + maxBatchBytes);
        }
        if (maxBatchDelayNanos <= 0) {
            throw new IllegalArgumentException("maxBatchDelayNanos must be positive: " + maxBatchDelayNanos);
        }
        this.maxBatchSize = maxBatchSize;
        this.maxBatchBytes = maxBatchBytes;
        this.maxBatchDelayNanos = maxBatchDelayNanos;
        this.pending = new ArrayList<>();
        this.pendingBytes = 0;
        this.firstEntryNanos = -1;
    }

    /**
     * Adds a command to the current batch.
     * <p>
     * If the batch was previously empty, the batching timer starts now
     * (tracked via the nanotime of the next {@link #shouldFlush(long)} call).
     *
     * @param command the opaque command bytes; must not be null
     * @throws NullPointerException if {@code command} is null
     */
    public void offer(byte[] command) {
        if (command == null) {
            throw new NullPointerException("command must not be null");
        }
        pending.add(command);
        pendingBytes += command.length;
    }

    public boolean shouldFlush(long currentNanos) {
        if (pending.isEmpty()) {
            return false;
        }

        if (firstEntryNanos == -1) {
            firstEntryNanos = currentNanos;
        }

        if (pending.size() >= maxBatchSize) {
            return true;
        }

        if (pendingBytes >= maxBatchBytes) {
            return true;
        }

        return (currentNanos - firstEntryNanos) >= maxBatchDelayNanos;
    }

    public List<byte[]> flush() {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> batch = List.copyOf(pending);
        pending.clear();
        pendingBytes = 0;
        firstEntryNanos = -1;
        return batch;
    }

    public int pendingCount() {
        return pending.size();
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    /**
     * Discards all pending commands. Used on leadership loss when
     * buffered proposals can no longer be committed.
     */
    public void reset() {
        pending.clear();
        pendingBytes = 0;
        firstEntryNanos = -1;
    }
}
