package com.aayushatharva.configd.txlog;

import java.io.Closeable;
import java.util.List;

/**
 * Transaction log interface.
 *
 * The transaction log is the foundation of Configd's replication protocol.
 * Each write produces a monotonically increasing sequence number. Downstream
 * nodes pull entries by providing their last-known sequence number.
 */
public interface TransactionLog extends Closeable {

    /** Append an entry and return its assigned sequence number. */
    long append(Operation operation, byte[] key, byte[] value);

    /** Read entries after the given sequence number, up to {@code limit}. */
    List<TransactionLogEntry> readAfter(long afterSequence, int limit);

    /** Read entries in range [fromSequence, toSequence]. */
    List<TransactionLogEntry> readRange(long fromSequence, long toSequence);

    /** Get the latest sequence number. */
    long getLatestSequence();

    /** Get the oldest available sequence number (entries before this have been compacted). */
    long getOldestSequence();

    /** Truncate entries older than the given sequence number. */
    void truncateBefore(long sequenceNumber);

    /** Total number of entries in the log. */
    long size();
}
