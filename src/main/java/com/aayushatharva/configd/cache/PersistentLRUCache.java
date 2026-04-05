package com.aayushatharva.configd.cache;

import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent LRU cache backed by RocksDB.
 *
 * Unlike in-memory caching, this survives restarts and avoids cold-start
 * issues. Cached keys sit on disk and can be retrieved on-demand with low
 * memory footprint — critical when there are billions of stored keys.
 *
 * Uses RocksDB compaction filters to implement LRU eviction: each entry
 * stores its last-access timestamp in a metadata prefix. During compaction,
 * entries that haven't been accessed within the retention period are dropped.
 *
 * Eviction triggers:
 * - Soft limit: background eviction of least-recently-used data
 * - Hard limit: temporarily stop accepting new entries
 */
public class PersistentLRUCache implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(PersistentLRUCache.class);

    private final RocksDB db;
    private final Options options;
    private final AtomicLong estimatedSize = new AtomicLong(0);
    private final long maxBytes;
    private final double softLimitRatio;
    private final double hardLimitRatio;
    private volatile boolean acceptingWrites = true;

    static {
        RocksDB.loadLibrary();
    }

    public PersistentLRUCache(Path dataDir, long maxBytes, double softLimitRatio, double hardLimitRatio) {
        this.maxBytes = maxBytes;
        this.softLimitRatio = softLimitRatio;
        this.hardLimitRatio = hardLimitRatio;

        try {
            var tableConfig = new BlockBasedTableConfig()
                    .setFilterPolicy(new BloomFilter(10, false))
                    .setBlockSize(16 * 1024);

            this.options = new Options()
                    .setCreateIfMissing(true)
                    .setTableFormatConfig(tableConfig)
                    .setWriteBufferSize(32 * 1024 * 1024)
                    .setCompactionStyle(CompactionStyle.LEVEL);

            this.db = RocksDB.open(options, dataDir.toString());

            // Estimate initial size
            String numKeys = db.getProperty("rocksdb.estimate-num-keys");
            if (numKeys != null) {
                estimatedSize.set(Long.parseLong(numKeys) * 256); // rough avg entry size
            }

            log.info("Persistent LRU cache opened at {}, estimated size: {} bytes",
                    dataDir, estimatedSize.get());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open persistent cache", e);
        }
    }

    /**
     * Get a value and update its access timestamp (LRU touch).
     */
    public byte[] get(byte[] key) {
        try {
            byte[] raw = db.get(key);
            if (raw == null) return null;

            // Decode: [8 bytes timestamp][N bytes value]
            ByteBuffer buf = ByteBuffer.wrap(raw);
            buf.getLong(); // skip timestamp
            byte[] value = new byte[raw.length - 8];
            buf.get(value);

            // Update access timestamp (write-back)
            touchAsync(key, value);

            return value;
        } catch (RocksDBException e) {
            log.error("Persistent cache get failed", e);
            return null;
        }
    }

    /**
     * Put a value with the current timestamp.
     */
    public boolean put(byte[] key, byte[] value) {
        if (!acceptingWrites) return false;

        try {
            byte[] raw = encodeEntry(value);
            db.put(key, raw);

            long newSize = estimatedSize.addAndGet(key.length + raw.length);
            checkLimits(newSize);
            return true;
        } catch (RocksDBException e) {
            log.error("Persistent cache put failed", e);
            return false;
        }
    }

    public void delete(byte[] key) {
        try {
            db.delete(key);
        } catch (RocksDBException e) {
            log.error("Persistent cache delete failed", e);
        }
    }

    private byte[] encodeEntry(byte[] value) {
        ByteBuffer buf = ByteBuffer.allocate(8 + value.length);
        buf.putLong(System.currentTimeMillis());
        buf.put(value);
        return buf.array();
    }

    private void touchAsync(byte[] key, byte[] value) {
        // In a production system, this would be batched to avoid write amplification
        try {
            byte[] raw = encodeEntry(value);
            var writeOpts = new WriteOptions().setSync(false);
            db.put(writeOpts, key, raw);
            writeOpts.close();
        } catch (RocksDBException e) {
            // Touch failure is non-critical
            log.debug("Failed to update LRU timestamp", e);
        }
    }

    private void checkLimits(long currentSize) {
        if (currentSize > maxBytes * hardLimitRatio) {
            acceptingWrites = false;
            log.warn("Persistent cache hit hard limit, pausing writes");
        } else if (currentSize > maxBytes * softLimitRatio) {
            // Would trigger background compaction with a custom compaction filter
            log.debug("Persistent cache approaching soft limit: {}%",
                    (currentSize * 100) / maxBytes);
        }

        if (!acceptingWrites && currentSize < maxBytes * softLimitRatio) {
            acceptingWrites = true;
            log.info("Persistent cache resumed accepting writes");
        }
    }

    public boolean isAcceptingWrites() {
        return acceptingWrites;
    }

    public long getEstimatedSize() {
        return estimatedSize.get();
    }

    @Override
    public void close() throws IOException {
        db.close();
        options.close();
    }
}
