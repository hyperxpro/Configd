package com.aayushatharva.configd.txlog;

import com.aayushatharva.configd.store.KVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Batched transaction log writer.
 *
 * Configd batches writes within 500ms windows into single disk writes
 * to reduce I/O overhead. This writer accumulates operations and flushes
 * them as a batch to both the transaction log and the KV store.
 */
public class BatchingTransactionLogWriter implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BatchingTransactionLogWriter.class);

    private static final long DEFAULT_BATCH_INTERVAL_MS = 500;

    private final TransactionLog txLog;
    private final KVStore store;
    private final long batchIntervalMs;
    private final List<PendingWrite> pendingWrites = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed = false;

    public record PendingWrite(Operation operation, byte[] key, byte[] value,
                               CompletableFuture<Long> future) {}

    public BatchingTransactionLogWriter(TransactionLog txLog, KVStore store) {
        this(txLog, store, DEFAULT_BATCH_INTERVAL_MS);
    }

    public BatchingTransactionLogWriter(TransactionLog txLog, KVStore store, long batchIntervalMs) {
        this.txLog = txLog;
        this.store = store;
        this.batchIntervalMs = batchIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cd-batch-writer");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::flush, batchIntervalMs, batchIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Submit a write operation. Returns a future that completes with the assigned
     * sequence number when the batch is flushed.
     */
    public CompletableFuture<Long> put(byte[] key, byte[] value) {
        return submit(new PendingWrite(Operation.SET, key, value, new CompletableFuture<>()));
    }

    public CompletableFuture<Long> delete(byte[] key) {
        return submit(new PendingWrite(Operation.DELETE, key, null, new CompletableFuture<>()));
    }

    private CompletableFuture<Long> submit(PendingWrite write) {
        if (closed) {
            write.future.completeExceptionally(new IllegalStateException("Writer is closed"));
            return write.future;
        }
        lock.lock();
        try {
            pendingWrites.add(write);
        } finally {
            lock.unlock();
        }
        return write.future;
    }

    /** Flush all pending writes as a batch. */
    public void flush() {
        List<PendingWrite> batch;
        lock.lock();
        try {
            if (pendingWrites.isEmpty()) return;
            batch = new ArrayList<>(pendingWrites);
            pendingWrites.clear();
        } finally {
            lock.unlock();
        }

        log.debug("Flushing batch of {} writes", batch.size());

        for (var write : batch) {
            try {
                // Append to transaction log
                long seq = txLog.append(write.operation(), write.key(), write.value());

                // Apply to KV store
                switch (write.operation()) {
                    case SET -> store.put(write.key(), write.value(), seq);
                    case DELETE -> store.delete(write.key(), seq);
                }

                write.future().complete(seq);
            } catch (Exception e) {
                log.error("Failed to write entry", e);
                write.future().completeExceptionally(e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        flush();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
