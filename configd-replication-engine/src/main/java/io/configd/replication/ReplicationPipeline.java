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
 * This implements the 200us batching window from ADR-0010. The pipeline
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

    /** Buffered commands awaiting flush. */
    private final ArrayList<byte[]> pending;

    /** Running total of bytes across all pending commands. */
    private long pendingBytes;

    /**
     * Nanotime of the first entry added to the current batch.
     * Reset to {@code -1} when the batch is empty.
     */
    private long firstEntryNanos;

    /**
     * Creates a new ReplicationPipeline.
     *
     * @param maxBatchSize       maximum number of entries before a flush is triggered
     * @param maxBatchBytes      maximum total bytes across all entries before a flush is triggered
     * @param maxBatchDelayNanos maximum nanoseconds since the first buffered entry before a
     *                           flush is triggered (e.g., 200_000 for 200us)
     * @throws IllegalArgumentException if any parameter is not positive
     */
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

    /**
     * Checks whether the current batch should be flushed based on
     * size, byte, or time thresholds.
     * <p>
     * If this is the first check after a new entry was added to an
     * empty batch, the batching timer is initialized to the provided
     * {@code currentNanos}.
     *
     * @param currentNanos the current monotonic nanotime
     * @return {@code true} if any flush threshold is met
     */
    public boolean shouldFlush(long currentNanos) {
        if (pending.isEmpty()) {
            return false;
        }

        // Initialize the batching timer on first check after offer
        if (firstEntryNanos == -1) {
            firstEntryNanos = currentNanos;
        }

        // Check count threshold
        if (pending.size() >= maxBatchSize) {
            return true;
        }

        // Check byte threshold
        if (pendingBytes >= maxBatchBytes) {
            return true;
        }

        // Check time threshold
        return (currentNanos - firstEntryNanos) >= maxBatchDelayNanos;
    }

    /**
     * Returns the accumulated commands and resets the pipeline for the
     * next batch. If no commands are pending, returns an empty list.
     *
     * @return an unmodifiable list of command byte arrays; never null
     */
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

    /**
     * Returns the number of commands currently buffered.
     *
     * @return pending command count
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Returns the total bytes across all currently buffered commands.
     *
     * @return pending byte total
     */
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
