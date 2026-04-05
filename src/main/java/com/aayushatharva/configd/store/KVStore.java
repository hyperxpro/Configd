package com.aayushatharva.configd.store;

import java.io.Closeable;
import java.util.List;

/**
 * Core key-value store interface.
 * Supports versioned reads (MVCC), range scans, and Bloom-filter-accelerated lookups.
 */
public interface KVStore extends Closeable {

    byte[] get(byte[] key);

    /** MVCC read: retrieve the value of {@code key} as of the given replication version. */
    byte[] get(byte[] key, long version);

    void put(byte[] key, byte[] value, long version);

    void delete(byte[] key, long version);

    /** Range scan from startKey (inclusive) to endKey (exclusive). */
    List<KVEntry> scan(byte[] startKey, byte[] endKey, int limit);

    boolean containsKey(byte[] key);

    long getCurrentVersion();

    void setCurrentVersion(long version);

    /** Flush pending writes and trigger compaction. */
    void compact();

    long approximateSize();
}
